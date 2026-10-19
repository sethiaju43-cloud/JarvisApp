package com.jarvis.app

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.animation.AccelerateDecelerateInterpolator
import android.animation.ValueAnimator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val greetings = listOf(
        "Hii! Main Jarvi hun\nBolo, kya karna hai? 😊",
        "Namaste! 🙏\nKya madad karun?",
        "Haste hue... 😄\nBatao kya chahiye?",
        "Sochti hui... 🤔\nKuch poochna hai?"
    )
    private var greetIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Character ko halka halka upar-niche float karwao
        ObjectAnimator.ofFloat(b.imgCharacter, "translationY", 0f, -18f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // Character par tap karo to bubble ka text badle
        b.imgCharacter.setOnClickListener {
            greetIndex = (greetIndex + 1) % greetings.size
            b.tvBubble.text = greetings[greetIndex]
        }

        b.btnMic.setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }
        b.btnApps.setOnClickListener {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }
        b.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Overlay permission mili hai to floating Jarvi start karo
        if (Settings.canDrawOverlays(this)) {
            ContextCompat.startForegroundService(
                this, Intent(this, FloatingService::class.java)
            )
        }
    }
}
