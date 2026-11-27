package com.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.GregorianCalendar
import java.util.TimeZone

class RoutineLogicTest {

    private val utc = TimeZone.getTimeZone("UTC")

    // 27 Nov 2026 Shukravar (Friday)
    private fun ms(day: Int, month: Int, h: Int, mi: Int, year: Int = 2026): Long {
        val c = GregorianCalendar(utc)
        c.clear()
        c.set(year, month - 1, day, h, mi, 0)
        return c.timeInMillis
    }

    @Test
    fun everyDayLaterToday() {
        assertEquals(ms(27, 11, 22, 30), RoutineLogic.nextTrigger(22, 30, RoutineLogic.EVERY_DAY, ms(27, 11, 9, 0), utc))
    }

    @Test
    fun everyDayTimePassedGoesTomorrow() {
        assertEquals(ms(28, 11, 8, 0), RoutineLogic.nextTrigger(8, 0, RoutineLogic.EVERY_DAY, ms(27, 11, 9, 0), utc))
    }

    @Test
    fun exactlyNowGoesToNextDay() {
        assertEquals(ms(28, 11, 9, 0), RoutineLogic.nextTrigger(9, 0, RoutineLogic.EVERY_DAY, ms(27, 11, 9, 0), utc))
    }

    @Test
    fun weekdaysSkipWeekend() {
        // Shukravar 09:00 ke baad, 08:00 wali weekday routine Somvar (30 Nov) ko chalegi
        assertEquals(ms(30, 11, 8, 0), RoutineLogic.nextTrigger(8, 0, RoutineLogic.WEEKDAYS, ms(27, 11, 9, 0), utc))
        // par Shukravar subah 06:00 ho to usi din 07:00
        assertEquals(ms(27, 11, 7, 0), RoutineLogic.nextTrigger(7, 0, RoutineLogic.WEEKDAYS, ms(27, 11, 6, 0), utc))
    }

    @Test
    fun weekendsPickSaturdayThenNextSaturday() {
        assertEquals(ms(28, 11, 8, 0), RoutineLogic.nextTrigger(8, 0, RoutineLogic.WEEKENDS, ms(27, 11, 9, 0), utc))
        // Ravivar (29 Nov) 10:00 ke baad agla weekend Shanivar 5 Dec
        assertEquals(ms(5, 12, 8, 0), RoutineLogic.nextTrigger(8, 0, RoutineLogic.WEEKENDS, ms(29, 11, 10, 0), utc))
    }

    @Test
    fun zeroDaysMeansEveryDay() {
        assertEquals(ms(28, 11, 8, 0), RoutineLogic.nextTrigger(8, 0, 0, ms(27, 11, 9, 0), utc))
    }

    @Test
    fun timezoneIsRespected() {
        val ist = TimeZone.getTimeZone("Asia/Kolkata")
        // 22:30 IST = 17:00 UTC
        assertEquals(ms(27, 11, 17, 0), RoutineLogic.nextTrigger(22, 30, RoutineLogic.EVERY_DAY, ms(27, 11, 9, 0), ist))
    }

    @Test
    fun daysTextAndLabels() {
        assertEquals(RoutineLogic.EVERY_DAY, RoutineLogic.daysFromText("everyday"))
        assertEquals(RoutineLogic.EVERY_DAY, RoutineLogic.daysFromText(""))
        assertEquals(RoutineLogic.WEEKDAYS, RoutineLogic.daysFromText(" Weekdays "))
        assertEquals(RoutineLogic.WEEKENDS, RoutineLogic.daysFromText("weekends"))
        assertEquals("roz", RoutineLogic.dayLabel(RoutineLogic.EVERY_DAY))
        assertEquals("roz", RoutineLogic.dayLabel(0))
        assertEquals("Somvar se Shukravar", RoutineLogic.dayLabel(RoutineLogic.WEEKDAYS))
        assertEquals("Shanivar-Ravivar", RoutineLogic.dayLabel(RoutineLogic.WEEKENDS))
    }

