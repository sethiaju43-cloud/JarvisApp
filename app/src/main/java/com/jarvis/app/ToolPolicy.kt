package com.jarvis.app

import org.json.JSONObject

/*
 * Tool call chalane se pehle ki jaanch (pure code, unit tested).
 * SAFE tool seedha chalta hai, CONFIRM tool (call/SMS/WhatsApp) kabhi seedha nahi chalta: pehle user se poochha jata hai.
 * Galat naam, galat arguments ya BLOCKED tool ko Reject milta hai aur wajah model ko wapas bhej di jati hai.
 */

sealed class ToolDecision {
    /** call = saaf kiye hue (normalized) arguments ke saath */
    class Allow(val call: ToolCall) : ToolDecision()

    class Confirm(val call: ToolCall, val question: String) : ToolDecision()

    class Reject(val code: String, val message: String) : ToolDecision()
}

object ToolPolicy {

    /** Ye tool kabhi model ko nahi dikhte, aur naam aaye to bhi kabhi nahi chalte */
    val BLOCKED_NAMES: Set<String> = setOf(
        "wipe_device", "factory_reset", "unlock_phone", "install_apk", "uninstall_app", "forget_face",
        "send_money", "make_payment", "transfer_money", "pay"
    )

    const val CONFIRM_PENDING_RESULT = "needs_confirmation: the user is being asked to confirm. Do not repeat this call. Tell the user briefly that you are waiting for their yes or no."
    const val MAX_RESULT_CHARS = 800

    fun truncateResult(s: String): String = if (s.length <= MAX_RESULT_CHARS) s else s.take(MAX_RESULT_CHARS) + "..."

    private class Coerced(val value: Any?, val error: String?)

    private fun numToString(n: Number): String {
        val d = n.toDouble()
        return if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else n.toString()
    }

    private fun coerce(p: ToolParam, v: Any): Coerced = when (p.type) {
        ParamType.STRING -> coerceString(p, v)
        ParamType.INTEGER -> coerceInt(p, v)
        ParamType.BOOLEAN -> coerceBool(p, v)
    }

    private fun coerceString(p: ToolParam, v: Any): Coerced {
        val raw: String = when (v) {
            is String -> v
            is Number -> numToString(v)
            is Boolean -> v.toString()
            else -> return Coerced(null, p.name + " must be text")
        }
        val str = raw.trim()
        if (str.isEmpty()) return Coerced(null, p.name + " is empty")
        if (str.length > p.maxLen) return Coerced(null, p.name + " is too long (max " + p.maxLen + " characters)")
        var out = str
        if (p.enumValues.isNotEmpty()) {
            val hit = p.enumValues.firstOrNull { it.equals(str, ignoreCase = true) }
                ?: return Coerced(null, p.name + " must be one of: " + p.enumValues.joinToString(", "))
            out = hit
        }
        val pat = p.pattern
        if (pat != null && !Regex(pat).matches(out)) return Coerced(null, p.name + " has a wrong format")
        return Coerced(out, null)
    }

    private fun coerceInt(p: ToolParam, v: Any): Coerced {
        val d: Double = when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull() ?: return Coerced(null, p.name + " must be a number")
            else -> return Coerced(null, p.name + " must be a number")
        }
        if (d != Math.floor(d)) return Coerced(null, p.name + " must be a whole number")
        val n = d.toLong()
        val lo = p.min
        val hi = p.max
        if (lo != null && n < lo) return Coerced(null, p.name + " must be at least " + lo)
        if (hi != null && n > hi) return Coerced(null, p.name + " must be at most " + hi)
        return Coerced(n.toInt(), null)
    }

    private fun coerceBool(p: ToolParam, v: Any): Coerced {
        val b: Boolean = when (v) {
            is Boolean -> v
            is String -> when (v.trim().lowercase()) {
                "true", "on", "yes" -> true
                "false", "off", "no" -> false
                else -> return Coerced(null, p.name + " must be true or false")
            }
            else -> return Coerced(null, p.name + " must be true or false")
        }
        return Coerced(b, null)
    }

    private fun padTime(t: String): String {
        val parts = t.split(":")
        return parts[0].padStart(2, '0') + ":" + parts[1]
    }

    /** offered = is request mein model ko jo tools dikhaye gaye (null = ye jaanch mat karo) */
    fun check(call: ToolCall, offered: Set<String>? = null): ToolDecision {
        val name = call.name.trim()
        if (name in BLOCKED_NAMES) return ToolDecision.Reject("blocked", "The tool '" + name + "' is not allowed.")
        val spec = ToolRegistry.get(name)
            ?: return ToolDecision.Reject("unknown_tool", "Unknown tool '" + name + "'. Use only the tools you were given, or call find_tool.")
        if (spec.risk == ToolRisk.BLOCKED) return ToolDecision.Reject("blocked", "The tool '" + name + "' is not allowed.")
        if (offered != null && name !in offered) {
            return ToolDecision.Reject("not_offered", "The tool '" + name + "' is not available right now. Call find_tool first.")
        }
        if (call.argsError) {
            return ToolDecision.Reject("bad_args", "The arguments were not valid JSON. Call the tool again with correct arguments.")
        }

        val norm = JSONObject()
        for (p in spec.params) {
            val v: Any? = if (call.args.has(p.name) && !call.args.isNull(p.name)) call.args.opt(p.name) else null
            if (v == null) {
                if (p.required) return ToolDecision.Reject("missing_arg", "Missing required argument: " + p.name)
                continue
            }
            val c = coerce(p, v)
            if (c.error != null) return ToolDecision.Reject("bad_arg", c.error)
            norm.put(p.name, c.value)
        }
        if (name == "set_alarm") norm.put("time", padTime(norm.getString("time")))
        if (name == "add_calendar_event" || name == "add_reminder") norm.put("datetime", norm.getString("datetime").replace('T', ' '))

        val clean = ToolCall(call.id, name, norm)
        if (spec.risk == ToolRisk.CONFIRM) {
            return ToolDecision.Confirm(clean, confirmQuestion(clean))
        }
        // Screen par "Send" dabane se pehle bhi poochho (purane Brain jaisa niyam)
        if (name == "tap_text" && isSendLabel(norm.getString("text"))) {
            return ToolDecision.Confirm(clean, "Send dabaun? (haan / nahi)")
        }
        return ToolDecision.Allow(clean)
    }

    fun isSendLabel(text: String): Boolean {
        val n = text.trim().lowercase()
        return n.startsWith("send") || n.contains("bhej") || n.contains("भेज") || n.contains("सेंड")
    }

    /** User se poochne wala sawal (Hinglish) */
    fun confirmQuestion(call: ToolCall): String {
        val a = call.args
        fun s(k: String) = ToolRegistry.oneLine(a.optString(k, ""))
        return when (call.name) {
            "make_call" -> s("target") + " ko call lagaun? (haan / nahi)"
            "send_sms" -> s("to") + " ko SMS bhejun: \"" + s("message") + "\"? (haan / nahi)"
            "whatsapp_send_message" -> s("to") + " ko WhatsApp par bhejun: \"" + s("message") + "\"? (haan / nahi)"
            else -> "Ye kaam karun? (haan / nahi)"
        }
    }
}
