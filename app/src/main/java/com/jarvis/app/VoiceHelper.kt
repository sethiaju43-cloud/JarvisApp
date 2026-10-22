package com.jarvis.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

object VoiceHelper {

    val names = listOf(
        "Awaaz 1: Cute", "Awaaz 2: Narm", "Awaaz 3: Khush",
        "Awaaz 4: Shant", "Awaaz 5: Tez", "Awaaz 6: Mithi"
    )
    private val pitches = floatArrayOf(1.25f, 1.1f, 1.4f, 1.0f, 1.3f, 1.2f)
    private val rates = floatArrayOf(1.0f, 0.9f, 1.1f, 0.85f, 1.2f, 0.95f)

    fun index(ctx: Context): Int {
        val i = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getInt("voice_idx", 0)
        return i.coerceIn(0, names.size - 1)
    }

    private fun engineVoices(tts: TextToSpeech): List<Voice> {
        val out = ArrayList<Voice>()
        try {
            val all = tts.voices
            if (all != null) {
                for (v in all) {
                    if (v.locale.language != "hi") continue
                    val f = v.features
                    val missing = f != null && f.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                    if (!missing) out.add(v)
                }
            }
        } catch (e: Exception) {
        }
        out.sortBy { it.name }
        return out
    }

    fun voiceCount(tts: TextToSpeech): Int {
        return engineVoices(tts).size
    }

    fun apply(ctx: Context, tts: TextToSpeech) {
        val idx = index(ctx)
        var set = false
        val vs = engineVoices(tts)
        if (vs.isNotEmpty()) {
            try {
                tts.voice = vs[idx % vs.size]
                set = true
            } catch (e: Exception) {
            }
        }
        if (!set) {
            val r = tts.setLanguage(Locale("hi", "IN"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.getDefault())
            }
        }
        tts.setPitch(pitches[idx])
        tts.setSpeechRate(rates[idx])
    }
}
