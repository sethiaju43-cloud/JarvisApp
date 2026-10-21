package com.jarvis.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiClient {

    // Pehla model busy ho to agla try hoga
    private val MODELS = listOf(
        "gemini-3.8-flash",
        "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite"
    )

    private const val SYSTEM_PROMPT =
        "Tumhara naam Jarvi hai. Tum user ki pyaari, caring, cute dost ho " +
        "aur user ko pyaar se 'jaan' bulati ho. " +
        "User jis bhasha mein baat kare (Hindi, Hinglish ya English) usi mein jawab do. " +
        "Jawab sirf 1 ya 2 chhote, mithe vaakya mein rakho. " +
        "Kabhi kabhi ek emoji lagao. " +
        "Agar user udaas ho to pyaar se haal poochho aur himmat do."

    private class Result(val ok: Boolean, val text: String, val retry: Boolean)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** history: Pair(role, text). role = "user" ya "model" */
    fun ask(apiKey: String, history: List<Pair<String, String>>): String {
        if (apiKey.isBlank()) {
            return "Pehle Settings mein apni Gemini API Key daalo 🔑"
        }
        val body = buildBody(history)
        var lastError = "Jawab nahi aaya, dobara try karo 🙏"
        for (model in MODELS) {
            for (attempt in 1..2) {
                val r = call(apiKey, model, body)
                if (r.ok) return r.text
                lastError = r.text
                if (!r.retry) return r.text
                try {
                    Thread.sleep(1200)
                } catch (e: Exception) {
                }
            }
        }
        return lastError
    }

    private fun buildBody(history: List<Pair<String, String>>): JSONObject {
        val contents = JSONArray()
        for ((role, text) in history) {
            contents.put(
                JSONObject()
                    .put("role", role)
                    .put("parts", JSONArray().put(JSONObject().put("text", text)))
            )
        }
        return JSONObject()
            .put("contents", contents)
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))
                )
            )
    }

    private fun call(apiKey: String, model: String, body: JSONObject): Result {
        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
                model + ":generateContent"
            val req = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val result = client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    val text = parse(raw)
                    if (text.isNotBlank()) {
                        Result(true, text, false)
                    } else {
                        Result(false, "Jawab khali aaya, dobara try karo 🙏", true)
                    }
                } else {
                    var msg = "Error " + resp.code
                    try {
                        msg = JSONObject(raw).getJSONObject("error").getString("message")
                    } catch (e: Exception) {
                    }
                    val c = resp.code
                    val retry = c == 429 || c == 500 || c == 502 ||
                        c == 503 || c == 504 || c == 404
                    Result(false, "Gemini error: " + msg, retry)
                }
            }
            return result
        } catch (e: Exception) {
            return Result(false, "Internet ya API mein dikkat hai: " + e.message, true)
        }
    }

    private fun parse(raw: String): String {
        try {
            val parts = JSONObject(raw)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (p.optBoolean("thought", false)) continue
                sb.append(p.optString("text", ""))
            }
            return sb.toString().trim()
        } catch (e: Exception) {
            return ""
        }
    }
}
