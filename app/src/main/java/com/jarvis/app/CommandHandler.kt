package com.jarvis.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

    /** Command mila to reply text lautao, nahi mila to null (tab Gemini se baat hogi) */
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
            return openApp(context, name)
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

    private fun cleanName(t: String): String {
        val words = t.split(" ").filter { it.isNotBlank() && it !in fillerWords }
        return words.joinToString(" ").trim()
    }

    private fun openApp(context: Context, name: String): String {
        if (name.isBlank()) return "Kaunsa app kholu? 🤔"
        val pm = context.packageManager

        // Pehle known apps dekho
        val pkg = knownApps.entries.firstOrNull { name.contains(it.key) }?.value
        if (pkg != null) {
            val i = pm.getLaunchIntentForPackage(pkg)
            if (i != null) return launchIntent(context, i, "$name khol diya!")
        }

        // Nahi mila to installed apps ke naam mein dhundo
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(main, PackageManager.MATCH_ALL)
        for (ri in list) {
            val label = ri.loadLabel(pm).toString().lowercase()
            if (label.contains(name) || name.contains(label)) {
                val i = pm.getLaunchIntentForPackage(ri.activityInfo.packageName)
                if (i != null) return launchIntent(context, i, "${ri.loadLabel(pm)} khol diya!")
            }
        }
        return "'$name' app mujhe nahi mila 😕"
    }

    private fun launchIntent(context: Context, i: Intent, okMsg: String): String {
        return try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            okMsg
        } catch (e: Exception) {
            "App khol nahi paya 😕"
        }
    }
}
