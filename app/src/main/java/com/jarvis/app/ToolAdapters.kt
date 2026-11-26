package com.jarvis.app

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/*
 * Provider ke hisaab se tool-calling ki JSON banana aur padhna (pure code, fixtures se unit tested).
 * Gemini: functionDeclarations / functionCall / functionResponse.
 * OpenAI-compatible (Groq, OpenRouter, Mistral, Cerebras, Custom): tools / tool_calls / role=tool.
 * Koi bhi galti hone par ToolAdapterException aata hai, aur Brain purane text path par wapas chala jata hai.
 *
 * NOTE: Gemini ke naye models ke liye "thoughtSignature" wapas bhejna zaroori hota hai. Isliye model ka asli content
 * ToolTurn.Model.raw mein bachaya jata hai aur bilkul waisa hi wapas bheja jata hai.
 */

private const val SYNTH_PREFIX = "synth_"

object GeminiToolAdapter {

    fun toolsJson(tools: List<ToolSpec>): JSONArray {
        val decls = JSONArray()
        for (t in tools) {
            val d = JSONObject()
            d.put("name", t.name)
            d.put("description", t.description)
            // Gemini khali parameters wale OBJECT ko manzoor nahi karta, isliye bina params ke field hi mat bhejo
            if (t.params.isNotEmpty()) d.put("parameters", ToolSchema.build(t.params, true))
            decls.put(d)
        }
        return JSONArray().put(JSONObject().put("functionDeclarations", decls))
    }

    private fun textContent(role: String, text: String): JSONObject {
        val parts = JSONArray().put(JSONObject().put("text", text))
        return JSONObject().put("role", role).put("parts", parts)
    }

    fun contents(turns: List<ToolTurn>): JSONArray {
        val arr = JSONArray()
        for (t in turns) {
            when (t) {
                is ToolTurn.User -> arr.put(textContent("user", t.text))
                is ToolTurn.Model -> {
                    val raw = t.raw
                    if (raw != null) {
                        if (!raw.has("role")) raw.put("role", "model")
                        arr.put(raw)
                    } else {
                        val parts = JSONArray()
                        if (t.text.isNotBlank()) parts.put(JSONObject().put("text", t.text))
                        for (c in t.calls) {
                            val fc = JSONObject().put("name", c.name).put("args", c.args)
                            if (!c.id.startsWith(SYNTH_PREFIX)) fc.put("id", c.id)
                            parts.put(JSONObject().put("functionCall", fc))
                        }
                        arr.put(JSONObject().put("role", "model").put("parts", parts))
                    }
                }
                is ToolTurn.Results -> {
                    val parts = JSONArray()
                    for (r in t.results) {
                        val fr = JSONObject().put("name", r.name).put("response", JSONObject().put("result", r.content))
                        if (!r.callId.startsWith(SYNTH_PREFIX)) fr.put("id", r.callId)
                        parts.put(JSONObject().put("functionResponse", fr))
                    }
                    arr.put(JSONObject().put("role", "user").put("parts", parts))
                }
            }
        }
        return arr
    }

    fun buildRequest(system: String, turns: List<ToolTurn>, tools: List<ToolSpec>): JSONObject {
        val root = JSONObject()
        if (system.isNotBlank()) {
            root.put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        }
        root.put("contents", contents(turns))
        if (tools.isNotEmpty()) {
            root.put("tools", toolsJson(tools))
            root.put(
                "tool_config",
                JSONObject().put("function_calling_config", JSONObject().put("mode", "AUTO"))
            )
        }
        return root
    }

    fun parse(raw: String): ToolTurn.Model {
        val o: JSONObject = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            throw ToolAdapterException("Gemini ka jawab JSON nahi tha")
        }
        if (o.has("error")) {
            val msg = o.optJSONObject("error")?.optString("message", "") ?: ""
            throw ToolAdapterException("Gemini error: " + OnlineParsers.redact(msg).take(200))
        }
        val cands = o.optJSONArray("candidates")
        if (cands == null || cands.length() == 0) throw ToolAdapterException("Gemini ne koi jawab nahi diya")
        val cand = cands.optJSONObject(0) ?: throw ToolAdapterException("Gemini ka jawab adhura tha")
        val content = cand.optJSONObject("content")
        val parts = content?.optJSONArray("parts")
        if (content == null || parts == null) {
            throw ToolAdapterException("Gemini ka jawab khali tha (" + cand.optString("finishReason", "?") + ")")
        }
        val text = StringBuilder()
        val calls = ArrayList<ToolCall>()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            val fc = p.optJSONObject("functionCall")
            if (fc != null) {
                val name = fc.optString("name", "")
                if (name.isBlank()) continue
                val id = fc.optString("id", "")
                calls.add(ToolCall(if (id.isBlank()) SYNTH_PREFIX + "g" + calls.size else id, name, fc.optJSONObject("args") ?: JSONObject()))
            } else if (p.has("text") && !p.optBoolean("thought", false)) {
                text.append(p.optString("text", ""))
            }
        }
        return ToolTurn.Model(text.toString().trim(), calls, content)
    }
}

