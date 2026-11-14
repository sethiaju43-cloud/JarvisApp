package com.jarvis.app

/*
 * Message auto-reply ke commands aur faisle ka hisaab. Is file mein Android ka koi code nahi hai,
 * isliye ye JVM unit tests mein seedha test hota hai.
 * Safety: har reply user ki haan ke baad hi jata hai (notification mein Bhejo / Mat bhejo).
 */

sealed class AutoReplyCommand {
    class On(val hours: Int) : AutoReplyCommand()
    object Off : AutoReplyCommand()
    class SetMessage(val text: String) : AutoReplyCommand()

    /** name "*" ka matlab sabhi (sirf 1-to-1 chat) */
    class AddContact(val name: String) : AutoReplyCommand()
    class RemoveContact(val name: String) : AutoReplyCommand()
    object ListContacts : AutoReplyCommand()
    object Status : AutoReplyCommand()
    object ShowLog : AutoReplyCommand()
}

object AutoReplyLogic {

    private val I = RegexOption.IGNORE_CASE
    const val DEFAULT_MESSAGE = "Abhi busy hun, thodi der mein baat karte hain 🙏 (auto-reply)"
    const val DEFAULT_HOURS = 3
    const val COOLDOWN_MS = 30L * 60L * 1000L
    const val MAX_DRAFTS_PER_HOUR = 20

    private val key = Regex("(?<![a-z])auto[- ]?reply(?![a-z])|ऑटो\\s*रिप्लाई", I)
    private val setMsg = Regex("(?:message|msg|text|jawab)\\s*(?:set\\s*karo|badlo|likho)?\\s*[:：]\\s*(.+)$", I)
    private val everyoneWord = Regex("(?<![a-z])(sabko|sab\\s+ko|sabhi\\s+ko|everyone|sabhi)(?![a-z])", I)
    private val addVerb = "(?:jodo|add\\s*karo|add|jod\\s*do|daalo|dalo)"
    private val removeVerb = "(?:hata\\s*do|hatao|remove|delete|mita\\s*do)"
    private val addRe = Regex("(.+?)\\s+ko\\s+$addVerb(?![a-z])", I)
    private val removeRe = Regex("(.+?)\\s+ko\\s+$removeVerb(?![a-z])", I)
    private val hoursRe = Regex("(\\d{1,2})\\s*(?:ghante|ghanta|hours?|hrs?)", I)

    private fun word(s: String) = Regex("(?<![a-z])" + s + "(?![a-z])", I)
    private val logWord = word("log")
    private val onWords = word("(?:chalu|on|start|shuru|laga|enable|activate)")
    private val offWords = word("(?:band|off|stop|hata|disable|khatam)")

    private fun cleanName(s: String): String {
        var n = s.trim().trim(',', ':', '-', '.')
        n = n.replace(Regex("^(?:mein|me|main|mei|se|ke\\s+liye)\\s+", I), "")
        return n.trim()
    }

    fun parse(text: String): AutoReplyCommand? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 300) return null
        if (!key.containsMatchIn(t)) return null
        val low = t.lowercase()

        val m = setMsg.find(t)
        if (m != null) {
            val msg = m.groupValues[1].trim()
            return if (msg.length in 3..200) AutoReplyCommand.SetMessage(msg) else null
        }
        if (low.contains("kya bheja") || logWord.containsMatchIn(t) || low.contains("history")) return AutoReplyCommand.ShowLog

        val rest = key.replace(t, " ")
        val rm = removeRe.find(rest)
        if (rm != null) {
            val name = cleanName(rm.groupValues[1])
            return if (everyoneWord.containsMatchIn(name)) AutoReplyCommand.RemoveContact("*") else if (name.isEmpty()) null else AutoReplyCommand.RemoveContact(name)
        }
        val am = addRe.find(rest)
        if (am != null) {
            val name = cleanName(am.groupValues[1])
            return if (everyoneWord.containsMatchIn(name)) AutoReplyCommand.AddContact("*") else if (name.isEmpty()) null else AutoReplyCommand.AddContact(name)
        }
        if (everyoneWord.containsMatchIn(rest) && Regex(addVerb, I).containsMatchIn(rest)) return AutoReplyCommand.AddContact("*")
        if (low.contains("list") || low.contains("kis kis") || low.contains("kaun kaun") || low.contains("kisko") || low.contains("contacts")) return AutoReplyCommand.ListContacts
        if (low.contains("status") || Regex("(?<![a-z])(on|chalu|band|off)\\s+hai", I).containsMatchIn(t)) return AutoReplyCommand.Status
        if (offWords.containsMatchIn(t)) return AutoReplyCommand.Off
        if (onWords.containsMatchIn(t)) {
            val h = hoursRe.find(t)?.groupValues?.get(1)?.toIntOrNull() ?: DEFAULT_HOURS
            return AutoReplyCommand.On(h.coerceIn(1, 12))
        }
        return null
    }

    private fun words(s: String): List<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** Bhejne wale ka naam chune hue logon mein se kisi se milta hai? ("Rahul" milta hai "Rahul Sharma" se) */
    fun senderMatches(sender: String, contacts: Collection<String>, everyone: Boolean): Boolean {
        if (sender.isBlank()) return false
        if (everyone) return true
        val sw = words(sender)
        for (c in contacts) {
            val cw = words(c)
            if (cw.isNotEmpty() && sw.containsAll(cw)) return true
        }
        return false
    }

    /**
     * Is message ke liye jawab ka draft banana chahiye? (Phir bhi bhejne se pehle user se poochha jayega.)
     * Group chat, code/OTP wala, jawab ka button na ho, cooldown ya ghante ki limit par: nahi.
     */
    fun shouldDraft(
        enabled: Boolean, everyone: Boolean, contacts: Collection<String>, sender: String,
        isGroup: Boolean, isSecret: Boolean, hasReplyAction: Boolean,
        lastDraftAtForSender: Long?, nowMs: Long, draftsLastHour: Int
    ): Boolean {
        if (!enabled || isGroup || isSecret || !hasReplyAction) return false
        if (!senderMatches(sender, contacts, everyone)) return false
        if (lastDraftAtForSender != null && nowMs - lastDraftAtForSender < COOLDOWN_MS) return false
        if (draftsLastHour >= MAX_DRAFTS_PER_HOUR) return false
        return true
    }
}
