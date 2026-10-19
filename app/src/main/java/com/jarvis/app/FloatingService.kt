package com.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat

class FloatingService : Service() {

    private var wm: WindowManager? = null
    private var floatView: View? = null
    private lateinit var params: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())

    private val messages = listOf(
        "Kya kar rahe ho? 😀",
        "Bolo, kya karna hai? 😊",
        "Main yahan hun! 🙌",
        "Kuch chahiye? 🤔"
    )
    private var msgIndex = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        if (floatView == null && Settings.canDrawOverlays(this)) {
            showFloating()
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "jarvis_channel"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Jarvis", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Jarvis chalu hai")
            .setContentText("Tap karke kholo")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notif)
        }
    }

    private fun showFloating() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        floatView = LayoutInflater.from(this).inflate(R.layout.floating_view, null)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 20
        params.y = 300

        val bubble = floatView!!.findViewById<TextView>(R.id.tvFloatBubble)
        val img = floatView!!.findViewById<View>(R.id.imgFloat)

        // Drag + tap handle karo
        img.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = e.rawX
                        touchY = e.rawY
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - touchX).toInt()
                        val dy = (e.rawY - touchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true
                        params.x = startX + dx
                        params.y = startY + dy
                        wm?.updateViewLayout(floatView, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            // Tap kiya to Jarvis chat khol do
                            val i = Intent(this@FloatingService, ChatActivity::class.java)
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(i)
                        }
                        return true
                    }
                }
                return false
            }
        })

        wm?.addView(floatView, params)

        // Har 8 second mein bubble dikhao
        val bubbleRunnable = object : Runnable {
            override fun run() {
                msgIndex = (msgIndex + 1) % messages.size
                bubble.text = messages[msgIndex]
                bubble.visibility = View.VISIBLE
                handler.postDelayed({ bubble.visibility = View.GONE }, 3500)
                handler.postDelayed(this, 8000)
            }
        }
        handler.postDelayed(bubbleRunnable, 3000)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        floatView?.let { wm?.removeView(it) }
        floatView = null
        super.onDestroy()
    }
}
