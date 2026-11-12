package com.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoLogicTest {

    private fun add(text: String): String {
        val c = TodoLogic.parse(text)
        assertTrue("Add nahi mila: $text", c is TodoCommand.Add)
        return (c as TodoCommand.Add).text
    }

    @Test
    fun addVariants() {
        assertEquals("dudh lana", add("todo mein dudh lana jodo"))
        assertEquals("dudh lana", add("dudh lana todo mein daalo"))
        assertEquals("bijli ka bill bharna", add("to-do list mein bijli ka bill bharna add karo"))
        assertEquals("kal bank jana hai", add("todo mein likho ki kal bank jana hai"))
        assertEquals("report bhejna", add("task add karo: report bhejna"))
        assertEquals("buy milk", add("add buy milk to my todo"))
    }

    @Test
    fun missingText() {
        assertTrue(TodoLogic.parse("todo add karo") is TodoCommand.MissingText)
    }

    @Test
    fun showCommands() {
        assertTrue(TodoLogic.parse("mere todo batao") is TodoCommand.ShowAll)
        assertTrue(TodoLogic.parse("todo list dikhao") is TodoCommand.ShowAll)
        assertTrue(TodoLogic.parse("kitne tasks baaki hain") is TodoCommand.ShowAll)
    }

    @Test
    fun doneAndRemove() {
        val d = TodoLogic.parse("dudh wala todo ho gaya")
        assertTrue(d is TodoCommand.Done)
        assertEquals("dudh wala", (d as TodoCommand.Done).about)
        val r = TodoLogic.parse("dudh wala todo hata do")
        assertTrue(r is TodoCommand.Remove)
        assertEquals("dudh wala", (r as TodoCommand.Remove).about)
        assertTrue(TodoLogic.parse("gym jana todo mein done karo") is TodoCommand.Done)
    }

    @Test
    fun clearAll() {
        assertTrue(TodoLogic.parse("sab todo hata do") is TodoCommand.ClearAll)
        assertTrue(TodoLogic.parse("sab tasks clear karo") is TodoCommand.ClearAll)
    }

    @Test
    fun otherSentencesAreIgnored() {
        assertNull(TodoLogic.parse("task manager kholo"))
        assertNull(TodoLogic.parse("aaj ka kaam kya hai"))
        assertNull(TodoLogic.parse("mujhe 5 baje yaad dilana"))
    }
}
