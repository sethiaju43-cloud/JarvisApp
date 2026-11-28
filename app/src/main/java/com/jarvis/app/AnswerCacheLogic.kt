package com.jarvis.app

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/*
 * Jo jawab API se paise lagakar aaya, wo yaad rakho: wahi sawaal dobara aaye to bina API ke wahi jawab do.
 * Saath mein backup/restore: jo kuch Jarvi ne seekha (kaam + jawab) ek JSON mein save karo aur baad mein wapas daalo.
 * Pure code (Android ke bina), unit tested. Original design.
 *
 * Bahut zaroori safety:
 *  - Sirf JAANKARI wale sawaal (kya/kaise/kyun...) cache hote hain. Phone ke command (torch, call, alarm...) kabhi nahi,
 *    warna "torch jala do" ka purana jawab aa jata aur torch jalti hi nahi.
 *  - Samay par depend karne wali cheezein (aaj, abhi, news, price, weather, score...) kabhi nahi.
 *  - Context/personal wale sawaal (mera, ye, wo, isko, pichla...) kabhi nahi.
 *  - Password/OTP/bank jaise shabd wale kabhi nahi.
 *  - Har jawab ki umar tay hai (chat 14 din, research 7 din ...). "naya jawab do" bolne par purana hat kar naya aata hai.
 */

class CacheEntry(
    val key: String,
    val question: String,
    val answer: String,
    val kind: String, // chat / research / plan / writing
    val createdAt: Long,
    val lastHit: Long,
    val hits: Int
)

object AnswerCacheLogic {

    const val MAX_ENTRIES = 100
    const val MAX_ANSWER = 4000
    const val MAX_QUESTION = 300
    const val MIN_TOKENS_CHAT = 4
    const val DAY_MS = 86_400_000L

    val KINDS: Set<String> = setOf("chat", "research", "plan", "writing")

    fun ttlMs(kind: String): Long = DAY_MS * when (kind) {
        "chat" -> 14L
        "research" -> 7L
        "plan" -> 14L
        "writing" -> 3L
        else -> 7L
    }

    private val TIME_WORDS: Set<String> = setOf(
        "aaj", "abhi", "kal", "parso", "today", "tomorrow", "yesterday", "now", "current", "currently", "latest", "recent", "recently",
        "news", "khabar", "khabrein", "weather", "mausam", "temperature", "price", "prices", "rate", "rates", "bhav", "score", "live",
        "result", "results", "stock", "stocks", "sensex", "nifty", "bitcoin", "crypto", "dollar", "trending", "election", "ipl", "date",
        "tarikh", "time", "samay", "baje", "schedule", "kab", "release", "released", "launch", "launched", "winner", "champion"
    )

    private val CONTEXT_WORDS: Set<String> = setOf(
        "mera", "meri", "mere", "mujhe", "mujhko", "mujhse", "hum", "hamara", "hamari", "my", "me", "mine", "main", "mai",
        "ye", "yeh", "wo", "woh", "isko", "usko", "iska", "uska", "iske", "uske", "isme", "usme", "isse", "usse", "inke", "unke",
        "it", "this", "that", "these", "those", "same", "previous", "pichla", "pichli", "upar", "above", "continue", "next", "last", "aage",
        "nearby", "near", "paas", "yahan", "yaha", "here", "nazdeek"
    )

    /** Jaankari maangne wale sawaal ki nishaaniyan */
    private val INFO_CUES: Set<String> = setOf(
        "kya", "kaun", "kaise", "kyun", "kyon", "kahan", "kidhar", "kitna", "kitne", "kitni", "kisne", "kisko", "kaunsa", "kaunsi",
        "what", "why", "how", "who", "when", "where", "which", "explain", "samjhao", "samjha", "matlab", "meaning", "difference",
        "antar", "define", "definition", "example", "udaharan", "fayde", "nuksan", "benefits", "advantages", "tips", "tarika", "tareeka",
        "steps", "guide", "compare", "comparison", "history", "itihas", "formula"
    )

