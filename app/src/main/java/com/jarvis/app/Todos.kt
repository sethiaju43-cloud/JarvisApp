package com.jarvis.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** To-do list: encrypt hokar phone mein rehti hai, kahin bheji nahi jati. */
object Todos {

    class Item(val id: Long, val text: String, var done: Boolean, val createdMs: Long)

    const val MAX_ITEMS = 200
    private const val PREFS = "jarvis_todos"
    private const val KEY = "list"

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun list(ctx: Context): List<Item> {
        val out = ArrayList<Item>()
        try {
            val arr = JSONArray(ApiKeyStore.open(sp(ctx).getString(KEY, "[]") ?: "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Item(o.getLong("id"), o.getString("t"), o.optBoolean("d", false), o.optLong("c", 0L)))
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun save(ctx: Context, items: List<Item>) {
        try {
            val arr = JSONArray()
            for (r in items) {
                arr.put(JSONObject().put("id", r.id).put("t", r.text).put("d", r.done).put("c", r.createdMs))
            }
            sp(ctx).edit().putString(KEY, ApiKeyStore.seal(arr.toString())).apply()
        } catch (e: Exception) {
        }
    }

    /** Naya kaam jodo. Limit poori ho to null. */
    @Synchronized
    fun add(ctx: Context, text: String): Item? {
        val items = ArrayList<Item>(list(ctx))
        if (items.size >= MAX_ITEMS) return null
        val id = (items.maxOfOrNull { it.id } ?: 0L) + 1L
        val item = Item(id, text.take(200), false, System.currentTimeMillis())
        items.add(item)
        save(ctx, items)
        return item
    }

    @Synchronized
    fun markDone(ctx: Context, id: Long) {
        val items = ArrayList<Item>(list(ctx))
        items.firstOrNull { it.id == id }?.done = true
        save(ctx, items)
    }

    @Synchronized
    fun remove(ctx: Context, id: Long) {
        val items = ArrayList<Item>(list(ctx))
        items.removeAll { it.id == id }
        save(ctx, items)
    }

    @Synchronized
    fun removeAll(ctx: Context): Int {
        val n = list(ctx).size
        save(ctx, emptyList())
        return n
    }
}
