package com.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class LocalSkillsLogicTest {

    private val tz = TimeZone.getTimeZone("UTC")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
        val c = Calendar.getInstance(tz)
        c.clear()
        c.set(y, mo - 1, d, h, mi, 0)
        return c.timeInMillis
    }

    private fun calc(text: String): String {
        val c = LocalSkillsLogic.parse(text)
        assertTrue("Calc nahi mila: $text", c is LocalCmd.Calc)
        val v = CalcEngine.eval((c as LocalCmd.Calc).expr)
        return CalcEngine.format(v!!)
    }

    @Test
    fun timeAndDate() {
        val t = LocalSkillsLogic.parse("time kya hai") as LocalCmd.TimeDate
        assertTrue(t.time)
        assertTrue(!t.date)
        assertTrue((LocalSkillsLogic.parse("aaj ki date batao") as LocalCmd.TimeDate).date)
        assertTrue(LocalSkillsLogic.parse("kitne baje hain") is LocalCmd.TimeDate)
        val both = LocalSkillsLogic.timeDateReply(at(2026, 11, 16, 15, 45), tz, true, true)
        assertEquals("Abhi 3:45 PM hai ⏰ (Somvaar, 16 November 2026)", both)
        assertEquals("Aaj Somvaar, 16 November 2026 hai 📅", LocalSkillsLogic.timeDateReply(at(2026, 11, 16, 15, 45), tz, false, true))
        assertEquals("Abhi 12:05 AM hai ⏰", LocalSkillsLogic.timeDateReply(at(2026, 11, 16, 0, 5), tz, true, false))
    }

    @Test
    fun calculator() {
        assertEquals("12", calc("5 plus 7 kitna hota hai"))
        assertEquals("96", calc("12 guna 8 kitna"))
        assertEquals("4.5", calc("25 ka 18 percent kitna hota hai"))
        assertEquals("4.5", calc("18 percent of 25 kitna"))
        assertEquals("30", calc("(2 + 3) * 6 kitna hota hai"))
        assertEquals("2.5", calc("5 divided by 2 kitna"))
        assertEquals("7", calc("10 minus 3 barabar kitna"))
        assertEquals("15", calc("7+8="))
        assertEquals("1000000", calc("1,000 guna 1,000 kitna hota hai"))
    }

    @Test
    fun calculatorEdgeCases() {
        assertNull(CalcEngine.eval("5 +"))
        assertNull(CalcEngine.eval("abc"))
        assertTrue(CalcEngine.eval("5 / 0")!!.isInfinite())
        assertEquals("0.333333", CalcEngine.format(1.0 / 3.0))
        assertNull(LocalSkillsLogic.parse("5 baje 7 minute kitna"))
        assertNull(LocalSkillsLogic.parse("meri umar 25 hai aur bhai ki 30 batao"))
    }

    @Test
    fun media() {
        assertTrue(LocalSkillsLogic.parse("gaana roko") is LocalCmd.MediaPause)
        assertTrue(LocalSkillsLogic.parse("pause karo") is LocalCmd.MediaPause)
        assertTrue(LocalSkillsLogic.parse("agla gaana") is LocalCmd.MediaNext)
        assertTrue(LocalSkillsLogic.parse("next song") is LocalCmd.MediaNext)
        assertTrue(LocalSkillsLogic.parse("pichhla gaana") is LocalCmd.MediaPrev)
        assertTrue(LocalSkillsLogic.parse("gaana chalu karo") is LocalCmd.MediaPlay)
        assertTrue(LocalSkillsLogic.parse("resume") is LocalCmd.MediaPlay)
        assertTrue(LocalSkillsLogic.parse("kaun sa gaana chal raha hai") is LocalCmd.NowPlaying)
        // naam wala gaana pehle ki tarah AI/music wale raaste se jayega
        assertNull(LocalSkillsLogic.parse("gaana chalao tum hi ho"))
    }

    @Test
    fun ringerAndClipboard() {
        assertEquals("vibrate", (LocalSkillsLogic.parse("phone vibrate par karo") as LocalCmd.Ringer).mode)
        assertEquals("silent", (LocalSkillsLogic.parse("silent mode lagao") as LocalCmd.Ringer).mode)
        assertEquals("normal", (LocalSkillsLogic.parse("normal mode") as LocalCmd.Ringer).mode)
        assertTrue(LocalSkillsLogic.parse("clipboard mein kya hai") is LocalCmd.ClipRead)
        val w = LocalSkillsLogic.parse("copy karo: mera naya number") as LocalCmd.ClipWrite
        assertEquals("mera naya number", w.text)
        assertTrue(LocalSkillsLogic.isPrivate("clipboard padho"))
        assertTrue(!LocalSkillsLogic.isPrivate("time kya hai"))
    }

    @Test
    fun miscSkills() {
        assertTrue(LocalSkillsLogic.parse("mere alarms dikhao") is LocalCmd.ShowAlarms)
        assertTrue(LocalSkillsLogic.parse("timers dikhao") is LocalCmd.ShowTimers)
        assertTrue(LocalSkillsLogic.parse("battery optimization band karo") is LocalCmd.BatteryOpt)
        assertTrue(LocalSkillsLogic.parse("sikka uchhalo") is LocalCmd.Coin)
        assertTrue(LocalSkillsLogic.parse("dice phenko") is LocalCmd.Dice)
        assertTrue(LocalSkillsLogic.parse("tum kya kya kar sakti ho") is LocalCmd.Help)
        assertTrue(LocalSkillsLogic.parse("help") is LocalCmd.Help)
    }

    @Test
    fun otherSentencesAreIgnored() {
        assertNull(LocalSkillsLogic.parse("aaj mausam kaisa hai"))
        assertNull(LocalSkillsLogic.parse("YouTube kholo"))
        assertNull(LocalSkillsLogic.parse("mujhe bahut udaas lag raha hai"))
        assertNull(LocalSkillsLogic.parse("timer lagao 5 minute ka"))
    }
}
