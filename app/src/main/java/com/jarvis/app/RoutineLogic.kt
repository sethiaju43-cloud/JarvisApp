package com.jarvis.app

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/*
 * Routines: "roz raat 10:30 par phone silent". Ek baar banane ke baad ye BINA kisi API/AI call ke apne aap chalte hain.
 * Pure code (Android ke bina), unit tested. Original design.
 * Sirf surakshit kaam allowed: ringer mode, volume, driving mode, aur ek notification. Kuch bhejna, call karna ya
 * app kholna routine mein nahi ho sakta.
 */

class RoutineStep(val kind: String, val arg: String)

/** days: bit0 = Somvar ... bit6 = Ravivar */
class Routine(val id: Int, val hour: Int, val minute: Int, val days: Int, val steps: List<RoutineStep>)

object RoutineLogic {

    const val EVERY_DAY = 127
    const val WEEKDAYS = 31
    const val WEEKENDS = 96
    const val MAX_ROUTINES = 10
    const val MAX_STEPS = 4

    fun daysFromText(s: String): Int = when (s.trim().lowercase()) {
        "weekdays" -> WEEKDAYS
        "weekends" -> WEEKENDS
        else -> EVERY_DAY
    }

    fun dayLabel(days: Int): String = when (days and EVERY_DAY) {
        EVERY_DAY, 0 -> "roz"
        WEEKDAYS -> "Somvar se Shukravar"
        WEEKENDS -> "Shanivar-Ravivar"
        else -> "kuch dinon"
    }

    fun stepError(kind: String, arg: String): String? = when (kind) {
        "ringer" -> if (arg == "normal" || arg == "vibrate" || arg == "silent") null else "ringer mode galat"
        "volume" -> {
            val v = arg.toIntOrNull()
            if (v != null && v in 0..100) null else "volume 0 se 100 ke beech hona chahiye"
        }
        "driving" -> if (arg == "on" || arg == "off") null else "driving mode on ya off hona chahiye"
        "notify" -> if (arg.isNotBlank() && arg.length <= 120) null else "message 1 se 120 akshar ka hona chahiye"
        else -> "ye kaam routine mein allowed nahi"
    }

    /** Tool ke alag-alag fields se steps banao. Galat/khali fields chhod di jati hain. */
    fun stepsFrom(ringer: String, volume: Int?, driving: String, notify: String): List<RoutineStep> {
        val out = ArrayList<RoutineStep>()
        if (ringer.isNotBlank()) out.add(RoutineStep("ringer", ringer.trim().lowercase()))
        if (volume != null) out.add(RoutineStep("volume", volume.toString()))
        if (driving.isNotBlank()) out.add(RoutineStep("driving", driving.trim().lowercase()))
        if (notify.isNotBlank()) out.add(RoutineStep("notify", ToolRegistry.oneLine(notify)))
        val ok = ArrayList<RoutineStep>()
        for (s in out) if (stepError(s.kind, s.arg) == null) ok.add(s)
        return ok.take(MAX_STEPS)
    }

    private fun allowed(c: Calendar, mask: Int): Boolean {
        val dow = c.get(Calendar.DAY_OF_WEEK)
        val bit = if (dow == Calendar.SUNDAY) 6 else dow - 2
        return (mask and (1 shl bit)) != 0
    }

    /** Agla samay jab routine chalni chahiye (abhi ke baad ka, sirf chune hue dinon mein) */
    fun nextTrigger(hour: Int, minute: Int, days: Int, nowMs: Long, tz: TimeZone): Long {
        val mask = if ((days and EVERY_DAY) == 0) EVERY_DAY else (days and EVERY_DAY)
        val c = GregorianCalendar(tz)
        c.timeInMillis = nowMs
        c.set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
        c.set(Calendar.MINUTE, minute.coerceIn(0, 59))
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        for (i in 0..8) {
            if (c.timeInMillis > nowMs && allowed(c, mask)) return c.timeInMillis
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return c.timeInMillis
    }

    fun stepText(s: RoutineStep): String = when (s.kind) {
        "ringer" -> "ringer " + s.arg
        "volume" -> "volume " + s.arg + "%"
        "driving" -> "driving mode " + s.arg
        "notify" -> "message: " + s.arg
        else -> s.kind
    }

    fun time(r: Routine): String = r.hour.toString().padStart(2, '0') + ":" + r.minute.toString().padStart(2, '0')

    fun describe(r: Routine): String =
        r.id.toString() + ") " + dayLabel(r.days) + " " + time(r) + " -> " + r.steps.joinToString(", ") { stepText(it) }

    fun toJson(items: List<Routine>): String {
        val arr = JSONArray()
        for (r in items) {
            val o = JSONObject()
            o.put("id", r.id)
            o.put("h", r.hour)
            o.put("m", r.minute)
            o.put("d", r.days)
            val s = JSONArray()
            for (st in r.steps) s.put(JSONObject().put("k", st.kind).put("a", st.arg))
            o.put("s", s)
            arr.put(o)
        }
        return arr.toString()
    }

    /** Kharab ya adhura data mile to jo sahi hai wahi rakho, baaki chhod do (crash nahi) */
    fun fromJson(raw: String): List<Routine> {
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<Routine>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val h = o.optInt("h", -1)
                val m = o.optInt("m", -1)
                val id = o.optInt("id", -1)
                if (id < 0 || h !in 0..23 || m !in 0..59) continue
                val steps = ArrayList<RoutineStep>()
                val sa = o.optJSONArray("s")
                if (sa != null) {
                    for (j in 0 until sa.length()) {
                        val so = sa.optJSONObject(j) ?: continue
                        val k = so.optString("k", "")
                        val a = so.optString("a", "")
                        if (stepError(k, a) == null) steps.add(RoutineStep(k, a))
                    }
                }
                if (steps.isEmpty()) continue
                out.add(Routine(id, h, m, o.optInt("d", EVERY_DAY), steps.take(MAX_STEPS)))
            }
        } catch (e: JSONException) {
            return emptyList()
        }
        return out.take(MAX_ROUTINES)
    }
}
