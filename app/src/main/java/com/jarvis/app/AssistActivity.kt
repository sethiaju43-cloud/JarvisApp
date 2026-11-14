package com.jarvis.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Chhota "quick Jarvi" voice card: kisi bhi app ke upar neeche se aata hai.
 * Mic dabane ki zaroorat nahi: khulte hi sunna shuru, jawab bolkar sunati hai.
 * Quick Settings tile se khulta hai. Sunna sirf tab hota hai jab ye card khula ho, chupke se kabhi nahi.
 */
class AssistActivity : Activity() {

    private var voice: VoiceInput? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private lateinit var status: TextView
    private lateinit var userTv: TextView
    private lateinit var replyTv: TextView
    private var busy = false
    private var closed = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun tv(text: String, size: Float, color: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(Color.parseColor(color))
        t.setPadding(0, dp(4), 0, dp(4))
        return t
    }

    private fun btn(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        return b
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.argb(90, 0, 0, 0)))

        val root = FrameLayout(this)
        root.setOnClickListener { finish() }

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(20), dp(18), dp(20), dp(16))
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#F2101828"))
        bg.cornerRadius = dp(24).toFloat()
        card.background = bg
        card.isClickable = true // card par tap se band na ho

        status = tv("🎙 Jarvi sun rahi hai…", 16f, "#FFFFFF")
        userTv = tv("", 15f, "#9FB4D0")
        replyTv = tv("", 16f, "#FFFFFF")
        card.addView(status)
        card.addView(userTv)
        card.addView(replyTv)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(btn("🎤 Phir bolo") { listen() })
        row.addView(btn("💬 Chat kholo") {
            startActivity(Intent(this, ChatActivity::class.java))
            finish()
        })
        row.addView(btn("✖") { finish() })
        card.addView(row)

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        )
        lp.setMargins(dp(12), dp(12), dp(12), dp(24))
        root.addView(card, lp)
        setContentView(root)

        tts = TextToSpeech(this) { st -> ttsReady = st == TextToSpeech.SUCCESS }

        voice = VoiceInput(this, object : VoiceInput.Callback {
            override fun onPartial(text: String) {
                if (!closed) userTv.text = "🗣 " + text
            }

            override fun onFinal(text: String) {
                if (closed) return
                userTv.text = "🗣 " + text
                handle(text)
            }

            override fun onError(msg: String) {
                if (!closed) status.text = "😕 " + msg
            }

            override fun onState(listening: Boolean) {
                if (!closed && listening && !busy) status.text = "🎙 Jarvi sun rahi hai…"
            }
        })

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            listen()
        } else {
            status.text = "Mic ki permission chahiye…"
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 41)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                listen()
            } else {
                status.text = "Mic permission nahi mili. Jarvis → Settings → Permissions mein Microphone Allow karo."
            }
        }
    }

    private fun listen() {
        if (busy || closed) return
        try {
            tts?.stop()
        } catch (e: Exception) {
        }
        replyTv.text = ""
        userTv.text = ""
        status.text = "🎙 Jarvi sun rahi hai…"
        voice?.start()
    }

    private fun handle(text: String) {
        val said = text.trim()
        if (said.isEmpty() || busy) return
        busy = true
        status.text = "🤔 Soch rahi hun…"
        val app = applicationContext
        Thread {
            ChatStore.append(app, said, true)
            // "haan/nahi" jaise jawab pehle poochhe gaye sawaal ke liye
            val pending = if (Brain.hasPending()) Brain.answerPending(app, said) else null
            val reply = pending ?: Brain.askSmart(app, listOf(Pair("user", said)))
            val isError = pending == null && Brain.lastReplyWasError
            if (!isError) ChatStore.append(app, reply, false)
            runOnUiThread {
                busy = false
                if (closed) return@runOnUiThread
                status.text = if (isError) "😕 Dikkat aayi" else "💬 Jarvi"
                replyTv.text = reply
                if (!isError && Prefs.speakReplies(app)) speak(reply)
            }
        }.start()
    }

    private fun speakable(s: String): String =
        s.replace(Regex("[\\p{So}\\p{Cs}\\u200d\\ufe0f]"), "").replace(Regex("\\s+"), " ").trim().take(500)

    private fun speak(text: String) {
        val t = tts ?: return
        if (!ttsReady) return
        val clean = speakable(text)
        if (clean.isEmpty()) return
        try {
            VoiceHelper.apply(this, t, clean)
            t.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "jarvi_assist")
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        closed = true
        if (busy) Brain.requestStop()
        try {
            voice?.destroy()
        } catch (e: Exception) {
        }
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }
}
