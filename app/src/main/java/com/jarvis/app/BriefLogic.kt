package com.jarvis.app

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/*
 * Daily brief: roz tay samay par "aaj ke reminder aur baaki kaam" ka chhota saar.
 * Is file mein Android ka koi code nahi hai, isliye ye JVM unit tests mein seedha test hota hai.
 */

sealed class BriefCommand {
    class On(val hour: Int, val minute: Int) : BriefCommand()
    object Off : BriefCommand()
    object Status : BriefCommand()
    object ShowNow : BriefCommand()
}

object BriefLogic {

    private val I = RegexOption.IGNORE_CASE

    private val briefWord = Regex("(?<![a-z])(?:brief|briefing)(?![a-z])", I)
    private val contextWord = Regex("(?<![a-z])(?:daily|morning|evening|roz|subah|shaam|aaj|mera|good\\s+morning)(?![a-z])", I)
    private val statusAsk = Regex("(?<![a-z])status(?![a-z])|(?<![a-z])(?:on|chalu|band|off)\\s+hai", I)
    private val offWords = Regex("(?<![a-z])(?:band|off|stop|hata|disable|khatam)(?![a-z])|nahi\\s+chahiye", I)
    private val onWords = Regex("(?<![a-z])(?:chalu|on|start|shuru|laga|enable|activate|chahiye|bhejo|do)(?![a-z])", I)
    private val timeRe = Regex("(?<![a-z0-9:])(\\d{1,2})(?::(\\d{2}))?\\s*(baje|bje|a\\.?m\\.?|p\\.?m\\.?|बजे)(?![a-z])|(?<![a-z0-9:])(\\d{1,2}):(\\d{2})(?![0-9])", I)
    private val eveningWord = Regex("(?<![a-z])(?:shaam|evening|raat|night)(?![a-z])", I)
    private val morningWord = Regex("(?<![a-z])(?:subah|savere|morning)(?![a-z])", I)

    fun parse(text: String): BriefCommand? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 160) return null
        if (!briefWord.containsMatchIn(t) || !contextWord.containsMatchIn(t)) return null
        if (statusAsk.containsMatchIn(t)) return BriefCommand.Status
        if (offWords.containsMatchIn(t)) return BriefCommand.Off

        val m = timeRe.find(t)
        if (m != null) {
            val hasSuffixGroup = m.groupValues[3].isNotEmpty()
            val h = (if (hasSuffixGroup) m.groupValues[1] else m.groupValues[4]).toIntOrNull() ?: return null
            val mi = (if (hasSuffixGroup) m.groupValues[2] else m.groupValues[5]).let { if (it.isEmpty()) 0 else it.toInt() }
            val sfx = m.groupValues[3].lowercase().replace(".", "")
            val hh = resolveHour(h, sfx, eveningWord.containsMatchIn(t), morningWord.containsMatchIn(t)) ?: return null
            if (mi > 59) return null
            return BriefCommand.On(hh, mi)
        }
        val wantsSchedule = Regex("(?<![a-z])(?:roz|daily|har\\s*roz)(?![a-z])", I).containsMatchIn(t) ||
            Regex("(?<![a-z])(?:chalu|on|start|shuru|laga|enable|activate)(?![a-z])", I).containsMatchIn(t)
        if (wantsSchedule) return BriefCommand.On(if (eveningWord.containsMatchIn(t)) 19 else 8, 0)
        return BriefCommand.ShowNow
    }

    private fun resolveHour(h: Int, sfx: String, evening: Boolean, morning: Boolean): Int? {
        if (h > 23) return null
        if (sfx == "am") return if (h in 1..12) h % 12 else null
        if (sfx == "pm") return if (h in 1..12) h % 12 + 12 else null
        if (h >= 13 || h == 0) return h
        if (evening) return if (h in 1..11) h + 12 else h
        if (morning) return h % 12
        return if (h in 1..4) h + 12 else h // bina batae: 5 se 11 subah, 1 se 4 dopahar/shaam
    }

    fun formatTime(hour: Int, minute: Int): String {
        var h = hour % 12
        if (h == 0) h = 12
        return String.format(Locale.US, "%d:%02d %s", h, minute, if (hour < 12) "AM" else "PM")
    }

    /** Agla samay jab brief bhejna hai (abhi ke baad wala pehla) */
    fun nextTriggerMs(hour: Int, minute: Int, nowMs: Long, tz: TimeZone): Long {
        val c = Calendar.getInstance(tz)
        c.timeInMillis = nowMs
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, minute)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= nowMs) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun sameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    private fun timeOf(ms: Long, tz: TimeZone): String {
        val c = Calendar.getInstance(tz)
        c.timeInMillis = ms
        return formatTime(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    /** Brief ka text. reminders = (samay, text) ki list, todos = baaki kaamon ke text. */
    fun compose(reminders: List<Pair<Long, String>>, todos: List<String>, nowMs: Long, tz: TimeZone): String {
        val now = Calendar.getInstance(tz)
        now.timeInMillis = nowMs
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val greet = if (hour < 12) "Good morning ☀️" else if (hour < 17) "Namaste 🌤" else "Good evening 🌙"
        val today = reminders.filter {
            val c = Calendar.getInstance(tz)
            c.timeInMillis = it.first
            it.first >= nowMs && sameDay(c, now)
        }.sortedBy { it.first }

        val sb = StringBuilder(greet)
        if (today.isEmpty() && todos.isEmpty()) {
            sb.append("\nAaj koi reminder ya baaki kaam nahi hai. Aapka din achha jaye 🙂")
            return sb.toString()
        }
        if (today.isNotEmpty()) {
            sb.append("\n⏰ Aaj ke reminder:")
            for (r in today.take(4)) sb.append("\n• ").append(timeOf(r.first, tz)).append(" - ").append(r.second.take(60))
            if (today.size > 4) sb.append("\n  aur ").append(today.size - 4).append(" reminder")
        }
        if (todos.isNotEmpty()) {
            sb.append("\n📝 Baaki kaam (").append(todos.size).append("):")
            for (t in todos.take(5)) sb.append("\n• ").append(t.take(60))
            if (todos.size > 5) sb.append("\n  aur ").append(todos.size - 5).append(" kaam")
        }
        return sb.toString()
    }
}
