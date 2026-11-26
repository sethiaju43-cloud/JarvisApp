package com.jarvis.app

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/*
 * Tool-calling ka Android hissa: provider ko request bhejna (AndroidToolLlm) aur tools chalana (AndroidToolRunner).
 * Settings mein "Naya tool-calling brain (beta)" ON ho tabhi chalta hai. Koi bhi dikkat ho to null/Failed
 * lautata hai aur Brain purane (text-protocol) tareeke par chala jata hai.
 */
object ToolBrain {

    fun enabled(ctx: Context): Boolean = Prefs.bool(ctx, "tools_mode", false)

    fun system(ctx: Context, timeLine: String, memLine: String): String =
        Prompts.chatOnly(ctx, Task.CHAT, timeLine, memLine) + ToolPrompt.RULES

    /** null = tool path use nahi ho sakta (koi online provider nahi), Brain purane tareeke se chale */
    fun run(ctx: Context, history: List<Pair<String, String>>, system: String, stop: () -> Boolean): AgentResult? {
        val llm = AndroidToolLlm(ctx, system)
        if (!llm.hasCandidates()) return null
        val confirm = ctx.applicationContext.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
            .getBoolean("confirm_send", true)
        val agent = ToolAgent(llm, AndroidToolRunner(ctx), confirm, stop = stop)
        return agent.run(ContextTrimmer.trim(history, 6000))
    }
}

class AndroidToolLlm(private val ctx: Context, private val system: String) : ToolLlm {

    private class Cand(val providerId: String, val model: String)

    private val json = "application/json".toMediaType()
    private val cands = ArrayList<Cand>()
    private val bad = HashSet<String>()

    init {
        try {
            for (b in ModelRouter.plan(ctx, Task.PHONE, false)) {
                if (b.isLocal) continue
                val pid = b.id.substringBefore("|")
                val model = b.id.substringAfter("|", "")
                if (model.isBlank() || ProviderRegistry.preset(pid) == null) continue
                if (ModelRouter.health.inCooldown(pid)) continue
                cands.add(Cand(pid, model))
                if (cands.size >= 3) break
            }
        } catch (e: Exception) {
            cands.clear()
        }
    }

    fun hasCandidates(): Boolean = cands.isNotEmpty()

    override fun next(turns: List<ToolTurn>, tools: List<ToolSpec>): ToolTurn.Model {
        var lastErr = "koi provider nahi"
        for (c in cands) {
            val k = c.providerId + "|" + c.model
            if (k in bad) continue
            val p = ProviderRegistry.preset(c.providerId) ?: continue
            val label = ProviderRegistry.title(ctx, p)
            UsageStats.hit(ctx, label)
            val raw: String = try {
                post(p, c.model, turns, tools)
            } catch (e: CallFail) {
                UsageStats.failure(ctx, label)
                lastErr = e.message ?: "fail"
                if (e.kind == ErrKind.AUTH) ModelRouter.health.cooldown(c.providerId, 10 * 60_000L, "API key galat ya expire")
                else if (e.kind == ErrKind.NETWORK) ModelRouter.health.cooldown(c.providerId, 30_000L, "internet/server dikkat")
                else bad.add(k)
                continue
            }
            try {
                val m = if (p.kind == ProviderKind.GEMINI) GeminiToolAdapter.parse(raw) else OpenAiToolAdapter.parse(raw)
                try {
                    var chars = system.length
                    for (t in turns) if (t is ToolTurn.User) chars += t.text.length
                    UsageStats.tokens(ctx, true, UsageStats.estTokens(chars), UsageStats.estTokens(m.text.length + 40 * m.calls.size))
                } catch (e: Exception) {
                }
                return m
            } catch (e: ToolAdapterException) {
                lastErr = e.message ?: "jawab samajh nahi aaya"
                bad.add(k)
            }
        }
        throw ToolAdapterException(lastErr)
    }

    private class CallFail(val kind: ErrKind, msg: String) : Exception(msg)

    private fun post(p: ProviderPreset, model: String, turns: List<ToolTurn>, tools: List<ToolSpec>): String {
        val key = ProviderRegistry.key(ctx, p.id)
        val base = ProviderRegistry.baseUrl(ctx, p.id)
        val rb = Request.Builder()
        val body: JSONObject
        if (p.kind == ProviderKind.GEMINI) {
            if (key.isBlank()) throw CallFail(ErrKind.AUTH, "Gemini API key nahi hai")
            body = GeminiToolAdapter.buildRequest(system, turns, tools)
            rb.url(base + "/models/" + model + ":generateContent").header("x-goog-api-key", key)
        } else {
            if (!Net.isHttps(base)) throw CallFail(ErrKind.BAD_REQUEST, "Sirf https:// URL allowed hai")
            if (key.isBlank() && !p.custom) throw CallFail(ErrKind.AUTH, p.name + " API key nahi hai")
            body = OpenAiToolAdapter.buildRequest(model, system, turns, tools)
            body.put("temperature", 0.3)
            rb.url("$base/chat/completions")
            if (key.isNotBlank()) rb.header("Authorization", "Bearer $key")
        }
        rb.post(body.toString().toRequestBody(json))
        try {
            val out: String = Net.api.newCall(rb.build()).execute().use { r ->
                val raw = r.body?.string() ?: ""
                if (!r.isSuccessful) {
                    val msg = OnlineParsers.errorMessage(raw, r.code)
                    throw CallFail(ErrorClassifier.classify(r.code, msg), msg)
                }
                raw
            }
            return out
        } catch (e: CallFail) {
            throw e
        } catch (e: Exception) {
            ModelRouter.lastNetError = e.javaClass.simpleName
            Net.clearDnsCache()
            throw CallFail(ErrKind.NETWORK, "Internet ya API mein dikkat: " + OnlineParsers.redact(e.message ?: ""))
        }
    }
}

