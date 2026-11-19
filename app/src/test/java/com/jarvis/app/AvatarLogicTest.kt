package com.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarLogicTest {

    @Test
    fun catalogHasUniqueIds() {
        assertEquals(28, AvatarLogic.all.size)
        assertEquals(AvatarLogic.all.size, AvatarLogic.all.map { it.id }.toSet().size)
    }

    @Test
    fun findByKeyword() {
        assertEquals("ninja", AvatarLogic.find("ninja chahiye")!!.id)
        assertEquals("cat_with_wry_smile", AvatarLogic.find("billi")!!.id)
        assertEquals("person_mage", AvatarLogic.find("jaadugar wala")!!.id)
        assertEquals("woman_technologist", AvatarLogic.find("techie ladki")!!.id)
        assertEquals("technologist", AvatarLogic.find("techie")!!.id)
        assertNull(AvatarLogic.find("kuch nahi"))
    }

    @Test
    fun setCommands() {
        val c = AvatarLogic.parse("avatar ninja lagao")
        assertTrue(c is AvatarCommand.Set)
        assertEquals("ninja", (c as AvatarCommand.Set).id)
        assertEquals("robot", (AvatarLogic.parse("Jarvi ka chehra robot kar do") as AvatarCommand.Set).id)
    }

    @Test
    fun resetAndList() {
        assertTrue(AvatarLogic.parse("avatar wapas purana kar do") is AvatarCommand.Reset)
        assertTrue(AvatarLogic.parse("avatar list dikhao") is AvatarCommand.ShowList)
        assertTrue(AvatarLogic.parse("avatar badlo") is AvatarCommand.ShowList)
    }

    @Test
    fun otherSentencesAreIgnored() {
        assertNull(AvatarLogic.parse("ninja ke baare mein batao"))
        assertNull(AvatarLogic.parse("aaj mausam kaisa hai"))
        assertNotNull(AvatarLogic.labelOf("robot"))
    }
}
