package com.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class BriefLogicTest {

    private val tz = TimeZone.getTimeZone("UTC")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
        val c = Calendar.getInstance(tz)
        c.clear()
        c.set(y, mo - 1, d, h, mi, 0)
        return c.timeInMillis
    }

    private fun on(text: String): BriefCommand.On {
        val c = BriefLogic.parse(text)
        assertTrue("On nahi mila: $text", c is BriefCommand.On)
        return c as BriefCommand.On
    }

    @Test
    fun turnOnWithTime() {
        val a = on("roz subah 8 baje daily brief chalu karo")
        assertEquals(8, a.hour)
        assertEquals(0, a.minute)
        assertEquals(19, on("roz shaam 7 baje brief laga do").hour)
        assertEquals(7, on("subah 7:30 ka brief chahiye").hour)
        assertEquals(30, on("subah 7:30 ka brief chahiye").minute)
        assertEquals(21, on("daily brief 9 pm chalu karo").hour)
    }

    @Test
    fun turnOnWithoutTimeUsesDefaults() {
        val m = on("daily brief chalu karo")
        assertEquals(8, m.hour)
        assertEquals(19, on("evening brief roz chalu karo").hour)
    }

    @Test
    fun offStatusAndShowNow() {
        assertTrue(BriefLogic.parse("daily brief band karo") is BriefCommand.Off)
        assertTrue(BriefLogic.parse("daily brief on hai kya") is BriefCommand.Status)
        assertTrue(BriefLogic.parse("aaj ka brief") is BriefCommand.ShowNow)
        assertTrue(BriefLogic.parse("mera brief sunao") is BriefCommand.ShowNow)
    }

    @Test
    fun otherSentencesAreIgnored() {
        assertNull(BriefLogic.parse("is paragraph ka brief mein summary do"))
        assertNull(BriefLogic.parse("aaj mausam kaisa hai"))
        assertNull(BriefLogic.parse("brief"))
    }

    @Test
    fun formatAndNextTrigger() {
        assertEquals("8:00 AM", BriefLogic.formatTime(8, 0))
        assertEquals("12:05 AM", BriefLogic.formatTime(0, 5))
        assertEquals("7:30 PM", BriefLogic.formatTime(19, 30))
        val now = at(2026, 11, 15, 10, 0)
        assertEquals(at(2026, 11, 16, 8, 0), BriefLogic.nextTriggerMs(8, 0, now, tz))
        assertEquals(at(2026, 11, 15, 19, 0), BriefLogic.nextTriggerMs(19, 0, now, tz))
    }

    @Test
    fun composeListsTodayRemindersAndTodos() {
        val now = at(2026, 11, 15, 8, 0)
        val rem = listOf(
            Pair(at(2026, 11, 15, 17, 0), "dawai leni hai"),
            Pair(at(2026, 11, 15, 7, 0), "purana, nikal gaya"),
            Pair(at(2026, 11, 16, 9, 0), "kal ka kaam")
        )
        val t = BriefLogic.compose(rem, listOf("dudh lana", "bijli ka bill"), now, tz)
        assertTrue(t.startsWith("Good morning"))
        assertTrue(t.contains("5:00 PM - dawai leni hai"))
        assertTrue(!t.contains("nikal gaya"))
        assertTrue(!t.contains("kal ka kaam"))
        assertTrue(t.contains("Baaki kaam (2)"))
        assertTrue(t.contains("dudh lana"))
    }

    @Test
    fun composeWhenNothingToDo() {
        val t = BriefLogic.compose(emptyList(), emptyList(), at(2026, 11, 15, 20, 0), tz)
        assertTrue(t.startsWith("Good evening"))
        assertTrue(t.contains("koi reminder ya baaki kaam nahi"))
    }

    @Test
    fun composeLimitsLongLists() {
        val now = at(2026, 11, 15, 8, 0)
        val rem = (1..6).map { Pair(at(2026, 11, 15, 9 + it, 0), "r$it") }
        val todos = (1..8).map { "kaam $it" }
        val t = BriefLogic.compose(rem, todos, now, tz)
        assertTrue(t.contains("aur 2 reminder"))
        assertTrue(t.contains("aur 3 kaam"))
    }
}
