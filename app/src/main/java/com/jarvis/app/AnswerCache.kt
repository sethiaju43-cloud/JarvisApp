package com.jarvis.app

import android.content.Context
import java.io.File

/*
 * Yaad kiye hue jawab ka Android hissa (file mein store). Jo jawab API se aaya wo yahan rakha jata hai;
 * wahi sawaal dobara aaye to bina API ke wahi jawab. Niyam AnswerCacheLogic mein hain.
 */
object AnswerCache {

    private const val FILE = "answer_cache.json"

    fun enabled(ctx: Context): Boolean = Prefs.bool(ctx, "answer_cache", true)

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    private fun load(ctx: Context): AnswerBook {
        return try {
            val f = file(ctx)
            if (f.exists()) AnswerBook(AnswerCacheLogic.fromJson(f.readText())) else AnswerBook(emptyList())
        } catch (e: Exception) {
            AnswerBook(emptyList())
        }
    }

    private fun save(ctx: Context, book: AnswerBook) {
        try {
            file(ctx).writeText(AnswerCacheLogic.toJson(book.all()))
        } catch (e: Exception) {
        }
    }

    private fun lastUser(history: List<Pair<String, String>>): String? = history.lastOrNull { it.first == "user" }?.second

    private fun previousUser(history: List<Pair<String, String>>): String? {
        val idx = history.indexOfLast { it.first == "user" }
        if (idx <= 0) return null
        return history.subList(0, idx).lastOrNull { it.first == "user" }?.second
    }

    /** Sawaal pehle ka hai to wahi jawab (bina API). "naya jawab do" ho to purana hata do aur null do. */
    @Synchronized
    fun lookup(ctx: Context, history: List<Pair<String, String>>): String? {
        if (!enabled(ctx)) return null
        val q = lastUser(history) ?: return null
        val now = System.currentTimeMillis()
        if (AnswerCacheLogic.isRefresh(q)) {
            val prev = previousUser(history)
            if (prev != null) {
                val b = load(ctx)
                if (b.remove(AnswerCacheLogic.key(prev))) save(ctx, b)
            }
            return null
        }
        if (!AnswerCacheLogic.cacheableChat(q)) return null
        val b = load(ctx)
        val e = AnswerCacheLogic.lookup(b.all(), q, now) ?: return null
        b.hit(e.key, now)
        save(ctx, b)
        return e.answer + AnswerCacheLogic.footer(e, now)
    }

    /** Cloud model ne jawab diya to yaad rakho (local model, search wale, error ya action wale jawab nahi) */
    @Synchronized
    fun store(ctx: Context, history: List<Pair<String, String>>, reply: String, route: RouteResult?, wasError: Boolean) {
        if (!enabled(ctx) || wasError || route == null || !route.ok) return
        if (route.backend?.isLocal != false || route.searchUsed) return
        val last = lastUser(history) ?: return
        val target = if (AnswerCacheLogic.isRefresh(last)) (previousUser(history) ?: return) else last
        if (!AnswerCacheLogic.cacheableChat(target)) return
        if (!AnswerCacheLogic.storable(reply)) return
        val now = System.currentTimeMillis()
        val b = load(ctx)
        b.prune(now)
        b.put(CacheEntry(AnswerCacheLogic.key(target), target.take(AnswerCacheLogic.MAX_QUESTION), reply.trim(), "chat", now, now, 0))
        save(ctx, b)
    }

    // ---------- background kaam (research / plan / likhai) ----------

    @Synchronized
    fun lookupJob(ctx: Context, kind: JobKind, text: String): String? {
        if (!enabled(ctx) || !AnswerCacheLogic.cacheableJob(text)) return null
        val now = System.currentTimeMillis()
        val key = AnswerCacheLogic.jobKey(kind.name.lowercase(), text)
        val b = load(ctx)
        val e = AnswerCacheLogic.lookupKey(b.all(), key, now) ?: return null
        b.hit(e.key, now)
        save(ctx, b)
        val days = ((now - e.createdAt) / AnswerCacheLogic.DAY_MS).toInt()
        val age = if (days < 1) "aaj ki" else days.toString() + " din purani"
        return "Pehle se yaad ki hui " + kind.label + " mili (" + age + "), bina API ke. Isi se jawab do. Naya chahiye to start_background_task ko fresh=true ke saath chalao.\n" + e.answer
    }

    @Synchronized
    fun storeJob(ctx: Context, kind: JobKind, text: String, result: String) {
        if (!enabled(ctx) || !AnswerCacheLogic.cacheableJob(text)) return
        val r = result.trim()
        if (r.length < 40 || r.contains("Ruk gayi")) return
        val now = System.currentTimeMillis()
        val b = load(ctx)
        b.prune(now)
        val key = AnswerCacheLogic.jobKey(kind.name.lowercase(), text)
        b.put(CacheEntry(key, text.take(AnswerCacheLogic.MAX_QUESTION), r.take(AnswerCacheLogic.MAX_ANSWER), kind.name.lowercase(), now, now, 0))
        save(ctx, b)
    }

    // ---------- status, saaf, backup ke liye ----------

    @Synchronized
    fun all(ctx: Context): List<CacheEntry> {
        val b = load(ctx)
        b.prune(System.currentTimeMillis())
        return b.all()
    }

    @Synchronized
    fun merge(ctx: Context, incoming: List<CacheEntry>): Int {
        val b = load(ctx)
        val n = b.merge(incoming, System.currentTimeMillis())
        if (n > 0) save(ctx, b)
        return n
    }

    @Synchronized
    fun statusText(ctx: Context): String {
        val b = load(ctx)
        val tasks = TaskMemory.all(ctx).size
        return "Jawab cache " + (if (enabled(ctx)) "chalu" else "band") + " hai. Yaad kiye hue jawab: " + b.size() +
            " (" + b.totalHits() + " baar bina API ke diye). Seekhe hue kaam: " + tasks + "."
    }

    @Synchronized
    fun clear(ctx: Context): String {
        val b = load(ctx)
        val n = b.clear()
        save(ctx, b)
        return "Yaad kiye hue jawab saaf kar diye (" + n + "). Seekhe hue kaam waise hi hain."
    }
}
