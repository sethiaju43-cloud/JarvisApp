package com.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

class ToolExtrasTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int, tz: TimeZone): Long {
        val c = GregorianCalendar(tz)
        c.clear()
        c.set(y, mo, d, h, mi)
        return c.timeInMillis
    }

    @Test
    fun timeParsesBothSeparators() {
        val want = ms(2026, Calendar.NOVEMBER, 26, 10, 30, utc)
        assertEquals(want, ToolTime.parse("2026-11-26 10:30", utc))
        assertEquals(want, ToolTime.parse("2026-11-26T10:30", utc))
        assertEquals(want, ToolTime.parse("  2026-11-26 10:30  ", utc))
    }

    @Test
    fun timeUsesGivenTimezone() {
        val ist = TimeZone.getTimeZone("Asia/Kolkata")
        val a = ToolTime.parse("2026-11-26 10:30", ist)!!
        val b = ToolTime.parse("2026-11-26 10:30", utc)!!
        assertEquals(5L * 3600_000L + 30L * 60_000L, b - a)
    }

    @Test
    fun timeRejectsImpossibleOrJunk() {
        assertNull(ToolTime.parse("2026-02-30 10:00", utc))
        assertNull(ToolTime.parse("2026-13-01 10:00", utc))
        assertNull(ToolTime.parse("2026-11-26 25:00", utc))
        assertNull(ToolTime.parse("kal subah", utc))
        assertNull(ToolTime.parse("", utc))
        assertNull(ToolTime.parse("2026-11-26 10:30 extra", utc))
    }

    @Test
    fun toolRejectionDetection() {
        assertTrue(ToolCapability.isToolRejection(ErrKind.BAD_REQUEST, "This model does not support tools"))
        assertTrue(ToolCapability.isToolRejection(ErrKind.BAD_REQUEST, "Function calling is not enabled for this model"))
        assertTrue(ToolCapability.isToolRejection(ErrKind.BAD_REQUEST, "tool_choice is not supported"))
        assertFalse(ToolCapability.isToolRejection(ErrKind.BAD_REQUEST, "Invalid temperature"))
        // doosre error kind (rate limit, auth, network) ko "tools nahi samajhta" mat maano
        assertFalse(ToolCapability.isToolRejection(ErrKind.RATE_LIMIT, "tool quota exceeded"))
        assertFalse(ToolCapability.isToolRejection(ErrKind.AUTH, "function key invalid"))
        assertFalse(ToolCapability.isToolRejection(ErrKind.NETWORK, "tool"))
    }

    @Test
    fun capabilityMarkExpiresAfterADay() {
        val now = 10L * 24L * 3600_000L
        assertTrue(ToolCapability.isMarked((now - 1000L).toString(), now))
        assertTrue(ToolCapability.isMarked((now - ToolCapability.TTL_MS + 1L).toString(), now))
        assertFalse(ToolCapability.isMarked((now - ToolCapability.TTL_MS).toString(), now))
        assertFalse(ToolCapability.isMarked("", now))
        assertFalse(ToolCapability.isMarked("abc", now))
        // future ka time (clock badla) mark nahi maana jata
        assertFalse(ToolCapability.isMarked((now + 5000L).toString(), now))
    }
}
