package com.jarvis.app

/*
 * Notification padhne ka hisaab. Is file mein Android ka koi code nahi hai, isliye ye JVM unit tests mein
 * seedha test hota hai. Notification ka text sirf phone ki RAM mein rehta hai (restart par saaf),
 * kahin save nahi hota aur cloud AI ko nahi bheja jata.
 */

object NotificationLogic {

    const val SECRET_TEXT = "(code ya OTP wala message, chhupa diya 🔒)"

    /** Bank, UPI, wallet, authenticator aur password apps ke notification hum padhte hi nahi */
    private val excludedPrefixes = listOf(
        "com.phonepe", "com.google.android.apps.nbu.paisa", "net.one97.paytm",
        "in.org.npci", "com.dreamplug", "com.mobikwik", "com.freecharge", "com.bharatpe",
        "com.google.android.apps.walletnfcrel",
        "com.google.android.apps.authenticator2", "com.authy",
        "com.bitwarden", "com.x8bit.bitwarden", "com.lastpass", "com.onepassword",
        "com.agilebits", "com.dashlane", "org.keepassdroid", "keepass2android"
    )

    private val secretWords = listOf(
        "otp", "one time password", "one-time password", "verification code", "verify code", "cvv",
        "do not share", "don't share", "kisi ko mat batana", "kisi ko na batayein", "password", "passcode",
        "login code", "security code"
    )
    private val codeNumber = Regex("(?<![0-9])[0-9]{4,8}(?![0-9])")
    private val codeHints = listOf("code", "pin", "pass")

    fun isExcludedApp(pkg: String): Boolean {
        val p = pkg.lowercase()
        if (p.contains("bank") || p.contains("upi") || p.contains("wallet")) return true
        return excludedPrefixes.any { p.startsWith(it) }
    }

    fun looksSecret(title: String, text: String): Boolean {
        val t = (title + " " + text).lowercase()
        if (secretWords.any { t.contains(it) }) return true
        return codeNumber.containsMatchIn(t) && codeHints.any { t.contains(it) }
    }

    class Item(
        val key: String,
        val pkg: String,
        val app: String,
        val title: String,
        val text: String,
        val whenMs: Long
    )

    /** Notification se Item banao. Bank wale ya khali notification par null. Code wale par text chhupa do. */
    fun make(key: String, pkg: String, app: String, title: String, text: String, whenMs: Long): Item? {
        if (isExcludedApp(pkg)) return null
        val ti = title.trim()
        val tx = text.trim()
        if (ti.isEmpty() && tx.isEmpty()) return null
        if (looksSecret(ti, tx)) return Item(key, pkg, app, "", SECRET_TEXT, whenMs)
        return Item(key, pkg, app, ti, tx, whenMs)
    }

    enum class Command { READ, CLEAR }

    private val readPhrases = listOf(
        "notification padho", "notifications padho", "notification padh", "notification batao", "notifications batao",
        "notification sunao", "notifications sunao", "naya notification", "naye notification", "koi notification",
        "mere notification", "mere notifications", "kaun sa message aaya", "kaun sa message aaya", "koi message aaya",
        "koi naya message", "read my notifications", "read notifications", "what are my notifications",
        "any new notifications", "नोटिफिकेशन पढ़ो"
    )
    private val clearPhrases = listOf(
        "notification saaf", "notifications saaf", "notification clear", "notifications clear",
        "notification hata", "notifications hata", "clear all notifications", "clear my notifications"
    )

    fun parseCommand(text: String): Command? {
        val t = text.lowercase().trim()
        if (t.isEmpty() || t.length > 80) return null
        if (clearPhrases.any { t.contains(it) }) return Command.CLEAR
        if (readPhrases.any { t.contains(it) }) return Command.READ
        return null
    }

    private fun clip(s: String, n: Int): String = if (s.length <= n) s else s.take(n).trimEnd() + "..."

    private fun line(item: Item): String {
        val sb = StringBuilder(item.app)
        if (item.title.isNotEmpty()) sb.append(", ").append(clip(item.title, 40))
        if (item.text.isNotEmpty()) sb.append(": ").append(clip(item.text, 90))
        return sb.toString()
    }

    /** Bolne layak chhota text: sabse naya pehle, 5 tak */
    fun speak(items: List<Item>, max: Int = 5): String {
        if (items.isEmpty()) return "Abhi koi naya notification nahi hai 🙂"
        val sorted = items.sortedByDescending { it.whenMs }
        val sb = StringBuilder("Aapke ").append(items.size).append(" notification hain.")
        for ((i, item) in sorted.take(max).withIndex()) {
            sb.append("\n").append(i + 1).append(") ").append(line(item))
        }
        if (items.size > max) sb.append("\nAur ").append(items.size - max).append(" notification baaki hain.")
        sb.append("\n(Bank, UPI aur OTP wale main nahi padhti 🔒)")
        return sb.toString()
    }
}

/** Abhi ke notification, sirf RAM mein. Max 40, 6 ghante se purane hat jate hain. */
object NotificationStore {
    private const val MAX_ITEMS = 40
    const val MAX_AGE_MS = 6L * 60L * 60L * 1000L
    private val items = LinkedHashMap<String, NotificationLogic.Item>()

    @Synchronized
    fun put(item: NotificationLogic.Item) {
        items.remove(item.key)
        items[item.key] = item
        while (items.size > MAX_ITEMS) {
            val first = items.keys.first()
            items.remove(first)
        }
    }

    @Synchronized
    fun remove(key: String) {
        items.remove(key)
    }

    @Synchronized
    fun recent(nowMs: Long): List<NotificationLogic.Item> =
        items.values.filter { nowMs - it.whenMs <= MAX_AGE_MS }.sortedByDescending { it.whenMs }

    @Synchronized
    fun clear() {
        items.clear()
    }
}
