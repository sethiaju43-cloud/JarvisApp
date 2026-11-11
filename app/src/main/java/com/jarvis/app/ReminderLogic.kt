package com.jarvis.app

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/*
 * "5 baje yaad dilana", "10 minute baad chai band karni hai yaad dilana", "kal subah 8 baje reminder lagao"
 * jaise vaakya samajhna. Is file mein Android ka koi code nahi hai, isliye ye JVM unit tests mein seedha test hota hai.
 * Ye AI ko bheje bina phone par hi hota hai.
 */

sealed class ReminderCommand {
    class Add(val whenMs: Long, val message: String, val repeatDaily: Boolean) : ReminderCommand()
    class MissingTime(val message: String) : ReminderCommand()
    object ShowAll : ReminderCommand()
    object CancelAll : ReminderCommand()
    class Cancel(val about: String) : ReminderCommand()
}

object ReminderLogic {

    const val DEFAULT_MESSAGE = "Yaad dilane ka waqt ho gaya"
    private val I = RegexOption.IGNORE_CASE

    private val triggers = listOf(
        Regex("(?<![a-z])yaad\\s*dil[a-z]*(?:\\s+(?:do|dena|de|dijiye|karo|kar\\s+do))?", I),
        Regex("(?<![a-z])remind\\s+me", I),
        Regex("(?<![a-z])reminders?(?:\\s+(?:lagao|laga\\s+do|laga\\s+dijiye|set(?:\\s+karo|\\s+kar\\s+do)?|bana(?:o|\\s+do)?|add\\s+karo))?", I),
        Regex("याद\\s*दिला[\\p{L}\\p{M}]*")
    )
    private val reminderNoun = Regex("(?<![a-z])reminders?(?![a-z])", I)

    private val numWords = mapOf(
        "ek" to 1.0, "do" to 2.0, "teen" to 3.0, "chaar" to 4.0, "char" to 4.0, "paanch" to 5.0, "panch" to 5.0,
        "chhe" to 6.0, "saat" to 7.0, "aath" to 8.0, "nau" to 9.0, "das" to 10.0, "pandrah" to 15.0, "bees" to 20.0,
        "tees" to 30.0, "chalis" to 40.0, "pachas" to 50.0, "aadha" to 0.5, "aadhe" to 0.5, "dedh" to 1.5
    )
    private val relPattern = Regex(
        "(?<![a-z0-9.])(\\d+(?:\\.\\d+)?|ek|do|teen|chaar|char|paanch|panch|chhe|saat|aath|nau|das|pandrah|bees|tees|chalis|pachas|aadha|aadhe|dedh)" +
            "\\s*(hours?|hrs?|ghante|ghanta|ghantey|minutes?|mins?|mint|minat|seconds?|secs?)(?![a-z])(?:\\s*(?:baad|bad|mein|me|later))?", I
    )
    private val relSpecial = Regex("(?<![a-z])(half an hour|an hour|a minute)(?![a-z])", I)

    private val absWithSuffix = Regex("(?<![a-z0-9:])(\\d{1,2})(?::(\\d{2}))?\\s*(baje|bje|a\\.?m\\.?|p\\.?m\\.?|बजे)(?![a-z])", I)
    private val absColon = Regex("(?<![a-z0-9:])(\\d{1,2}):(\\d{2})(?![0-9])")

    private val parts = listOf(
        Pair(Regex("(?<![a-z])(subah|savere|morning|सुबह|सवेरे)(?![a-z])", I), "am"),
        Pair(Regex("(?<![a-z])(dopahar|dophar|afternoon|दोपहर)(?![a-z])", I), "noon"),
        Pair(Regex("(?<![a-z])(shaam|sham|evening|शाम)(?![a-z])", I), "pm"),
        Pair(Regex("(?<![a-z])(raat|night|रात)(?![a-z])", I), "night")
    )
    private val dayAfter = Regex("(?<![a-z])(parso|day after tomorrow|परसों)(?![a-z])", I)
    private val dayTomorrow = Regex("(?<![a-z])(kal|tomorrow|कल)(?![a-z])", I)
    private val dayToday = Regex("(?<![a-z])(aaj|today|आज)(?![a-z])", I)
    private val dailyWords = Regex("(?<![a-z])(har\\s*roz|roz|daily|everyday|every\\s+day|हर\\s*रोज|रोज)", I)

