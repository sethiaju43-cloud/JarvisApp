package com.jarvis.app

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/*
 * Local skills: wo chhote kaam jo bina AI ke, bina internet ke seedha phone par ho jate hain
 * (samay/tarikh, hisaab, gaana roko/agla, ringer mode, clipboard, sikka/dice, madad).
 * Is file mein Android ka koi code nahi hai, isliye ye JVM unit tests mein seedha test hota hai.
 */

sealed class LocalCmd {
    class TimeDate(val time: Boolean, val date: Boolean) : LocalCmd()
    class Calc(val expr: String) : LocalCmd()
    object MediaPause : LocalCmd()
    object MediaPlay : LocalCmd()
    object MediaNext : LocalCmd()
    object MediaPrev : LocalCmd()
    object NowPlaying : LocalCmd()
    class Ringer(val mode: String) : LocalCmd() // vibrate | normal | silent
    object ClipRead : LocalCmd()
    class ClipWrite(val text: String) : LocalCmd()
    object ShowAlarms : LocalCmd()
    object ShowTimers : LocalCmd()
    object BatteryOpt : LocalCmd()
    object Coin : LocalCmd()
    object Dice : LocalCmd()
    object Help : LocalCmd()
}

/** Chhota, surakshit hisaab-kitaab (+ - * / % aur brackets). Kuch bhi galat ho to null. */
object CalcEngine {

    fun eval(expr: String): Double? {
        val p = P(expr)
        val v = p.expr() ?: return null
        return if (p.atEnd()) v else null
    }

    private class P(val s: String) {
        var i = 0

        fun skip() {
            while (i < s.length && s[i] == ' ') i++
        }

        fun atEnd(): Boolean {
            skip()
            return i >= s.length
        }

        fun expr(): Double? {
            var v = term() ?: return null
            while (true) {
                skip()
                if (i < s.length && (s[i] == '+' || s[i] == '-')) {
                    val op = s[i++]
                    val r = term() ?: return null
                    v = if (op == '+') v + r else v - r
                } else break
            }
            return v
        }

        fun term(): Double? {
            var v = factor() ?: return null
            while (true) {
                skip()
                if (i < s.length && (s[i] == '*' || s[i] == '/')) {
                    val op = s[i++]
                    val r = factor() ?: return null
                    v = if (op == '*') v * r else v / r
                } else break
            }
            return v
        }

        fun factor(): Double? {
            skip()
            if (i >= s.length) return null
            var v: Double
            val c = s[i]
            if (c == '-') {
                i++
                v = -(factor() ?: return null)
            } else if (c == '+') {
                i++
                v = factor() ?: return null
            } else if (c == '(') {
                i++
                v = expr() ?: return null
                skip()
                if (i >= s.length || s[i] != ')') return null
                i++
            } else {
                val st = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                v = s.substring(st, i).toDoubleOrNull() ?: return null
            }
            skip()
            while (i < s.length && s[i] == '%') {
                i++
                v /= 100.0
                skip()
            }
            return v
        }
    }

    /** 12.0 -> "12", 3.14159265 -> "3.141593" */
    fun format(v: Double): String {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.format(Locale.US, "%d", v.toLong())
        var s = String.format(Locale.US, "%.6f", v)
        s = s.trimEnd('0').trimEnd('.')
        return s
    }
}

object LocalSkillsLogic {

    private val I = RegexOption.IGNORE_CASE

    // ---------- Samay / tarikh ----------
    private val askTime = Regex(
        "(?<![a-z])(?:time|samay|waqt)\\s+(?:kya|kitna|batao|bata\\s+do)|kitne\\s+baje|what\\s+(?:is\\s+the\\s+)?time|current\\s+time|(?<![a-z])time\\s+(?:please|pls)", I
    )
    private val askDate = Regex(
        "aaj\\s+(?:ki\\s+|ka\\s+)?(?:date|tarikh|tareekh)|(?:date|tarikh|tareekh)\\s+(?:kya|batao)|aaj\\s+(?:kaun\\s+sa|kya)\\s+(?:din|day)|what\\s+(?:is\\s+)?(?:today'?s\\s+date|day)|today'?s\\s+date", I
    )