    /** Phone ke kaam wale shabd: inke saath kabhi cache nahi */
    private val ACTION_WORDS: Set<String> = setOf(
        "kholo", "khol", "open", "call", "dial", "bhejo", "bhej", "send", "sms", "whatsapp", "lagao", "laga", "chalu", "band", "play",
        "bajao", "pause", "volume", "brightness", "torch", "flashlight", "alarm", "timer", "reminder", "screenshot", "scroll", "tap",
        "click", "type", "likho", "photo", "selfie", "camera", "wifi", "bluetooth", "hotspot", "silent", "vibrate", "ringer", "remember",
        "yaad", "forget", "bhool", "delete", "hatao", "cancel", "lock", "unlock", "install", "uninstall", "download", "share"
    )

    private val REFRESH_PHRASES: List<String> = listOf(
        "naya jawab", "naye jawab", "nayi jawab", "naya batao", "dobara poocho", "dobara pucho", "dobara batao", "fresh answer",
        "new answer", "refresh karo", "phir se batao", "phirse batao", "update karo", "purana jawab", "ye purana"
    )

    fun key(text: String): String = TaskLogic.normalize(text)

    /** "naya jawab do" jaisa maang: purana jawab hatao aur naya lo */
    fun isRefresh(text: String): Boolean {
        val n = key(text)
        return REFRESH_PHRASES.any { n.contains(it) }
    }

    private fun hasYear(toks: List<String>): Boolean =
        toks.any { it.length == 4 && it.all { c -> c.isDigit() } && (it.toIntOrNull() ?: 0) in 1990..2100 }

    private fun common(question: String, toks: List<String>): Boolean {
        if (question.length > MAX_QUESTION) return false
        if (TaskLogic.sensitiveCommand(question)) return false
        if (isRefresh(question)) return false
        if (toks.any { it in TIME_WORDS || it in CONTEXT_WORDS }) return false
        if (hasYear(toks)) return false
        return true
    }

    /** Normal baat-cheet ka sawaal cache ke layak hai? Sirf jaankari wale, apne aap mein poore (self-contained) sawaal. */
    fun cacheableChat(question: String): Boolean {
        val toks = TaskLogic.tokenize(question).map { it.norm }
        if (toks.size < MIN_TOKENS_CHAT) return false
        if (!common(question, toks)) return false
        if (toks.any { it in ACTION_WORDS }) return false
        return question.contains('?') || toks.any { it in INFO_CUES }
    }

    /** Kaam ke shabd jo topic nahi batate (inhe hata kar bacha hua hi asli topic hai) */
    private val JOB_FILLER: Set<String> = setOf(
        "research", "report", "karo", "kar", "do", "dena", "de", "bana", "banao", "likh", "likho", "plan", "essay", "article",
        "detailed", "detail", "deep", "gehri", "ek", "par", "pe", "ke", "ki", "ka", "ko", "mein", "me", "se", "aur", "for", "on",
        "about", "the", "a", "an", "of", "and", "background", "kaam", "kijiye", "kijie", "chahiye", "full", "poori", "pura"
    )

    /** Sirf kaam ke context se samajh aane wale shabd ("is topic par"): inke saath cache nahi */
    private val JOB_CONTEXT: Set<String> = setOf("is", "us", "isi", "usi", "ispar", "uspar", "topic", "subject", "wahi", "wohi")

    /**
     * Background kaam (research/plan/likhai) ka command cache ke layak hai?
     * Command mein asli topic ke shabd hone chahiye, warna "research kar do" kisi bhi topic ka purana jawab de deta
     * (topic to pichli baat-cheet se aata hai, command se nahi).
     */
    fun cacheableJob(text: String): Boolean {
        val toks = TaskLogic.tokenize(text).map { it.norm }
        if (toks.size < 3) return false
        if (!common(text, toks)) return false
        if (toks.any { it in JOB_CONTEXT }) return false
        val topic = toks.filter { it !in JOB_FILLER }
        return topic.size >= 2 || topic.any { it.length >= 6 }
    }

    /** Jawab ka text store karne layak hai? (khaali, bahut lamba, action wala, ya sawaal-wapas wala nahi) */
    fun storable(reply: String): Boolean {
        val r = reply.trim()
        if (r.length < 15 || r.length > MAX_ANSWER) return false
        if (r.endsWith("?")) return false
        if (r.contains("Ruk gayi")) return false
        for (m in listOf("ACTION:", "OPEN:", "REMEMBER:", "DONE:", "| MORE")) if (r.contains(m)) return false
        return true
    }

    fun jobKey(kind: String, text: String): String = "job:" + kind + ":" + key(text)