object OpenAiToolAdapter {

    fun toolsJson(tools: List<ToolSpec>): JSONArray {
        val arr = JSONArray()
        for (t in tools) {
            val fn = JSONObject()
            fn.put("name", t.name)
            fn.put("description", t.description)
            fn.put("parameters", ToolSchema.build(t.params, false))
            arr.put(JSONObject().put("type", "function").put("function", fn))
        }
        return arr
    }

    fun messages(system: String, turns: List<ToolTurn>): JSONArray {
        val arr = JSONArray()
        if (system.isNotBlank()) arr.put(JSONObject().put("role", "system").put("content", system))
        for (t in turns) {
            when (t) {
                is ToolTurn.User -> arr.put(JSONObject().put("role", "user").put("content", t.text))
                is ToolTurn.Model -> {
                    val m = JSONObject().put("role", "assistant")
                    if (t.text.isNotBlank()) m.put("content", t.text) else m.put("content", JSONObject.NULL)
                    if (t.calls.isNotEmpty()) {
                        val tc = JSONArray()
                        for (c in t.calls) {
                            val fn = JSONObject().put("name", c.name).put("arguments", c.args.toString())
                            tc.put(JSONObject().put("id", c.id).put("type", "function").put("function", fn))
                        }
                        m.put("tool_calls", tc)
                    }
                    arr.put(m)
                }
                is ToolTurn.Results -> {
                    for (r in t.results) {
                        arr.put(JSONObject().put("role", "tool").put("tool_call_id", r.callId).put("content", r.content))
                    }
                }
            }
        }
        return arr
    }

    fun buildRequest(model: String, system: String, turns: List<ToolTurn>, tools: List<ToolSpec>): JSONObject {
        val root = JSONObject()
        root.put("model", model)
        root.put("messages", messages(system, turns))
        if (tools.isNotEmpty()) {
            root.put("tools", toolsJson(tools))
            root.put("tool_choice", "auto")
        }
        return root
    }

    private fun contentText(c: Any?): String {
        if (c is String) return c
        if (c is JSONArray) {
            val sb = StringBuilder()
            for (i in 0 until c.length()) {
                val p = c.optJSONObject(i) ?: continue
                sb.append(p.optString("text", ""))
            }
            return sb.toString()
        }
        return ""
    }

    fun parse(raw: String): ToolTurn.Model {
        val o: JSONObject = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            throw ToolAdapterException("Provider ka jawab JSON nahi tha")
        }
        val err = o.opt("error")
        if (err != null && err != JSONObject.NULL) {
            val msg = if (err is JSONObject) err.optString("message", "") else err.toString()
            throw ToolAdapterException("Provider error: " + OnlineParsers.redact(msg).take(200))
        }
        val choices = o.optJSONArray("choices")
        if (choices == null || choices.length() == 0) throw ToolAdapterException("Provider ne koi jawab nahi diya")
        val msg = choices.optJSONObject(0)?.optJSONObject("message") ?: throw ToolAdapterException("Provider ka jawab adhura tha")
        val text = contentText(msg.opt("content")).trim()
        val calls = ArrayList<ToolCall>()
        val tcs = msg.optJSONArray("tool_calls")
        if (tcs != null) {
            for (i in 0 until tcs.length()) {
                val tc = tcs.optJSONObject(i) ?: continue
                val fn = tc.optJSONObject("function") ?: continue
                val name = fn.optString("name", "")
                if (name.isBlank()) continue
                val id = tc.optString("id", "")
                val a = fn.opt("arguments")
                var args = JSONObject()
                var bad = false
                if (a is JSONObject) {
                    args = a
                } else if (a is String && a.isNotBlank()) {
                    try {
                        args = JSONObject(a)
                    } catch (e: JSONException) {
                        bad = true
                    }
                }
                calls.add(ToolCall(if (id.isBlank()) SYNTH_PREFIX + "o" + i else id, name, args, bad))
            }
        }
        if (text.isEmpty() && calls.isEmpty()) throw ToolAdapterException("Provider ka jawab khali tha")
        return ToolTurn.Model(text, calls, null)
    }
}
