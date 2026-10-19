package com.jarvis.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.app.databinding.ActivityPermissionsBinding

class PermissionsActivity : AppCompatActivity() {

    private lateinit var b: ActivityPermissionsBinding

    private class Perm(
        val title: String,
        val sub: String,
        val granted: () -> Boolean,
        val action: () -> Unit
    )

    private val launcher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refresh()
        }

    private lateinit var perms: List<Perm>

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPermissionsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnBack.setOnClickListener { finish() }

        perms = listOf(
            Perm("Accessibility Service", "Back, Home, Scroll, Tap",
                { JarvisAccessibilityService.instance != null },
                { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }),

            Perm("Display over other apps", "Floating Jarvi",
                { Settings.canDrawOverlays(this) },
                {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }),

            Perm("Microphone", "Voice input",
                { has(Manifest.permission.RECORD_AUDIO) },
                { launcher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }),

            Perm("Camera", "Photo / Selfie",
                { has(Manifest.permission.CAMERA) },
                { launcher.launch(arrayOf(Manifest.permission.CAMERA)) }),

            Perm("Phone & SMS", "Call, Message (optional)",
                { has(Manifest.permission.CALL_PHONE) && has(Manifest.permission.SEND_SMS) },
                {
                    launcher.launch(
                        arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS)
                    )
                }),

            Perm("Notifications", "Background running",
                {
                    Build.VERSION.SDK_INT < 33 ||
                        has(Manifest.permission.POST_NOTIFICATIONS)
                },
                {
                    if (Build.VERSION.SDK_INT >= 33) {
                        launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    }
                })
        )

        for (p in perms) {
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_permission, b.container, false)
            row.findViewById<TextView>(R.id.tvPermTitle).text = p.title
            row.findViewById<TextView>(R.id.tvPermSub).text = p.sub
            row.findViewById<TextView>(R.id.btnAllow).setOnClickListener { p.action() }
            b.container.addView(row)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        for (i in perms.indices) {
            val btn = b.container.getChildAt(i).findViewById<TextView>(R.id.btnAllow)
            if (perms[i].granted()) {
                btn.text = "Done ✓"
                btn.alpha = 0.5f
            } else {
                btn.text = "Allow"
                btn.alpha = 1f
            }
        }
    }
}
