package com.jarvis.app

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.util.TimeZone

/*
 * Routines ka Android hissa: banana, hatana, alarm lagana aur samay par chalana.
 * Chalte waqt koi AI/API call nahi hoti. Alarm setAlarmClock se lagta hai (Reminders/DailyBrief jaisa), isliye bina extra permission ke
 * theek samay par chalta hai (status bar mein alarm ka nishaan dikh sakta hai).
 */
object Routines {

    private const val PREFS = "jarvis_routines"
    private const val REQ_BASE = 9100
    private const val CHANNEL = "jarvis_routines"

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(ctx: Context): List<Routine> = RoutineLogic.fromJson(sp(ctx).getString("items", "") ?: "")

    private fun save(ctx: Context, items: List<Routine>) {
        sp(ctx).edit().putString("items", RoutineLogic.toJson(items)).apply()
    }

    private fun pending(ctx: Context, id: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, REQ_BASE + id, Intent(ctx, RoutineReceiver::class.java).putExtra("id", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun schedule(ctx: Context, r: Routine) {
        try {
            val whenMs = RoutineLogic.nextTrigger(r.hour, r.minute, r.days, System.currentTimeMillis(), TimeZone.getDefault())
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val show = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            am.setAlarmClock(AlarmManager.AlarmClockInfo(whenMs, show), pending(ctx, r.id))
        } catch (e: Exception) {
        }
    }

    private fun unschedule(ctx: Context, id: Int) {
        try {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(ctx, id))
        } catch (e: Exception) {
        }
    }

    fun add(ctx: Context, hour: Int, minute: Int, days: Int, steps: List<RoutineStep>): Routine? {
        val items = ArrayList<Routine>(list(ctx))
        if (items.size >= RoutineLogic.MAX_ROUTINES) return null
        val id = (items.maxOfOrNull { it.id } ?: 0) + 1
        val r = Routine(id, hour, minute, days, steps)
        items.add(r)
        save(ctx, items)
        schedule(ctx, r)
        return r
    }

    fun remove(ctx: Context, id: Int): Boolean {
        val items = ArrayList<Routine>(list(ctx))
        val hit = items.firstOrNull { it.id == id } ?: return false
        items.remove(hit)
        save(ctx, items)
        unschedule(ctx, id)
        return true
    }

    /** Phone restart ya app update ke baad sab routines ke alarm dobara lagao */
    fun rescheduleAll(ctx: Context) {
        for (r in list(ctx)) schedule(ctx, r)
    }

    // ---------- tools ke liye ----------

    fun addFromArgs(ctx: Context, a: JSONObject): String {
        val t = a.optString("time", "").split(":")
        if (t.size != 2) return "Time sahi nahi mila (HH:MM chahiye)."
        val h = t[0].toIntOrNull() ?: return "Time sahi nahi mila (HH:MM chahiye)."
        val m = t[1].toIntOrNull() ?: return "Time sahi nahi mila (HH:MM chahiye)."
        val vol: Int? = if (a.has("volume_percent")) a.optInt("volume_percent", 50) else null
        val steps = RoutineLogic.stepsFrom(a.optString("ringer_mode", ""), vol, a.optString("driving_mode", ""), a.optString("notify_text", ""))
        if (steps.isEmpty()) return "Routine mein kya karna hai ye nahi bataya. Ringer mode, volume, driving mode ya message mein se kuch batao."
        val r = add(ctx, h, m, RoutineLogic.daysFromText(a.optString("days", "")), steps)
            ?: return "Routines ki limit (" + RoutineLogic.MAX_ROUTINES + ") poori ho gayi. Kuch hata do 🗑"
        return "Routine ban gayi 🔁 " + RoutineLogic.describe(r) + ". Ye ab bina internet/AI ke apne aap chalegi."
    }

    fun listText(ctx: Context): String {
        val items = list(ctx)
        if (items.isEmpty()) return "Abhi koi routine nahi hai."
        return items.joinToString("\n") { RoutineLogic.describe(it) }
    }

    fun removeByNumber(ctx: Context, id: Int): String =
        if (remove(ctx, id)) "Routine #" + id + " hata di 🗑" else "Is number ki routine nahi mili. Pehle list_routines dekho."

    // ---------- chalana ----------

    fun fire(ctx: Context, id: Int) {
        val r = list(ctx).firstOrNull { it.id == id } ?: return
        schedule(ctx, r) // pehle agla alarm lagao, taaki beech mein kuch bigde to bhi kal chale
        val done = ArrayList<String>()
        var msg = ""
        for (s in r.steps) {
            try {
                when (s.kind) {
                    "ringer" -> done.add(LocalSkills.run(ctx, LocalCmd.Ringer(s.arg)).lineSequence().firstOrNull() ?: "ringer")
                    "volume" -> {
                        PhoneActions.interpret(ctx, "ACTION: VOLUME " + s.arg)
                        done.add("volume " + s.arg + "%")
                    }
                    "driving" -> {
                        if (s.arg == "on") DrivingMode.turnOn(ctx) else DrivingMode.turnOff(ctx)
                        done.add("driving mode " + s.arg)
                    }
                    "notify" -> msg = s.arg
                }
            } catch (e: Exception) {
                done.add(s.kind + ": nahi ho paya")
            }
        }
        post(ctx, r, done, msg)
    }

    @SuppressLint("MissingPermission")
    private fun post(ctx: Context, r: Routine, done: List<String>, msg: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvi routines", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val title = if (msg.isNotBlank()) "🔁 " + msg else "🔁 Routine " + RoutineLogic.time(r) + " chali"
            val body = if (done.isEmpty()) "Routine #" + r.id else done.joinToString(", ")
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            NotificationManagerCompat.from(ctx).notify(7500 + r.id, n)
        } catch (e: Exception) {
        }
    }
}
