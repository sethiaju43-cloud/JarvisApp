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
    private val history = mutableListOf<Pair<String, String>>()
    private lateinit var adapter: MessageAdapter
    private var tts: TextToSpeech? = null
    private var ttsReady = false

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

        // Purani chat wapas dikhao, na ho to greeting
        val saved = ChatStore.load(this)
        if (saved.isEmpty()) {
            addMessage("Haan! Main yahan hun. Bolo, kya karna hai? 😊", false)
        } else {
            messages.addAll(saved)
            adapter.notifyDataSetChanged()
            b.rvChat.scrollToPosition(messages.size - 1)
        }

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

    private fun sendMessage(text: String) {
        addMessage(text, true)

        // Pehle seedha command check karo (app kholna, back, home...)
        val cmdReply = CommandHandler.handle(this, text)
        if (cmdReply != null) {
            // "Bhool jao" bola to chat bhi saaf kar do
            if (cmdReply.contains("sab bhool gayi")) {
                messages.clear()
                history.clear()
                adapter.notifyDataSetChanged()
                ChatStore.clear(this)
            }
            addMessage(cmdReply, false)
            speak(cmdReply)
            return
        }

        // Seedha command nahi mila to Gemini samjhega aur jawab dega
        history.add("user" to text)
        addMessage("...", false)
        val thinkingIndex = messages.size - 1
        val key = getSharedPreferences("jarvis", MODE_PRIVATE).getString("api_key", "") ?: ""

        Thread {
            var reply = "Kuch dikkat aa gayi jaan 🥺 Dobara bolo"
            try {
                reply = GeminiClient.askSmart(this@ChatActivity, key, history.toList())
            } catch (e: Exception) {
            }
            val finalReply = reply
            runOnUiThread {
                history.add("model" to finalReply)
                messages[thinkingIndex] = ChatMessage(finalReply, false)
                adapter.notifyItemChanged(thinkingIndex)
                ChatStore.save(this@ChatActivity, messages)
                b.rvChat.scrollToPosition(messages.size - 1)
                speak(finalReply)
            }
        }.start()
    }

    private fun addMessage(text: String, isUser: Boolean) {
        messages.add(ChatMessage(text, isUser))
        adapter.notifyItemInserted(messages.size - 1)
        b.rvChat.scrollToPosition(messages.size - 1)
        ChatStore.save(this, messages)
    }

    private fun speak(text: String) {
        val t = tts
        if (t != null && ttsReady) {
            VoiceHelper.apply(this, t)
            var s = text.replace(Regex("[\\p{So}\\p{Cn}\\uFE0F\\u200D]"), " ")
            s = s.replace("*", "").replace("#", "").replace(Regex("\\s+"), " ").trim()
            if (s.isEmpty()) s = "Ji"
            t.speak(s.take(3500), TextToSpeech.QUEUE_FLUSH, null, "jarvis")
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
