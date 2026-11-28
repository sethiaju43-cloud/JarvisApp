package com.jarvis.app

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/*
 * "Seekha hua kaam": jo kaam ek baar model (API) ki madad se ho gaya, wo yaad rakho, taaki wahi kaam dobara bolne par
 * bina kisi API call ke seedha chal jaye. Pure code (Android ke bina), unit tested. Original design.
 *
 * Kaise: kaam poora hone par uske tool-steps ko command ke saath save karte hain. Command mein jo shabd steps ke andar bhi
 * aate hain (jaise contact ka naam) wo "slot" ban jate hain, taaki "Rahul ki chat kholo" seekhne ke baad "Amit ki chat kholo" bhi chal jaye.
 * Surakshit niyam: sirf allowlist wale tools, kaam mein koi step fail na hua ho, password/OTP/bank jaise shabd na hon,
 * aur call/SMS/WhatsApp/Send ho to har baar user se poochha jata hai.
 */

class MacroStep(val tool: String, val args: JSONObject)

class Macro(
    val id: Int,
    val pattern: List<String>, // command ke shabd; slot "{1}", "{2}" ... hote hain
    val examples: List<String>, // slot ki asli (seekhte waqt ki) values
    val steps: List<MacroStep>,
    val uses: Int,
    val fails: Int, // lagaataar fail
    val lastUsed: Long
)

class Tok(val norm: String, val raw: String)

class MacroMatch(val macro: Macro, val values: List<String>)

object TaskLogic {

    const val MAX_MACROS = 30
    const val MAX_STEPS = 12
    const val MAX_SLOTS = 3
    const val MAX_FAILS = 2

    val FILLERS: Set<String> = setOf("please", "plz", "pls", "jarvi", "jarvis", "zara", "bhai", "yaar", "ji", "na")

    /** Ye shabd kabhi slot nahi bante (command ka dhaancha hain, naam/cheez nahi) */
    private val STOP: Set<String> = setOf(
        "kholo", "karo", "kar", "do", "dena", "aur", "phir", "fir", "ko", "ki", "ka", "ke", "hai", "me", "mein", "par", "pe", "se",
        "the", "and", "then", "open", "play", "send", "bhejo", "chalu", "band", "lagao", "laga", "dikhao", "batao", "wala", "wali",
        "jao", "ja", "pe", "on", "off", "for", "with", "start", "stop"
    )

    val REPLAYABLE: Set<String> = setOf(
        "open_app", "go_home", "go_back", "open_recents", "scroll_screen", "tap_text", "type_text", "take_screenshot", "set_torch",
        "adjust_volume", "set_volume", "media_control", "play_music", "set_ringer_mode", "swipe_screen", "wait", "wait_for_screen_text",
        "scroll_to", "open_settings", "navigate", "open_url", "take_photo", "lock_device", "show_notifications", "open_dialer",
        "copy_to_clipboard"
    )

    /** Sirf aakhri step ho sakte hain (har baar user se poochha jata hai) */
    val CONFIRM_LAST: Set<String> = setOf("make_call", "send_sms", "whatsapp_send_message")

    /** Jaankari wale tools: recording mein shamil nahi hote (chalne par kuch badalte nahi) */
    val INFO_TOOLS: Set<String> = setOf("read_screen_text", "read_screen_elements", "get_lock_state", "get_battery_status", "find_tool")

    private val SENSITIVE_WORDS = listOf(
        "password", "passcode", "pin", "otp", "cvv", "upi", "bank", "atm", "card", "paisa", "paise", "payment", "pay", "login", "passwd", "पासवर्ड"
    )

    // ---------- shabd ----------

