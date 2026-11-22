package com.jarvis.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import java.util.TimeZone

/** Local skills ko chalata hai (sab phone par, bina AI ke aur bina internet ke) */
object LocalSkills {

    fun run(ctx: Context, c: LocalCmd): String {
        val app = ctx.applicationContext
        return when (c) {
            is LocalCmd.TimeDate -> LocalSkillsLogic.timeDateReply(System.currentTimeMillis(), TimeZone.getDefault(), c.time, c.date)
            is LocalCmd.Calc -> {
                val v = CalcEngine.eval(c.expr)
                if (v == null) "Hisaab samajh nahi aaya 😕 Jaise bolo: '25 ka 18 percent kitna hota hai'"
                else if (v.isNaN() || v.isInfinite()) "Zero se bhaag nahi ho sakta 🙂"
                else c.expr.trim() + " = " + CalcEngine.format(v) + " 🧮"
            }
            is LocalCmd.MediaPause -> mediaKey(app, KeyEvent.KEYCODE_MEDIA_PAUSE, "Gaana roka ⏸", needActive = true)
            is LocalCmd.MediaPlay -> mediaKey(app, KeyEvent.KEYCODE_MEDIA_PLAY, "Gaana chalu ▶️", needActive = false)
            is LocalCmd.MediaNext -> mediaKey(app, KeyEvent.KEYCODE_MEDIA_NEXT, "Agla gaana ⏭", needActive = true)
            is LocalCmd.MediaPrev -> mediaKey(app, KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Pichhla gaana ⏮", needActive = true)
            is LocalCmd.NowPlaying -> nowPlaying(app)
            is LocalCmd.Ringer -> ringer(app, c.mode)
            is LocalCmd.ClipRead -> {
                val t = clipText(app)
                if (t.isNullOrBlank()) "Clipboard khali hai (ya Android ne abhi padhne nahi diya, app khula rakhke dobara bolo)."
                else "Clipboard mein ye hai 📋: " + (if (t.length > 300) t.take(300) + "..." else t)
            }
            is LocalCmd.ClipWrite -> {
                try {
                    val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Jarvi", c.text))
                    "Clipboard mein copy kar diya 📋"
                } catch (e: Exception) {
                    "Copy nahi ho paya 😕"
                }
            }
            is LocalCmd.ShowAlarms -> openIntent(app, Intent(AlarmClock.ACTION_SHOW_ALARMS), "Aapke alarms dikha rahi hun ⏰")
            is LocalCmd.ShowTimers -> openIntent(app, Intent(AlarmClock.ACTION_SHOW_TIMERS), "Aapke timers dikha rahi hun ⏳")
            is LocalCmd.BatteryOpt -> openIntent(
                app, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                "Battery optimization ki list khol di. Jarvis dhundhke 'Don't optimize' kar do, taaki reminder aur brief time par aayein 🔋"
            )
            is LocalCmd.Coin -> if (java.util.Random().nextBoolean()) "Sikka gira: Chhaap (Heads) 🪙" else "Sikka gira: Patt (Tails) 🪙"
            is LocalCmd.Dice -> "Dice par aaya: " + (java.util.Random().nextInt(6) + 1) + " 🎲"
            is LocalCmd.Help -> LocalSkillsLogic.HELP_TEXT
        }
    }

    private fun openIntent(app: Context, i: Intent, ok: String): String =
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(i)
            ok
        } catch (e: Exception) {
            "Ye screen is phone par khul nahi payi 😕"
        }

    private fun mediaKey(app: Context, code: Int, ok: String, needActive: Boolean): String {
        return try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (needActive && !am.isMusicActive) {
                "Abhi koi gaana ya video chal nahi raha 🙂"
            } else {
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                ok
            }
        } catch (e: Exception) {
            "Ye nahi ho paya 😕"
        }
    }

    private fun nowPlaying(app: Context): String {
        if (!JarviNotificationListener.isEnabled(app)) {
            return "Ye dekhne ke liye Settings → Permissions → 'Notification access' Allow karo 🔔"
        }
        return try {
            val msm = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val list = msm.getActiveSessions(ComponentName(app, JarviNotificationListener::class.java))
            val c = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
            val meta = c?.metadata
            val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            if (title.isNullOrBlank()) "Abhi koi gaana chal nahi raha 🙂"
            else "Abhi chal raha hai 🎵: " + title + (if (artist.isNullOrBlank()) "" else " - $artist")
        } catch (e: Exception) {
            "Gaane ki jaankari nahi mil paayi 😕"
        }
    }

    private fun ringer(app: Context, mode: String): String {
        return try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            when (mode) {
                "vibrate" -> {
                    am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                    "Phone ab vibrate par hai 📳"
                }
                "normal" -> {
                    am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                    "Phone ab normal sound par hai 🔔"
                }
                else -> {
                    val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    if (nm.isNotificationPolicyAccessGranted) {
                        am.ringerMode = AudioManager.RINGER_MODE_SILENT
                        "Phone ab silent par hai 🔕"
                    } else {
                        "Silent karne ke liye Settings → 'Do Not Disturb access' mein Jarvis ko Allow karna padega. Tab tak 'vibrate mode' bolo 📳"
                    }
                }
            }
        } catch (e: Exception) {
            "Ringer mode badal nahi paayi 😕"
        }
    }

    private fun clipText(app: Context): String? =
        try {
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.getItemAt(0)?.coerceToText(app)?.toString()
        } catch (e: Exception) {
            null
        }
}
