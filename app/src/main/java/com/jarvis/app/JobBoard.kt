package com.jarvis.app

/*
 * Background kaam ka bahi-khata (pure code, Android ke bina, unit tested).
 * Bade kaam (research, lamba likhna, plan) ek alag "sub-agent" mein background mein chalte hain; yahan sirf unki ginti
 * aur halat rakhi jati hai: kaun chal raha hai, kaun poora hua, kaun cancel hua.
 * Niyam: ek kism ka ek hi kaam ek samay par, aur kul 2 se zyada nahi (battery, data aur budget bachane ke liye).
 */

enum class JobKind(val label: String) {
    RESEARCH("research"),
    WRITING("likhai"),
    PLAN("plan")
}

enum class JobState { RUNNING, DONE, FAILED, CANCELLED }

class Job(val id: Int, val kind: JobKind, val brief: String, val startedAt: Long) {
    @Volatile
    var state: JobState = JobState.RUNNING

    @Volatile
    var result: String = ""

    @Volatile
    var endedAt: Long = 0L

    /** Kitne helper agents mein baanta gaya (1 = akela agent) */
    @Volatile
    var workers: Int = 1

    /** Chalte waqt ka chhota haal, jaise "2/4 hisse poore" */
    @Volatile
    var progress: String = ""
}

sealed class JobStart {
    class Started(val job: Job) : JobStart()
    class Busy(val reason: String) : JobStart()
}

class JobBoard(private val maxRunning: Int = 2, private val keepFinished: Int = 8) {

    private val jobs = ArrayList<Job>()
    private var nextId = 1

    @Synchronized
    fun start(kind: JobKind, brief: String, now: Long, workers: Int = 1): JobStart {
        val running = jobs.filter { it.state == JobState.RUNNING }
        val same = running.firstOrNull { it.kind == kind }
        if (same != null) {
            return JobStart.Busy("Ek " + kind.label + " (#" + same.id + ") pehle se chal rahi hai. Use khatam hone do ya cancel karo.")
        }
        if (running.size >= maxRunning) {
            return JobStart.Busy("Abhi " + running.size + " kaam background mein chal rahe hain. Ek khatam hone do.")
        }
        val j = Job(nextId, kind, brief.trim(), now)
        j.workers = workers
        nextId++
        jobs.add(j)
        trim()
        return JobStart.Started(j)
    }

    private fun trim() {
        while (jobs.count { it.state != JobState.RUNNING } > keepFinished) {
            val old = jobs.first { it.state != JobState.RUNNING }
            jobs.remove(old)
        }
    }

    private fun find(id: Int): Job? = jobs.firstOrNull { it.id == id }

    @Synchronized
    fun complete(id: Int, text: String, now: Long): Boolean {
        val j = find(id) ?: return false
        if (j.state != JobState.RUNNING) return false // cancel ho chuka ho to purana nateeja mat rakho
        j.result = text.take(6000)
        j.state = JobState.DONE
        j.endedAt = now
        trim()
        return true
    }

    @Synchronized
    fun fail(id: Int, reason: String, now: Long): Boolean {
        val j = find(id) ?: return false
        if (j.state != JobState.RUNNING) return false
        j.result = reason.take(200)
        j.state = JobState.FAILED
        j.endedAt = now
        trim()
        return true
    }

    @Synchronized
    fun cancel(id: Int, now: Long): Boolean {
        val j = find(id) ?: return false
        if (j.state != JobState.RUNNING) return false
        j.state = JobState.CANCELLED
        j.endedAt = now
        trim()
        return true
    }

    @Synchronized
    fun isCancelled(id: Int): Boolean = find(id)?.state == JobState.CANCELLED

    @Synchronized
    fun get(id: Int): Job? = find(id)

    @Synchronized
    fun latest(): Job? = jobs.lastOrNull()

    @Synchronized
    fun runningJobs(): List<Job> = jobs.filter { it.state == JobState.RUNNING }

    fun elapsed(ms: Long): String {
        val s = ms / 1000L
        if (s < 5L) return "abhi"
        if (s < 60L) return s.toString() + " sec"
        return (s / 60L).toString() + " min"
    }

    fun describe(j: Job, now: Long): String {
        val head = "#" + j.id + " " + j.kind.label
        return when (j.state) {
            JobState.RUNNING -> head + " chal rahi hai (" + elapsed(now - j.startedAt) + ")" +
                (if (j.progress.isNotBlank()) " [" + j.progress + "]" else "") + ": " + j.brief.take(80)
            JobState.DONE -> head + " ho gayi ✅\n" + j.result
            JobState.FAILED -> head + " nahi ho payi 😕 " + j.result
            JobState.CANCELLED -> head + " cancel ki gayi 🛑"
        }
    }
}