    private val cancelWords = listOf("hata do", "hata", "delete", "cancel", "mita do", "mita", "remove", "band kar")
    private val allWords = listOf("sab", "saare", "sabhi", "saari", "all")
    private val showWords = listOf("batao", "bata do", "dikhao", "dikha do", "list", "kaun", "kitne", "kya kya", "sunao", "dekho", "show", "what", "padho")

    private val lead = setOf(
        "mujhe", "mujhko", "mereko", "muje", "please", "pls", "jarvi", "jarvis", "hey", "ok", "ki", "ke", "ko",
        "ye", "yeh", "to", "that", "at", "on", "in", "by", "par", "pe", "tak"
    )
    private val trail = setOf("ki", "ke", "liye", "please", "pls", "na", "yaar", "zara", "jara", "at", "on", "in", "for", "to")

    private fun cal(ms: Long, tz: TimeZone): Calendar {
        val c = Calendar.getInstance(tz)
        c.timeInMillis = ms
        return c
    }

    private fun atHour(base: Calendar, dayAdd: Int, h24: Int, m: Int): Long {
        val c = base.clone() as Calendar
        c.add(Calendar.DAY_OF_YEAR, dayAdd)
        c.set(Calendar.HOUR_OF_DAY, h24)
        c.set(Calendar.MINUTE, m)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun numFrom(s: String): Double = s.toDoubleOrNull() ?: numWords[s.lowercase()] ?: 0.0

    /** Ghante/minute ka samay nikalo. Samajh na aaye to null. */
    private fun resolve(h: Int, m: Int, sfx: String, part: String, dayOffset: Int?, nowMs: Long, tz: TimeZone): Long? {
        if (h > 23 || m > 59) return null
        val now = cal(nowMs, tz)
        val s = sfx.lowercase().replace(".", "")
        var h24 = -1
        when {
            s == "am" -> { if (h > 12 || h == 0) return null; h24 = h % 12 }
            s == "pm" -> { if (h > 12 || h == 0) return null; h24 = h % 12 + 12 }
            part == "am" -> h24 = if (h > 12) h else h % 12
            part == "noon" -> h24 = if (h == 12) 12 else if (h in 1..6) h + 12 else h
            part == "pm" -> h24 = if (h in 1..11) h + 12 else h
            part == "night" -> h24 = if (h in 6..11) h + 12 else if (h == 12) 0 else h
            h >= 13 || h == 0 -> h24 = h
        }
        if (h24 >= 0) {
            var t = atHour(now, dayOffset ?: 0, h24, m)
            if (t <= nowMs) {
                if (dayOffset == null) t = atHour(now, 1, h24, m) else return null
            }
            return t
        }
        // 1 se 12 ke beech, am/pm nahi bola
        val am = h % 12
        val pm = am + 12
        if (dayOffset == null) {
            val options = listOf(atHour(now, 0, am, m), atHour(now, 0, pm, m), atHour(now, 1, am, m))
            return options.filter { it > nowMs }.minOrNull()
        }
        val chosen = if (h in 1..4) pm else if (h in 5..11) am else 12
        val t = atHour(now, dayOffset, chosen, m)
        return if (t > nowMs) t else null
    }

    fun cleanMessage(s: String): String {
        val toks = s.replace(Regex("[,;:!?।]+"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        while (toks.isNotEmpty() && toks.first().lowercase().trim('.') in lead) toks.removeAt(0)
        while (toks.isNotEmpty() && toks.last().lowercase().trim('.') in trail) toks.removeAt(toks.size - 1)
        return toks.joinToString(" ").trim()
    }

    private fun wordRegex(w: String) = Regex("(?<![a-z])" + Regex.escape(w) + "(?![a-z])", I)

    fun parse(text: String, nowMs: Long, tz: TimeZone): ReminderCommand? {
        val raw = text.trim()
        if (raw.isEmpty() || raw.length > 300) return null
        if (triggers.none { it.containsMatchIn(raw) }) return null
        val low = raw.lowercase()

        // Reminders dikhao / hatao
        if (reminderNoun.containsMatchIn(raw)) {
            if (cancelWords.any { low.contains(it) }) {
                if (allWords.any { wordRegex(it).containsMatchIn(low) }) return ReminderCommand.CancelAll
                var about = low
                val drop = cancelWords + listOf("reminders", "reminder", "wala", "wali", "wale", "karo", "kar", "do", "mera", "meri", "mere", "ye", "wo", "woh")
                for (w in drop) about = wordRegex(w).replace(about, " ")
                return ReminderCommand.Cancel(cleanMessage(about))
            }
            if (raw.none { it.isDigit() } && showWords.any { low.contains(it) }) return ReminderCommand.ShowAll
        }

        var rest = raw
        var whenMs: Long? = null
        var repeat = false

        if (dailyWords.containsMatchIn(rest)) {
            repeat = true
            rest = dailyWords.replace(rest, " ")
        }

        val rm = relPattern.find(rest)
        if (rm != null) {
            val unit = rm.groupValues[2].lowercase()
            val mult = if (unit.startsWith("h") || unit.startsWith("g")) 3_600_000.0 else if (unit.startsWith("m")) 60_000.0 else 1000.0
            val delta = (numFrom(rm.groupValues[1]) * mult).toLong()
            if (delta > 0) {
                whenMs = nowMs + delta
                rest = rest.removeRange(rm.range)
                repeat = false
            }
        }
        if (whenMs == null) {
            val sp = relSpecial.find(rest)
            if (sp != null) {
                val k = sp.groupValues[1].lowercase()
                val delta = if (k == "a minute") 60_000L else if (k == "an hour") 3_600_000L else 1_800_000L
                whenMs = nowMs + delta
                rest = rest.removeRange(sp.range)
                repeat = false
            }
        }

        var timeFound = whenMs != null
        if (whenMs == null) {
            var part = ""
            for ((re, name) in parts) {
                if (re.containsMatchIn(rest)) {
                    if (part.isEmpty()) part = name
                    rest = re.replace(rest, " ")
                }
            }
            var dayOffset: Int? = null
            if (dayAfter.containsMatchIn(rest)) {
                dayOffset = 2
                rest = dayAfter.replace(rest, " ")
            } else if (dayTomorrow.containsMatchIn(rest)) {
                dayOffset = 1
                rest = dayTomorrow.replace(rest, " ")
            }
            if (dayToday.containsMatchIn(rest)) {
                if (dayOffset == null) dayOffset = 0
                rest = dayToday.replace(rest, " ")
            }
            var h = -1
            var m = 0
            var sfx = ""
            val a = absWithSuffix.find(rest)
            if (a != null) {
                h = a.groupValues[1].toInt()
                m = if (a.groupValues[2].isEmpty()) 0 else a.groupValues[2].toInt()
                sfx = a.groupValues[3]
                rest = rest.removeRange(a.range)
            } else {
                val c = absColon.find(rest)
                if (c != null) {
                    h = c.groupValues[1].toInt()
                    m = c.groupValues[2].toInt()
                    rest = rest.removeRange(c.range)
                }
            }
            if (h >= 0) {
                timeFound = true
                whenMs = resolve(h, m, sfx, part, dayOffset, nowMs, tz)
            }
        }

        for (t in triggers) rest = t.replace(rest, " ")
        val msg = cleanMessage(rest)
        if (!timeFound || whenMs == null) return ReminderCommand.MissingTime(msg)
        return ReminderCommand.Add(whenMs, if (msg.isEmpty()) DEFAULT_MESSAGE else msg, repeat)
    }

    private fun sameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    /** "aaj 5:00 PM", "kal 8:30 AM", "15/11 7:00 AM" */
    fun describe(whenMs: Long, nowMs: Long, tz: TimeZone): String {
        val c = cal(whenMs, tz)
        val n = cal(nowMs, tz)
        val tomorrow = n.clone() as Calendar
        tomorrow.add(Calendar.DAY_OF_YEAR, 1)
        val day = when {
            sameDay(c, n) -> "aaj"
            sameDay(c, tomorrow) -> "kal"
            else -> String.format(Locale.US, "%02d/%02d", c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1)
        }
        var h = c.get(Calendar.HOUR)
        if (h == 0) h = 12
        val ap = if (c.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
        return String.format(Locale.US, "%s %d:%02d %s", day, h, c.get(Calendar.MINUTE), ap)
    }

    /** Roz wale reminder ka agla samay (ghadi badalne par bhi wahi ghanta-minute) */
    fun nextDaily(whenMs: Long, tz: TimeZone): Long {
        val c = cal(whenMs, tz)
        c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }
}
