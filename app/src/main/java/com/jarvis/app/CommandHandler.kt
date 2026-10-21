package com.jarvis.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat

object CommandHandler {

    private val knownApps = mapOf(
        "youtube" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp",
        "instagram" to "com.instagram.android",
        "chrome" to "com.android.chrome",
        "facebook" to "com.facebook.katana",
        "telegram" to "org.telegram.messenger",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps"
    )

    // Hindi akshar ko English shabdon mein badalna
    private val replacements = listOf(
        "यूट्यूब" to "youtube", "यूट्युब" to "youtube", "युटुब" to "youtube",
        "यूटूब" to "youtube", "यूटयूब" to "youtube", "यू ट्यूब" to "youtube",
        "व्हाट्सएप" to "whatsapp", "व्हाट्सऐप" to "whatsapp", "वॉट्सऐप" to "whatsapp",
        "व्हाट्सअप" to "whatsapp", "वाट्सएप" to "whatsapp", "वाट्सऐप" to "whatsapp",
        "इंस्टाग्राम" to "instagram", "क्रोम" to "chrome", "कैमरा" to "camera",
        "फेसबुक" to "facebook", "टेलीग्राम" to "telegram", "जीमेल" to "gmail",
        "मैप्स" to "maps", "मैप" to "maps", "गूगल" to "google",
        "खोलो" to "kholo", "खोलिए" to "kholo", "खोल दो" to "kholo", "खोल" to "kholo",
        "ओपन" to "kholo", "चालू" to "kholo", "शुरू" to "kholo", "चलाओ" to "kholo",
        "सर्च" to "search", "ढूंढो" to "search", "ढूँढो" to "search", "ढूंढ" to "search",
        "ढूँढ" to "search", "खोजो" to "search", "खोजिए" to "search", "खोजें" to "search",
        "पढ़" to "padh", "पढ" to "padh", "स्क्रीन" to "screen", "सुनाओ" to "sunao",
        "बताओ" to "batao", "क्या लिखा" to "kya likha", "लिखा" to "likha",
        "दिख" to "dikh", "किसका" to "kiska", "किसकी" to "kiski", "किसके" to "kiske",
        "कॉल" to "call", "नंबर" to "number", "नम्बर" to "number",
        "बैक" to "back", "पीछे" to "peeche", "होम" to "home",
        "स्क्रॉल" to "scroll", "स्क्रोल" to "scroll", "क्लिक" to "click", "टैप" to "tap",
        "नीचे" to "neeche", "ऊपर" to "upar", "रिसेंट" to "recent",
        "जार्वी" to "jarvi", "जारवी" to "jarvi", "जार्विस" to "jarvis",
        "जर्विस" to "jarvis", "जारविस" to "jarvis"
    )

    private val fillerWords = listOf(
        "kholo", "khol", "kholna", "open", "karo", "kar", "do", "dena", "jao", "ko", "app",
        "please", "jarvis", "jarvi", "click", "tap", "dabao", "hey", "mere", "liye", "zara",
        "na", "करो", "कर", "दो", "हे", "मेरे", "लिए", "प्लीज", "प्लीज़", "ज़रा", "जरा", "को"
    )

    private val searchStop = listOf(
        "search", "youtube", "google", "chrome", "par", "pe", "mein", "me", "ka", "ki", "ke",
        "ko", "karo", "kar", "do", "dena", "kholo", "mere", "liye", "jarvi", "jarvis", "hey",
        "please", "zara", "na", "karke", "dhundo", "dikhao",
        "पर", "में", "का", "की", "के", "को", "करो", "कर", "दो", "हे", "मेरे", "लिए",
        "प्लीज", "जरा", "ज़रा"
    )

