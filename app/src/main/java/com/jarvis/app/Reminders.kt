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
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/**
 * Reminders: phone ke alarm-clock tareeke se lagte hain (sahi samay par bajte hain, Doze mein bhi),
 * list encrypt hokar phone mein rehti hai, aur phone restart ya app update ke baad dobara lag jati hai.
 */
object Reminders {

    class Item(val id: Long, var whenMs: Long, val message: String, val repeatDaily: Boolean)

    const val MAX_ITEMS = 50
    private const val PREFS = "jarvis_reminders"
    private const val KEY = "list"
    private const val CHANNEL = "jarvis_reminders"

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun list(ctx: Context): List<Item> {
        val out = ArrayList<Item>()
        try {
            val arr = JSONArray(ApiKeyStore.open(sp(ctx).getString(KEY, "[]") ?: "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Item(o.getLong("id"), o.getLong("t"), o.getString("m"), o.optBoolean("r", false)))
            }
        } catch (e: Exception) {
        }
        return out.sortedBy { it.whenMs }
    }

    private fun save(ctx: Context, items: List<Item>) {
        try {
            val arr = JSONArray()
            for (r in items) {
                arr.put(JSONObject().put("id", r.id).put("t", r.whenMs).put("m", r.message).put("r", r.repeatDaily))
            }
            sp(ctx).edit().putString(KEY, ApiKeyStore.seal(arr.toString())).apply()
        } catch (e: Exception) {
        }
    }

    fun notificationsAllowed(ctx: Context): Boolean =
        try { NotificationManagerCompat.from(ctx.applicationContext).areNotificationsEnabled() } catch (e: Exception) { true }

    private fun pending(ctx: Context, id: Long): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).putExtra("id", id)
        return PendingIntent.getBroadcast(
            ctx, (id % 1_000_000L).toInt(), i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun schedule(ctx: Context, item: Item) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val show = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(item.whenMs, show), pending(ctx, item.id))
        } catch (e: Exception) {
        }
    }

    private fun unschedule(ctx: Context, id: Long) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pending(ctx, id))
        } catch (e: Exception) {
        }
    }

    /** Naya reminder lagao. Limit poori ho to null. */
    @Synchronized
    fun add(ctx: Context, whenMs: Long, message: String, repeatDaily: Boolean): Item? {
        val items = ArrayList<Item>(list(ctx))
        if (items.size >= MAX_ITEMS) return null
        val id = (items.maxOfOrNull { it.id } ?: 0L) + 1L
        val item = Item(id, whenMs, message.take(200), repeatDaily)
        items.add(item)
        save(ctx, items)
        schedule(ctx, item)
        return item
    }

    @Synchronized
    fun remove(ctx: Context, id: Long) {
        val items = ArrayList<Item>(list(ctx))
        unschedule(ctx, id)
        items.removeAll { it.id == id }
        save(ctx, items)
    }

    @Synchronized
    fun removeAll(ctx: Context): Int {
        val items = list(ctx)
        for (r in items) unschedule(ctx, r.id)
        save(ctx, emptyList())
        return items.size
    }

    @SuppressLint("MissingPermission")
    private fun post(ctx: Context, item: Item, missed: Boolean) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Jarvi reminders", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setContentTitle(if (missed) "⏰ Chhooti hui yaad" else "⏰ Jarvi yaad dila rahi hai")
                .setContentText(item.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(item.message))
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            NotificationManagerCompat.from(ctx).notify(5000 + (item.id % 100000L).toInt(), n)
        } catch (e: Exception) {
        }
    }

    /** Alarm bajne par (ReminderReceiver se) */
    @Synchronized
    fun fire(ctx: Context, id: Long) {
        val items = ArrayList<Item>(list(ctx))
        val item = items.firstOrNull { it.id == id } ?: return
        post(ctx, item, false)
        if (item.repeatDaily) {
            item.whenMs = advance(item.whenMs)
            save(ctx, items)
            schedule(ctx, item)
        } else {
            items.remove(item)
            save(ctx, items)
        }
    }

    /** Roz wale reminder ko agle aane wale samay par le jao */
    private fun advance(fromMs: Long): Long {
        val tz = TimeZone.getDefault()
        val now = System.currentTimeMillis()
        var next = ReminderLogic.nextDaily(fromMs, tz)
        var guard = 0
        while (next <= now && guard < 400) {
            next = ReminderLogic.nextDaily(next, tz)
            guard++
        }
        return next
    }

    /** Phone restart ya app update ke baad sab reminders dobara lagao. Beech mein chhoote hue ek baar bata do. */
    @Synchronized
    fun rescheduleAll(ctx: Context) {
        val items = ArrayList<Item>(list(ctx))
        val now = System.currentTimeMillis()
        var changed = false
        for (item in ArrayList<Item>(items)) {
            if (item.whenMs > now) {
                schedule(ctx, item)
            } else if (item.repeatDaily) {
                item.whenMs = advance(item.whenMs)
                schedule(ctx, item)
                changed = true
            } else {
                post(ctx, item, true)
                items.remove(item)
                changed = true
            }
        }
        if (changed) save(ctx, items)
    }
}
