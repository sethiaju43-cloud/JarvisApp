package com.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MasterPlanTest {

    @Test
    fun workerCountIsClamped() {
        assertEquals(1, MasterPlan.clampWorkers(JobKind.WRITING, 5)) // likhai baanti nahi jati
        assertEquals(1, MasterPlan.clampWorkers(JobKind.RESEARCH, 0))
        assertEquals(1, MasterPlan.clampWorkers(JobKind.RESEARCH, -3))
        assertEquals(3, MasterPlan.clampWorkers(JobKind.RESEARCH, 3))
        assertEquals(5, MasterPlan.clampWorkers(JobKind.RESEARCH, 9))
        assertEquals(2, MasterPlan.clampWorkers(JobKind.PLAN, 2))
    }

    @Test
    fun parsesJsonArrayOfStrings() {
        val r = MasterPlan.parse("[\"electric cars ki battery kaise bani hoti hai\", \"charging stations ki halat India mein\", \"sasti EV kaun si hai\"]", 5)
        assertEquals(3, r.size)
        assertEquals("charging stations ki halat India mein", r[1])
    }

    @Test
    fun parsesFencedJsonAndObjects() {
        val raw = "Ye lo:\n```json\n[{\"question\":\"battery ki life kitni hoti hai\"},{\"q\":\"charging mein kitna time lagta hai\"},{\"topic\":\"sarkari subsidy aur tax chhoot\"}]\n```"
        val r = MasterPlan.parse(raw, 5)
        assertEquals(3, r.size)
        assertEquals("battery ki life kitni hoti hai", r[0])
        assertEquals("sarkari subsidy aur tax chhoot", r[2])
    }

    @Test
    fun capsAtRequestedCountAndDedupes() {
        val raw = "[\"pehla bada sawaal\", \"PEHLA BADA SAWAAL\", \"doosra bada sawaal\", \"teesra bada sawaal\", \"chautha bada sawaal\"]"
        val r = MasterPlan.parse(raw, 3)
        assertEquals(listOf("pehla bada sawaal", "doosra bada sawaal", "teesra bada sawaal"), r)
    }

    @Test
    fun fallsBackToListLines() {
        val raw = "Sure! Ye hisse hain:\n1. battery aur range ka tulna\n2) charging network ki jaankari\n- sarkari yojana aur chhoot\n* ab"
        val r = MasterPlan.parse(raw, 5)
        assertEquals(3, r.size)
        assertEquals("battery aur range ka tulna", r[0])
        // intro wali line ya bahut chhoti "ab" list item nahi maani jati
        assertFalse(r.any { it.startsWith("Sure") })
    }

    @Test
    fun proseOnlyGivesNothing() {
        assertTrue(MasterPlan.parse("Mujhe samajh nahi aaya, kya aap dobara bata sakte hain?", 5).isEmpty())
        assertTrue(MasterPlan.parse("", 5).isEmpty())
        assertTrue(MasterPlan.parse("[1, 2, 3]", 5).isEmpty())
        assertTrue(MasterPlan.parse("[bad json", 5).isEmpty())
    }

    @Test
    fun briefsAndPrompts() {
        val wb = MasterPlan.workerBrief("EV ka future", "battery ki life", 2, 4)
        assertTrue(wb.contains("EV ka future"))
        assertTrue(wb.contains("battery ki life"))
        assertTrue(wb.contains("2/4"))
        val ps = MasterPlan.plannerSystem(4, "Abhi: kal.")
        assertTrue(ps.contains("4"))
        assertTrue(ps.contains("JSON"))
        assertTrue(MasterPlan.plannerUser("EV ka future", 4).contains("EV ka future"))
    }

    @Test
    fun synthesisPromptKeepsPartsAndCapsLength() {
        val p = MasterPlan.synthesisPrompt("EV ka future", listOf(Pair("battery", "x".repeat(10000)), Pair("charging", "chhota nateeja")), 4)
        assertTrue(p.contains("EV ka future"))
        assertTrue(p.contains("2/4"))
        assertTrue(p.contains("HISSA 1: battery"))
        assertTrue(p.contains("HISSA 2: charging"))
        assertTrue(p.contains("UNTRUSTED"))
        assertTrue(p.length < 4000)
    }

    @Test
    fun fallbackJoinHasEveryPart() {
        val t = MasterPlan.fallbackJoin("EV ka future", listOf(Pair("battery", "a".repeat(2000)), Pair("charging", "ye bhi hai")))
        assertTrue(t.startsWith("Research: EV ka future"))
        assertTrue(t.contains("battery:"))
        assertTrue(t.contains("charging:"))
        assertTrue(t.contains("ye bhi hai"))
        assertFalse(t.contains("a".repeat(1600)))
    }

    @Test
    fun usableResultCheck() {
        assertFalse(MasterPlan.isUsable(""))
        assertFalse(MasterPlan.isUsable("chhota"))
        assertFalse(MasterPlan.isUsable("Kaam poora nahi ho paya 😕"))
        assertFalse(MasterPlan.isUsable("x".repeat(100) + " Kaam poora nahi ho paya"))
        assertTrue(MasterPlan.isUsable("Battery ki life aam taur par 8 saal ya 1.6 lakh km hoti hai, company ke hisaab se."))
    }

    @Test
    fun costAndStagger() {
        assertEquals(8, MasterPlan.estimateCalls(1))
        assertEquals(32, MasterPlan.estimateCalls(5))
        assertTrue(MasterPlan.estimateCalls(3) < MasterPlan.estimateCalls(5))
        assertEquals(0L, MasterPlan.staggerMs(0))
        assertEquals(3600L, MasterPlan.staggerMs(3))
    }
}
