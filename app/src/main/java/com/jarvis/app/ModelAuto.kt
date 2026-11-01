package com.jarvis.app

/**
 * Models KHUD chunna (automatic). Provider ki /models list se chat wale models chunta hai,
 * unhe ek line mein lagata hai (tagda pehle ya tez pehle), aur sirf kuch hi (max) rakhta hai.
 * Is line ko ModelChain tier ke hisaab se sab providers mein milata hai.
 *
 * Dhyan: ye aapki key ki list se hi chunta hai. Agar aapke account par billing on hai to bade models paid ho sakte hain.
 * OpenRouter par sirf ':free' wale models liye jaate hain.
 */
object ModelAuto {

    // Ye naam wale models chat ke liye nahi hote (awaaz, image, embedding, safety...)
    private val skip = listOf(
        "embed", "tts", "whisper", "audio", "imagen", "image", "veo", "live", "transcribe", "moderation",
        "guard", "safeguard", "robotics", "aqa", "learnlm", "computer-use", "dall", "rerank", "ocr",
        "deep-research", "speech", "vision-preview"
    )

    private val versionRegex = Regex("(\\d+(?:\\.\\d+)?)")

    fun isChatModel(providerId: String, name: String): Boolean {
        val n = name.lowercase().trim()
        if (n.isEmpty()) return false
        if (skip.any { n.contains(it) }) return false
        if (providerId == "openrouter" && !n.endsWith(":free")) return false
        // Gemini API par Gemma models system-instruction nahi leti, isliye yahan nahi
        if (providerId == "gemini" && n.contains("gemma")) return false
        return true
    }

    private fun versionOf(name: String): Double {
        val m = versionRegex.find(name) ?: return 0.0
        return m.groupValues[1].toDoubleOrNull() ?: 0.0
    }

    private fun score(name: String): Int {
        val n = name.lowercase()
        var s = ModelRank.score(n)
        // Preview/experimental models aksar kam bharosemand hote hain: thoda peeche
        if (n.contains("preview") || n.contains("exp")) s -= 2
        return s
    }

    /**
     * names = provider ki list. strongFirst=true: tagda model pehle, phir halka. false: halka/tez pehle.
     * max = ek provider ke kitne models (tier) rakhne hain.
     */
    fun order(providerId: String, names: List<String>, strongFirst: Boolean, max: Int): List<String> {
        val cands = names.map { it.trim() }.filter { isChatModel(providerId, it) }.distinct()
        val cmp = Comparator<String> { a, b ->
            val sa = score(a)
            val sb = score(b)
            when {
                sa != sb -> if (strongFirst) sb.compareTo(sa) else sa.compareTo(sb)
                else -> versionOf(b).compareTo(versionOf(a)) // barabar ho to naya version pehle
            }
        }
        return cands.sortedWith(cmp).take(max)
    }
}
