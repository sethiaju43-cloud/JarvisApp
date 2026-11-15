package com.jarvis.app

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import java.util.TimeZone

/**
 * Features screen: driving mode, auto-reply, reminders, to-do aur notification, sab buttons se.
 * (Bolkar bhi ho jata hai, ye screen dekhne aur badalne ke liye hai.)
 */
class FeaturesActivity : ScreenActivity() {

    private var firstResume = true

    override fun screenTitle() = "Features"

    override fun onResume() {
        super.onResume()
        // Settings se wapas aane par naya haal dikhao
        if (firstResume) firstResume = false else rebuild()
    }

    private fun askText(title: String, hint: String, onOk: (String) -> Unit) {
        val et = EditText(this)
        et.hint = hint
        AlertDialog.Builder(this).setTitle(title).setView(et)
            .setPositiveButton("OK") { _, _ ->
                val t = et.text.toString().trim()
                if (t.isNotEmpty()) onOk(t)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirm(title: String, onYes: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title)
            .setPositiveButton("Haan") { _, _ -> onYes() }
            .setNegativeButton("Nahi", null)
            .show()
    }

    /** Ek line: text + ek ya do chhote button */
    private fun itemRow(label: String, b1: String, a1: () -> Unit, b2: String = "", a2: () -> Unit = {}): LinearLayout {
        val c = this
        val r = UiKit.row(c)
        val t = UiKit.text(c, label, 14f)
        t.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f)
        r.addView(t)
        r.addView(UiKit.rowButton(c, b1, a1))
        if (b2.isNotEmpty()) r.addView(UiKit.rowButton(c, b2, a2))
        return r
    }