    // ---------- Hisaab ----------
    private val calcCue = Regex(
        "(?<![a-z])(?:kitna|kitne|kitni|calculate|calc|hisab|hisaab|equals?|barabar|kya\\s+hota|kya\\s+hoga|kya\\s+hogi|nikalo|nikaal|solve|answer|jawab)(?![a-z])|=", I
    )
    private val pctA = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:ka|ke|of)\\s*(\\d+(?:\\.\\d+)?)\\s*(?:percent|%|pratishat)", I)
    private val pctB = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:percent|%|pratishat)\\s*(?:of|ka)\\s*(\\d+(?:\\.\\d+)?)", I)
    private val opWords = listOf(
        Regex("(?<![a-z])(?:plus|jod|jode|add)(?![a-z])", I) to "+",
        Regex("(?<![a-z])(?:minus|ghata|ghatao|subtract)(?![a-z])", I) to "-",
        Regex("(?<![a-z])(?:multiplied\\s+by|multiply|guna|gunaa|into|times)(?![a-z])", I) to "*",
        Regex("(?<![a-z])(?:divided\\s+by|divide|bhaag|bhag|upon)(?![a-z])", I) to "/",
        Regex("(?<=\\d)\\s*[x×]\\s*(?=\\d)", I) to "*",
        Regex("÷") to "/",
        Regex("−") to "-"
    )
    private val exprPart = Regex("[0-9+\\-*/().% ]{3,}")

    fun toExpression(text: String): String? {
        var t = text.lowercase().replace(Regex("(?<=\\d),(?=\\d{3})"), "")
        t = pctA.replace(t) { "(" + it.groupValues[1] + "*" + it.groupValues[2] + "/100)" }
        t = pctB.replace(t) { "(" + it.groupValues[2] + "*" + it.groupValues[1] + "/100)" }
        for ((re, sym) in opWords) t = re.replace(t, " $sym ")
        var best: String? = null
        for (m in exprPart.findAll(t)) {
            val c = m.value.trim()
            val nums = Regex("\\d+(?:\\.\\d+)?").findAll(c).count()
            val hasOp = c.any { it == '+' || it == '-' || it == '*' || it == '/' || it == '%' }
            if (nums >= 2 && hasOp && (best == null || c.length > best.length)) best = c
        }
        return best
    }

    // ---------- Media ----------
    private fun norm(text: String): String =
        text.lowercase().replace(Regex("[,.!?।]"), " ").replace(Regex("\\s+"), " ").trim()

    private val nowPlaying = Regex("kaun\\s+sa\\s+(?:gaana|gana|song)\\s+chal|ye\\s+(?:gaana|gana|song)\\s+kaun|now\\s+playing|gaane\\s+ka\\s+naam|what'?s\\s+playing", I)
    private val mediaNext = Regex("(?:agla|next|skip)\\s*(?:gaana|gana|song|track)?(?:\\s+(?:karo|kar\\s+do|laga\\s+do))?$|gaana\\s+(?:badlo|skip)", I)
    private val mediaPrev = Regex("(?:pichhla|pichla|previous|prev|peeche\\s+wala)\\s*(?:gaana|gana|song|track)?(?:\\s+(?:karo|kar\\s+do|laga\\s+do))?$", I)
    private val mediaPause = Regex("^(?:jarvi\\s+)?(?:(?:gaana|gana|music|song|video)\\s+(?:roko|ruko|pause|ruka\\s+do|rok\\s+do|stop|band\\s+karo)|pause)(?:\\s+(?:karo|kar\\s+do))?$", I)
    private val mediaPlay = Regex("^(?:jarvi\\s+)?(?:(?:gaana|gana|music|song)\\s+(?:chalu|shuru|resume|play|chalao)|resume|play)(?:\\s+(?:karo|kar\\s+do))?$", I)

    // ---------- Ringer ----------
    private val ringerVibrate = Regex("(?<![a-z])vibrat(?:e|ion)(?:\\s+mode)?(?![a-z])", I)
    private val ringerSilent = Regex("silent\\s+mode|(?<![a-z])ringer\\s+(?:off|silent)", I)
    private val ringerNormal = Regex("normal\\s+mode|general\\s+mode|sound\\s+mode\\s+(?:on|chalu)|ringer\\s+(?:on|chalu)", I)

    // ---------- Clipboard ----------
    private val clipRead = Regex("clipboard\\s+(?:mein\\s+)?(?:kya|padho|dikhao|batao)|copy\\s+kiya\\s+hua\\s+(?:padho|dikhao|batao)|kya\\s+copy\\s+kiya", I)
    private val clipWrite = Regex("(?:clipboard\\s*(?:mein)?\\s*)?copy\\s*karo\\s*[:：]\\s*(.+)$", I)

    // ---------- Misc ----------
    private val showAlarms = Regex("(?:mere\\s+)?alarms?\\s+(?:dikhao|dikha\\s+do|list|batao)|alarm\\s+list", I)
    private val showTimers = Regex("(?:mere\\s+)?timers?\\s+(?:dikhao|dikha\\s+do|list)", I)
    private val batteryOpt = Regex("battery\\s+optimi[sz]ation", I)
    private val coin = Regex("sikka\\s+(?:uchhalo|uchalo|phenko)|coin\\s+flip|toss\\s+(?:karo|kar\\s+do)|heads\\s+ya\\s+tails|flip\\s+a\\s+coin", I)
    private val dice = Regex("dice\\s+(?:phenko|roll|fenko)|pasa\\s+(?:phenko|fenko)|roll\\s+a\\s+dice|ludo\\s+ka\\s+pasa", I)
    private val help = Regex("^(?:jarvi\\s+)?(?:help|madad|commands?\\s+batao|features?\\s+batao)$|kya\\s+kya\\s+kar\\s+sakti|tum\\s+kya\\s+kya\\s+kar|what\\s+can\\s+you\\s+do", I)

    fun parse(text: String): LocalCmd? {
        val raw = text.trim()
        if (raw.isEmpty() || raw.length > 200) return null
        val t = norm(raw)

        val w = clipWrite.find(raw)
        if (w != null) {
            val c = w.groupValues[1].trim()
            if (c.isNotEmpty() && c.length <= 500) return LocalCmd.ClipWrite(c)
        }
        if (clipRead.containsMatchIn(t)) return LocalCmd.ClipRead
        if (help.containsMatchIn(t)) return LocalCmd.Help

        val wantTime = askTime.containsMatchIn(t)
        val wantDate = askDate.containsMatchIn(t)
        if (wantTime || wantDate) return LocalCmd.TimeDate(wantTime || !wantDate, wantDate)

        val lower = raw.lowercase()
        if (lower.length <= 90 && (calcCue.containsMatchIn(lower) || Regex("^[0-9+\\-*/().% x×÷=?]+$").matches(lower))) {
            val e = toExpression(lower)
            if (e != null) return LocalCmd.Calc(e)
        }

        if (nowPlaying.containsMatchIn(t)) return LocalCmd.NowPlaying
        if (t.split(" ").size <= 5) {
            if (mediaNext.containsMatchIn(t)) return LocalCmd.MediaNext
            if (mediaPrev.containsMatchIn(t)) return LocalCmd.MediaPrev
            if (mediaPause.containsMatchIn(t)) return LocalCmd.MediaPause
            if (mediaPlay.containsMatchIn(t)) return LocalCmd.MediaPlay
        }

        if (ringerVibrate.containsMatchIn(t)) return LocalCmd.Ringer("vibrate")
        if (ringerSilent.containsMatchIn(t)) return LocalCmd.Ringer("silent")
        if (ringerNormal.containsMatchIn(t)) return LocalCmd.Ringer("normal")

        if (showTimers.containsMatchIn(t)) return LocalCmd.ShowTimers
        if (showAlarms.containsMatchIn(t)) return LocalCmd.ShowAlarms
        if (batteryOpt.containsMatchIn(t)) return LocalCmd.BatteryOpt
        if (coin.containsMatchIn(t)) return LocalCmd.Coin
        if (dice.containsMatchIn(t)) return LocalCmd.Dice
        return null
    }

    /** Is command ka jawab AI ki yaaddasht mein nahi jana chahiye (clipboard mein nijji cheez ho sakti hai) */
    fun isPrivate(text: String): Boolean {
        val c = parse(text)
        return c is LocalCmd.ClipRead || c is LocalCmd.ClipWrite
    }

    private val days = arrayOf("Ravivaar", "Somvaar", "Mangalvaar", "Budhvaar", "Guruvaar", "Shukravaar", "Shanivaar")
    private val months = arrayOf(
        "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"
    )

    fun timeDateReply(nowMs: Long, tz: TimeZone, time: Boolean, date: Boolean): String {
        val c = Calendar.getInstance(tz)
        c.timeInMillis = nowMs
        var h = c.get(Calendar.HOUR)
        if (h == 0) h = 12
        val ap = if (c.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
        val tm = String.format(Locale.US, "%d:%02d %s", h, c.get(Calendar.MINUTE), ap)
        val dt = days[c.get(Calendar.DAY_OF_WEEK) - 1] + ", " + c.get(Calendar.DAY_OF_MONTH) + " " +
            months[c.get(Calendar.MONTH)] + " " + c.get(Calendar.YEAR)
        return when {
            time && date -> "Abhi $tm hai ⏰ ($dt)"
            time -> "Abhi $tm hai ⏰"
            else -> "Aaj $dt hai 📅"
        }
    }

    const val HELP_TEXT =
        "Main bina AI ke ye sab kar sakti hun:\n" +
            "⏰ 'time kya hai', 'aaj ki date'\n" +
            "🧮 '25 ka 18 percent kitna hota hai', '12 guna 8'\n" +
            "🎵 'gaana roko', 'agla gaana', 'kaun sa gaana chal raha hai'\n" +
            "🔔 'vibrate mode', 'normal mode'\n" +
            "📋 'clipboard mein kya hai', 'copy karo: ...'\n" +
            "📝 'todo mein dudh lana jodo', '5 baje yaad dilana ...'\n" +
            "🚗 'driving mode chalu karo', 'notification padho'\n" +
            "🌅 'daily brief chalu karo', '💬 auto reply chalu karo'\n" +
            "🎭 'avatar ninja lagao', 'sikka uchhalo', 'dice phenko'\n" +
            "Baaki sab ke liye mujhse normal baat karo 🙂"
}
