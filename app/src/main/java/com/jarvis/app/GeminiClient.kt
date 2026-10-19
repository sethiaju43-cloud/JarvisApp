package com.jarvis.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiClient {

    // Agar ye model kabhi band ho jaye to yahan naam badal dena
    private const val MODEL = "gemini-2.5-flash"

    private const val SYSTEM_PROMPT =
        "Tumhara naam Jarvi hai. Tum ek pyari, friendly AI dost ho. " +
        "User ki bhasha mein jawab do (Hindi/Hinglish/English). " +
        "Jawab chhota, natural aur dostana rakho. Emoji kabhi kabhi use karo."

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** history: list of Pair(role, text). role = "user" ya "model" */
    fun ask(apiKey: String, history: List<Pair<String, String>>): String {
        if (apiKey.isBlank()) {
            return "Pehle Settings mein apni Gemini API Key daalo 🔑"
        }
        return try {
            val contents = JSONArray()
            for ((role, text) in history) {
                contents.put(
                    JSONObject()
                        .put("role", role)
                        .put("parts", JSONArray().put(JSONObject().put("text", text)))
                )
            }
            val body = JSONObject()
                .put("contents", contents)
                .put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))
                    )
                )

            val url =
                "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
            val req = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    val msg = try {
                        JSONObject(raw).getJSONObject("error").getString("message")
                    } catch (e: Exception) {
                        "Error ${resp.code}"
                    }
                    return "Gemini error: $msg"
                }
                JSONObject(raw)
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim()
            }
        } catch (e: Exception) {
            "Internet ya API mein dikkat hai: ${e.message}"
        }
    }
}