class AndroidToolRunner(private val ctx: Context) : ToolRunner {

    // Ye tools screen badalte hain: agla kadam lene se pehle thoda ruko
    private val uiTools = setOf("open_app", "tap_text", "type_text", "scroll_screen", "go_home", "go_back", "open_recents", "open_settings", "navigate", "open_url", "play_music")

    override fun run(call: ToolCall): ToolOutput {
        return try {
            when (call.name) {
                "read_screen_text" -> readScreen()
                "search_web" -> searchWeb(call.args.optString("query", ""))
                "get_current_time" -> ToolOutput(
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm, EEEE", java.util.Locale.ENGLISH).format(java.util.Date()),
                    silent = true
                )
                "remember_fact" -> remember(call.args.optString("fact", ""))
                "find_tool" -> findTool(call.args.optString("query", ""))
                else -> legacy(call)
            }
        } catch (e: Exception) {
            ToolOutput("Ye kaam nahi ho paya 😕 (" + OnlineParsers.redact(e.message ?: e.javaClass.simpleName).take(80) + ")")
        }
    }

    private fun legacy(call: ToolCall): ToolOutput {
        val line = ToolRegistry.legacyLine(call) ?: return ToolOutput("This tool is not supported yet.")
        val res = PhoneActions.interpret(ctx, line)
        if (call.name in uiTools) {
            try {
                Thread.sleep(1200)
            } catch (e: InterruptedException) {
            }
        }
        return ToolOutput(res)
    }

    private fun readScreen(): ToolOutput {
        if (!Prefs.screenToAi(ctx)) {
            return ToolOutput("User ne Privacy setting mein screen text AI ko bhejna band kiya hai. Settings -> Privacy mein allow karne par hi padh sakti ho.", silent = true)
        }
        val svc = JarvisAccessibilityService.instance
            ?: return ToolOutput("Accessibility band ya atak gayi hai, screen padh nahi sakti. Jarvis Phone Control OFF phir ON karna padega.", silent = true)
        if (svc.isPrivateApp()) {
            return ToolOutput("Ye private app hai (bank, UPI, message ya password), iski screen nahi padh sakti.", silent = true)
        }
        val s = svc.readScreenText()
        val body = if (s.isBlank()) "(screen par koi text nahi mila)" else s.take(2500)
        return ToolOutput(ToolPrompt.untrusted("SCREEN TEXT", body), silent = true)
    }

    private fun searchWeb(q: String): ToolOutput {
        val r = SearchService.search(ctx, q)
        if (r.hits.isEmpty()) {
            return ToolOutput("Search se kuch nahi mila" + (if (r.error.isNotBlank()) " (" + r.error.take(80) + ")" else ""), silent = true)
        }
        return ToolOutput(ToolPrompt.untrusted("SEARCH RESULTS", SearchFormat.promptBlock(r.hits)), silent = true)
    }

    private fun remember(fact: String): ToolOutput {
        val msg = when (MemoryStore.add(ctx, fact, "fact", auto = false)) {
            MemoryStore.AddResult.SAVED -> "Theek hai, yaad rakh liya 📝"
            MemoryStore.AddResult.DUPLICATE -> "Ye mujhe pehle se yaad hai 🙂"
            MemoryStore.AddResult.SENSITIVE_PENDING -> "Ye nijji jaankari lagti hai, isliye user ki haan ke bina save nahi ki 🔒"
            MemoryStore.AddResult.DISABLED -> "Memory band hai. Settings -> Memory mein chalu karo."
            MemoryStore.AddResult.TOO_SHORT -> "Kya yaad rakhun? Thoda aur batao 🙂"
        }
        return ToolOutput(msg)
    }

    private fun findTool(q: String): ToolOutput {
        val found = ToolRegistry.search(q)
        if (found.isEmpty()) return ToolOutput("No matching tool found.", silent = true)
        val names = ArrayList<String>()
        for (t in found) names.add(t.name)
        return ToolOutput("Now available: " + names.joinToString(", ") + ". You can call them.", names, silent = true)
    }
}
