package com.jarvis.app

/*
 * To-do list ke seedhe commands: "todo mein dudh lana jodo", "mere todo batao", "dudh wala todo ho gaya",
 * "sab todo hata do". Is file mein Android ka koi code nahi hai, isliye ye JVM unit tests mein seedha test hota hai.
 */

sealed class TodoCommand {
    class Add(val text: String) : TodoCommand()
    object MissingText : TodoCommand()
    object ShowAll : TodoCommand()
    class Done(val about: String) : TodoCommand()
    class Remove(val about: String) : TodoCommand()
    object ClearAll : TodoCommand()
}

object TodoLogic {

    private val I = RegexOption.IGNORE_CASE
    private val noun = Regex("(?<![a-z])(?:to[- ]?do|todos?|task\\s+list|tasks?)(?:\\s+list)?(?![a-z])", I)

    private val addVerbs = listOf("add karo", "add kar do", "add", "jodo", "jod do", "jod dena", "daalo", "dalo", "daal do", "likho", "likh do", "rakho", "rakh do", "bana do")
    private val doneWords = listOf("ho gaya", "ho gayi", "ho gaye", "done", "completed", "complete", "khatam")
    private val removeVerbs = listOf("hata do", "hata", "delete", "remove", "mita do", "mita", "cancel")
    private val clearWords = listOf("saaf", "clear")
    private val allWords = listOf("sab", "saare", "sabhi", "saari", "all")
    private val showWords = listOf("batao", "bata do", "dikhao", "dikha do", "list", "kya kya", "kaun", "kitne", "sunao", "padho", "dekho", "show", "what")

    private val lead = setOf(
        "mein", "me", "main", "m", "my", "mere", "meri", "mera", "list", "from", "se", "ko", "ki", "ke", "to", "in", "into",
        "the", "a", "please", "pls", "jarvi", "jarvis", "mujhe", "hey", "ok", "bhi", "add", "ye", "wo", "woh"
    )
    private val trail = setOf("mein", "me", "main", "list", "to", "in", "into", "ko", "ki", "ke", "please", "pls", "na", "yaar", "bhi", "mujhe", "se", "from", "my", "mere", "meri", "mera", "the")

    private fun wordRegex(w: String) = Regex("(?<![a-z])" + Regex.escape(w) + "(?![a-z])", I)

    private fun clean(s: String): String {
        val toks = s.replace(Regex("[,;:!?।]+"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        while (toks.isNotEmpty() && toks.first().lowercase().trim('.') in lead) toks.removeAt(0)
        while (toks.isNotEmpty() && toks.last().lowercase().trim('.') in trail) toks.removeAt(toks.size - 1)
        return toks.joinToString(" ").trim()
    }

    /** Naam wala hissa nikalo: noun aur command ke shabd hata do, bacha hua kaam ka text hai */
    private fun leftover(raw: String, words: List<String>): String {
        var s = noun.replace(raw, " ")
        for (w in words.sortedByDescending { it.length }) s = wordRegex(w).replace(s, " ")
        return clean(s)
    }

    fun parse(text: String): TodoCommand? {
        val raw = text.trim()
        if (raw.isEmpty() || raw.length > 300) return null
        if (!noun.containsMatchIn(raw)) return null
        val low = raw.lowercase()

        val hasRemove = removeVerbs.any { wordRegex(it).containsMatchIn(low) }
        val hasClear = clearWords.any { wordRegex(it).containsMatchIn(low) }
        val hasAll = allWords.any { wordRegex(it).containsMatchIn(low) }
        if ((hasRemove || hasClear) && hasAll) return TodoCommand.ClearAll
        if (hasRemove) return TodoCommand.Remove(leftover(raw, removeVerbs + addVerbs + allWords))

        if (addVerbs.any { wordRegex(it).containsMatchIn(low) }) {
            val t = leftover(raw, addVerbs)
            return if (t.isEmpty()) TodoCommand.MissingText else TodoCommand.Add(t)
        }
        if (doneWords.any { wordRegex(it).containsMatchIn(low) }) return TodoCommand.Done(leftover(raw, doneWords + listOf("karo", "kar", "do")))
        if (showWords.any { wordRegex(it).containsMatchIn(low) }) return TodoCommand.ShowAll
        return null
    }
}
