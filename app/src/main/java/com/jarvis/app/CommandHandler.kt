package com.jarvis.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore

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
        "मैप्स" to "maps", "मैप" to "maps",
        "खोलो" to "kholo", "खोलिए" to "kholo", "खोल दो" to "kholo", "खोल" to "kholo",
        "ओपन" to "kholo", "चालू" to "kholo", "शुरू" to "kholo", "चलाओ" to "kholo",
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

    private fun normalize(raw: String): String {
        var s = raw.lowercase().trim()
        for ((a, b) in replacements) {
            s = s.replace(a, b)
        }
        s = s.replace(Regex("[,.!?।]"), " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    /** Seedha command mila to reply lautao, nahi mila to null (tab Gemini samjhega) */
    fun handle(context: Context, raw: String): String? {
        val t = normalize(raw)
        val wordCount = t.split(" ").filter { it.isNotBlank() }.size

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
            val act = r.substring(ai + 7).lines().first().trim().uppercase()
            return runAction(act)
        }
        return reply
    }

    private fun runAction(a: String): String {
        val svc = JarvisAccessibilityService.instance
            ?: return "Pehle Permissions mein Accessibility Service allow karo 🔐"
        return when {
            a.contains("BACK") -> {
                svc.goBack(); "Back kar diya!"
            }
            a.contains("HOME") -> {
                svc.goHome(); "Home par aa gaye!"
            }
            a.contains("RECENT") -> {
                svc.openRecents(); "Recent apps khol diye!"
            }
            a.contains("SCROLL_UP") -> {
                svc.scrollUp(); "Upar scroll kar diya!"
            }
            a.contains("SCROLL") -> {
                svc.scrollDown(); "Neeche scroll kar diya!"
            }
            else -> "Ye kaam mujhe samajh nahi aaya 😕"
        }
    }

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
