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
import java.util.TimeZone

/**
 * Daily brief: roz tay samay par notification (aaj ke reminder + baaki kaam). Sab phone par hi banta hai,
 * AI ya internet ki zaroorat nahi, kuch cloud ko nahi jata. Lock screen par text chhupa rehta hai.
 */
object DailyBrief {

    private const val PREFS = "jarvis_brief"
    private const val CHANNEL = "jarvis_brief"
    private const val REQ = 9001
    private const val NOTIF_ID = 9001

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOn(ctx: Context): Boolean = sp(ctx).getBoolean("on", false)
    fun hour(ctx: Context): Int = sp(ctx).getInt("hour", 8)
    fun minute(ctx: Context): Int = sp(ctx).getInt("minute", 0)

    fun enable(ctx: Context, hour: Int, minute: Int) {
        sp(ctx).edit().putBoolean("on", true).putInt("hour", hour.coerceIn(0, 23)).putInt("minute", minute.coerceIn(0, 59)).apply()
        schedule(ctx)
    }

    fun disable(ctx: Context) {
        sp(ctx).edit().putBoolean("on", false).apply()
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pending(ctx))
        } catch (e: Exception) {
        }
    }

    private fun pending(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, REQ, Intent(ctx, BriefReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** Agla brief lagao (chalu ho tabhi) */
    fun schedule(ctx: Context) {
        if (!isOn(ctx)) return
        try {
            val whenMs = BriefLogic.nextTriggerMs(hour(ctx), minute(ctx), System.currentTimeMillis(), TimeZone.getDefault())
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val show = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            am.setAlarmClock(AlarmManager.AlarmClockInfo(whenMs, show), pending(ctx))
        } catch (e: Exception) {
        }
    }

    /** Phone restart ya app update ke baad */
    fun reschedule(ctx: Context) = schedule(ctx)

    /** Abhi ka brief text (command ya screen se "abhi dekho" ke liye) */
    fun composeNow(ctx: Context): String {
        val reminders = Reminders.list(ctx).map { Pair(it.whenMs, it.message) }
        val todos = Todos.list(ctx).filter { !it.done }.map { it.text }
        return BriefLogic.compose(reminders, todos, System.currentTimeMillis(), TimeZone.getDefault())
    }

    @SuppressLint("MissingPermission")
    private fun post(ctx: Context, text: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvi daily brief", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val pub = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("☀️ Jarvi ka daily brief tayyar hai")
                .build()
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("🌅 Jarvi ka daily brief")
                .setContentText(text.lines().firstOrNull() ?: "")
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(pub)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n)
        } catch (e: Exception) {
        }
    }

    /** Alarm bajne par (BriefReceiver se): brief dikhao aur agla kal ke liye lagao */
    fun fire(ctx: Context) {
        if (isOn(ctx)) post(ctx, composeNow(ctx))
        schedule(ctx)
    }
}
