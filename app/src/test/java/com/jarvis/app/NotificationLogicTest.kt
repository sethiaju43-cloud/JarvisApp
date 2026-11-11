package com.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NotificationLogicTest {

    @Before
    fun clean() {
        NotificationStore.clear()
    }

    @Test
    fun bankAndPaymentAppsAreExcluded() {
        assertTrue(NotificationLogic.isExcludedApp("com.phonepe.app"))
        assertTrue(NotificationLogic.isExcludedApp("com.sbi.bank.app"))
        assertTrue(NotificationLogic.isExcludedApp("com.bitwarden.app"))
        assertFalse(NotificationLogic.isExcludedApp("com.whatsapp"))
    }

    @Test
    fun otpAndCodesAreDetected() {
        assertTrue(NotificationLogic.looksSecret("VM-HDFC", "Your OTP is 123456"))
        assertTrue(NotificationLogic.looksSecret("Bank", "Use 482913 as your verification code"))
        assertFalse(NotificationLogic.looksSecret("Rahul", "kal milte hain"))
        assertFalse(NotificationLogic.looksSecret("Amazon", "Order 123456 shipped"))
    }

    @Test
    fun makeHidesSecretsAndSkipsBanks() {
        assertNull(NotificationLogic.make("k1", "com.phonepe.app", "PhonePe", "Paid", "Rs 10", 1L))
        val s = NotificationLogic.make("k2", "com.android.mms", "Messages", "VM-HDFC", "Your OTP is 123456", 1L)
        assertNotNull(s)
        assertEquals(NotificationLogic.SECRET_TEXT, s!!.text)
        assertEquals("", s.title)
        assertNull(NotificationLogic.make("k3", "com.whatsapp", "WhatsApp", " ", "  ", 1L))
    }

    @Test
    fun commandsAreDetected() {
        assertEquals(NotificationLogic.Command.READ, NotificationLogic.parseCommand("notification padho"))
        assertEquals(NotificationLogic.Command.READ, NotificationLogic.parseCommand("koi naya message aaya?"))
        assertEquals(NotificationLogic.Command.CLEAR, NotificationLogic.parseCommand("sab notifications saaf karo"))
        assertNull(NotificationLogic.parseCommand("notification dikhao"))
        assertNull(NotificationLogic.parseCommand("aaj mausam kaisa hai"))
    }

    @Test
    fun speakListsNewestFirstAndLimits() {
        val items = (1..7).map {
            NotificationLogic.make("k$it", "com.whatsapp", "WhatsApp", "Dost $it", "message $it", it.toLong())!!
        }
        val t = NotificationLogic.speak(items, 3)
        assertTrue(t.contains("7 notification"))
        assertTrue(t.indexOf("Dost 7") < t.indexOf("Dost 6"))
        assertFalse(t.contains("Dost 1"))
        assertTrue(t.contains("Aur 4 notification baaki"))
    }

    @Test
    fun emptyListMessage() {
        assertTrue(NotificationLogic.speak(emptyList()).contains("koi naya notification nahi"))
    }

    @Test
    fun storeDedupesRemovesAndExpires() {
        val now = 10L * NotificationStore.MAX_AGE_MS
        NotificationStore.put(NotificationLogic.make("a", "com.whatsapp", "WhatsApp", "A", "x", now)!!)
        NotificationStore.put(NotificationLogic.make("a", "com.whatsapp", "WhatsApp", "A2", "y", now)!!)
        NotificationStore.put(NotificationLogic.make("old", "com.whatsapp", "WhatsApp", "Old", "z", now - NotificationStore.MAX_AGE_MS - 1)!!)
        assertEquals(1, NotificationStore.recent(now).size)
        assertEquals("A2", NotificationStore.recent(now)[0].title)
        NotificationStore.remove("a")
        assertEquals(0, NotificationStore.recent(now).size)
    }
}
