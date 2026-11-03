package com.jarvis.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Online AI ki kitni calls hui, iski ginti (sirf phone mein rehti hai, kahin bheji nahi jati) */
object UsageStats {

    private const val KEEP_DAYS = 7
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("jarvis_usage", Context.MODE_PRIVATE)

    private fun dayKey(ms: Long): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(ms))

    /** Ek asli online request gayi. label = provider ka naam (jaise "Google Gemini"). */
    @Synchronized
    fun hit(ctx: Context, label: String, now: Long = System.currentTimeMillis()) {
        try {
            val p = sp(ctx)
            val k = "n:" + dayKey(now) + ":" + label
            val e = p.edit()
            e.putInt(k, p.getInt(k, 0) + 1)
            // 7 din se purani ginti hata do
            val oldest = "n:" + dayKey(now - KEEP_DAYS * DAY_MS)
            for (key in p.all.keys) {
                if (key.startsWith("n:") && key.length >= 10 && key.substring(0, 10) < oldest) e.remove(key)
            }
            e.apply()
        } catch (t: Exception) {
        }
    }

    /** Ek din ki ginti: provider -> calls */
    private fun perProvider(ctx: Context, day: String): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        val prefix = "n:$day:"
        for ((k, v) in sp(ctx).all) {
            if (k.startsWith(prefix) && v is Int) out[k.removePrefix(prefix)] = v
        }
        return out
    }

    fun report(ctx: Context, now: Long = System.currentTimeMillis()): String {
        val today = perProvider(ctx, dayKey(now))
        val yesterday = perProvider(ctx, dayKey(now - DAY_MS)).values.sum()
        var week = 0
        for (i in 0 until KEEP_DAYS) week += perProvider(ctx, dayKey(now - i * DAY_MS)).values.sum()
        return format(today, yesterday, week)
    }

    /** Sirf text banata hai (tests ke liye alag) */
    fun format(today: Map<String, Int>, yesterday: Int, week: Int): String {
        val total = today.values.sum()
        val sb = StringBuilder("📊 Aaj online AI ki ")
        if (total == 0) {
            sb.append("koi call nahi hui.")
        } else {
            sb.append(total).append(" calls hui (")
            sb.append(today.entries.sortedByDescending { it.value }.joinToString(", ") { it.key + " " + it.value })
            sb.append(").")
        }
        sb.append(" Kal: ").append(yesterday).append(". Pichhle 7 din: ").append(week).append(".")
        sb.append("\nEk sawaal par retry ya kai-step kaam mein kai calls lag sakti hain. ")
        sb.append("Local model installed ho to 'local pehle' mode se online calls kam hongi.")
        return sb.toString()
    }

    private val apiWord = Regex("\\b(api|ai calls?|online calls?)\\b")
    private val askWords = listOf("kitni", "kitna", "usage", "use hui", "use hua", "kharch", "count", "ginti", "limit")
    private val notAsk = listOf("key", "kaise", "how", "kya hai", "kya hota")

    /** "API kitni use hui" jaisa sawaal? (chhota sawaal, AI ko bhejne ki zaroorat nahi) */
    fun isUsageQuestion(text: String): Boolean {
        val t = text.lowercase().trim()
        if (t.split(" ").size > 12) return false
        if (notAsk.any { t.contains(it) }) return false
        return apiWord.containsMatchIn(t) && askWords.any { t.contains(it) }
    }
}

/** Har asli online request ko ginta hai, baaki kaam andar wale backend ka hi hai */
class CountingBackend(private val ctx: Context, private val inner: Backend) : Backend by inner {
    override fun call(req: RouterRequest): Outcome {
        if (!inner.isLocal) UsageStats.hit(ctx, inner.label.substringBefore(" · "))
        return inner.call(req)
    }
}
