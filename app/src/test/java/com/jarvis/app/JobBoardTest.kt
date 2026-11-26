package com.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JobBoardTest {

    private fun started(r: JobStart): Job {
        assertTrue("Started chahiye tha", r is JobStart.Started)
        return (r as JobStart.Started).job
    }

    private fun busyReason(r: JobStart): String {
        assertTrue("Busy chahiye tha", r is JobStart.Busy)
        return (r as JobStart.Busy).reason
    }

    @Test
    fun idsStartAtOneAndGoUp() {
        val b = JobBoard()
        assertEquals(1, started(b.start(JobKind.RESEARCH, "AI par research", 0L)).id)
        assertEquals(2, started(b.start(JobKind.WRITING, "essay", 0L)).id)
    }

    @Test
    fun sameKindCannotRunTwice() {
        val b = JobBoard()
        started(b.start(JobKind.RESEARCH, "a", 0L))
        val why = busyReason(b.start(JobKind.RESEARCH, "b", 0L))
        assertTrue(why.contains("#1"))
    }

    @Test
    fun atMostTwoRunAtOnce() {
        val b = JobBoard()
        started(b.start(JobKind.RESEARCH, "a", 0L))
        started(b.start(JobKind.WRITING, "b", 0L))
        val why = busyReason(b.start(JobKind.PLAN, "c", 0L))
        assertTrue(why.contains("2"))
    }

    @Test
    fun finishedJobFreesTheSlot() {
        val b = JobBoard()
        val j = started(b.start(JobKind.RESEARCH, "a", 0L))
        assertTrue(b.complete(j.id, "nateeja", 10L))
        assertEquals(JobState.DONE, j.state)
        assertEquals("nateeja", j.result)
        assertEquals(10L, j.endedAt)
        started(b.start(JobKind.RESEARCH, "dobara", 20L))
    }

    @Test
    fun cancelledJobIgnoresLateResult() {
        val b = JobBoard()
        val j = started(b.start(JobKind.RESEARCH, "a", 0L))
        assertFalse(b.isCancelled(j.id))
        assertTrue(b.cancel(j.id, 5L))
        assertTrue(b.isCancelled(j.id))
        // thread baad mein khatam hua to bhi nateeja mat rakho
        assertFalse(b.complete(j.id, "der se aaya", 9L))
        assertFalse(b.fail(j.id, "x", 9L))
        assertEquals("", j.result)
        assertEquals(JobState.CANCELLED, j.state)
        assertFalse(b.cancel(j.id, 10L))
    }

    @Test
    fun failStoresReason() {
        val b = JobBoard()
        val j = started(b.start(JobKind.PLAN, "a", 0L))
        assertTrue(b.fail(j.id, "internet nahi tha", 3L))
        assertEquals(JobState.FAILED, j.state)
        assertEquals("internet nahi tha", j.result)
        assertFalse(b.complete(j.id, "late", 4L))
    }

    @Test
    fun unknownIdIsHandled() {
        val b = JobBoard()
        assertFalse(b.complete(99, "x", 0L))
        assertFalse(b.fail(99, "x", 0L))
        assertFalse(b.cancel(99, 0L))
        assertFalse(b.isCancelled(99))
        assertNull(b.get(99))
        assertNull(b.latest())
    }

    @Test
    fun oldFinishedJobsAreDropped() {
        val b = JobBoard(maxRunning = 2, keepFinished = 2)
        for (i in 1..5) {
            val j = started(b.start(JobKind.RESEARCH, "job $i", i.toLong()))
            b.complete(j.id, "r$i", i.toLong())
        }
        assertNull(b.get(1))
        assertNull(b.get(3))
        assertNotNull(b.get(4))
        assertNotNull(b.get(5))
        assertEquals(5, b.latest()!!.id)
    }

    @Test
    fun runningJobsAreNeverDropped() {
        val b = JobBoard(maxRunning = 2, keepFinished = 1)
        val long = started(b.start(JobKind.WRITING, "lamba kaam", 0L))
        for (i in 1..4) {
            val j = started(b.start(JobKind.RESEARCH, "r$i", i.toLong()))
            b.complete(j.id, "ok", i.toLong())
        }
        assertNotNull(b.get(long.id))
        assertEquals(1, b.runningJobs().size)
    }

    @Test
    fun resultIsCapped() {
        val b = JobBoard()
        val j = started(b.start(JobKind.WRITING, "a", 0L))
        b.complete(j.id, "x".repeat(9000), 1L)
        assertEquals(6000, j.result.length)
    }

    @Test
    fun elapsedText() {
        val b = JobBoard()
        assertEquals("abhi", b.elapsed(3000L))
        assertEquals("30 sec", b.elapsed(30_000L))
        assertEquals("3 min", b.elapsed(180_000L))
    }

    @Test
    fun describeAllStates() {
        val b = JobBoard()
        val j = started(b.start(JobKind.RESEARCH, "electric cars ka future", 0L))
        val run = b.describe(j, 120_000L)
        assertTrue(run.contains("#1"))
        assertTrue(run.contains("research"))
        assertTrue(run.contains("chal rahi hai"))
        assertTrue(run.contains("2 min"))
        assertTrue(run.contains("electric cars"))

        b.complete(j.id, "Saar: badhiya", 130_000L)
        val done = b.describe(j, 140_000L)
        assertTrue(done.contains("✅"))
        assertTrue(done.endsWith("Saar: badhiya"))

        val k = started(b.start(JobKind.PLAN, "trip", 0L))
        b.fail(k.id, "internet nahi", 1L)
        assertTrue(b.describe(k, 2L).contains("😕"))
        assertTrue(b.describe(k, 2L).contains("internet nahi"))

        val c = started(b.start(JobKind.WRITING, "essay", 0L))
        b.cancel(c.id, 1L)
        assertTrue(b.describe(c, 2L).contains("🛑"))
    }
}
