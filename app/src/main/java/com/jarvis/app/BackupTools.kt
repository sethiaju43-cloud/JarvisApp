package com.jarvis.app

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Jo kuch Jarvi ne seekha (kaam + yaad kiye jawab) uska backup aur restore.
 * Export: JSON banta hai, phone mein ek snapshot file rehti hai aur share menu khulta hai (Keep, Drive, WhatsApp...).
 * Restore: backup ka text chat mein paste karo; ye BINA API ke, seedha yahin phone par hota hai.
 * API keys aur settings backup mein kabhi nahi jaate. Restore sirf jodta hai, kuch delete nahi karta.
 */
object BackupTools {

    private fun appVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    fun export(ctx: Context): String {
        val app = ctx.applicationContext
        val tasks = TaskMemory.all(app)
        val answers = AnswerCache.all(app)
        val now = System.currentTimeMillis()
        val json = BackupLogic.export(tasks, answers, Build.MODEL ?: "phone", appVersion(app), now)
        var saved = ""
        try {
            val dir = File(app.filesDir, "backups")
            dir.mkdirs()
            val name = "jarvi-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now)) + ".json"
            File(dir, name).writeText(json)
            val old = dir.listFiles()?.sortedByDescending { it.name } ?: emptyList()
            for (f in old.drop(5)) f.delete()
            saved = name
        } catch (e: Exception) {
        }
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_SUBJECT, "Jarvi backup")
        i.putExtra(Intent.EXTRA_TEXT, json)
        val chooser = Intent.createChooser(i, "Jarvi backup bhejo")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val shared = try {
            app.startActivity(chooser)
            true
        } catch (e: Exception) {
            false
        }
        return "Backup ban gaya 📦 " + tasks.size + " seekhe hue kaam aur " + answers.size + " yaad kiye jawab." +
            (if (shared) " Share menu khula hai: Keep, Drive ya WhatsApp (apne aap ko) mein save kar lo." else " Share menu nahi khul paya.") +
            (if (saved.isNotEmpty()) " Phone mein bhi ek copy rakhi hai." else "") +
            " Wapas daalne ke liye wahi backup text chat mein paste kar dena, API nahi lagegi. API keys isme nahi hoti."
    }

    /** Chat mein backup paste hua ho to seedha yahin restore karo (bina API ke). Nahi to null. */
    fun tryImport(ctx: Context, history: List<Pair<String, String>>): String? {
        val text = history.lastOrNull { it.first == "user" }?.second ?: return null
        if (!BackupLogic.looksLikeBackup(text)) return null
        val app = ctx.applicationContext
        return when (val r = BackupLogic.parse(text)) {
            is BackupLogic.ParseResult.Bad -> "Restore nahi ho paya 😕 " + r.message
            is BackupLogic.ParseResult.Ok -> {
                val t = TaskMemory.merge(app, r.parsed.tasks)
                val a = AnswerCache.merge(app, r.parsed.answers)
                BackupLogic.summary(t, a, r.parsed.device)
            }
        }
    }
}
