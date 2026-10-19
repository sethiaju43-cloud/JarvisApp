package com.jarvis.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)

        b.btnBack.setOnClickListener { finish() }

        // API key
        b.etKey.setText(prefs.getString("api_key", ""))
        b.btnSaveKey.setOnClickListener {
            prefs.edit().putString("api_key", b.etKey.text.toString().trim()).apply()
            Toast.makeText(this, "Key save ho gayi ✅", Toast.LENGTH_SHORT).show()
        }

        // Language
        if (prefs.getString("lang", "hi-IN") == "en-IN") b.rbEnglish.isChecked = true
        else b.rbHindi.isChecked = true
        b.rgLang.setOnCheckedChangeListener { _, id ->
            val l = if (id == b.rbEnglish.id) "en-IN" else "hi-IN"
            prefs.edit().putString("lang", l).apply()
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
}
