package com.jarvis.app

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings (upar se neeche kheenchne par) ka "Jarvi" button: dabate hi voice card khulta hai.
 * Tile jodne ke liye: Quick Settings kholo, ✏️ (edit) dabao, "Jarvi" ko upar kheench kar rakho.
 */
class JarviTileService : TileService() {

    override fun onStartListening() {
        val t = qsTile ?: return
        t.state = Tile.STATE_INACTIVE
        t.label = "Jarvi"
        t.icon = Icon.createWithResource(this, android.R.drawable.ic_btn_speak_now)
        t.updateTile()
    }

    override fun onClick() {
        val i = Intent(this, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(i)
            }
        } catch (e: Exception) {
        }
    }
}
