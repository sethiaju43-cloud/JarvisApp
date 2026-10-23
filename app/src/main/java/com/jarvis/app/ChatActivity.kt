package com.jarvis.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.jarvis.app.databinding.ActivityChatBinding

class ChatActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var b: ActivityChatBinding
    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: MessageAdapter
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var voiceWarned = false

    // Kitne jawab abhi aane baaki hain ("..." dikh raha hai)
    private var inFlight = 0

    private val voiceLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) {
                val list = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val text = list?.firstOrNull()
                if (!text.isNullOrBlank()) sendMessage(text)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityChatBinding.inflate(layoutInflater)
        setContentView(b.root)

        tts = TextToSpeech(this, this)

        adapter = MessageAdapter(messages)
        b.rvChat.layoutManager = LinearLayoutManager(this)
        b.rvChat.adapter = adapter

        b.btnBack.setOnClickListener { finish() }
        b.btnSend.setOnClickListener { sendFromInput() }
        b.btnVoice.setOnClickListener { startVoice() }
        b.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendFromInput()
                true
            } else false
        }
    }

    override fun onResume() {
        super.onResume()
        // Floating Jarvi ki baatein bhi yahin dikhni chahiye, isliye har baar save ki hui chat dobara lo
        if (inFlight == 0) reloadFromStore()
    }

    private fun reloadFromStore() {
        val saved = ChatStore.load(this)
        messages.clear()
        if (saved.isEmpty()) {
            messages.add(ChatMessage("Haan! Main yahan hun. Bolo, kya karna hai? 😊", false))
        } else {
            messages.addAll(saved)
        }
        adapter.notifyDataSetChanged()
        b.rvChat.scrollToPosition(messages.size - 1)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val t = tts
            if (t != null) {
                VoiceHelper.apply(this, t)
            }
            ttsReady = true
        }
    }

    private fun startVoice() {
        val lang = getSharedPreferences("jarvis", MODE_PRIVATE)
            .getString("lang", "hi-IN") ?: "hi-IN"
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Bolo...")
        }
        try {
            voiceLauncher.launch(i)
        } catch (e: Exception) {
            Toast.makeText(this, "Is phone mein voice input nahi mila", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendFromInput() {
        val t = b.etInput.text.toString().trim()
        if (t.isEmpty()) return
        b.etInput.setText("")
        sendMessage(t)
    }

    /** Peeche ke thread par chalta hai: Haan/Nahi, seedha command, phir Gemini */
    private fun work(text: String): String {
        val ctx = applicationContext
        if (GeminiClient.hasPending()) {
            val pr = GeminiClient.answerPending(ctx, text)
            if (pr != null) return pr
        }
        val cmd = CommandHandler.handle(ctx, text)
        if (cmd != null) return cmd
        val key = ApiKeyStore.getKey(ctx)
        // Asli yaaddasht GeminiClient ke paas hai, yahan sirf aakhri baat bhejni hai
        return GeminiClient.askSmart(ctx, key, listOf(Pair("user", text)))
    }

    private fun sendMessage(text: String) {
        // Pehla kaam chal raha ho to naya shuru mat karo (sirf "ruko" chalega)
        if (inFlight > 0) {
            val low = text.lowercase().trim()
            if (low == "ruko" || low == "ruk" || low == "stop" || low == "bas" ||
                low == "रुको" || low == "स्टॉप"
            ) {
                GeminiClient.requestStop()
                Toast.makeText(this, "Ruk rahi hun 🛑", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Ek minute jaan, pehla kaam ho raha hai ⏳", Toast.LENGTH_SHORT).show()
            }
            return
        }

        addMessage(text, true)
        val placeholder = addPlaceholder()

        Thread {
            var r: String? = null
            try {
                r = work(text)
            } catch (e: Exception) {
            }
            val reply = r ?: "Kuch dikkat aa gayi jaan 🥺 Dobara bolo"
            // Chat screen band ho tab bhi jawab save rahe
            ChatStore.append(applicationContext, reply, false)
            runOnUiThread {
                inFlight--
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (reply.contains("sab bhool gayi")) {
                    // "Bhool jao" bola: chat saaf, sirf ye jawab dikhao
                    messages.clear()
                    messages.add(ChatMessage(reply, false))
                    adapter.notifyDataSetChanged()
                    speak(reply)
                } else {
                    fillPlaceholder(placeholder, reply)
                }
            }
        }.start()
    }

    private fun addMessage(text: String, isUser: Boolean) {
        messages.add(ChatMessage(text, isUser))
        adapter.notifyItemInserted(messages.size - 1)
        b.rvChat.scrollToPosition(messages.size - 1)
        ChatStore.append(this, text, isUser)
    }

    /** "..." wala message jodo aur uski pehchaan lautao (jagah ka number yaad nahi rakhte) */
    private fun addPlaceholder(): ChatMessage {
        inFlight++
        val p = ChatMessage("...", false)
        messages.add(p)
        adapter.notifyItemInserted(messages.size - 1)
        b.rvChat.scrollToPosition(messages.size - 1)
        return p
    }

    /** "..." ki jagah jawab likho. Agar "..." hat chuka ho to chupchaap chhod do */
    private fun fillPlaceholder(p: ChatMessage, text: String) {
        val idx = messages.indexOfFirst { it === p }
        if (idx < 0) return
        messages[idx] = ChatMessage(text, false)
        adapter.notifyItemChanged(idx)
        b.rvChat.scrollToPosition(messages.size - 1)
        speak(text)
    }

    private fun speak(text: String) {
        val t = tts
        if (t != null && ttsReady) {
            var s = text.replace(Regex("[\\p{So}\\p{Cn}\\uFE0F\\u200D]"), " ")
            s = s.replace("*", "").replace("#", "").replace(Regex("\\s+"), " ").trim()
            if (s.isEmpty()) s = "Ji"
            val okVoice = VoiceHelper.apply(this, t, s)
            if (!okVoice && !voiceWarned && VoiceHelper.speakLang(this, s) == "en") {
                voiceWarned = true
                Toast.makeText(
                    this,
                    "English awaaz nahi mili. Phone Settings mein Text-to-speech se download kar lo",
                    Toast.LENGTH_LONG
                ).show()
            }
            t.speak(s.take(3500), TextToSpeech.QUEUE_FLUSH, null, "jarvis")
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
