package com.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ReminderLogicTest {

    private val tz = TimeZone.getTimeZone("UTC")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
        val c = Calendar.getInstance(tz)
        c.clear()
        c.set(y, mo - 1, d, h, mi, 0)
        return c.timeInMillis
    }

    // Aaj: 11 Nov 2026, subah 10:00 (UTC)
    private val now = at(2026, 11, 11, 10, 0)

    private fun add(text: String): ReminderCommand.Add {
        val r = ReminderLogic.parse(text, now, tz)
        assertTrue("Add nahi mila: $text", r is ReminderCommand.Add)
        return r as ReminderCommand.Add
    }

    @Test
    fun fiveBajeWithoutAmPmPicksNextFutureTime() {
        val a = add("5 baje yaad dilana dawai leni hai")
        assertEquals(at(2026, 11, 11, 17, 0), a.whenMs)
        assertEquals("dawai leni hai", a.message)
        assertFalse(a.repeatDaily)
    }

    @Test
    fun relativeMinutes() {
        val a = add("10 minute baad chai band karne ko yaad dilana")
        assertEquals(now + 10 * 60_000L, a.whenMs)
        assertEquals("chai band karne ko", a.message)
    }

    @Test
    fun tomorrowMorning() {
        val a = add("kal subah 8 baje yaad dilana ki dawai leni hai")
        assertEquals(at(2026, 11, 12, 8, 0), a.whenMs)
        assertEquals("dawai leni hai", a.message)
    }

    @Test
    fun dailyReminder() {
        val a = add("roz subah 7 baje reminder lagao yoga karna")
        assertTrue(a.repeatDaily)
        assertEquals(at(2026, 11, 12, 7, 0), a.whenMs)
        assertEquals("yoga karna", a.message)
    }

    @Test
    fun englishRelative() {
        val a = add("remind me in 2 hours to call mom")
        assertEquals(now + 2 * 3_600_000L, a.whenMs)
        assertEquals("call mom", a.message)
    }

    @Test
    fun englishAbsolute() {
        val a = add("remind me at 6:30 pm to buy milk")
        assertEquals(at(2026, 11, 11, 18, 30), a.whenMs)
        assertEquals("buy milk", a.message)
    }

    @Test
    fun eveningAndNight() {
        assertEquals(at(2026, 11, 11, 18, 0), add("shaam 6 baje meeting yaad dilao").whenMs)
        assertEquals(at(2026, 11, 11, 22, 0), add("raat 10 baje yaad dila do ki darwaza band karna").whenMs)
    }

    @Test
    fun tomorrowThreeIsAfternoon() {
        assertEquals(at(2026, 11, 12, 15, 0), add("kal 3 baje reminder set karo doctor").whenMs)
    }

    @Test
    fun halfAnHourHindi() {
        val a = add("mujhe aadha ghanta baad paani peene ko yaad dila do")
        assertEquals(now + 30 * 60_000L, a.whenMs)
        assertEquals("paani peene ko", a.message)
    }

    @Test
    fun twentyFourHourClock() {
        val a = add("17:45 par yaad dila do match dekhna")
        assertEquals(at(2026, 11, 11, 17, 45), a.whenMs)
        assertEquals("match dekhna", a.message)
    }

    @Test
    fun missingTimeAsksBack() {
        val r = ReminderLogic.parse("yaad dilana dawai leni hai", now, tz)
        assertTrue(r is ReminderCommand.MissingTime)
        assertEquals("dawai leni hai", (r as ReminderCommand.MissingTime).message)
    }

    @Test
    fun emptyMessageGetsDefault() {
        assertEquals(ReminderLogic.DEFAULT_MESSAGE, add("yaad dila do 5 minute baad").message)
    }

    @Test
    fun showAndCancelCommands() {
        assertTrue(ReminderLogic.parse("mere reminders batao", now, tz) is ReminderCommand.ShowAll)
        assertTrue(ReminderLogic.parse("sab reminder hata do", now, tz) is ReminderCommand.CancelAll)
        val c = ReminderLogic.parse("chai wala reminder hata do", now, tz)
        assertTrue(c is ReminderCommand.Cancel)
        assertEquals("chai", (c as ReminderCommand.Cancel).about)
    }

    @Test
    fun otherSentencesAreNotReminders() {
        assertNull(ReminderLogic.parse("alarm lagao 5 baje", now, tz))
        assertNull(ReminderLogic.parse("yaad rakho ki main Delhi mein rehta hun", now, tz))
        assertNull(ReminderLogic.parse("aaj mausam kaisa hai", now, tz))
    }

    @Test
    fun describeShowsDayAndTime() {
        assertEquals("aaj 5:00 PM", ReminderLogic.describe(at(2026, 11, 11, 17, 0), now, tz))
        assertEquals("kal 8:30 AM", ReminderLogic.describe(at(2026, 11, 12, 8, 30), now, tz))
        assertEquals("15/11 12:05 AM", ReminderLogic.describe(at(2026, 11, 15, 0, 5), now, tz))
    }

    @Test
    fun nextDailyIsOneDayLater() {
        assertEquals(at(2026, 11, 13, 7, 0), ReminderLogic.nextDaily(at(2026, 11, 12, 7, 0), tz))
    }
}
