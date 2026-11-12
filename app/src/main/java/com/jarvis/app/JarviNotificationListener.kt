package com.jarvis.app

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/**
 * Notifications padhne wali service (sirf tab chalti hai jab user Settings mein 'Notification access' allow kare).
 * Text sirf phone ki RAM mein (NotificationStore) rehta hai, kahin save nahi hota aur cloud AI ko nahi jata.
 * Bank/UPI/authenticator ke notification gine hi nahi jate, aur OTP/code wale ka text chhupa diya jata hai.
 */
class JarviNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: JarviNotificationListener? = null

        fun isEnabled(ctx: Context): Boolean =
            try {
                NotificationManagerCompat.getEnabledListenerPackages(ctx.applicationContext).contains(ctx.packageName)
            } catch (e: Exception) {
                false
            }
    }

    override fun onListenerConnected() {
        instance = this
        try {
            val active = activeNotifications
            if (active != null) for (sbn in active) add(sbn, false)
        } catch (e: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) add(sbn, true)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null) NotificationStore.remove(sbn.key)
    }

    private fun appLabel(pkg: String): String =
        try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }

    // Driving mode mein ek hi message do baar na bole (notification update hone par)
    private val spoken = HashMap<String, String>()

    private fun add(sbn: StatusBarNotification, announce: Boolean) {
        try {
            if (sbn.packageName == packageName) return
            val n = sbn.notification ?: return
            // Chalte hue (music, download) aur group ke summary notification chhodo
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            val ex = n.extras
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
            val item = NotificationLogic.make(sbn.key, sbn.packageName, appLabel(sbn.packageName), title, text, sbn.postTime)
            if (item != null) {
                NotificationStore.put(item)
                // Driving mode chalu ho to naya message bolkar sunao (sirf message category, code/OTP kabhi nahi)
                if (announce && n.category == Notification.CATEGORY_MESSAGE && DrivingMode.isOn(this)) {
                    val line = DrivingLogic.speakLine(item.app, item.title, item.text)
                    if (line != null && spoken[sbn.key] != item.text) {
                        if (spoken.size > 100) spoken.clear()
                        spoken[sbn.key] = item.text
                        DrivingSpeaker.say(this, line)
                    }
                }
            }
        } catch (e: Exception) {
        }
    }

    /** Saare hatane layak notification saaf karo (user ke kehne par) */
    fun dismissAll() {
        try {
            cancelAllNotifications()
        } catch (e: Exception) {
        }
        NotificationStore.clear()
    }
}
