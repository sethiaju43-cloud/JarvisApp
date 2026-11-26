package com.jarvis.app

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/*
 * Tools ke chhote pure helpers (Android ke bina, unit tested).
 */

object ToolTime {
    /** "YYYY-MM-DD HH:MM" (ya T se) ko millis mein badlo. Galat date (jaise 30 Feb) par null. */
    fun parse(text: String, tz: TimeZone): Long? {
        val t = text.trim().replace('T', ' ')
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        f.isLenient = false
        f.timeZone = tz
        val pos = ParsePosition(0)
        val d = f.parse(t, pos)
        if (d == null || pos.index != t.length) return null
        return d.time
    }
}

/** Kaun sa model tools nahi samajhta ye yaad rakhna (24 ghante), taaki baar-baar bekaar request na jaye */
object ToolCapability {
    const val TTL_MS = 24L * 60L * 60L * 1000L

    fun isToolRejection(kind: ErrKind, msg: String): Boolean {
        if (kind != ErrKind.BAD_REQUEST) return false
        val m = msg.lowercase()
        return m.contains("tool") || m.contains("function")
    }

    fun isMarked(stored: String, now: Long): Boolean {
        val ts = stored.trim().toLongOrNull() ?: return false
        return now - ts in 0 until TTL_MS
    }
}