    private fun normalize(raw: String): String {
        var s = raw.lowercase().trim()
        for ((a, b) in replacements) {
            s = s.replace(a, b)
        }
        s = s.replace(Regex("[,.!?।]"), " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    /** Kya user screen padhne / dekhne ko keh raha hai? (vaakya kitna bhi lamba ho) */
    fun wantsScreen(raw: String): Boolean {
        val t = normalize(raw)
        if (t.contains("padhai")) return false
        if (t.contains("padh")) return true
        if (t.contains("screen")) return true
        if (t.contains("likha")) return true
        if (t.contains("kiska") || t.contains("kiski") || t.contains("kiske")) return true
        if (t.contains("dikh") && (t.contains("kya") || t.contains("kaun"))) return true
        if (t.contains("number") && t.contains("call")) return true
        return false
    }

    /** Seedha command mila to reply lautao, nahi mila to null (tab Gemini samjhega) */
    fun handle(context: Context, raw: String): String? {
        val t = normalize(raw)
        val wordCount = t.split(" ").filter { it.isNotBlank() }.size

        // YouTube ya Google par search
        if (t.contains("search") && wordCount <= 12) {
            val q = searchQuery(t)
            if (q.isNotBlank()) {
                if (t.contains("youtube")) return openYoutubeSearch(context, q)
                if (t.contains("google") || t.contains("chrome")) return openWebSearch(context, q)
            }
        }

        // App kholo
        if ((t.contains("khol") || t.startsWith("open ")) && wordCount <= 8) {
            val name = cleanName(t)
            if (name.contains("camera")) {
                return launchIntent(
                    context, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
                    "Camera khol diya!"
                )
            }
            val r = openApp(context, name)
            if (r != null) return r
            // Nahi mila to Gemini samjhega
        }

        // Screen padhne wale sawaal Gemini ke paas jayenge (screen ka text saath mein)
        if (wantsScreen(raw)) return null

        // Phone control (Accessibility chahiye) - sirf chhote commands par
        val isControl = t.contains("back") || t.contains("home") ||
            t.contains("scroll") || t.contains("click") || t.contains("tap") ||
            t.contains("recent") || t.contains("peeche")

        if (isControl && wordCount <= 6) {
            val svc = JarvisAccessibilityService.instance
                ?: return "Pehle Permissions mein Accessibility Service allow karo 🔐"

            return when {
                t.contains("back") || t.contains("peeche") -> {
                    svc.goBack(); "Back kar diya!"
                }
                t.contains("home") -> {
                    svc.goHome(); "Home par aa gaye!"
                }
                t.contains("recent") -> {
                    svc.openRecents(); "Recent apps khol diye!"
                }
                t.contains("scroll") || t.contains("neeche") || t.contains("upar") -> {
                    if (t.contains("upar") || t.contains(" up")) {
                        svc.scrollUp(); "Upar scroll kar diya!"
                    } else {
                        svc.scrollDown(); "Neeche scroll kar diya!"
                    }
                }
                t.contains("click") || t.contains("tap") -> {
                    val name = cleanName(t)
                    if (name.isBlank()) {
                        svc.tapCenter(); "Tap kar diya!"
                    } else if (svc.clickByText(name)) {
                        "'$name' par click kar diya!"
                    } else {
                        "Screen par '$name' nahi mila 😕"
                    }
                }
                else -> null
            }
        }
        return null
    }

    /** Gemini ka jawab dekho: usme OPEN: ya ACTION: ho to kaam karo, warna jawab waisa hi lautao */
    fun interpret(context: Context, reply: String): String {
        val r = reply.trim()
        val oi = r.indexOf("OPEN:")
        if (oi >= 0) {
            val name = r.substring(oi + 5).lines().first().trim().trim('"', '\'', '*', '.')
            return openByLabel(context, name)
        }
        val ai = r.indexOf("ACTION:")
        if (ai >= 0) {
            val line = r.substring(ai + 7).lines().first().trim()
            return runAction(context, line)
        }
        return reply
    }

    private fun runAction(context: Context, line: String): String {
        val up = line.uppercase()

        // Call: Accessibility ki zarurat nahi
        if (up.startsWith("CALL")) {
            val target = line.drop(4).trim().trim('"', '\'', '<', '>')
            return callTarget(context, target)
        }

        val svc = JarvisAccessibilityService.instance
            ?: return "Pehle Permissions mein Accessibility Service allow karo 🔐"

        if (up.startsWith("TYPE")) {
            val txt = line.drop(4).trim().trim('"', '\'')
            if (txt.isBlank()) return "Kya likhun jaan? 🤔"
            return if (svc.typeText(txt)) {
                "Likh diya: " + txt + " ✍️"
            } else {
                "Likhne ki jagah nahi mili. Pehle jahan likhna hai wahan tap kar do 😕"
            }
        }

        if (up.startsWith("CLICK")) {
            val name = line.drop(5).trim().trim('"', '\'')
            if (name.isBlank()) return "Kaunsa button dabaun? 🤔"
            return if (svc.clickByText(name)) {
                "'" + name + "' dabaa diya! 👆"
            } else {
                "Screen par '" + name + "' nahi mila 😕"
            }
        }

        return when {
            up.contains("BACK") -> {
                svc.goBack(); "Back kar diya!"
            }
            up.contains("HOME") -> {
                svc.goHome(); "Home par aa gaye!"
            }
            up.contains("RECENT") -> {
                svc.openRecents(); "Recent apps khol diye!"
            }
            up.contains("SCROLL_UP") -> {
                svc.scrollUp(); "Upar scroll kar diya!"
            }
            up.contains("SCROLL") -> {
                svc.scrollDown(); "Neeche scroll kar diya!"
            }
            else -> "Ye kaam mujhe samajh nahi aaya 😕"
        }
    }

    // ---------- Call ----------
    private fun callTarget(context: Context, target: String): String {
        val digits = target.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 5) {
            return placeCall(context, digits, digits)
        }
        if (!hasContacts(context)) {
            return "Naam se call karne ke liye Permissions mein Contacts allow karo 📒"
        }
        for (n in target.split("|")) {
            val c = findContact(context, n.trim())
            if (c != null) return placeCall(context, c.second, c.first)
        }
        return "'" + target + "' naam ka contact nahi mila 😕"
    }

    private fun placeCall(context: Context, number: String, label: String): String {
        val canCall = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
        val action = if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val i = Intent(action, Uri.parse("tel:" + Uri.encode(number)))
        return launchIntent(context, i, label + " ko call laga rahi hun 📞")
    }

    private fun hasContacts(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun findContact(context: Context, name: String): Pair<String, String>? {
        val want = name.lowercase().replace(" ", "")
        if (want.length < 2) return null
        var best: Pair<String, String>? = null
        var bestScore = 0
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        ) ?: return null
        cursor.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                val l = n.lowercase().replace(" ", "")
                var score = 0
                if (l == want) {
                    score = 4
                } else if (l.startsWith(want) || (l.length >= 3 && want.startsWith(l))) {
                    score = 3
                } else if (l.contains(want)) {
                    score = 2
                }
                if (score > bestScore) {
                    best = Pair(n, num)
                    bestScore = score
                }
            }
        }
        return best
    }

    // ---------- Apps ----------
    /** Phone mein jitne apps hain unki list: (naam, package) */
    fun installedApps(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(main, PackageManager.MATCH_ALL)
        val out = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()
        for (ri in list) {
            val label = ri.loadLabel(pm).toString().trim()
            val pkg = ri.activityInfo.packageName
            if (label.isNotEmpty() && seen.add(pkg)) {
                out.add(Pair(label, pkg))
            }
        }
        out.sortBy { it.first.lowercase() }
        return out
    }

    private fun findByLabel(context: Context, wanted: String): Pair<String, String>? {
        val w = wanted.lowercase().replace(" ", "")
        if (w.length < 2) return null
        var best: Pair<String, String>? = null
        var bestScore = 0
        for (app in installedApps(context)) {
            val l = app.first.lowercase().replace(" ", "")
            var score = 0
            if (l == w) {
                score = 4
            } else if (l.startsWith(w) || (l.length >= 3 && w.startsWith(l))) {
                score = 3
            } else if (l.contains(w) || (l.length >= 3 && w.contains(l))) {
                score = 2
            }
            if (score > bestScore) {
                best = app
                bestScore = score
            }
        }
        return best
    }

    fun openByLabel(context: Context, wanted: String): String {
        val app = findByLabel(context, wanted)
        if (app != null) {
            val i = context.packageManager.getLaunchIntentForPackage(app.second)
            if (i != null) return launchIntent(context, i, app.first + " khol diya! 😊")
        }
        return "'" + wanted + "' app mujhe nahi mila 😕"
    }

    private fun cleanName(t: String): String {
        val words = t.split(" ").filter { it.isNotBlank() && it !in fillerWords }
        return words.joinToString(" ").trim()
    }

    private fun searchQuery(t: String): String {
        val words = t.split(" ").filter { it.isNotBlank() && it !in searchStop }
        return words.joinToString(" ").trim()
    }

    private fun openYoutubeSearch(context: Context, q: String): String {
        val i = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))
        )
        if (context.packageManager.getLaunchIntentForPackage("com.google.android.youtube") != null) {
            i.setPackage("com.google.android.youtube")
        }
        return launchIntent(context, i, "YouTube par '" + q + "' search kar diya! 🔎")
    }

    private fun openWebSearch(context: Context, q: String): String {
        val i = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))
        )
        return launchIntent(context, i, "Google par '" + q + "' search kar diya! 🔎")
    }

    private fun openApp(context: Context, name: String): String? {
        if (name.isBlank()) return null
        val pm = context.packageManager

        val pkg = knownApps.entries.firstOrNull { name.contains(it.key) }?.value
        if (pkg != null) {
            val i = pm.getLaunchIntentForPackage(pkg)
            if (i != null) return launchIntent(context, i, name + " khol diya!")
        }

        val found = findByLabel(context, name)
        if (found != null) {
            val i = pm.getLaunchIntentForPackage(found.second)
            if (i != null) return launchIntent(context, i, found.first + " khol diya!")
        }
        return null
    }

    private fun launchIntent(context: Context, i: Intent, okMsg: String): String {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Handler(Looper.getMainLooper()).post {
            try {
                context.startActivity(i)
            } catch (e: Exception) {
            }
        }
        return okMsg
    }
}
