package com.jarvis.app

import android.content.Context
import java.util.TimeZone

/**
 * Seedhe commands jo AI ko bheje bina phone par hi ho jate hain (privacy + API ki bachat):
 *  - driving mode: "driving mode chalu karo", "driving mode band karo"
 *  - reminders: "5 baje yaad dilana ...", "mere reminders batao", "sab reminder hata do"
 *  - to-do list: "todo mein dudh lana jodo", "mere todo batao", "dudh wala todo ho gaya"
 *  - notifications: "notification padho", "notification saaf karo"
 */
object FeatureCommands {

    fun handle(ctx: Context, text: String): String? {
        val now = System.currentTimeMillis()
        val tz = TimeZone.getDefault()
        val d = DrivingLogic.parse(text)
        if (d != null) return runDriving(ctx, d)
        val r = ReminderLogic.parse(text, now, tz)
        if (r != null) return runReminder(ctx, r, now, tz)
        val t = TodoLogic.parse(text)
        if (t != null) return runTodo(ctx, t)
        val n = NotificationLogic.parseCommand(text)
        if (n != null) return runNotification(ctx, n)
        return null
    }

    private fun runDriving(ctx: Context, c: DrivingCommand): String {
        return when (c) {
            DrivingCommand.ON -> {
                DrivingMode.turnOn(ctx)
                var s = "Driving mode chalu 🚗 (" + DrivingMode.DEFAULT_HOURS + " ghante ke liye). Ab main chhote jawab dungi " +
                    "aur naye message bolkar sunaungi. Band karne ko bolo: 'driving mode band karo'. Dhyan se chalana 🙏"
                if (!JarviNotificationListener.isEnabled(ctx)) {
                    s += "\n(Message sunane ke liye Settings → Permissions → 'Notification access' Allow karo.)"
                }
                s
            }
            DrivingCommand.OFF -> {
                val was = DrivingMode.isOn(ctx)
                DrivingMode.turnOff(ctx)
                if (was) "Driving mode band ✅" else "Driving mode pehle se band tha 🙂"
            }
            DrivingCommand.STATUS ->
                if (DrivingMode.isOn(ctx)) "Driving mode chalu hai 🚗 (lagbhag " + DrivingMode.remainingMinutes(ctx) + " minute baaki)"
                else "Driving mode band hai."
        }
    }

    private fun runTodo(ctx: Context, c: TodoCommand): String {
        return when (c) {
            is TodoCommand.Add -> {
                val item = Todos.add(ctx, c.text)
                if (item == null) {
                    "To-do list bhar gayi (" + Todos.MAX_ITEMS + "). Kuch hata do 🗑"
                } else {
                    val pending = Todos.list(ctx).count { !it.done }
                    "Todo mein jod diya ✅: " + c.text + " (ab " + pending + " kaam baaki)"
                }
            }
            is TodoCommand.MissingText -> "Kya jodun? Jaise: 'todo mein dudh lana jodo' 📝"
            is TodoCommand.ShowAll -> {
                val items = Todos.list(ctx)
                val pending = items.filter { !it.done }
                val doneCount = items.size - pending.size
                if (items.isEmpty()) {
                    "Todo list khali hai 🙂 Bolo: 'todo mein dudh lana jodo'"
                } else if (pending.isEmpty()) {
                    "Sab kaam ho chuke hain 🎉 ($doneCount ho gaye). 'sab todo hata do' bolke list saaf kar sakte ho."
                } else {
                    val sb = StringBuilder("Aapke ").append(pending.size).append(" kaam baaki hain:")
                    for ((i, item) in pending.withIndex()) sb.append("\n").append(i + 1).append(") ").append(item.text)
                    if (doneCount > 0) sb.append("\n✅ ").append(doneCount).append(" ho chuke")
                    sb.toString()
                }
            }
            is TodoCommand.Done -> {
                val pending = Todos.list(ctx).filter { !it.done }
                if (c.about.isEmpty()) {
                    "Kaun sa kaam hua? 'mere todo batao' bolkar dekh lo."
                } else {
                    val m = SelfCommands.bestMatch(c.about, pending.map { it.text }, 0.5)
                    when {
                        m.index < 0 -> "Is naam ka koi baaki kaam nahi mila 🙂"
                        m.ambiguous -> "Isse milte-julte kai kaam hain. Thoda saaf bolo."
                        else -> {
                            val item = pending[m.index]
                            Todos.markDone(ctx, item.id)
                            "Shabash 🎉 ho gaya: " + item.text
                        }
                    }
                }
            }
            is TodoCommand.Remove -> {
                val items = Todos.list(ctx)
                if (c.about.isEmpty()) {
                    "Kaun sa kaam hataun? 'mere todo batao' bolkar dekh lo, ya 'sab todo hata do' bolo."
                } else {
                    val m = SelfCommands.bestMatch(c.about, items.map { it.text }, 0.5)
                    when {
                        m.index < 0 -> "Is naam ka koi kaam nahi mila 🙂"
                        m.ambiguous -> "Isse milte-julte kai kaam hain. Thoda saaf bolo ya 'sab todo hata do' bolo."
                        else -> {
                            val item = items[m.index]
                            Todos.remove(ctx, item.id)
                            "Todo se hata diya 🗑: " + item.text
                        }
                    }
                }
            }
            is TodoCommand.ClearAll -> {
                val n = Todos.removeAll(ctx)
                if (n == 0) "Todo list pehle se khali thi 🙂" else "Saari todo list saaf kar di ($n kaam) 🗑"
            }
        }
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