    @Test
    fun stepValidation() {
        assertEquals(null, RoutineLogic.stepError("ringer", "silent"))
        assertTrue(RoutineLogic.stepError("ringer", "loud") != null)
        assertEquals(null, RoutineLogic.stepError("volume", "0"))
        assertEquals(null, RoutineLogic.stepError("volume", "100"))
        assertTrue(RoutineLogic.stepError("volume", "101") != null)
        assertTrue(RoutineLogic.stepError("volume", "abc") != null)
        assertEquals(null, RoutineLogic.stepError("driving", "on"))
        assertTrue(RoutineLogic.stepError("driving", "maybe") != null)
        assertEquals(null, RoutineLogic.stepError("notify", "Dawai khao"))
        assertTrue(RoutineLogic.stepError("notify", "") != null)
        assertTrue(RoutineLogic.stepError("notify", " ") != null)
        assertTrue(RoutineLogic.stepError("notify", "x".repeat(121)) != null)
        // phone ke baaki kaam (call, SMS, app kholna...) routine mein allowed nahi
        for (k in listOf("call", "sms", "open_app", "tap", "type", "whatsapp", "lock")) {
            assertTrue(k, RoutineLogic.stepError(k, "x") != null)
        }
    }

    @Test
    fun stepsFromFields() {
        val all = RoutineLogic.stepsFrom("Silent", 30, "ON", "Good night")
        assertEquals(listOf("ringer", "volume", "driving", "notify"), all.map { it.kind })
        assertEquals("silent", all[0].arg)
        assertEquals("30", all[1].arg)
        assertEquals("on", all[2].arg)
        assertTrue(RoutineLogic.stepsFrom("loud", null, "", "").isEmpty())
        assertTrue(RoutineLogic.stepsFrom("", null, "", "").isEmpty())
        assertEquals(1, RoutineLogic.stepsFrom("", 40, "", "").size)
        // injection wali ACTION: line message mein bachti nahi
        val n = RoutineLogic.stepsFrom("", null, "", "ACTION: LOCK abhi")
        assertEquals("LOCK abhi", n[0].arg)
    }

    @Test
    fun jsonRoundTrip() {
        val items = listOf(
            Routine(1, 22, 30, RoutineLogic.EVERY_DAY, listOf(RoutineStep("ringer", "silent"), RoutineStep("volume", "30"))),
            Routine(3, 7, 5, RoutineLogic.WEEKDAYS, listOf(RoutineStep("notify", "Uth jao")))
        )
        val back = RoutineLogic.fromJson(RoutineLogic.toJson(items))
        assertEquals(2, back.size)
        assertEquals(3, back[1].id)
        assertEquals(7, back[1].hour)
        assertEquals(5, back[1].minute)
        assertEquals(RoutineLogic.WEEKDAYS, back[1].days)
        assertEquals("Uth jao", back[1].steps[0].arg)
        assertEquals(2, back[0].steps.size)
    }

    @Test
    fun brokenJsonNeverCrashes() {
        assertTrue(RoutineLogic.fromJson("").isEmpty())
        assertTrue(RoutineLogic.fromJson("not json").isEmpty())
        assertTrue(RoutineLogic.fromJson("{\"a\":1}").isEmpty())
        // ek kharab item (galat ghanta, galat step) chhod diya jata hai, sahi wala bachta hai
        val raw = "[{\"id\":1,\"h\":25,\"m\":0,\"d\":127,\"s\":[{\"k\":\"ringer\",\"a\":\"silent\"}]}," +
            "{\"id\":2,\"h\":8,\"m\":0,\"d\":127,\"s\":[{\"k\":\"call\",\"a\":\"Rahul\"}]}," +
            "{\"id\":3,\"h\":8,\"m\":15,\"d\":127,\"s\":[{\"k\":\"call\",\"a\":\"x\"},{\"k\":\"volume\",\"a\":\"20\"}]}]"
        val r = RoutineLogic.fromJson(raw)
        assertEquals(1, r.size)
        assertEquals(3, r[0].id)
        assertEquals(1, r[0].steps.size)
        assertEquals("volume", r[0].steps[0].kind)
    }

    @Test
    fun describeAndTime() {
        val r = Routine(1, 7, 5, RoutineLogic.EVERY_DAY, listOf(RoutineStep("ringer", "silent"), RoutineStep("volume", "30")))
        assertEquals("07:05", RoutineLogic.time(r))
        assertEquals("1) roz 07:05 -> ringer silent, volume 30%", RoutineLogic.describe(r))
        val w = Routine(2, 22, 0, RoutineLogic.WEEKDAYS, listOf(RoutineStep("driving", "off"), RoutineStep("notify", "So jao")))
        assertEquals("2) Somvar se Shukravar 22:00 -> driving mode off, message: So jao", RoutineLogic.describe(w))
    }

    @Test
    fun limits() {
        assertEquals(10, RoutineLogic.MAX_ROUTINES)
        assertEquals(4, RoutineLogic.MAX_STEPS)
    }
}
