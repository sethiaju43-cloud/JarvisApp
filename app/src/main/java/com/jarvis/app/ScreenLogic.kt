package com.jarvis.app

/*
 * Screen par tap/scroll/wait ke pure niyam (Android ke bina, unit tested). Original design.
 * Idea: screen ke buttons/fields ko #number do (read_screen_elements), phir number se tap karo (tap_element):
 * naam se tap karne mein galat button dab sakta hai, number se nahi. Number purane snapshot ke hain, isliye tap se pehle
 * check hota hai ki screen badli to nahi.
 */

class RawElement(val label: String, val kind: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

class ScreenElement(val id: Int, val label: String, val kind: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val cx: Int get() = (left + right) / 2
    val cy: Int get() = (top + bottom) / 2
    val area: Long get() = (right - left).toLong() * (bottom - top).toLong()
}

object ScreenLogic {

    const val MAX_ELEMENTS = 40
    const val SNAPSHOT_MAX_AGE_MS = 60_000L
    private const val NEAR_PX = 80

    fun cleanLabel(s: String): String = ToolRegistry.oneLine(s).take(60)

    /** Kaccha list -> number wali list: khaali naam hatao, dohrae hue hatao, upar se neeche/baayen se daayen, 40 tak */
    fun assign(raw: List<RawElement>): List<ScreenElement> {
        val seen = HashSet<String>()
        val kept = ArrayList<RawElement>()
        for (r in raw) {
            val label = cleanLabel(r.label)
            if (label.isEmpty()) continue
            if (r.right <= r.left || r.bottom <= r.top) continue
            val key = label.lowercase() + "|" + r.left + "," + r.top + "," + r.right + "," + r.bottom
            if (!seen.add(key)) continue
            kept.add(RawElement(label, r.kind, r.left, r.top, r.right, r.bottom))
        }
        val sorted = kept.sortedWith(compareBy<RawElement> { it.top }.thenBy { it.left })
        val out = ArrayList<ScreenElement>()
        for ((i, r) in sorted.take(MAX_ELEMENTS).withIndex()) {
            out.add(ScreenElement(i + 1, r.label, r.kind, r.left, r.top, r.right, r.bottom))
        }
        return out
    }

    fun format(els: List<ScreenElement>, appLabel: String): String {
        val sb = StringBuilder()
        sb.append("App: ").append(if (appLabel.isBlank()) "?" else cleanLabel(appLabel)).append(". ")
        sb.append(els.size).append(" cheezein (tap karne ke liye tap_element(id) use karo):\n")
        for (e in els) {
            sb.append("#").append(e.id).append(" [").append(e.kind).append("] ").append(e.label)
                .append(" (").append(e.cx).append(",").append(e.cy).append(")\n")
        }
        return sb.toString().trim()
    }

    private fun norm(s: String): String = s.lowercase().replace(Regex("\\s+"), " ").trim()

    /** Kya screen ke text mein ye shabd dikh raha hai? (chhota-bada aur extra space ignore) */
    fun textVisible(screen: String, needle: String): Boolean {
        val n = norm(needle)
        if (n.isEmpty()) return false
        return norm(screen).contains(n)
    }

    /** Purane element jaisa ek element naye snapshot mein hai? Wahi naam aur lagbhag wahi jagah. Nahi to null (screen badal gayi). */
    fun sameElement(old: ScreenElement, fresh: List<ScreenElement>): ScreenElement? {
        val name = old.label.lowercase()
        var best: ScreenElement? = null
        var bestDist = Int.MAX_VALUE
        for (f in fresh) {
            if (f.label.lowercase() != name) continue
            val dx = Math.abs(f.cx - old.cx)
            val dy = Math.abs(f.cy - old.cy)
            if (dx > NEAR_PX || dy > NEAR_PX) continue
            val d = dx + dy
            if (d < bestDist) {
                bestDist = d
                best = f
            }
        }
        return best
    }

    /** Is point par kaun sa element hai? Sabse chhota jo us point ko ghere. */
    fun hit(els: List<ScreenElement>, x: Int, y: Int): ScreenElement? {
        var best: ScreenElement? = null
        for (e in els) {
            if (x < e.left || x > e.right || y < e.top || y > e.bottom) continue
            val b = best
            if (b == null || e.area < b.area) best = e
        }
        return best
    }

    /** Ungli ki disha: up = neeche se upar. Lauta hua: x1, y1, x2, y2 */
    fun swipeVector(dir: String, w: Int, h: Int): FloatArray {
        val fw = w.toFloat()
        val fh = h.toFloat()
        return when (dir.trim().lowercase()) {
            "up" -> floatArrayOf(fw * 0.5f, fh * 0.7f, fw * 0.5f, fh * 0.3f)
            "down" -> floatArrayOf(fw * 0.5f, fh * 0.3f, fw * 0.5f, fh * 0.7f)
            "left" -> floatArrayOf(fw * 0.8f, fh * 0.5f, fw * 0.2f, fh * 0.5f)
            "right" -> floatArrayOf(fw * 0.2f, fh * 0.5f, fw * 0.8f, fh * 0.5f)
            else -> floatArrayOf(fw * 0.5f, fh * 0.7f, fw * 0.5f, fh * 0.3f)
        }
    }

    /** scroll_to mein "down" ka matlab: neeche ka content dikhao = ungli upar jayegi */
    fun fingerDirForScroll(direction: String): String = if (direction.trim().lowercase() == "up") "down" else "up"
}
