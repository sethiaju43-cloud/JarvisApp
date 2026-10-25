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

    /**
     * Kis bhasha ki awaaz mein bolna hai: "hi" ya "en".
     * Jawab mein Hindi akshar (देवनागरी) ho to hamesha "hi", warna Settings ki Language ke hisaab se.
     */
    fun speakLang(ctx: Context, text: String): String {
        for (c in text) {
            if (c in 'ऀ'..'ॿ') return "hi"
        }
        val lang = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
            .getString("lang", "hi-IN") ?: "hi-IN"
        return if (lang.startsWith("en")) "en" else "hi"
    }

    private fun engineVoices(tts: TextToSpeech, lang: String): List<Voice> {
        val out = ArrayList<Voice>()
        try {
            val all = tts.voices
            if (all != null) {
                for (v in all) {
                    if (v.locale.language != lang) continue
                    val f = v.features
                    val missing = f != null && f.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                    if (!missing) out.add(v)
                }
            }
        } catch (e: Exception) {
        }
        // India wali awaaz pehle, phir naam ke hisaab se
        out.sortWith(compareBy<Voice>({ if (it.locale.country == "IN") 0 else 1 }, { it.name }))
        return out
    }

    /** (offline awaaz, online awaaz) - online wali ko internet chahiye */
    fun voiceStats(tts: TextToSpeech, lang: String): Pair<Int, Int> {
        var off = 0
        var on = 0
        for (v in engineVoices(tts, lang)) {
            if (v.isNetworkConnectionRequired) on++ else off++
        }
        return Pair(off, on)
    }

    fun voiceCount(tts: TextToSpeech, lang: String = "hi"): Int {
        return engineVoices(tts, lang).size
    }

    /**
     * Awaaz lagao. true = chahi hui bhasha ki awaaz mil gayi,
     * false = nahi mili (Hindi ya phone ki bhasha par wapas aa gaye).
     */
    fun apply(ctx: Context, tts: TextToSpeech, text: String = ""): Boolean {
        val idx = index(ctx)
        val lang = speakLang(ctx, text)
        var ok = false
        // Internet na ho to wo awaaz mat chuno jo internet maangti hai (warna phone bolta hi nahi)
        val offline = !NetworkMonitor.isOnline(ctx)
        val vs = engineVoices(tts, lang).filter { !offline || !it.isNetworkConnectionRequired }
        if (vs.isNotEmpty()) {
            try {
                tts.voice = vs[idx % vs.size]
                ok = true
            } catch (e: Exception) {
            }
        }
        if (!ok) {
            val want = if (lang == "en") Locale("en", "IN") else Locale("hi", "IN")
            val r = tts.setLanguage(want)
            ok = !(r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED)
            if (!ok) {
                val r2 = tts.setLanguage(Locale("hi", "IN"))
                if (r2 == TextToSpeech.LANG_MISSING_DATA || r2 == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.getDefault())
                }
            }
        }
        tts.setPitch(pitches[idx])
        tts.setSpeechRate(rates[idx])
        return ok
    }
}
