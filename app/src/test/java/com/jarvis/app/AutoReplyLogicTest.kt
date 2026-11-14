package com.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoReplyLogicTest {

    @Test
    fun turnOnAndOff() {
        val on = AutoReplyLogic.parse("auto reply chalu karo")
        assertTrue(on is AutoReplyCommand.On)
        assertEquals(3, (on as AutoReplyCommand.On).hours)
        assertEquals(2, (AutoReplyLogic.parse("auto-reply on karo 2 ghante") as AutoReplyCommand.On).hours)
        assertTrue(AutoReplyLogic.parse("auto reply band karo") is AutoReplyCommand.Off)
    }

    @Test
    fun setMessage() {
        val c = AutoReplyLogic.parse("auto reply ka message: Main meeting mein hun, baad mein call karta hun")
        assertTrue(c is AutoReplyCommand.SetMessage)
        assertEquals("Main meeting mein hun, baad mein call karta hun", (c as AutoReplyCommand.SetMessage).text)
    }

    @Test
    fun addAndRemoveContacts() {
        val a = AutoReplyLogic.parse("auto reply mein Rahul ko jodo")
        assertTrue(a is AutoReplyCommand.AddContact)
        assertEquals("Rahul", (a as AutoReplyCommand.AddContact).name)
        val r = AutoReplyLogic.parse("auto reply se Rahul ko hata do")
        assertTrue(r is AutoReplyCommand.RemoveContact)
        assertEquals("Rahul", (r as AutoReplyCommand.RemoveContact).name)
        assertEquals("*", (AutoReplyLogic.parse("auto reply sabko jodo") as AutoReplyCommand.AddContact).name)
        assertEquals("Rahul Sharma", (AutoReplyLogic.parse("auto reply mein Rahul Sharma ko add karo") as AutoReplyCommand.AddContact).name)
        assertTrue(AutoReplyLogic.parse("auto reply log dikhao") is AutoReplyCommand.ShowLog)
    }

    @Test
    fun listStatusLog() {
        assertTrue(AutoReplyLogic.parse("auto reply list") is AutoReplyCommand.ListContacts)
        assertTrue(AutoReplyLogic.parse("auto reply on hai kya") is AutoReplyCommand.Status)
        assertTrue(AutoReplyLogic.parse("auto reply ne kya bheja") is AutoReplyCommand.ShowLog)
    }

    @Test
    fun otherSentencesAreIgnored() {
        assertNull(AutoReplyLogic.parse("Rahul ko message bhejo hello"))
        assertNull(AutoReplyLogic.parse("aaj mausam kaisa hai"))
        assertNull(AutoReplyLogic.parse("reply karo"))
    }

    @Test
    fun senderMatching() {
        assertTrue(AutoReplyLogic.senderMatches("Rahul Sharma", listOf("rahul"), false))
        assertFalse(AutoReplyLogic.senderMatches("Rahula", listOf("rahul"), false))
        assertFalse(AutoReplyLogic.senderMatches("Priya", listOf("rahul"), false))
        assertTrue(AutoReplyLogic.senderMatches("Priya", emptyList(), true))
        assertFalse(AutoReplyLogic.senderMatches("", listOf("rahul"), true))
    }

    private fun draft(
        enabled: Boolean = true, group: Boolean = false, secret: Boolean = false, action: Boolean = true,
        last: Long? = null, now: Long = 10_000_000L, perHour: Int = 0, sender: String = "Rahul"
    ) = AutoReplyLogic.shouldDraft(enabled, false, listOf("rahul"), sender, group, secret, action, last, now, perHour)

    @Test
    fun draftDecision() {
        assertTrue(draft())
        assertFalse(draft(enabled = false))
        assertFalse(draft(group = true))
        assertFalse(draft(secret = true))
        assertFalse(draft(action = false))
        assertFalse(draft(sender = "Priya"))
    }

    @Test
    fun cooldownAndHourlyLimit() {
        val now = 10_000_000L
        assertFalse(draft(last = now - 1000L, now = now))
        assertTrue(draft(last = now - AutoReplyLogic.COOLDOWN_MS - 1, now = now))
        assertFalse(draft(perHour = AutoReplyLogic.MAX_DRAFTS_PER_HOUR))
    }
}
