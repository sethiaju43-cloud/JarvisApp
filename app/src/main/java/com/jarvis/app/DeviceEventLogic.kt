package com.jarvis.app

/*
 * Phone ke events (charger, battery, headphone, airplane, storage...) ki khud-b-khud ghoshna. Bina kisi AI/API call ke.
 * Pure code (Android ke bina), unit tested. Original design; ek jaisi ghoshna baar-baar na ho iske liye niyam:
 *  - Flapping: ek hi event 3 second ke andar dobara aaye (kharab charger/cable) to chup raho.
 *  - Repeat: ek hi line 60 second ke andar dobara mat bolo.
 *  - Battery low / full: ek charge-cycle mein sirf ek baar.
 *  - Charger ki line thodi der rok kar bolo; itni der mein ulta event aa jaye to dono chhod do.
 */

enum class EventKind(val group: String) {
    CHARGER_ON("charging"), CHARGER_OFF("charging"),
    BATTERY_LOW("battery"), BATTERY_FULL("battery"),
    BT_ON("bluetooth"), BT_OFF("bluetooth"),
    HEADSET_ON("headset"), HEADSET_OFF("headset"),
    AIRPLANE_ON("airplane"), AIRPLANE_OFF("airplane"),
    SAVER_ON("power_saver"), SAVER_OFF("power_saver"),
    RINGER("ringer"),
    APP_INSTALLED("apps"), APP_REMOVED("apps"),
    STORAGE_LOW("storage"), STORAGE_OK("storage")
}

class DeviceEvent(val kind: EventKind, val detail: String = "", val pct: Int = -1)

sealed class GateDecision {
    class Say(val text: String) : GateDecision()
    class Skip(val reason: String) : GateDecision()
}

class EventSettings(val master: Boolean, val groups: Set<String>, val speak: Boolean, val lowPct: Int)

object DeviceEventLogic {

    val GROUPS: List<String> = listOf("charging", "battery", "bluetooth", "headset", "airplane", "power_saver", "ringer", "apps", "storage")
    val DEFAULT_GROUPS: Set<String> = setOf("charging", "battery", "bluetooth", "headset", "storage")
    const val DEFAULT_LOW = 20

    /** User ke shabd se group ka naam. "all" = sab. Samajh na aaye to null. */
    fun groupFromText(s: String): String? {
        val t = s.trim().lowercase().replace('-', '_').replace(' ', '_')
        if (t in GROUPS) return t
        return when (t) {
            "all", "sab", "everything" -> "all"
            "charger", "charge", "plug" -> "charging"
            "battery_low", "low_battery", "full" -> "battery"
            "earphone", "earphones", "headphone", "headphones" -> "headset"
            "flight", "flight_mode", "airplane_mode" -> "airplane"
            "battery_saver", "saver" -> "power_saver"
            "silent", "volume" -> "ringer"
            "app", "install", "uninstall" -> "apps"
            "space", "memory" -> "storage"
            "bt" -> "bluetooth"
            else -> null
        }
    }

    fun clampLow(p: Int): Int = p.coerceIn(5, 50)

    fun encodeGroups(g: Set<String>): String = GROUPS.filter { it in g }.joinToString(",")

    fun decodeGroups(raw: String): Set<String> = raw.split(",").map { it.trim() }.filter { it in GROUPS }.toSet()

    fun isOn(s: EventSettings, kind: EventKind): Boolean = s.master && kind.group in s.groups

    /** group = "all" ya koi ek group. on=false par "all" poora band karta hai, ek group sirf wo hatata hai. */
    fun apply(cur: EventSettings, group: String, on: Boolean): EventSettings {
        if (group == "all") {
            return if (on) EventSettings(true, GROUPS.toSet(), cur.speak, cur.lowPct)
            else EventSettings(false, cur.groups, cur.speak, cur.lowPct)
        }
        val g = LinkedHashSet<String>(cur.groups)
        if (on) g.add(group) else g.remove(group)
        val master = g.isNotEmpty() && (on || cur.master)
        return EventSettings(master, g, cur.speak, cur.lowPct)
    }

    fun describe(s: EventSettings): String {
        if (!s.master || s.groups.isEmpty()) return "Phone events ki ghoshna abhi band hai."
        val names = GROUPS.filter { it in s.groups }.joinToString(", ")
        return "Phone events ki ghoshna chalu hai: " + names + ". Bolkar sunana: " + (if (s.speak) "haan" else "nahi") +
            ". Low battery: " + s.lowPct + "% par."
    }

    private fun clean(d: String): String = ToolRegistry.oneLine(d).take(40)

    private fun pctText(p: Int): String = if (p in 0..100) " (battery " + p + "%)" else ""

