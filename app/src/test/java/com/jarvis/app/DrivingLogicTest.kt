package com.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingLogicTest {

    @Test
    fun turnOnPhrases() {
        assertEquals(DrivingCommand.ON, DrivingLogic.parse("driving mode chalu karo"))
        assertEquals(DrivingCommand.ON, DrivingLogic.parse("driving mode on"))
        assertEquals(DrivingCommand.ON, DrivingLogic.parse("gaadi chala raha hun"))
        assertEquals(DrivingCommand.ON, DrivingLogic.parse("main drive kar rahi hun"))
        assertEquals(DrivingCommand.ON, DrivingLogic.parse("I'm driving"))
    }

    @Test
    fun turnOffPhrases() {
        assertEquals(DrivingCommand.OFF, DrivingLogic.parse("driving mode band karo"))
        assertEquals(DrivingCommand.OFF, DrivingLogic.parse("driving mode off"))
        assertEquals(DrivingCommand.OFF, DrivingLogic.parse("drive khatam ho gaya"))
    }

    @Test
    fun statusQuestion() {
        assertEquals(DrivingCommand.STATUS, DrivingLogic.parse("driving mode on hai kya?"))
        assertEquals(DrivingCommand.STATUS, DrivingLogic.parse("driving mode status"))
    }

    @Test
    fun otherSentencesAreIgnored() {
        assertNull(DrivingLogic.parse("aaj mausam kaisa hai"))
        assertNull(DrivingLogic.parse("gaadi ka insurance kab khatam hota hai"))
        assertNull(DrivingLogic.parse("driving license kaise banega"))
        assertNull(DrivingLogic.parse("driving mode kya hota hai"))
    }

    @Test
    fun speakLineFormat() {
        assertEquals("WhatsApp par Rahul ka message: kal milte hain", DrivingLogic.speakLine("WhatsApp", "Rahul", "kal milte hain"))
        assertEquals("WhatsApp par naya message: hello", DrivingLogic.speakLine("WhatsApp", "", "hello"))
    }

    @Test
    fun secretsAndEmptyAreNeverSpoken() {
        assertNull(DrivingLogic.speakLine("Messages", "VM-HDFC", "Your OTP is 123456"))
        assertNull(DrivingLogic.speakLine("Messages", "x", NotificationLogic.SECRET_TEXT))
        assertNull(DrivingLogic.speakLine("WhatsApp", "Rahul", "   "))
    }

    @Test
    fun longMessagesAreClipped() {
        val line = DrivingLogic.speakLine("WhatsApp", "Rahul", "a".repeat(300))!!
        assertTrue(line.length < 200)
        assertTrue(line.endsWith("..."))
    }

    @Test
    fun promptLineMentionsShortAnswers() {
        assertTrue(DrivingLogic.PROMPT_LINE.contains("chhota"))
    }
}
