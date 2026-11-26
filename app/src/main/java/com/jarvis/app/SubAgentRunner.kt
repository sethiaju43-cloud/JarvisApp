package com.jarvis.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/*
 * Background sub-agent chalane wala Android hissa. Alag thread mein ToolAgent chalta hai, par sirf padhne wale tools ke saath
 * (SubAgent.READ_ONLY_TOOLS), isliye wo phone par kuch nahi kar sakta. Poora hone par notification aata hai.
 * Dhyan: kaam sirf app ke process mein chalta hai. Agar Android app ko band kar de to chalta kaam kho jata hai.
 */
object SubAgentRunner {

    val board = JobBoard()
    private const val CHANNEL = "jarvi_jobs"

    fun start(ctx: Context, kindText: String, brief: String): String {
        val kind = SubAgent.kindFromText(kindText) ?: return "Kaam ka type sahi nahi: research, writing ya plan chuno."
        val app = ctx.applicationContext
        if (brief.isBlank()) return "Kaam ka vivaran khali hai. Batao kya karna hai."
        if (Budget.state(app).exceeded) return "Budget poora ho gaya, abhi background kaam nahi chala sakti."
        if (!AndroidToolLlm(app, "", Task.REASONING).hasCandidates()) {
            return "Background kaam ke liye online model chahiye. Settings mein API key daalo, ya cooldown khatam hone do 😕"
        }
        return when (val r = board.start(kind, brief, System.currentTimeMillis())) {
            is JobStart.Busy -> r.reason
            is JobStart.Started -> {
                launch(app, r.job)
                "Background mein shuru kar diya ✅ (#" + r.job.id + " " + kind.label + "). Kuch minute lagenge. Tum baat karte raho, poora hote hi notification aayega."
            }
        }
    }

    fun status(id: Int): String {
        val j = if (id > 0) board.get(id) else board.latest()
        if (j == null) return if (id > 0) "#" + id + " number ka koi background kaam nahi mila." else "Abhi koi background kaam nahi hai."
        return board.describe(j, System.currentTimeMillis())
    }

    fun cancel(id: Int): String {
        val j = if (id > 0) board.get(id) else board.runningJobs().lastOrNull()
        if (j == null) return "Koi chalta hua background kaam nahi mila."
        return if (board.cancel(j.id, System.currentTimeMillis())) "Background kaam #" + j.id + " rok diya 🛑"
        else "Ye kaam pehle hi khatam ho chuka hai."
    }

    private fun launch(app: Context, job: Job) {
        val t = Thread({ run(app, job) }, "jarvi-subagent-" + job.id)
        t.isDaemon = true
        t.start()
    }

    private fun run(app: Context, job: Job) {
        try {
            val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm, EEEE", java.util.Locale.ENGLISH).format(java.util.Date())
            val llm = AndroidToolLlm(app, SubAgent.systemPrompt(job.kind, "Abhi ka samay: " + time + "."), Task.REASONING)
            val agent = ToolAgent(
                llm, AndroidToolRunner(app),
                confirmCalls = true,
                maxRounds = SubAgent.maxRounds(job.kind),
                maxCalls = 30,
                resultLimit = 2500,
                finalizeOnLimit = true,
                toolNames = SubAgent.READ_ONLY_TOOLS,
                stop = { board.isCancelled(job.id) }
            )
            val res = agent.run(listOf(Pair("user", job.brief)))
            val now = System.currentTimeMillis()
            when (res) {
                is AgentResult.Done -> board.complete(job.id, res.text, now)
                is AgentResult.Failed -> board.fail(job.id, "Model se jawab nahi mila: " + res.reason.take(100), now)
                is AgentResult.NeedConfirm -> board.fail(job.id, "Is kaam ke liye background agent ko ijazat nahi.", now)
            }
        } catch (e: Throwable) {
            board.fail(job.id, "Kuch gadbad ho gayi: " + OnlineParsers.redact(e.message ?: e.javaClass.simpleName).take(100), System.currentTimeMillis())
        }
        if (job.state != JobState.CANCELLED) notifyDone(app, job)
    }

    @SuppressLint("MissingPermission")
    private fun notifyDone(ctx: Context, job: Job) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvi background kaam", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val ok = job.state == JobState.DONE
            val title = if (ok) "✅ " + job.kind.label + " ho gayi (#" + job.id + ")" else "😕 " + job.kind.label + " nahi ho payi (#" + job.id + ")"
            val body = job.result
            val pub = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .build()
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(SubAgent.preview(body))
                .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(3000)))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(pub)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            NotificationManagerCompat.from(ctx).notify(7000 + job.id, n)
        } catch (e: Exception) {
        }
    }
}
