package com.jarvis.app

import android.content.Context
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.dnsoverhttps.DnsOverHttps
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.TimeUnit

object GeminiClient {

    // Pehla model busy ya dheema ho to agla try hoga
    private val MODELS = listOf(
        "gemini-3.8-flash",
        "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite"
    )

    private const val SYSTEM_PROMPT =
        "Tumhara naam Jarvi hai. Tum user ki pyaari, caring, cute dost ho " +
        "aur user ko pyaar se 'jaan' bulati ho. " +
        "User jis bhasha mein baat kare (Hindi, Hinglish ya English) usi mein jawab do. " +
        "Normal baat mein sirf 1 ya 2 chhote, mithe vaakya rakho. " +
        "Search ya screen padhne ke jawab mein zarurat ho to 4 ya 5 vaakya tak bolo. " +
        "Star, hash jaise symbols ya lambi list mat likho, kyunki jawab bolkar sunaya jata hai. " +
        "Kabhi kabhi ek emoji lagao. " +
        "Agar user udaas ho to pyaar se haal poochho aur himmat do."

    private const val COMMAND_RULES =
        " Tum user ke phone ko control bhi kar sakti ho. " +
        "Agar user kisi app ko kholne ya chalane ko kahe (kisi bhi bhasha ya akshar mein), " +
        "to sirf ek line mein aise jawab do: OPEN: <app ka naam, neeche di gayi list se bilkul waisa hi>. " +
        "Agar user peeche jaana, home par jaana, recent apps dikhana ya scroll karne ko kahe, " +
        "to sirf ek line mein jawab do: ACTION: BACK ya ACTION: HOME ya ACTION: RECENTS " +
        "ya ACTION: SCROLL_DOWN ya ACTION: SCROLL_UP. " +
        "Aise command par koi aur shabd mat likho. " +
        "Agar sawaal taaza jaankari ka ho (news, mausam, score, daam, kisi cheez ke baare mein search), " +
        "to Google Search se dekhkar sahi jawab do. " +
        "Agar user ke message ke saath [SCREEN TEXT] diya ho, to usi text ko padhkar " +
        "saaf aur chhote shabdon mein sunao ya samjhao. " +
        "Baaki sab baat par normal pyaar se baat karo. "

    private class Result(
        val ok: Boolean,
        val text: String,
        val retry: Boolean,
        val code: Int
    )

    // ---- DNS ki dikkat ka ilaaj: pehle Google DNS, phir phone ka DNS ----
    private var cacheHost = ""
    private var cacheAddrs: List<InetAddress> = emptyList()
    private var cacheTime = 0L

    private val bootstrapClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val doh: DnsOverHttps = DnsOverHttps.Builder()
        .client(bootstrapClient)
        .url("https://dns.google/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)),
            InetAddress.getByAddress(byteArrayOf(8, 8, 4, 4))
        )
        .build()