    fun line(ev: DeviceEvent): String {
        val d = clean(ev.detail)
        return when (ev.kind) {
            EventKind.CHARGER_ON -> "Charger laga diya 🔌" + pctText(ev.pct)
            EventKind.CHARGER_OFF -> "Charger nikal diya" + pctText(ev.pct)
            EventKind.BATTERY_LOW -> "Battery sirf " + ev.pct + "% bachi hai 🔋 Charger laga lo"
            EventKind.BATTERY_FULL -> "Battery full ho gayi ✅ Charger nikal sakte ho"
            EventKind.BT_ON -> if (d.isEmpty()) "Bluetooth device juda" else "Bluetooth se juda: " + d
            EventKind.BT_OFF -> if (d.isEmpty()) "Bluetooth device hata" else "Bluetooth se hata: " + d
            EventKind.HEADSET_ON -> "Headphone laga 🎧"
            EventKind.HEADSET_OFF -> "Headphone nikal gaya"
            EventKind.AIRPLANE_ON -> "Airplane mode chalu ✈️"
            EventKind.AIRPLANE_OFF -> "Airplane mode band"
            EventKind.SAVER_ON -> "Battery saver chalu"
            EventKind.SAVER_OFF -> "Battery saver band"
            EventKind.RINGER -> "Ringer mode: " + d
            EventKind.APP_INSTALLED -> "Naya app install hua: " + d
            EventKind.APP_REMOVED -> "App hata diya gaya: " + d
            EventKind.STORAGE_LOW -> "Phone ki storage kam hai 📦 Kuch purani cheezein hata do"
            EventKind.STORAGE_OK -> "Storage ab theek hai"
        }
    }

    /** Bolne ke liye: emoji aur ajeeb symbols hata do */
    fun spoken(text: String): String {
        val sb = StringBuilder()
        for (ch in text) if (ch.isLetterOrDigit() || ch.isWhitespace() || ch == '.' || ch == ',' || ch == '%' || ch == ':') sb.append(ch)
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    /** Battery ki halat se event: charging mein 100% / full, ya bina charging ke low% se neeche */
    fun fromBattery(pct: Int, charging: Boolean, full: Boolean, lowPct: Int): DeviceEvent? {
        if (pct < 0) return null
        if (full || (charging && pct >= 100)) return DeviceEvent(EventKind.BATTERY_FULL, "", pct)
        if (!charging && pct <= lowPct) return DeviceEvent(EventKind.BATTERY_LOW, "", pct)
        return null
    }
}

class EventGate(
    private val flapMs: Long = 3000L,
    private val repeatMs: Long = 60_000L,
    private val chargerDelayMs: Long = 1500L
) {
    private val lastByKind = HashMap<EventKind, Long>()
    private val lastByText = HashMap<String, Long>()
    private var fullSaid = false
    private var lowSaid = false
    private var pendingCharger: Boolean? = null
    private var pendingAt = 0L

    /** Har battery update par bulao: level wapas theek ho gaya to agli baar phir low bol sakte hain */
    @Synchronized
    fun noteLevel(pct: Int, lowPct: Int) {
        if (pct > lowPct + 5) lowSaid = false
    }

    @Synchronized
    fun decide(ev: DeviceEvent, now: Long): GateDecision {
        // Naya charge-cycle shuru/khatam hone par low/full ke flag saaf karo
        if (ev.kind == EventKind.CHARGER_ON) lowSaid = false
        if (ev.kind == EventKind.CHARGER_OFF) fullSaid = false

        val prev = lastByKind[ev.kind]
        lastByKind[ev.kind] = now
        if (prev != null && now - prev < flapMs) return GateDecision.Skip("flapping (" + (now - prev) + " ms ago)")

        if (ev.kind == EventKind.BATTERY_FULL && fullSaid) return GateDecision.Skip("already said full")
        if (ev.kind == EventKind.BATTERY_LOW && lowSaid) return GateDecision.Skip("already warned this cycle")

        val text = DeviceEventLogic.line(ev)
        val key = ev.kind.name + "|" + text
        val seen = lastByText[key]
        if (seen != null && now - seen < repeatMs) return GateDecision.Skip("repeat (" + (now - seen) + " ms ago)")

        lastByText[key] = now
        if (ev.kind == EventKind.BATTERY_FULL) fullSaid = true
        if (ev.kind == EventKind.BATTERY_LOW) lowSaid = true
        return GateDecision.Say(text)
    }

    /** Charger laga/nikla: turant mat bolo, pehle thodi der ruko. Ulta event aa jaye to dono bekaar. */
    @Synchronized
    fun submitCharger(connected: Boolean, now: Long) {
        val p = pendingCharger
        if (p != null && p != connected) {
            pendingCharger = null
            return
        }
        pendingCharger = connected
        pendingAt = now
    }

    /** Intezaar poora ho chuka ho to charger ki line (ya Skip) do, nahi to null */
    @Synchronized
    fun flushCharger(now: Long, pct: Int): GateDecision? {
        val p = pendingCharger ?: return null
        if (now - pendingAt < chargerDelayMs) return null
        pendingCharger = null
        return decide(DeviceEvent(if (p) EventKind.CHARGER_ON else EventKind.CHARGER_OFF, "", pct), now)
    }
}
