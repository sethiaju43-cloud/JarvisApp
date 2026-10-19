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
import java.util.Locale

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

        addMessage("Haan! Main yahan hun. Bolo, kya karna hai? 😊", false)

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
            val r = tts?.setLanguage(Locale("hi", "IN"))
            ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            if (!ttsReady) {
                tts?.setLanguage(Locale.getDefault())
                ttsReady = true
            }
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

        // Pehle check karo ki ye phone control command hai ya nahi
        val cmdReply = CommandHandler.handle(this, text)
        if (cmdReply != null) {
            addMessage(cmdReply, false)
            speak(cmdReply)
            return
        }

        // Command nahi hai to Gemini se baat karo
        history.add("user" to text)
        addMessage("...", false)
        val thinkingIndex = messages.size - 1
        val key = getSharedPreferences("jarvis", MODE_PRIVATE).getString("api_key", "") ?: ""

        Thread {
            val reply = GeminiClient.ask(key, history.toList())
            runOnUiThread {
                history.add("model" to reply)
                messages[thinkingIndex] = ChatMessage(reply, false)
                adapter.notifyItemChanged(thinkingIndex)
                b.rvChat.scrollToPosition(messages.size - 1)
                speak(reply)
            }
        }.start()
    }

    private fun addMessage(text: String, isUser: Boolean) {
        messages.add(ChatMessage(text, isUser))
        adapter.notifyItemInserted(messages.size - 1)
        b.rvChat.scrollToPosition(messages.size - 1)
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