    private val smartDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val now = System.currentTimeMillis()
            if (hostname == cacheHost && cacheAddrs.isNotEmpty() && now - cacheTime < 300000) {
                return cacheAddrs
            }
            var res: List<InetAddress> = emptyList()
            try {
                res = doh.lookup(hostname)
            } catch (e: Exception) {
            }
            if (res.isEmpty()) {
                res = Dns.SYSTEM.lookup(hostname)
            }
            cacheHost = hostname
            cacheAddrs = res
            cacheTime = now
            return res
        }
    }

    private val client = OkHttpClient.Builder()
        .dns(smartDns)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Phone ke commands, search aur screen padhna samajhne wala smart jawab */
    fun askSmart(context: Context, apiKey: String, history: List<Pair<String, String>>): String {
        val apps = CommandHandler.installedApps(context)
        val names = StringBuilder()
        var count = 0
        for (a in apps) {
            if (count >= 250) break
            if (count > 0) names.append(", ")
            names.append(a.first)
            count++
        }
        val prompt = SYSTEM_PROMPT + COMMAND_RULES +
            "Phone mein installed apps ki list: " + names.toString()

        // Agar user screen padhne ko keh raha hai to screen ka text saath bhejo
        var hist: List<Pair<String, String>> = history
        val last = history.lastOrNull()
        if (last != null && last.first == "user" && CommandHandler.wantsScreen(last.second)) {
            val svc = JarvisAccessibilityService.instance
            val screen = if (svc != null) svc.readScreenText() else ""
            val shown = if (screen.isBlank()) "(screen par koi text nahi mila)" else screen
            val q = last.second + "\n\n[SCREEN TEXT]\n" + shown
            val copy = ArrayList<Pair<String, String>>(history)
            copy[copy.size - 1] = Pair("user", q)
            hist = copy
        }

        val reply = askWith(apiKey, hist, prompt, true)
        return CommandHandler.interpret(context, reply)
    }

    /** Sirf baat-cheet (phone control ke bina) */
    fun ask(apiKey: String, history: List<Pair<String, String>>): String {
        return askWith(apiKey, history, SYSTEM_PROMPT, false)
    }

    private fun askWith(
        apiKey: String,
        history: List<Pair<String, String>>,
        prompt: String,
        useSearch: Boolean
    ): String {
        if (apiKey.isBlank()) {
            return "Pehle Settings mein apni Gemini API Key daalo 🔑"
        }
        val body = buildBody(history, prompt, useSearch)
        val start = System.currentTimeMillis()
        var lastError = "Jawab nahi aaya, dobara try karo 🙏"
        outer@ for (model in MODELS) {
            for (attempt in 1..2) {
                if (System.currentTimeMillis() - start > 70000) break@outer
                val r = call(apiKey, model, body)
                if (r.ok) return r.text
                lastError = r.text
                if (!r.retry) return r.text
                // Search ka quota khatam ho to seedha bina search ke try karo
                if (useSearch && r.code == 429) break@outer
                try {
                    Thread.sleep(600)
                } catch (e: Exception) {
                }
            }
        }
        if (useSearch) {
            val plainBody = buildBody(history, prompt, false)
            val r = call(apiKey, MODELS[0], plainBody)
            if (r.ok) return r.text
            lastError = r.text
        }
        return lastError
    }

    private fun buildBody(
        history: List<Pair<String, String>>,
        prompt: String,
        useSearch: Boolean
    ): JSONObject {
        val contents = JSONArray()
        for ((role, text) in history) {
            contents.put(
                JSONObject()
                    .put("role", role)
                    .put("parts", JSONArray().put(JSONObject().put("text", text)))
            )
        }
        // Kam sochkar jaldi jawab do
        val gen = JSONObject().put(
            "thinkingConfig",
            JSONObject().put("thinkingLevel", "low")
        )
        val body = JSONObject()
            .put("contents", contents)
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts", JSONArray().put(JSONObject().put("text", prompt))
                )
            )
            .put("generationConfig", gen)
        if (useSearch) {
            body.put(
                "tools",
                JSONArray().put(JSONObject().put("google_search", JSONObject()))
            )
        }
        return body
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
                        Result(true, text, false, 200)
                    } else {
                        Result(false, "Jawab khali aaya, dobara try karo 🙏", true, 200)
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
                    Result(false, "Gemini error: " + msg, retry, c)
                }
            }

            // Agar model ko koi setting pasand na aayi to ek-ek hata kar dobara try karo
            if (result.code == 400) {
                val plain = JSONObject(body.toString())
                if (plain.has("generationConfig")) {
                    plain.remove("generationConfig")
                    return call(apiKey, model, plain)
                }
                if (plain.has("tools")) {
                    plain.remove("tools")
                    return call(apiKey, model, plain)
                }
            }
            return result
        } catch (e: Exception) {
            return Result(false, "Internet ya API mein dikkat hai: " + e.message, true, 0)
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
