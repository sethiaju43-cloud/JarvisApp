package com.jarvis.app

import org.json.JSONArray
import org.json.JSONObject

/*
 * Tool-calling ke basic dibbe. Is file mein Android ka koi code nahi hai, isliye JVM unit tests mein seedha test hota hai.
 * Original design (Jarvi ke liye), kisi doosre app ka code nahi.
 */

enum class ToolRisk { SAFE, CONFIRM, BLOCKED }

enum class ParamType { STRING, INTEGER, BOOLEAN }

class ToolParam(
    val name: String,
    val type: ParamType,
    val description: String,
    val required: Boolean = true,
    val enumValues: List<String> = emptyList(),
    val min: Int? = null,
    val max: Int? = null,
    val maxLen: Int = 500,
    val pattern: String? = null
)

/** group: "core" (hamesha model ko dikhao), "extra" (find_tool se milte hain), "meta" (find_tool khud) */
class ToolSpec(
    val name: String,
    val description: String,
    val params: List<ToolParam>,
    val risk: ToolRisk,
    val group: String,
    val keywords: List<String> = emptyList()
)

/** Model ne jo tool call maanga. argsError = arguments valid JSON nahi the. */
class ToolCall(
    val id: String,
    val name: String,
    val args: JSONObject,
    val argsError: Boolean = false
)

/** Tool chalane ka nateeja (chhota text, model ko wapas bhejte hain) */
class ToolResult(val callId: String, val name: String, val content: String)

/** Model ke saath baatcheet ka ek mod. Provider adapters isse apni JSON mein badalte hain. */
sealed class ToolTurn {
    class User(val text: String) : ToolTurn()

    /** raw = provider ka asli jawab (Gemini ko ye bilkul waisa hi wapas bhejna padta hai, thoughtSignature ke saath) */
    class Model(val text: String, val calls: List<ToolCall>, val raw: JSONObject? = null) : ToolTurn()

    class Results(val results: List<ToolResult>) : ToolTurn()
}

class ToolAdapterException(message: String) : Exception(message)

/** Params ko JSON-schema (chhota hissa) mein badalna. upper = Gemini style (STRING, OBJECT) */
object ToolSchema {

    fun typeName(t: ParamType, upper: Boolean): String {
        val n = when (t) {
            ParamType.STRING -> "string"
            ParamType.INTEGER -> "integer"
            ParamType.BOOLEAN -> "boolean"
        }
        return if (upper) n.uppercase() else n
    }

    fun describe(p: ToolParam): String {
        val lo = p.min
        val hi = p.max
        return if (p.type == ParamType.INTEGER && lo != null && hi != null) p.description + " (range " + lo + "-" + hi + ")" else p.description
    }

    fun build(params: List<ToolParam>, upper: Boolean): JSONObject {
        val props = JSONObject()
        val req = JSONArray()
        for (p in params) {
            val o = JSONObject()
            o.put("type", typeName(p.type, upper))
            o.put("description", describe(p))
            if (p.enumValues.isNotEmpty()) {
                val e = JSONArray()
                for (v in p.enumValues) e.put(v)
                o.put("enum", e)
            }
            props.put(p.name, o)
            if (p.required) req.put(p.name)
        }
        val root = JSONObject()
        root.put("type", if (upper) "OBJECT" else "object")
        root.put("properties", props)
        if (req.length() > 0) root.put("required", req)
        return root
    }
}