    fun tokenize(text: String): List<Tok> {
        val out = ArrayList<Tok>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) {
                val raw = sb.toString()
                val norm = raw.lowercase()
                if (norm !in FILLERS) out.add(Tok(norm, raw))
                sb.setLength(0)
            }
        }
        for (ch in text) {
            if (Character.isLetterOrDigit(ch)) sb.append(ch) else flush()
        }
        flush()
        return out
    }

    fun normalize(text: String): String = tokenize(text).joinToString(" ") { it.norm }

    fun sensitiveCommand(command: String): Boolean {
        val toks = tokenize(command).map { it.norm }
        return SENSITIVE_WORDS.any { it in toks }
    }

    private val longDigits = Regex("[0-9]{6,}")

    private fun stringArgs(a: JSONObject): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val it = a.keys()
        while (it.hasNext()) {
            val k = it.next()
            val v = a.opt(k)
            if (v is String) out.add(Pair(k, v))
        }
        return out
    }

    // ---------- seekhne layak? ----------

    /** Recording ke layak steps (jaankari wale hata kar, dohraye hue hata kar). Layak nahi to null. */
    fun learnableSteps(calls: List<ToolCall>): List<MacroStep>? {
        val kept = ArrayList<ToolCall>()
        var info = false
        for (c in calls) {
            if (c.name in INFO_TOOLS) {
                info = true
                continue
            }
            kept.add(c)
        }
        if (kept.size < 2 || kept.size > MAX_STEPS) return null
        for ((i, c) in kept.withIndex()) {
            val last = i == kept.size - 1
            val ok = c.name in REPLAYABLE || (last && c.name in CONFIRM_LAST)
            if (!ok) return null
        }
        // Screen padhkar jo likha gaya wo har baar badal sakta hai: aise typing wale kaam mat seekho
        if (info && kept.any { it.name == "type_text" }) return null
        // type_text mein lambe number (password/card jaisa) mat rakho
        for (c in kept) {
            if (c.name == "type_text" && stringArgs(c.args).any { longDigits.containsMatchIn(it.second) }) return null
        }
        val out = ArrayList<MacroStep>()
        for (c in kept) {
            val prev = out.lastOrNull()
            if (prev != null && prev.tool == c.name && prev.args.toString() == c.args.toString()) continue // dohraya hua step
            out.add(MacroStep(c.name, JSONObject(c.args.toString())))
        }
        if (out.size < 2) return null
        return out
    }

    // ---------- banana ----------

    private fun wordRegex(word: String): Regex =
        Regex("(?i)(?<![\\p{L}\\p{N}])" + Regex.escape(word) + "(?![\\p{L}\\p{N}])")

    /** Command + chale hue steps se Macro banao. Seekhne layak na ho to null. */
    fun build(id: Int, command: String, calls: List<ToolCall>, now: Long): Macro? {
        if (sensitiveCommand(command)) return null
        val toks = tokenize(command)
        if (toks.size < 3) return null
        val steps = learnableSteps(calls) ?: return null

        // slot: command ka wo shabd jo kisi step ke text mein poora shabd ban kar aata hai
        val slotToks = ArrayList<Tok>()
        for (t in toks) {
            if (slotToks.size >= MAX_SLOTS) break
            if (t.norm.length < 3 || t.norm in STOP || t.norm.all { it.isDigit() }) continue
            if (slotToks.any { it.norm == t.norm }) continue
            val rx = wordRegex(t.raw)
            val appears = steps.any { s -> stringArgs(s.args).any { rx.containsMatchIn(it.second) } }
            if (appears) slotToks.add(t)
        }
        // kam se kam 2 pakke (slot nahi) shabd chahiye, warna ye har cheez se mil jayega
        val fixed = toks.count { t -> slotToks.none { it.norm == t.norm } }
        if (fixed < 2) return null

        val pattern = ArrayList<String>()
        for (t in toks) {
            val idx = slotToks.indexOfFirst { it.norm == t.norm }
            pattern.add(if (idx >= 0) "{" + (idx + 1) + "}" else t.norm)
        }
        val newSteps = ArrayList<MacroStep>()
        for (s in steps) {
            val a = JSONObject(s.args.toString())
            for ((k, v) in stringArgs(s.args)) {
                var nv = v
                for ((idx, st) in slotToks.withIndex()) nv = wordRegex(st.raw).replace(nv, "{" + (idx + 1) + "}")
                if (nv != v) a.put(k, nv)
            }
            newSteps.add(MacroStep(s.tool, a))
        }
        return Macro(id, pattern, slotToks.map { it.raw }, newSteps, 0, 0, now)
    }

    // ---------- milana ----------

    private fun isSlot(p: String): Boolean = p.length >= 3 && p[0] == '{' && p[p.length - 1] == '}'

    private fun slotIndex(p: String): Int = p.substring(1, p.length - 1).toIntOrNull() ?: 0

    /** Naya command kisi seekhe hue kaam se milta hai? Shabd-ke-shabd, bas slot wale shabd alag ho sakte hain. */
    fun match(macros: List<Macro>, command: String): MacroMatch? {
        val toks = tokenize(command)
        if (toks.isEmpty()) return null
        var best: MacroMatch? = null
        var bestFixed = -1
        for (m in macros) {
            if (m.pattern.size != toks.size) continue
            val values = arrayOfNulls<String>(MAX_SLOTS)
            var ok = true
            var fixed = 0
            for (i in toks.indices) {
                val p = m.pattern[i]
                if (isSlot(p)) {
                    val k = slotIndex(p)
                    if (k < 1 || k > MAX_SLOTS) {
                        ok = false
                        break
                    }
                    val prev = values[k - 1]
                    if (prev != null && prev.lowercase() != toks[i].norm) {
                        ok = false // ek hi slot do jagah alag value nahi le sakta
                        break
                    }
                    values[k - 1] = toks[i].raw
                } else {
                    if (p != toks[i].norm) {
                        ok = false
                        break
                    }
                    fixed++
                }
            }
            if (!ok) continue
            if (best == null || fixed > bestFixed || (fixed == bestFixed && m.uses > best.macro.uses)) {
                best = MacroMatch(m, values.map { it ?: "" })
                bestFixed = fixed
            }
        }
        return best
    }

    /** Slot ki values bhar kar chalne layak tool calls banao */
    fun instantiate(m: Macro, values: List<String>): List<ToolCall> {
        val out = ArrayList<ToolCall>()
        for ((i, s) in m.steps.withIndex()) {
            val a = JSONObject(s.args.toString())
            for ((k, v) in stringArgs(s.args)) {
                var nv = v
                for (idx in values.indices) nv = nv.replace("{" + (idx + 1) + "}", values[idx])
                if (nv != v) a.put(k, nv)
            }
            out.add(ToolCall("m" + i, s.tool, a))
        }
        return out
    }

    // ---------- natija ----------

    private fun firstLine(s: String): String = s.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""

    /** Step ka nateeja fail hai? Sirf pehli line dekhte hain (neeche screen ka text hota hai, usme galti se emoji ho sakta hai) */
    fun isFailure(text: String): Boolean {
        val l = firstLine(text)
        return l.contains("😕") || l.contains("Screen badal gayi") || l.contains("Accessibility band") ||
            l.contains("private app hai") || l.contains("not supported") || l.contains("Unknown action")
    }

    fun describe(m: Macro): String {
        val sb = StringBuilder()
        var slotNo = 0
        for (p in m.pattern) {
            if (sb.isNotEmpty()) sb.append(' ')
            if (isSlot(p)) {
                val k = slotIndex(p)
                val ex = m.examples.getOrNull(k - 1) ?: "?"
                sb.append("<").append(ex).append(">")
                slotNo++
            } else {
                sb.append(p)
            }
        }
        sb.append(" (").append(m.steps.size).append(" step")
        if (slotNo > 0) sb.append(", badal sakte: ").append(m.examples.take(slotNo).joinToString(", "))
        sb.append(", ").append(m.uses).append(" baar chala)")
        return sb.toString()
    }

    // ---------- JSON ----------

    fun toJson(items: List<Macro>): String {
        val arr = JSONArray()
        for (m in items) {
            val o = JSONObject()
            o.put("id", m.id)
            o.put("p", JSONArray(m.pattern))
            o.put("e", JSONArray(m.examples))
            val ss = JSONArray()
            for (s in m.steps) ss.put(JSONObject().put("t", s.tool).put("a", s.args))
            o.put("s", ss)
            o.put("u", m.uses)
            o.put("f", m.fails)
            o.put("l", m.lastUsed)
            arr.put(o)
        }
        return arr.toString()
    }

    /** Kharab data mile to jo sahi hai wahi lo. Allowlist se bahar ke tools phir se jaanche jate hain. */
    fun fromJson(raw: String): List<Macro> {
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<Macro>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optInt("id", -1)
                val pa = o.optJSONArray("p") ?: continue
                val pattern = ArrayList<String>()
                for (j in 0 until pa.length()) pattern.add(pa.optString(j, ""))
                if (id < 0 || pattern.size < 3 || pattern.any { it.isBlank() }) continue
                val ex = ArrayList<String>()
                val ea = o.optJSONArray("e")
                if (ea != null) for (j in 0 until ea.length()) ex.add(ea.optString(j, ""))
                val steps = ArrayList<MacroStep>()
                val sa = o.optJSONArray("s") ?: continue
                var bad = false
                for (j in 0 until sa.length()) {
                    val so = sa.optJSONObject(j)
                    val t = so?.optString("t", "") ?: ""
                    val a = so?.optJSONObject("a")
                    val last = j == sa.length() - 1
                    if (so == null || a == null || !(t in REPLAYABLE || (last && t in CONFIRM_LAST))) {
                        bad = true
                        break
                    }
                    steps.add(MacroStep(t, a))
                }
                if (bad || steps.size < 2 || steps.size > MAX_STEPS) continue
                out.add(Macro(id, pattern, ex, steps, o.optInt("u", 0), o.optInt("f", 0), o.optLong("l", 0L)))
            }
        } catch (e: JSONException) {
            return emptyList()
        }
        return out.take(MAX_MACROS)
    }
}