    fun lookupKey(entries: List<CacheEntry>, key: String, now: Long): CacheEntry? {
        for (e in entries) {
            if (e.key != key) continue
            if (now - e.createdAt < ttlMs(e.kind) && now >= e.createdAt) return e
        }
        return null
    }

    fun lookup(entries: List<CacheEntry>, question: String, now: Long): CacheEntry? = lookupKey(entries, key(question), now)

    fun footer(e: CacheEntry, now: Long): String {
        val days = ((now - e.createdAt) / DAY_MS).toInt()
        val age = if (days < 1) "aaj ka" else days.toString() + " din purana"
        return "\n\n💾 Yaad se, bina API (" + age + "). Naya chahiye to 'naya jawab do' likho."
    }

    fun valid(e: CacheEntry, now: Long): Boolean {
        if (e.key.isBlank() || e.key.length > 400) return false
        if (e.answer.isBlank() || e.answer.length > MAX_ANSWER) return false
        if (e.kind !in KINDS) return false
        if (e.createdAt <= 0L || e.createdAt > now + DAY_MS) return false
        if (now - e.createdAt >= ttlMs(e.kind)) return false
        if (e.key.startsWith("job:")) return true
        return cacheableChat(e.question) && key(e.question) == e.key
    }

    fun toJson(items: List<CacheEntry>): String {
        val arr = JSONArray()
        for (e in items) {
            val o = JSONObject()
            o.put("k", e.key)
            o.put("q", e.question)
            o.put("a", e.answer)
            o.put("t", e.kind)
            o.put("c", e.createdAt)
            o.put("l", e.lastHit)
            o.put("h", e.hits)
            arr.put(o)
        }
        return arr.toString()
    }

    fun fromJson(raw: String): List<CacheEntry> {
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<CacheEntry>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val k = o.optString("k", "")
                val a = o.optString("a", "")
                if (k.isBlank() || a.isBlank()) continue
                out.add(
                    CacheEntry(
                        k, o.optString("q", "").take(MAX_QUESTION), a.take(MAX_ANSWER), o.optString("t", "chat"),
                        o.optLong("c", 0L), o.optLong("l", 0L), o.optInt("h", 0)
                    )
                )
            }
        } catch (e: JSONException) {
            return emptyList()
        }
        return out
    }
}

/** Yaad kiye hue jawabon ki list ke saath badlav (pure) */
class AnswerBook(items: List<CacheEntry>) {

    private val list = ArrayList<CacheEntry>(items)

    fun all(): List<CacheEntry> = ArrayList(list)

    fun size(): Int = list.size

    fun get(key: String): CacheEntry? = list.firstOrNull { it.key == key }

    /** Naya jodo (wahi key ho to badal do). Bhar jaye to sabse kam kaam ka (kam hits, purana) hatao. */
    fun put(e: CacheEntry) {
        list.removeAll { it.key == e.key }
        while (list.size >= AnswerCacheLogic.MAX_ENTRIES) {
            val victim = list.minWithOrNull(compareBy<CacheEntry> { it.hits }.thenBy { it.lastHit }) ?: break
            list.remove(victim)
        }
        list.add(e)
    }

    fun hit(key: String, now: Long): Boolean {
        val i = list.indexOfFirst { it.key == key }
        if (i < 0) return false
        val e = list[i]
        list[i] = CacheEntry(e.key, e.question, e.answer, e.kind, e.createdAt, now, e.hits + 1)
        return true
    }

    fun remove(key: String): Boolean = list.removeAll { it.key == key }

    fun clear(): Int {
        val n = list.size
        list.clear()
        return n
    }

    fun totalHits(): Int = list.sumOf { it.hits }

    fun prune(now: Long): Int {
        val before = list.size
        list.removeAll { now - it.createdAt >= AnswerCacheLogic.ttlMs(it.kind) }
        return before - list.size
    }

    /** Backup se jodo: sirf sahi entries, wahi key ho to naya wala rakho. Kitne naye/naye-jaise jude, wo lauta do. */
    fun merge(incoming: List<CacheEntry>, now: Long): Int {
        var added = 0
        for (e in incoming) {
            if (!AnswerCacheLogic.valid(e, now)) continue
            val have = get(e.key)
            if (have == null) {
                put(e)
                added++
            } else if (e.createdAt > have.createdAt) {
                put(CacheEntry(e.key, e.question, e.answer, e.kind, e.createdAt, have.lastHit, have.hits))
                added++
            }
        }
        return added
    }
}

