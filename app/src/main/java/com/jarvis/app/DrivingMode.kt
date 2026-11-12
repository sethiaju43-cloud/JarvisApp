package com.jarvis.app

import android.content.Context
import android.speech.tts.TextToSpeech

/**
 * Driving mode (haath se ON/OFF). Chalu hone par:
 *  - Jarvi ke jawab bahut chhote ho jate hain (Prompts mein hint jata hai)
 *  - naye message ke notification bolkar sunaye jate hain (Notification access allow ho to)
 * Safety: 3 ghante baad apne aap band ho jata hai, taaki bhoolne par chalu na rahe.
 */
object DrivingMode {

    private const val KEY = "driving_until"
    const val DEFAULT_HOURS = 3

    private fun until(ctx: Context): Long = Prefs.str(ctx, KEY, "0").toLongOrNull() ?: 0L

    fun isOn(ctx: Context): Boolean = System.currentTimeMillis() < until(ctx)

    fun remainingMinutes(ctx: Context): Int {
        val ms = until(ctx) - System.currentTimeMillis()
        return if (ms <= 0) 0 else (ms / 60000L).toInt() + 1
    }

    fun turnOn(ctx: Context, hours: Int = DEFAULT_HOURS) {
        Prefs.setStr(ctx, KEY, (System.currentTimeMillis() + hours * 3_600_000L).toString())
    }

    fun turnOff(ctx: Context) {
        Prefs.setStr(ctx, KEY, "0")
        DrivingSpeaker.stop()
    }

    /** Prompt mein jodne wali line (band ho to khali) */
    fun line(ctx: Context): String = if (isOn(ctx)) DrivingLogic.PROMPT_LINE else ""
}

/** Driving mode mein naye message bolkar sunane wala (apna alag TextToSpeech) */
object DrivingSpeaker {

    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayList<String>()
    private var lastSpokenAt = 0L

    @Synchronized
    fun say(ctx: Context, text: String) {
        val now = System.currentTimeMillis()
        if (now - lastSpokenAt < 4000L) return // bahut jaldi jaldi message aaye to sab mat bolo
        lastSpokenAt = now
        val app = ctx.applicationContext
        if (tts == null) {
            pending.add(text)
            tts = TextToSpeech(app) { status ->
                synchronized(this) {
                    ready = status == TextToSpeech.SUCCESS
                    if (ready) {
                        for (p in pending) speakNow(app, p)
                    }
                    pending.clear()
                }
            }
            return
        }
        if (!ready) {
            pending.add(text)
            return
        }
        speakNow(app, text)
    }

    private fun speakNow(app: Context, text: String) {
        val t = tts ?: return
        try {
            VoiceHelper.apply(app, t, text)
            t.speak(text, TextToSpeech.QUEUE_ADD, null, "jarvi_drive")
        } catch (e: Exception) {
        }
    }

    @Synchronized
    fun stop() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
        }
        tts = null
        ready = false
        pending.clear()
    }
}