    override fun build() {
        val c: Context = this
        val now = System.currentTimeMillis()
        val tz = TimeZone.getDefault()
        val listenerOn = JarviNotificationListener.isEnabled(c)

        // ---------- Driving mode ----------
        container.addView(UiKit.section(c, "🚗 Driving mode"))
        val dv = UiKit.card(c)
        val driving = DrivingMode.isOn(c)
        dv.addView(UiKit.text(c, if (driving) "Chalu hai (lagbhag " + DrivingMode.remainingMinutes(c) + " minute baaki)" else "Band hai", 14f, true))
        dv.addView(UiKit.text(c, "Chalu hone par jawab chhote ho jate hain aur naye message bolkar sunaye jate hain. " + DrivingMode.DEFAULT_HOURS + " ghante baad apne aap band.", 12f, dim = true))
        dv.addView(UiKit.switchRow(c, "Driving mode", driving) { on ->
            if (on) DrivingMode.turnOn(c) else DrivingMode.turnOff(c)
            rebuild()
        })
        container.addView(dv)

        // ---------- Auto-reply ----------
        container.addView(UiKit.section(c, "💬 Auto-reply"))
        val ar = UiKit.card(c)
        val arOn = AutoReply.isOn(c)
        ar.addView(UiKit.text(c, if (arOn) "Chalu hai (lagbhag " + AutoReply.remainingMinutes(c) + " minute baaki)" else "Band hai", 14f, true))
        ar.addView(UiKit.text(c, "Har jawab bhejne se pehle aapse poochha jata hai (notification mein Bhejo / Mat bhejo). Group chat mein kabhi nahi.", 12f, dim = true))
        if (!listenerOn) {
            ar.addView(UiKit.text(c, "⚠️ Pehle neeche se 'Notification access' Allow karo.", 12f))
        }
        ar.addView(UiKit.switchRow(c, "Auto-reply (" + AutoReplyLogic.DEFAULT_HOURS + " ghante ke liye)", arOn) { on ->
            if (on) {
                if (!JarviNotificationListener.isEnabled(c)) {
                    UiKit.toast(c, "Pehle Notification access Allow karo")
                } else {
                    AutoReply.turnOn(c, AutoReplyLogic.DEFAULT_HOURS)
                }
            } else {
                AutoReply.turnOff(c)
            }
            rebuild()
        })
        ar.addView(UiKit.text(c, "Jawab: " + AutoReply.message(c), 13f))
        ar.addView(UiKit.button(c, "✏️ Jawab ka text badlo") {
            askText("Auto-reply ka jawab", "jaise: Abhi busy hun, baad mein baat karte hain") { t ->
                if (NotificationLogic.looksSecret("", t)) {
                    UiKit.toast(c, "Is text mein OTP/password jaisi cheez lag rahi hai, nahi rakh sakti")
                } else {
                    AutoReply.setMessage(c, t.take(200))
                    rebuild()
                }
            }
        })
        ar.addView(UiKit.switchRow(c, "Sabhi 1-to-1 chat ke liye (har baar poochhke)", AutoReply.everyone(c)) { on ->
            AutoReply.setEveryone(c, on)
        })
        val who = AutoReply.contacts(c)
        ar.addView(UiKit.text(c, if (who.isEmpty()) "Chune hue log: koi nahi" else "Chune hue log:", 13f, true))
        for (name in who) {
            ar.addView(itemRow(name, "🗑", {
                AutoReply.removeContact(c, name)
                rebuild()
            }))
        }
        ar.addView(UiKit.button(c, "➕ Kisi ko jodo") {
            askText("Contact ka naam (jaise WhatsApp mein dikhta hai)", "jaise: Rahul") { n ->
                AutoReply.addContact(c, n.take(60))
                rebuild()
            }
        })
        val sent = AutoReply.logLines(c).take(5)
        if (sent.isNotEmpty()) {
            ar.addView(UiKit.text(c, "Pichhle bheje gaye jawab:", 13f, true))
            for (s in sent) ar.addView(UiKit.text(c, s, 12f, dim = true))
        }
        container.addView(ar)

        // ---------- Reminders ----------
        container.addView(UiKit.section(c, "⏰ Reminders"))
        val rm = UiKit.card(c)
        val reminders = Reminders.list(c)
        if (reminders.isEmpty()) {
            rm.addView(UiKit.text(c, "Abhi koi reminder nahi hai.", 13f, dim = true))
        }
        for (r in reminders) {
            val label = ReminderLogic.describe(r.whenMs, now, tz) + (if (r.repeatDaily) " (roz)" else "") + ": " + r.message
            rm.addView(itemRow(label, "🗑", {
                Reminders.remove(c, r.id)
                rebuild()
            }))
        }
        if (!Reminders.notificationsAllowed(c)) {
            rm.addView(UiKit.text(c, "⚠️ Notifications band hain, isliye reminder dikhega nahi. Permissions mein Allow karo.", 12f))
        }
        rm.addView(UiKit.button(c, "➕ Naya reminder") {
            askText("Reminder likho ('yaad dilana' shabd ke saath)", "jaise: kal subah 8 baje yaad dilana dawai leni hai") { t ->
                val reply = FeatureCommands.handle(c, t)
                UiKit.toast(c, reply ?: "Samajh nahi aaya. 'yaad dilana' shabd ke saath likho, jaise: 5 baje yaad dilana chai")
                rebuild()
            }
        })
        container.addView(rm)

        // ---------- To-do ----------
        container.addView(UiKit.section(c, "📝 To-do"))
        val td = UiKit.card(c)
        val todos = Todos.list(c)
        val pending = todos.filter { !it.done }
        val doneCount = todos.size - pending.size
        if (pending.isEmpty()) {
            td.addView(UiKit.text(c, if (todos.isEmpty()) "To-do list khali hai." else "Sab kaam ho chuke hain 🎉", 13f, dim = true))
        }
        for (t in pending) {
            td.addView(itemRow(t.text, "✅", {
                Todos.markDone(c, t.id)
                rebuild()
            }, "🗑", {
                Todos.remove(c, t.id)
                rebuild()
            }))
        }
        if (doneCount > 0) td.addView(UiKit.text(c, "✅ $doneCount ho chuke", 12f, dim = true))
        td.addView(UiKit.button(c, "➕ Naya kaam") {
            askText("Naya kaam", "jaise: dudh lana") { t ->
                if (Todos.add(c, t) == null) UiKit.toast(c, "To-do list bhar gayi")
                rebuild()
            }
        })
        if (todos.isNotEmpty()) {
            td.addView(UiKit.button(c, "🧹 Poori list saaf karo") {
                confirm("Poori to-do list saaf karni hai?") {
                    Todos.removeAll(c)
                    rebuild()
                }
            })
        }
        container.addView(td)

        // ---------- Notifications ----------
        container.addView(UiKit.section(c, "🔔 Notifications padhna"))
        val nt = UiKit.card(c)
        nt.addView(UiKit.text(c, if (listenerOn) "Allow hai ✅ (abhi " + NotificationStore.recent(now).size + " notification yaad hain, sirf phone ki memory mein)" else "Allow nahi hai", 14f, true))
        nt.addView(UiKit.text(c, "Bank, UPI aur OTP wale notification kabhi nahi padhe jate. Kuch cloud AI ko nahi jata.", 12f, dim = true))
        nt.addView(UiKit.button(c, "🔐 Notification access kholo") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        container.addView(nt)
    }
}
