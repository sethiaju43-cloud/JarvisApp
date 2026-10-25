package com.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Bade model ka download background mein chalta rahe, isliye foreground service (notification dikhti hai) */
class ModelDownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notif(title: String, text: String, pct: Int): Notification {
        val channel = "jarvis_download"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channel, "Model download", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val b = NotificationCompat.Builder(this, channel)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
        if (pct in 0..100) b.setProgress(100, pct, false) else b.setProgress(0, 0, true)
        return b.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("id")
        val model = if (id != null) LocalModelCatalog.find(this, id) else null
        val first = notif("Model download", "Shuru ho raha hai...", -1)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(2, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(2, first)
            }
        } catch (e: Exception) {
            ModelDownloader.activeId = null
            stopSelf()
            return START_NOT_STICKY
        }
        if (model == null) {
            ModelDownloader.activeId = null
            stopSelf()
            return START_NOT_STICKY
        }
        Thread {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            ModelDownloader.run(applicationContext, model) { st ->
                val pct = if (st.total > 0) (st.downloaded * 100 / st.total).toInt() else -1
                if (st.status == "running") {
                    nm.notify(2, notif(model.name, "$pct% download hua", pct))
                }
            }
            val st = ModelDownloader.state(model.id)
            stopForeground(STOP_FOREGROUND_REMOVE)
            val msg = when (st?.status) {
                "done" -> "Download poora hua"
                "cancelled" -> "Download roka gaya"
                else -> "Download fail: " + (st?.error ?: "")
            }
            val done = NotificationCompat.Builder(this, "jarvis_download")
                .setContentTitle(model.name).setContentText(msg)
                .setSmallIcon(android.R.drawable.stat_sys_download_done).setAutoCancel(true).build()
            nm.notify(3, done)
            stopSelf()
        }.start()
        return START_NOT_STICKY
    }
}
