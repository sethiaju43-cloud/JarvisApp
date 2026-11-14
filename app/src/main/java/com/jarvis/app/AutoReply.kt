package com.jarvis.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray

/**
 * Message auto-reply (sirf aapke chune logon ko, sirf 1-to-1 chat, har baar aapki haan ke baad).
 * Jawab ka text aapka apna likha template hai, AI se nahi banta, kisi ka message cloud par nahi jata.
 * Jab chalu ho: naya message aane par notification aata hai "bhejun?" [✅ Bhejo] [✖ Mat bhejo].
 */
object AutoReply {

    const val ACTION_SEND = "com.jarvis.app.AUTOREPLY_SEND"
    const val ACTION_SKIP = "com.jarvis.app.AUTOREPLY_SKIP"
    private const val PREFS = "jarvis_autoreply"
    private const val CHANNEL = "jarvis_autoreply"
    private const val DRAFT_LIFE_MS = 15L * 60L * 1000L

    private val supported = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger",
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"
    )

    class Draft(val action: Notification.Action, val text: String, val sender: String, val createdMs: Long)

    private val drafts = HashMap<Int, Draft>()
    private val lastDraftBySender = HashMap<String, Long>()
    private val draftTimes = ArrayList<Long>()
    private var nextId = 1

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- Settings ----------
    fun isOn(ctx: Context): Boolean = System.currentTimeMillis() < sp(ctx).getLong("until", 0L)

    fun remainingMinutes(ctx: Context): Int {
        val ms = sp(ctx).getLong("until", 0L) - System.currentTimeMillis()
        return if (ms <= 0) 0 else (ms / 60000L).toInt() + 1
    }

    fun turnOn(ctx: Context, hours: Int) {
        sp(ctx).edit().putLong("until", System.currentTimeMillis() + hours * 3_600_000L).apply()
    }

    fun turnOff(ctx: Context) {
        sp(ctx).edit().putLong("until", 0L).apply()
        synchronized(drafts) { drafts.clear() }
    }

    fun message(ctx: Context): String {
        val s = ApiKeyStore.open(sp(ctx).getString("msg", "") ?: "")
        return if (s.isBlank()) AutoReplyLogic.DEFAULT_MESSAGE else s
    }

    fun setMessage(ctx: Context, text: String) {
        sp(ctx).edit().putString("msg", ApiKeyStore.seal(text.take(200))).apply()
    }

    fun everyone(ctx: Context): Boolean = sp(ctx).getBoolean("everyone", false)

    fun setEveryone(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean("everyone", v).apply()
    }

    private fun readList(ctx: Context, key: String): ArrayList<String> {
        val out = ArrayList<String>()
        try {
            val arr = JSONArray(ApiKeyStore.open(sp(ctx).getString(key, "[]") ?: "[]"))
            for (i in 0 until arr.length()) out.add(arr.getString(i))
        } catch (e: Exception) {
        }
        return out
    }

    private fun writeList(ctx: Context, key: String, list: List<String>) {
        val arr = JSONArray()
        for (s in list) arr.put(s)
        sp(ctx).edit().putString(key, ApiKeyStore.seal(arr.toString())).apply()
    }

    fun contacts(ctx: Context): List<String> = readList(ctx, "contacts")

    @Synchronized
    fun addContact(ctx: Context, name: String): Boolean {
        val list = readList(ctx, "contacts")
        if (list.any { it.equals(name, ignoreCase = true) }) return false
        if (list.size >= 50) return false
        list.add(name.take(60))
        writeList(ctx, "contacts", list)
        return true
    }

    @Synchronized
    fun removeContact(ctx: Context, name: String): Boolean {
        val list = readList(ctx, "contacts")
        val before = list.size
        list.removeAll { it.equals(name, ignoreCase = true) }
        writeList(ctx, "contacts", list)
        return list.size != before
    }

    // ---------- Jo bheja gaya uska hisaab (aapko dikhane ke liye) ----------
    @Synchronized
    private fun appendLog(ctx: Context, sender: String, text: String) {
        val list = readList(ctx, "log")
        list.add(System.currentTimeMillis().toString() + "|" + sender + "|" + text)
        while (list.size > 20) list.removeAt(0)
        writeList(ctx, "log", list)
    }

    fun logLines(ctx: Context): List<String> {
        val tz = java.util.TimeZone.getDefault()
        val now = System.currentTimeMillis()
        return readList(ctx, "log").reversed().map {
            val p = it.split("|", limit = 3)
            if (p.size < 3) it else ReminderLogic.describe(p[0].toLongOrNull() ?: 0L, now, tz) + " → " + p[1] + ": " + p[2]
        }
    }

    // ---------- Naya message aane par ----------
    private fun findReplyAction(n: Notification): Notification.Action? {
        val acts = n.actions ?: return null
        if (Build.VERSION.SDK_INT >= 28) {
            for (a in acts) {
                val ri = a.remoteInputs
                if (a.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY && ri != null && ri.isNotEmpty() && a.actionIntent != null) return a
            }
        }
        for (a in acts) {
            val ri = a.remoteInputs
            if (ri != null && ri.isNotEmpty() && a.actionIntent != null) return a
        }
        return null
    }

    private fun isGroup(n: Notification): Boolean {
        val ex = n.extras
        if (ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)) return true
        return ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) != null
    }

    /** Listener se bulaya jata hai: draft banana chahiye to user se poochhne wala notification dikhao */
    fun consider(ctx: Context, sbn: StatusBarNotification, item: NotificationLogic.Item) {
        try {
            if (!isOn(ctx)) return
            if (sbn.packageName !in supported) return
            val n = sbn.notification ?: return
            val action = findReplyAction(n)
            val now = System.currentTimeMillis()
            val sender = item.title
            val ok: Boolean
            val id: Int
            synchronized(drafts) {
                val it = drafts.entries.iterator()
                while (it.hasNext()) if (now - it.next().value.createdMs > DRAFT_LIFE_MS) it.remove()
                draftTimes.removeAll { now - it > 3_600_000L }
                ok = AutoReplyLogic.shouldDraft(
                    true, everyone(ctx), contacts(ctx), sender, isGroup(n),
                    item.text == NotificationLogic.SECRET_TEXT, action != null,
                    lastDraftBySender[sender.lowercase()], now, draftTimes.size
                )
                if (!ok || action == null) return
                id = nextId++
                drafts[id] = Draft(action, message(ctx), sender, now)
                lastDraftBySender[sender.lowercase()] = now
                draftTimes.add(now)
            }
            postDraft(ctx, id, sender, item.text, message(ctx))
        } catch (e: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun postDraft(ctx: Context, id: Int, sender: String, incoming: String, reply: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvi auto-reply", NotificationManager.IMPORTANCE_HIGH))
            }
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val send = PendingIntent.getBroadcast(
                ctx, id * 2, Intent(ctx, AutoReplyReceiver::class.java).setAction(ACTION_SEND).putExtra("id", id), flags
            )
            val skip = PendingIntent.getBroadcast(
                ctx, id * 2 + 1, Intent(ctx, AutoReplyReceiver::class.java).setAction(ACTION_SKIP).putExtra("id", id), flags
            )
            val clipped = if (incoming.length <= 100) incoming else incoming.take(100) + "..."
            val pub = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle("✉ Auto-reply: bhejna hai?")
                .build()
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle("✉ " + sender + " ko auto-reply bhejun?")
                .setContentText(clipped)
                .setStyle(NotificationCompat.BigTextStyle().bigText(sender + " ne likha: " + clipped + "\n\nMera jawab: " + reply))
                .addAction(0, "✅ Bhejo", send)
                .addAction(0, "✖ Mat bhejo", skip)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(pub)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setTimeoutAfter(10L * 60L * 1000L)
                .build()
            NotificationManagerCompat.from(ctx).notify(6000 + id % 1000, n)
        } catch (e: Exception) {
        }
    }

    private fun cancelNotif(ctx: Context, id: Int) {
        try {
            NotificationManagerCompat.from(ctx).cancel(6000 + id % 1000)
        } catch (e: Exception) {
        }
    }

    /** "✅ Bhejo" dabane par: asli reply bhejo */
    fun send(ctx: Context, id: Int): String {
        val d = synchronized(drafts) { drafts.remove(id) }
            ?: return "Ye jawab ab purana ho gaya. Dobara message aaye to phir poochhungi."
        return try {
            val ris = d.action.remoteInputs
            val intent = Intent()
            val bundle = Bundle()
            bundle.putCharSequence(ris[0].resultKey, d.text)
            RemoteInput.addResultsToIntent(ris, intent, bundle)
            d.action.actionIntent.send(ctx, 0, intent)
            appendLog(ctx, d.sender, d.text)
            cancelNotif(ctx, id)
            "Bhej diya ✅"
        } catch (e: Exception) {
            "Bhej nahi payi 😕"
        }
    }

    /** "✖ Mat bhejo" dabane par */
    fun skip(ctx: Context, id: Int) {
        synchronized(drafts) { drafts.remove(id) }
        cancelNotif(ctx, id)
    }
}
