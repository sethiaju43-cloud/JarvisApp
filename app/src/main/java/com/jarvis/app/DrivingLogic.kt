package com.jarvis.app

/*
 * Driving mode ke seedhe commands aur bolne wale text ka hisaab. Is file mein Android ka koi code nahi hai,
 * isliye ye JVM unit tests mein seedha test hota hai.
 */

enum class DrivingCommand { ON, OFF, STATUS }

object DrivingLogic {

    private val I = RegexOption.IGNORE_CASE

    /** AI ko diya jane wala hint: gaadi mein ho to bahut chhote jawab */
    const val PROMPT_LINE =
        "[Driving mode chalu hai: user gaadi chala raha hai. Jawab bahut chhota (1 ya 2 vaakya), seedha aur bolne layak rakho. " +
            "Lambi list, steps ya screen par dekhne wali baat mat do. Dhyan bhatkane wale sawaal mat poochho; " +
            "zaroori ho tabhi ek chhota sawaal. Phone ko haath se chalane ko mat kaho.] "

    private val statusAsk = Regex("(driving|drive)\\s*mode.*(status|(?<![a-z])(on|chalu|band|off)\\s+hai)", I)
    private val offWords = Regex("(driving|drive)\\s*mode.*(?<![a-z])(off|band|stop|hata|disable|khatam)(?![a-z])|(drive|driving)\\s+(khatam|ho\\s+gaya|ho\\s+gayi)", I)
    private val onWords = Regex(
        "(driving|drive)\\s*mode.*(?<![a-z])(on|chalu|start|shuru|laga|enable|activate)(?![a-z])|" +
            "(gaadi|gadi|car|bike)\\s+(chala|chal)\\s*(raha|rahi)|(drive|driving)\\s+kar\\s*(raha|rahi)|(?<![a-z])i'?m\\s+driving", I
    )

    fun parse(text: String): DrivingCommand? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 120) return null
        if (statusAsk.containsMatchIn(t)) return DrivingCommand.STATUS
        if (offWords.containsMatchIn(t)) return DrivingCommand.OFF
        if (onWords.containsMatchIn(t)) return DrivingCommand.ON
        return null
    }

    /** Naye message ko bolne layak line banao. Code/OTP wala ya khali ho to null (chup raho). */
    fun speakLine(app: String, title: String, text: String): String? {
        val tx = text.trim()
        if (tx.isEmpty() || tx == NotificationLogic.SECRET_TEXT) return null
        if (NotificationLogic.looksSecret(title, tx)) return null
        val who = title.trim()
        val body = if (tx.length <= 120) tx else tx.take(120).trimEnd() + "..."
        return if (who.isEmpty()) app + " par naya message: " + body else app + " par " + who + " ka message: " + body
    }
}