/** Seekhe hue kaamon ki list ke saath badlav (pure): jodna, hatana, nateeja likhna */
class MacroBook(items: List<Macro>) {

    private val list = ArrayList<Macro>(items)

    fun all(): List<Macro> = ArrayList(list)

    fun nextId(): Int = (list.maxOfOrNull { it.id } ?: 0) + 1

    private fun samePattern(a: Macro, b: Macro): Boolean = a.pattern == b.pattern

    /** Jodna. Wahi command pehle se ho aur wo theek chal raha ho to naya nahi jodte. Bhar jaye to sabse purana hatao. */
    fun add(m: Macro): Boolean {
        val dup = list.firstOrNull { samePattern(it, m) }
        if (dup != null) {
            if (dup.fails == 0) return false
            list.remove(dup)
        }
        while (list.size >= TaskLogic.MAX_MACROS) {
            val oldest = list.minByOrNull { it.lastUsed } ?: break
            list.remove(oldest)
        }
        list.add(m)
        return true
    }

    /** Chalne ka nateeja: kamyab to uses badho aur fails 0; fail ho to fails badho, 2 lagaataar par hata do. */
    fun record(id: Int, ok: Boolean, now: Long): Boolean {
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val m = list[idx]
        if (ok) {
            list[idx] = Macro(m.id, m.pattern, m.examples, m.steps, m.uses + 1, 0, now)
            return true
        }
        val f = m.fails + 1
        if (f >= TaskLogic.MAX_FAILS) {
            list.removeAt(idx)
        } else {
            list[idx] = Macro(m.id, m.pattern, m.examples, m.steps, m.uses, f, m.lastUsed)
        }
        return true
    }

