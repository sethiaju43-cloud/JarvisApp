package com.jarvis.app

import android.Manifest
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.Random

class FloatingService : Service() {

    companion object {
        var instance: FloatingService? = null

        /** Screenshot ke waqt Jarvi ko chhupana taaki wo button ko na dhake */
        fun hideForShot(hide: Boolean) {
            val s = instance ?: return
            s.handler.post {
                s.floatView?.visibility = if (hide) View.INVISIBLE else View.VISIBLE
            }
        }
    }

    private var wm: WindowManager? = null
    var floatView: View? = null
    private var bubble: TextView? = null
    private var pet: PetAnimator? = null
    private lateinit var params: WindowManager.LayoutParams
    val handler = Handler(Looper.getMainLooper())
    private val rnd = Random()

    private var walkAnim: ValueAnimator? = null
    private var hideRunnable: Runnable? = null
    private var shiftX = 0
    private var shiftY = 0
    private var dragging = false
    private var listening = false
    private var lastTouch = System.currentTimeMillis()
    private var accWarnAt = 0L

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val history = ArrayList<Pair<String, String>>()

    private val idleLines = listOf(
        "Hello jaan! 😊", "Kya kar rahe ho? 😀", "Main yahan hun! 🙌",
        "Bore ho rahe ho? 🤔", "Mujhse baat karo na 💕"
    )

    override fun onBind(intent: Intent?): IBinder? = null

