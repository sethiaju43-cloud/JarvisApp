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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/*
 * Background sub-agent chalane wala Android hissa.
 * - Akela agent (workers = 1): ek ToolAgent, sirf padhne wale tools ke saath.
 * - Master + helpers (workers 2..5): master kaam ko hisson mein todta hai, har hissa alag thread mein ek helper agent karta hai
 *   (sab ek saath), phir master sabka nateeja jodta hai. Koi helper fail ho to baaki ke nateeje se kaam chalta hai.
 * Sab agents sirf SubAgent.READ_ONLY_TOOLS use karte hain, isliye phone par kuch nahi kar sakte.
 * Dhyan: kaam sirf app ke process mein chalta hai. Agar Android app ko band kar de to chalta kaam kho jata hai.
 */
object SubAgentRunner {

    val board = JobBoard()
    private const val CHANNEL = "jarvi_jobs"

    fun start(ctx: Context, kindText: String, brief: String, workersWanted: Int = 1): String {
        val kind = SubAgent.kindFromText(kindText) ?: return "Kaam ka type sahi nahi: research, writing ya plan chuno."
        val app = ctx.applicationContext
        if (brief.isBlank()) return "Kaam ka vivaran khali hai. Batao kya karna hai."
        if (Budget.state(app).exceeded) return "Budget poora ho gaya, abhi background kaam nahi chala sakti."
        if (!AndroidToolLlm(app, "", Task.REASONING).hasCandidates()) {
            return "Background kaam ke liye online model chahiye. Settings mein API key daalo, ya cooldown khatam hone do 😕"
        }
        val n = MasterPlan.clampWorkers(kind, workersWanted)
        return when (val r = board.start(kind, brief, System.currentTimeMillis(), n)) {
            is JobStart.Busy -> r.reason
            is JobStart.Started -> {
                launch(app, r.job)
                val how = if (n > 1) "master + " + n + " helper agents ek saath, lagbhag " + MasterPlan.estimateCalls(n) + " model calls"
                else "ek agent, lagbhag " + MasterPlan.estimateCalls(1) + " model calls"
                "Background mein shuru kar diya ✅ (#" + r.job.id + " " + kind.label + ": " + how + "). Kuch minute lagenge. Tum baat karte raho, poora hote hi notification aayega."
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
        return if (board.cancel(j.id, System.currentTimeMillis())) "Background kaam #" + j.id + " rok diya 🛑 (sab helpers bhi ruk jayenge)"
        else "Ye kaam pehle hi khatam ho chuka hai."
    }

    private fun launch(app: Context, job: Job) {
        val t = Thread({ run(app, job) }, "jarvi-subagent-" + job.id)
        t.isDaemon = true
        t.start()
    }

    private fun timeLine(): String =
        "Abhi ka samay: " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm, EEEE", java.util.Locale.ENGLISH).format(java.util.Date()) + "."

    private fun agentRun(app: Context, job: Job, system: String, brief: String, rounds: Int): AgentResult {
        val llm = AndroidToolLlm(app, system, Task.REASONING)
        val agent = ToolAgent(
            llm, AndroidToolRunner(app),
            confirmCalls = true,
            maxRounds = rounds,
            maxCalls = 30,
            resultLimit = 2500,
            finalizeOnLimit = true,
            toolNames = SubAgent.READ_ONLY_TOOLS,
            stop = { board.isCancelled(job.id) }
        )
        return agent.run(listOf(Pair("user", brief)))
    }

    private fun runMaster(app: Context, job: Job, n: Int): AgentResult {
        val time = timeLine()
        // 1) Master kaam ko hisson mein todta hai
        var subs: List<String> = emptyList()
        try {
            val plan = AndroidToolLlm(app, MasterPlan.plannerSystem(n, time), Task.REASONING)
                .next(listOf(ToolTurn.User(MasterPlan.plannerUser(job.brief, n))), emptyList())
            subs = MasterPlan.parse(plan.text, n)
        } catch (e: ToolAdapterException) {
            subs = emptyList()
        }
        // Hisse na bane to akela agent poora kaam kare
        if (subs.size < 2) return agentRun(app, job, SubAgent.systemPrompt(job.kind, time), job.brief, SubAgent.maxRounds(job.kind))

        // 2) Helpers ek saath
        val parts: List<String> = subs
        val total = parts.size
        job.progress = "0/" + total + " hisse poore"
        val results = arrayOfNulls<String>(total)
        val latch = CountDownLatch(total)
        val finished = AtomicInteger(0)
        for (i in 0 until total) {
            val t = Thread({
                try {
                    Thread.sleep(MasterPlan.staggerMs(i))
                    if (!board.isCancelled(job.id)) {
                        val r = agentRun(
                            app, job, SubAgent.workerPrompt(time),
                            MasterPlan.workerBrief(job.brief, parts[i], i + 1, total), SubAgent.MAX_WORKER_ROUNDS
                        )
                        if (r is AgentResult.Done && MasterPlan.isUsable(r.text)) results[i] = r.text
                    }
                } catch (e: Throwable) {
                    results[i] = null
                } finally {
                    job.progress = finished.incrementAndGet().toString() + "/" + total + " hisse poore"
                    latch.countDown()
                }
            }, "jarvi-worker-" + job.id + "-" + i)
            t.isDaemon = true
            t.start()
        }
        try {
            latch.await(15, TimeUnit.MINUTES)
        } catch (e: InterruptedException) {
        }
        if (board.isCancelled(job.id)) return AgentResult.Done("Ruk gayi 🛑", emptyList())

        val got = ArrayList<Pair<String, String>>()
        for (i in 0 until total) {
            val r = results[i]
            if (r != null) got.add(Pair(parts[i], r))
        }
        if (got.isEmpty()) return AgentResult.Failed("kisi bhi helper agent se jawab nahi mila (limit ya internet ki dikkat ho sakti hai)")

        // 3) Master sabka nateeja jodta hai
        job.progress = "nateeje jod raha hoon"
        var text = ""
        try {
            text = AndroidToolLlm(app, SubAgent.systemPrompt(job.kind, time), Task.REASONING)
                .next(listOf(ToolTurn.User(MasterPlan.synthesisPrompt(job.brief, got, total))), emptyList()).text
        } catch (e: ToolAdapterException) {
            text = ""
        }
        if (text.isBlank()) text = MasterPlan.fallbackJoin(job.brief, got)
        return AgentResult.Done(text, emptyList())
    }

    private fun run(app: Context, job: Job) {
        try {
            val res: AgentResult = if (job.workers > 1) runMaster(app, job, job.workers)
            else agentRun(app, job, SubAgent.systemPrompt(job.kind, timeLine()), job.brief, SubAgent.maxRounds(job.kind))
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