/** Backup/restore ka format aur niyam (pure). API keys, licence, voice/face jaisa kuch bhi isme kabhi nahi jata. */
object BackupLogic {

    const val FORMAT = "jarvi-backup"
    const val VERSION = 1
    const val MAX_BYTES = 2_000_000
    const val EXPORT_BUDGET = 250_000

    class Parsed(val tasks: List<Macro>, val answers: List<CacheEntry>, val device: String, val createdAt: Long)

    sealed class ParseResult {
        class Ok(val parsed: Parsed) : ParseResult()
        class Bad(val message: String) : ParseResult()
    }

    fun looksLikeBackup(text: String): Boolean = text.length >= 40 && text.contains("\"format\"") && text.contains(FORMAT)

    private fun answerJson(e: CacheEntry): JSONObject =
        JSONObject().put("k", e.key).put("q", e.question).put("a", e.answer).put("t", e.kind).put("c", e.createdAt).put("l", e.lastHit).put("h", e.hits)

    /** Budget (bytes) ke andar sabse kaam ke jawab chunkar backup banao. Kaam (tasks) hamesha poore jate hain. */
    fun export(tasks: List<Macro>, answers: List<CacheEntry>, device: String, appVersion: String, now: Long, budget: Int = EXPORT_BUDGET): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("appVersion", appVersion)
        root.put("device", device.take(60))
        root.put("createdAt", now)
        root.put("tasks", JSONArray(TaskLogic.toJson(tasks)))
        val ans = JSONArray()
        var size = root.toString().length
        val ranked = answers.sortedWith(compareByDescending<CacheEntry> { it.hits }.thenByDescending { it.lastHit })
        for (e in ranked) {
            val o = answerJson(e)
            val add = o.toString().length + 1
            if (size + add > budget) continue
            ans.put(o)
            size += add
        }
        root.put("answers", ans)
        return root.toString()
    }

    fun parse(text: String): ParseResult {
        if (text.length > MAX_BYTES) return ParseResult.Bad("File bahut badi hai (" + (text.length / 1_000_000) + " MB+). Jarvi ka backup itna bada nahi hota.")
        val a = text.indexOf('{')
        val b = text.lastIndexOf('}')
        if (a < 0 || b <= a) return ParseResult.Bad("Ye Jarvi ka backup nahi lag raha.")
        val o: JSONObject = try {
            JSONObject(text.substring(a, b + 1))
        } catch (e: JSONException) {
            return ParseResult.Bad("Backup ka JSON adhura ya kharab hai. Poora text ek saath paste karo.")
        }
        if (o.optString("format", "") != FORMAT) return ParseResult.Bad("Ye Jarvi ka backup nahi hai.")
        val v = o.optInt("version", 0)
        if (v > VERSION) return ParseResult.Bad("Ye backup Jarvi ke naye version ka hai. Pehle app update karo, phir restore karo.")
        if (v < 1) return ParseResult.Bad("Backup ka version samajh nahi aaya.")
        val tasks = TaskLogic.fromJson(o.optJSONArray("tasks")?.toString() ?: "")
        val answers = AnswerCacheLogic.fromJson(o.optJSONArray("answers")?.toString() ?: "")
        return ParseResult.Ok(Parsed(tasks, answers, o.optString("device", "?").take(60), o.optLong("createdAt", 0L)))
    }

    /** Backup ke kaam jodo. Wahi command pehle se theek chal raha ho to nahi jodte (dobara restore se kuch nahi badalta). */
    fun mergeTasks(book: MacroBook, incoming: List<Macro>): Int {
        var added = 0
        for (m in incoming) {
            val nm = Macro(book.nextId(), m.pattern, m.examples, m.steps, m.uses, 0, m.lastUsed)
            if (book.add(nm)) added++
        }
        return added
    }

    fun summary(addedTasks: Int, addedAnswers: Int, device: String): String {
        if (addedTasks == 0 && addedAnswers == 0) {
            return "Restore karne ko kuch naya nahi (" + device + " ka backup) — sab pehle se is phone par hai."
        }
        return "Restore ho gaya ✅ (" + device + " ka backup): " + addedTasks + " seekhe hue kaam aur " + addedAnswers +
            " yaad kiye jawab add hue. Jo pehle se tha wo waisa hi hai, kuch delete nahi hua."
    }
}
