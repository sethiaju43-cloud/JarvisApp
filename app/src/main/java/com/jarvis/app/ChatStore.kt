package com.jarvis.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Chat ki purani baatein phone mein save karta hai (aakhri 100 messages) */
object ChatStore {

    private const val PREFS = "jarvis_chat"
    private const val KEY = "msgs"
    private const val MAX = 100

    @Synchronized
    fun load(ctx: Context): List<ChatMessage> {
        val out = ArrayList<ChatMessage>()
        try {
            val s = ctx.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "[]") ?: "[]"
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(ChatMessage(o.getString("t"), o.getBoolean("u")))
            }
        } catch (e: Exception) {
        }
        return out
    }

    @Synchronized
    fun save(ctx: Context, list: List<ChatMessage>) {
        try {
            val clean = list.filter { it.text != "..." }
            val start = (clean.size - MAX).coerceAtLeast(0)
            val arr = JSONArray()
            for (i in start until clean.size) {
                val m = clean[i]
                arr.put(JSONObject().put("t", m.text).put("u", m.isUser))
            }
            ctx.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, arr.toString())
                .apply()
        } catch (e: Exception) {
        }
    }

    /** Ek naya message aakhir mein jodo (floating Jarvi ki baatein bhi yahin save hoti hain) */
    @Synchronized
    fun append(ctx: Context, text: String, isUser: Boolean) {
        val list = ArrayList(load(ctx))
        list.add(ChatMessage(text, isUser))
        save(ctx, list)
    }

    @Synchronized
    fun clear(ctx: Context) {
        try {
            ctx.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY)
                .apply()
        } catch (e: Exception) {
        }
    }
}
