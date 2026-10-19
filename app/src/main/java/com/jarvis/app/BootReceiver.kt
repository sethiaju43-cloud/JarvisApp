package com.jarvis.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("auto_start", false)
            if (autoStart && Settings.canDrawOverlays(context)) {
                ContextCompat.startForegroundService(
                    context, Intent(context, FloatingService::class.java)
                )
            }
        }
    }
}
