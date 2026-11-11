package com.jarvis.app

import android.content.Context
import java.util.TimeZone

/**
 * Seedhe commands jo AI ko bheje bina phone par hi ho jate hain (privacy + API ki bachat):
 *  - reminders: "5 baje yaad dilana ...", "mere reminders batao", "sab reminder hata do"
 *  - notifications: "notification padho", "notification saaf karo"
 */
object FeatureCommands {

    fun handle(ctx: Context, text: String): String? {
        val now = System.currentTimeMillis()
        val tz = TimeZone.getDefault()
        val r = ReminderLogic.parse(text, now, tz)
        if (r != null) return runReminder(ctx, r, now, tz)
        val n = NotificationLogic.parseCommand(text)
        if (n != null) return runNotification(ctx, n)
        return null
    }

    private fun runReminder(ctx: Context, r: ReminderCommand, now: Long, tz: TimeZone): String {
        return when (r) {
            is ReminderCommand.Add -> {
                val item = Reminders.add(ctx, r.whenMs, r.message, r.repeatDaily)
                if (item == null) {
                    "Reminders ki limit (" + Reminders.MAX_ITEMS + ") poori ho gayi. Kuch hata do 🗑"
                } else {
                    var s = "Theek hai ⏰ " + ReminderLogic.describe(r.whenMs, now, tz) +
                        (if (r.repeatDaily) " (roz)" else "") + " yaad dilaungi: " + r.message
                    if (!Reminders.notificationsAllowed(ctx)) {
                        s += "\n⚠️ Notification band hai, isliye reminder dikhega nahi. Settings → Permissions mein 'Notifications' Allow karo."
                    }
                    s
                }
            }
            is ReminderCommand.MissingTime ->
                "Kab yaad dilaun? Jaise: '5 baje " + (if (r.message.isEmpty()) "dawai leni hai" else r.message) +
                    " yaad dilana' ya '10 minute baad ...' ⏰"
            is ReminderCommand.ShowAll -> {
                val items = Reminders.list(ctx)
                if (items.isEmpty()) {
                    "Abhi koi reminder nahi hai. Bolo: '5 baje yaad dilana ...' ⏰"
                } else {
                    val sb = StringBuilder("Aapke ").append(items.size).append(" reminder hain:")
                    for ((i, item) in items.withIndex()) {
                        sb.append("\n").append(i + 1).append(") ")
                            .append(ReminderLogic.describe(item.whenMs, now, tz))
                            .append(if (item.repeatDaily) " (roz)" else "")
                            .append(": ").append(item.message)
                    }
                    sb.toString()
                }
            }
            is ReminderCommand.CancelAll -> {
                val n = Reminders.removeAll(ctx)
                if (n == 0) "Koi reminder tha hi nahi 🙂" else "Saare $n reminder hata diye 🗑"
            }
            is ReminderCommand.Cancel -> {
                val items = Reminders.list(ctx)
                if (r.about.isEmpty()) {
                    "Kaun sa reminder hataun? 'mere reminders batao' bolkar dekh lo, ya 'sab reminder hata do' bolo."
                } else {
                    val m = SelfCommands.bestMatch(r.about, items.map { it.message }, 0.5)
                    when {
                        m.index < 0 -> "Is naam ka koi reminder nahi mila 🙂"
                        m.ambiguous -> "Isse milte-julte kai reminder hain. Thoda saaf bolo ya 'sab reminder hata do' bolo."
                        else -> {
                            val item = items[m.index]
                            Reminders.remove(ctx, item.id)
                            "Reminder hata diya 🗑: " + item.message
                        }
                    }
                }
            }
        }
    }

    private fun runNotification(ctx: Context, c: NotificationLogic.Command): String {
        if (!JarviNotificationListener.isEnabled(ctx)) {
            return "Notification padhne ke liye Settings → Permissions → 'Notification access' Allow karo. " +
                "Ye sirf phone par chalta hai, cloud AI ko kuch nahi jata 🔒 " +
                "(Android 13+ par pehle Settings → Apps → Jarvis → ⋮ → 'Allow restricted settings' dabana pad sakta hai.)"
        }
        return when (c) {
            NotificationLogic.Command.READ -> NotificationLogic.speak(NotificationStore.recent(System.currentTimeMillis()))
            NotificationLogic.Command.CLEAR -> {
                val svc = JarviNotificationListener.instance
                if (svc == null) {
                    "Notification service abhi jud nahi paayi. Thodi der baad dobara bolo, ya Permissions mein ek baar OFF phir ON karo."
                } else {
                    svc.dismissAll()
                    "Saare notification saaf kar diye ✅"
                }
            }
        }
    }
}