    /** number = list mein dikhne wala 1-based number */
    fun forget(number: Int): Macro? {
        if (number < 1 || number > list.size) return null
        return list.removeAt(number - 1)
    }

    fun clear() {
        list.clear()
    }
}

/** Seekhe hue kaam ko bina model ke chalana (pure: nakli runner se test hota hai) */
class MacroPlayer(
    private val runner: ToolRunner,
    private val confirmCalls: Boolean = true,
    private val stop: () -> Boolean = { false }
) {
    private fun firstLine(s: String): String = s.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(200) ?: ""

    fun run(calls: List<ToolCall>): AgentResult {
        val allowed = HashSet<String>()
        for (c in calls) allowed.add(c.name)
        val lines = ArrayList<String>()
        for ((i, c) in calls.withIndex()) {
            if (stop()) return AgentResult.Done("Ruk gayi 🛑", lines)
            val d = ToolPolicy.check(c, allowed)
            var toRun: ToolCall? = null
            if (d is ToolDecision.Reject) {
                return AgentResult.Failed("step " + (i + 1) + ": " + d.message)
            } else if (d is ToolDecision.Confirm) {
                if (confirmCalls) {
                    val line = ToolRegistry.legacyLine(d.call) ?: return AgentResult.Failed("step " + (i + 1) + ": not supported")
                    return AgentResult.NeedConfirm(d.question, line, lines)
                }
                toRun = d.call
            } else if (d is ToolDecision.Allow) {
                toRun = d.call
            }
            if (toRun == null) continue
            val out = runner.run(toRun)
            val cl = out.confirmLine
            if (cl != null) return AgentResult.NeedConfirm(out.confirmQuestion ?: "Pakka karun? (haan / nahi)", cl, lines)
            if (TaskLogic.isFailure(out.text)) return AgentResult.Failed("step " + (i + 1) + ": " + firstLine(out.text))
            lines.add(firstLine(out.text))
        }
        val last = lines.lastOrNull() ?: "ho gaya"
        return AgentResult.Done("Seekhe hue tareeke se kar diya ✅ (bina API) " + last, lines)
    }
}