    private fun installCrashLogger() {
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(object : Thread.UncaughtExceptionHandler {
            override fun uncaughtException(t: Thread, e: Throwable) {
                try {
                    val sw = java.io.StringWriter()
                    e.printStackTrace(java.io.PrintWriter(sw))
                    getSharedPreferences("jarvis", MODE_PRIVATE).edit()
                        .putString("crash", (java.util.Date().toString() + "\n" + sw.toString()).take(900))
                        .commit()
                } catch (x: Exception) {
                }
                // Background thread ki galti se poora app (aur Accessibility) na gire
                if (t === Looper.getMainLooper().thread) {
                    old?.uncaughtException(t, e)
                }
            }
        })
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashLogger()
        tts = TextToSpeech(applicationContext, TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                val t = tts
                if (t != null) {
                    val r = t.setLanguage(Locale("hi", "IN"))
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        t.setLanguage(Locale.getDefault())
                    }
                    t.setPitch(1.25f)
                    t.setSpeechRate(1.0f)
                    t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            handler.post { pet?.talk(60000) }
                        }

                        override fun onDone(utteranceId: String?) {
                            handler.post { calm() }
                        }

                        override fun onError(utteranceId: String?) {
                            handler.post { calm() }
                        }
                    })
                    ttsReady = true
                }
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        if (floatView == null && Settings.canDrawOverlays(this)) {
            showFloating()
        }
        return START_STICKY
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            val micOk = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (micOk) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(1, notif, type)
        } else {
            startForeground(1, notif)
        }
    }

    private fun showFloating() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val v = LayoutInflater.from(this).inflate(R.layout.floating_view, null)
        floatView = v

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

        bubble = v.findViewById<TextView>(R.id.tvFloatBubble)
        val petBox = v.findViewById<View>(R.id.petBox)
        val front = v.findViewById<ImageView>(R.id.petFront)
        val back = v.findViewById<ImageView>(R.id.petBack)
        pet = PetAnimator(applicationContext, petBox, front, back)

        petBox.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var downAt = 0L
            private var moved = false

            override fun onTouch(view: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        walkAnim?.cancel()
                        dragging = true
                        moved = false
                        downAt = System.currentTimeMillis()
                        startX = params.x
                        startY = params.y
                        touchX = e.rawX
                        touchY = e.rawY
                        lastTouch = System.currentTimeMillis()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - touchX).toInt()
                        val dy = (e.rawY - touchY).toInt()
                        if (!moved && (Math.abs(dx) > 12 || Math.abs(dy) > 12)) {
                            moved = true
                            pet?.stop()
                            pet?.show("walk_left")
                        }
                        if (moved) {
                            if (Math.abs(dx) > 3) pet?.face(if (dx > 0) 1 else -1)
                            params.x = startX + dx
                            params.y = startY + dy
                            updateLayout()
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        dragging = false
                        if (moved) {
                            say("Wah, nayi jagah! 😍", 2000L)
                            pet?.laugh(1800)
                        } else if (System.currentTimeMillis() - downAt > 700) {
                            openChat()
                        } else {
                            onPetTapped()
                        }
                        return true
                    }
                }
                return false
            }
        })

        wm?.addView(v, params)
        handler.postDelayed({
            say("Hello jaan! 😊", 2500L)
            pet?.wave(2400)
        }, 1000)
        handler.postDelayed(auto, 9000)
    }

    private fun updateLayout() {
        val v = floatView ?: return
        try {
            wm?.updateViewLayout(v, params)
        } catch (e: Exception) {
        }
    }

    // ---------- Bubble ----------
    private fun say(text: String, ms: Long) {
        val b = bubble ?: return
        hideBubbleNow()
        b.text = text
        b.visibility = View.VISIBLE
        b.measure(
            View.MeasureSpec.makeMeasureSpec(dp(120), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        shiftY = minOf(b.measuredHeight + dp(2), params.y.coerceAtLeast(0))
        shiftX = ((b.measuredWidth - dp(90)) / 2).coerceAtLeast(0)
        params.y -= shiftY
        params.x -= shiftX
        updateLayout()
        val r = Runnable { hideBubbleNow() }
        hideRunnable = r
        handler.postDelayed(r, ms)
    }

    private fun hideBubbleNow() {
        val old = hideRunnable
        if (old != null) handler.removeCallbacks(old)
        hideRunnable = null
        val b = bubble ?: return
        if (b.visibility == View.VISIBLE) {
            b.visibility = View.GONE
            params.y += shiftY
            params.x += shiftX
            shiftX = 0
            shiftY = 0
            updateLayout()
        }
    }

    private fun calm() {
        pet?.stop()
        pet?.show("talk_closed")
    }

    // ---------- Tap par sunna ----------
    private fun onPetTapped() {
        lastTouch = System.currentTimeMillis()
        val p = pet ?: return
        if (listening) {
            cancelListening()
            return
        }
        if (p.isSleeping()) p.wake()
        stopSpeaking()
        startListening()
    }

    private fun startListening() {
        val micOk = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!micOk) {
            say("Pehle Permissions mein Microphone allow karo 🎤", 3500L)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say("Is phone mein voice sunne ki service nahi mili 😕", 3500L)
            return
        }
        listening = true
        say("Bolo jaan... 🎤", 10000L)
        pet?.think(30000)

        val lang = getSharedPreferences("jarvis", MODE_PRIVATE)
            .getString("lang", "hi-IN") ?: "hi-IN"
        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(b0: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, b0: Bundle?) {}

            override fun onError(error: Int) {
                if (!listening) return
                listening = false
                destroyRecognizer()
                calm()
                if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    say("Kuch sunai nahi diya jaan 🥺", 2500L)
                } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    say("Microphone allow karo 🎤", 3000L)
                } else {
                    say("Mic mein dikkat aayi (code " + error + ") 😕", 3000L)
                }
            }

            override fun onResults(results: Bundle?) {
                listening = false
                destroyRecognizer()
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull()
                if (text.isNullOrBlank()) {
                    calm()
                    say("Kuch sunai nahi diya jaan 🥺", 2500L)
                } else {
                    handleSpoken(text)
                }
            }
        })

        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        sr.startListening(i)
    }

    private fun cancelListening() {
        listening = false
        destroyRecognizer()
        calm()
        hideBubbleNow()
    }

    private fun destroyRecognizer() {
        val old = recognizer
        recognizer = null
        handler.post {
            try {
                old?.destroy()
            } catch (e: Exception) {
            }
        }
    }

    private fun stopSpeaking() {
        try {
            tts?.stop()
        } catch (e: Exception) {
        }
    }

    // ---------- Jo bola use samajhna ----------
    private fun handleSpoken(text: String) {
        pet?.think(30000)
        say("Soch rahi hun... 💭", 20000L)

        val cmdReply = CommandHandler.handle(this, text)
        if (cmdReply != null) {
            reply(cmdReply)
            return
        }

        history.add(Pair("user", text))
        val snapshot = ArrayList(history)
        val key = getSharedPreferences("jarvis", MODE_PRIVATE)
            .getString("api_key", "") ?: ""
        Thread {
            var r = "Kuch dikkat aa gayi jaan 🥺 Dobara bolo"
            try {
                r = GeminiClient.askSmart(applicationContext, key, snapshot)
            } catch (e: Exception) {
            }
            val finalReply = r
            handler.post {
                history.add(Pair("model", finalReply))
                while (history.size > 16) {
                    history.removeAt(0)
                    history.removeAt(0)
                }
                reply(finalReply)
            }
        }.start()
    }

    private fun reply(text: String) {
        say(text, 2500L + text.length * 70L)
        val t = tts
        if (t != null && ttsReady) {
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvi")
        } else {
            pet?.talk(2500)
        }
    }

    private fun openChat() {
        val i = Intent(this, ChatActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    // ---------- Accessibility atak gayi to bata do ----------
    private fun checkAccessibility() {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        if (enabled.contains(packageName) && JarvisAccessibilityService.instance == null) {
            val now = System.currentTimeMillis()
            if (now - accWarnAt > 600000L) {
                accWarnAt = now
                say("Accessibility atak gayi 😕 Settings mein OFF phir ON karo", 7000L)
            }
        }
    }

    // ---------- Khud se kuch karna ----------
    private val auto = object : Runnable {
        override fun run() {
            val p = pet
            if (p != null && !dragging && !listening && !p.isSleeping()) {
                checkAccessibility()
                if (System.currentTimeMillis() - lastTouch > 120000) {
                    hideBubbleNow()
                    p.sleep()
                    say("Zzz... 😴", 2500L)
                } else if (!p.busy) {
                    when (rnd.nextInt(3)) {
                        0 -> {
                            say(idleLines[rnd.nextInt(idleLines.size)], 2600L)
                            p.wave(2600)
                        }
                        1 -> {
                            say(idleLines[rnd.nextInt(idleLines.size)], 3000L)
                            p.talk(3000)
                        }
                        else -> walkRandom()
                    }
                }
            }
            handler.postDelayed(this, 9000)
        }
    }

    private fun walkRandom() {
        val p = pet ?: return
        val v = floatView ?: return
        hideBubbleNow()
        val maxX = resources.displayMetrics.widthPixels - v.width
        if (maxX <= 0) return
        val tx = rnd.nextInt(maxX)
        val dist = Math.abs(tx - params.x)
        if (dist < dp(60)) return
        val dur = (dist * 4L).coerceAtLeast(900L)
        p.face(if (tx > params.x) 1 else -1)
        p.walkFrames(dur)
        walkAnim?.cancel()
        val a = ValueAnimator.ofInt(params.x, tx)
        a.duration = dur
        a.interpolator = LinearInterpolator()
        a.addUpdateListener { anim ->
            params.x = anim.animatedValue as Int
            updateLayout()
        }
        walkAnim = a
        a.start()
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        walkAnim?.cancel()
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
        }
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
        }
        pet?.release()
        val v = floatView
        if (v != null) {
            try {
                wm?.removeView(v)
            } catch (e: Exception) {
            }
        }
        floatView = null
        super.onDestroy()
    }
}
