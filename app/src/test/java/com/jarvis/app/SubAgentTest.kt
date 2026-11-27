package com.jarvis.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentTest {

    @Test
    fun readOnlyToolsAreSafeAndNeverTouchThePhone() {
        for (n in SubAgent.READ_ONLY_TOOLS) {
            val spec = ToolRegistry.get(n)
            assertTrue("registry mein nahi: $n", spec != null)
            assertEquals(n, ToolRisk.SAFE, spec!!.risk)
            // phone chalane wali purani ACTION:/OPEN: line se in tools ka koi rishta nahi
            assertNull(n, ToolRegistry.legacyLine(ToolCall("x", n, JSONObject())))
        }
        for (bad in listOf("make_call", "send_sms", "whatsapp_send_message", "tap_text", "type_text", "open_app", "start_background_task", "find_tool")) {
            assertFalse(bad, SubAgent.READ_ONLY_TOOLS.contains(bad))
        }
    }

    @Test
    fun kindParsing() {
        assertEquals(JobKind.RESEARCH, SubAgent.kindFromText("research"))
        assertEquals(JobKind.RESEARCH, SubAgent.kindFromText("  RESEARCH "))
        assertEquals(JobKind.WRITING, SubAgent.kindFromText("writing"))
        assertEquals(JobKind.WRITING, SubAgent.kindFromText("write"))
        assertEquals(JobKind.PLAN, SubAgent.kindFromText("plan"))
        assertNull(SubAgent.kindFromText("hack"))
        assertNull(SubAgent.kindFromText(""))
    }

    @Test
    fun roundsPerKind() {
        assertEquals(12, SubAgent.maxRounds(JobKind.RESEARCH))
        assertTrue(SubAgent.maxRounds(JobKind.WRITING) < SubAgent.maxRounds(JobKind.RESEARCH))
        assertTrue(SubAgent.maxRounds(JobKind.PLAN) >= 6)
    }

    @Test
    fun promptsCarryRulesAndTime() {
        for (k in JobKind.values()) {
            val p = SubAgent.systemPrompt(k, "Abhi ka samay: 2026-11-27 10:00.")
            assertTrue(p.contains("UNTRUSTED"))
            assertTrue(p.contains("2026-11-27 10:00"))
            assertTrue(p.contains("phone ko chala nahi sakte"))
        }
        assertTrue(SubAgent.systemPrompt(JobKind.RESEARCH, "").contains("4 se 8"))
        assertTrue(SubAgent.systemPrompt(JobKind.PLAN, "").contains("backup"))
    }

    @Test
    fun workerPromptIsNarrowAndSafe() {
        val p = SubAgent.workerPrompt("Abhi ka samay: aaj.")
        assertTrue(p.contains("EK hissa"))
        assertTrue(p.contains("UNTRUSTED"))
        assertTrue(p.contains("phone ko chala nahi sakte"))
        assertTrue(p.contains("2 se 4"))
        assertEquals(6, SubAgent.MAX_WORKER_ROUNDS)
    }

    @Test
    fun previewIsOneShortLine() {
        assertEquals("Pehli line", SubAgent.preview("\n\nPehli line\nDusri line"))
        assertEquals("", SubAgent.preview("   "))
        val p = SubAgent.preview("x".repeat(500))
        assertEquals(143, p.length)
        assertTrue(p.endsWith("..."))
    }

    @Test
    fun jobIntentWords() {
        for (t in listOf("electric cars par deep research karo", "ek report banao AI ki", "RESEARCH kar do", "essay likh do 500 words ka",
            "script likh do YouTube ke liye", "ye background mein karo", "job status batao", "रिसर्च करो",
            "4 helper agents se parallel mein dekho")) {
            assertTrue(t, JobIntent.looksLikeJob(t))
        }
        for (t in listOf("torch on karo", "Rahul ko call lagao", "namaste", "alarm lagao 7 baje", "WhatsApp kholo")) {
            assertFalse(t, JobIntent.looksLikeJob(t))
        }
    }
}
