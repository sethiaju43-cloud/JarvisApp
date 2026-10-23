package com.jarvis.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)

        b.btnBack.setOnClickListener { finish() }

        // Awaaz ka engine taiyaar karo
        tts = TextToSpeech(this, TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                refreshVoiceInfo()
            } else {
                b.tvVoiceInfo.text = "Is phone mein awaaz ka engine nahi chal paya."
            }
        })

        // API key
        // Purani khuli key ho to taale mein daalkar hata do
        val savedKey = ApiKeyStore.getKey(this)
        ApiKeyStore.removeOldCopy(this)
        b.etKey.setText(savedKey)
        b.btnSaveKey.setOnClickListener {
            ApiKeyStore.setKey(this, b.etKey.text.toString().trim())
            Toast.makeText(this, "Key save ho gayi ✅", Toast.LENGTH_SHORT).show()
        }

        // Language
        if (prefs.getString("lang", "hi-IN") == "en-IN") b.rbEnglish.isChecked = true
        else b.rbHindi.isChecked = true
        b.rgLang.setOnCheckedChangeListener { _, id ->
            val l = if (id == b.rbEnglish.id) "en-IN" else "hi-IN"
            prefs.edit().putString("lang", l).apply()
            refreshVoiceInfo()
            testVoice()
        }

        // Jarvi ki awaaz
        val saved = VoiceHelper.index(this)
        for (i in VoiceHelper.names.indices) {
            val rb = RadioButton(this)
            rb.id = 1000 + i
            rb.text = VoiceHelper.names[i]
            rb.setTextColor(ContextCompat.getColor(this, R.color.text_white))
            b.rgVoice.addView(rb)
            if (i == saved) rb.isChecked = true
        }
        b.rgVoice.setOnCheckedChangeListener { _, id ->
            prefs.edit().putInt("voice_idx", id - 1000).apply()
            testVoice()
        }
        b.btnTestVoice.setOnClickListener { testVoice() }

        // Khud se bolna aur chup rehne par poochna
        b.swIdleTalk.isChecked = prefs.getBoolean("idle_talk", true)
        b.swIdleTalk.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("idle_talk", on).apply()
        }
        b.swCheckin.isChecked = prefs.getBoolean("checkin", true)
        b.swCheckin.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("checkin", on).apply()
        }

        // SMS, call, WhatsApp bhejne se pehle poochna
        b.swConfirm.isChecked = prefs.getBoolean("confirm_send", true)
        b.swConfirm.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("confirm_send", on).apply()
        }

        // Floating character
        b.swFloating.isChecked = prefs.getBoolean("floating", true)
        b.swFloating.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("floating", on).apply()
            if (on) {
                if (Settings.canDrawOverlays(this)) {
                    ContextCompat.startForegroundService(
                        this, Intent(this, FloatingService::class.java)
                    )
                } else {
                    Toast.makeText(this, "Pehle 'Display over other apps' allow karo", Toast.LENGTH_LONG).show()
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            } else {
                stopService(Intent(this, FloatingService::class.java))
            }
        }

        // Auto start
        b.swAutoStart.isChecked = prefs.getBoolean("auto_start", false)
        b.swAutoStart.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("auto_start", on).apply()
        }

        // Stop
        b.btnStop.setOnClickListener {
            stopService(Intent(this, FloatingService::class.java))
            finishAffinity()
        }
    }

    private fun testVoice() {
        val t = tts
        if (t == null || !ttsReady) {
            Toast.makeText(this, "Awaaz abhi taiyaar nahi, thodi der baad try karo", Toast.LENGTH_SHORT).show()
            return
        }
        val en = VoiceHelper.speakLang(this, "") == "en"
        val sample = if (en) {
            "Hello, I am Jarvi. How does my voice sound?"
        } else {
            "Hello jaan, main Jarvi hun. Meri awaaz kaisi lag rahi hai?"
        }
        VoiceHelper.apply(this, t, sample)
        t.speak(sample, TextToSpeech.QUEUE_FLUSH, null, "test")
    }

    /** Chuni hui bhasha ki kitni awaaz phone mein hain, ye dikhao */
    private fun refreshVoiceInfo() {
        val t = tts
        if (t == null || !ttsReady) return
        val lang = VoiceHelper.speakLang(this, "")
        val name = if (lang == "en") "English" else "Hindi"
        val n = VoiceHelper.voiceCount(t, lang)
        val how = "phone Settings mein Text-to-speech kholo, Google engine mein " +
            name + " voice data download karo."
        b.tvVoiceInfo.text = if (n > 0) {
            "Is phone mein " + n + " " + name + " awaaz mili. Zyada awaaz ke liye: " + how
        } else {
            name + " awaaz nahi mili. " + how
        }
    }

    override fun onDestroy() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }
}
