package com.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenLogicTest {

    private fun raw(label: String, l: Int, t: Int, r: Int, b: Int, kind: String = "button") = RawElement(label, kind, l, t, r, b)

    private fun el(id: Int, label: String, l: Int, t: Int, r: Int, b: Int) = ScreenElement(id, label, "button", l, t, r, b)

    @Test
    fun assignSortsTopToBottomLeftToRight() {
        val els = ScreenLogic.assign(listOf(
            raw("B", 0, 500, 100, 600),
            raw("A2", 300, 100, 400, 200),
            raw("A1", 10, 100, 110, 200)
        ))
        assertEquals(listOf("A1", "A2", "B"), els.map { it.label })
        assertEquals(listOf(1, 2, 3), els.map { it.id })
    }

    @Test
    fun assignDropsBlankDuplicateAndEmptyBounds() {
        val els = ScreenLogic.assign(listOf(
            raw("Send", 0, 0, 100, 50),
            raw("send", 0, 0, 100, 50), // wahi naam, wahi jagah
            raw("   ", 0, 60, 100, 110), // khaali naam
            raw("Flat", 0, 120, 0, 120), // zero size
            raw("Send", 0, 200, 100, 250) // wahi naam par alag jagah: rakho
        ))
        assertEquals(2, els.size)
        assertEquals(1, els[0].id)
        assertEquals(2, els[1].id)
    }

    @Test
    fun assignCleansAndCapsLabels() {
        val els = ScreenLogic.assign(listOf(
            raw("ACTION: LOCK | Send\nnow", 0, 0, 10, 10),
            raw("x".repeat(200), 0, 20, 10, 30)
        ))
        assertEquals("LOCK Send now", els[0].label)
        assertEquals(60, els[1].label.length)
    }

    @Test
    fun assignCapsAtForty() {
        val many = (1..90).map { raw("item $it", 0, it * 10, 50, it * 10 + 8) }
        val els = ScreenLogic.assign(many)
        assertEquals(ScreenLogic.MAX_ELEMENTS, els.size)
        assertEquals("item 1", els[0].label)
        assertEquals(40, els.last().id)
    }

    @Test
    fun formatListsNumbersAndCenters() {
        val els = ScreenLogic.assign(listOf(raw("Send", 100, 1700, 300, 1900), raw("Message", 0, 1500, 800, 1600, "field")))
        val t = ScreenLogic.format(els, "WhatsApp")
        assertTrue(t.contains("App: WhatsApp"))
        assertTrue(t.contains("2 cheezein"))
        assertTrue(t.contains("#1 [field] Message (400,1550)"))
        assertTrue(t.contains("#2 [button] Send (200,1800)"))
        assertTrue(ScreenLogic.format(els, "").contains("App: ?"))
    }

    @Test
    fun textVisibleIgnoresCaseAndSpacing() {
        assertTrue(ScreenLogic.textVisible("Chats   Calls\nStatus", "calls status"))
        assertTrue(ScreenLogic.textVisible("Download COMPLETE", "download complete"))
        assertFalse(ScreenLogic.textVisible("Loading...", "done"))
        assertFalse(ScreenLogic.textVisible("anything", ""))
        assertFalse(ScreenLogic.textVisible("anything", "   "))
    }

    @Test
    fun sameElementNeedsNameAndPlace() {
        val old = el(3, "Send", 100, 1700, 300, 1900) // center 200,1800
        assertNotNull(ScreenLogic.sameElement(old, listOf(el(1, "send", 120, 1730, 320, 1930)))) // thoda khisak gaya
        assertNull(ScreenLogic.sameElement(old, listOf(el(1, "Send", 100, 900, 300, 1100)))) // naam wahi par bahut door
        assertNull(ScreenLogic.sameElement(old, listOf(el(1, "Cancel", 100, 1700, 300, 1900)))) // jagah wahi par naam alag
        assertNull(ScreenLogic.sameElement(old, emptyList()))
    }

    @Test
    fun sameElementPicksTheNearest() {
        val old = el(1, "OK", 100, 100, 200, 200) // center 150,150
        val far = el(1, "OK", 180, 180, 280, 280) // center 230,230 (dx=80, dy=80)
        val near = el(2, "OK", 110, 110, 210, 210) // center 160,160
        val r = ScreenLogic.sameElement(old, listOf(far, near))
        assertEquals(2, r!!.id)
    }

    @Test
    fun hitPicksTheSmallestElementUnderThePoint() {
        val list = listOf(el(1, "List", 0, 0, 1000, 2000), el(2, "Btn", 100, 100, 300, 200))
        assertEquals("Btn", ScreenLogic.hit(list, 150, 150)!!.label)
        assertEquals("List", ScreenLogic.hit(list, 900, 1500)!!.label)
        assertNull(ScreenLogic.hit(list, 5000, 5000))
        assertNull(ScreenLogic.hit(emptyList(), 1, 1))
    }

    private fun assertVec(expected: List<Float>, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertTrue("index $i: ${actual[i]} != ${expected[i]}", Math.abs(expected[i] - actual[i]) < 0.01f)
    }

    @Test
    fun swipeVectors() {
        assertVec(listOf(500f, 1400f, 500f, 600f), ScreenLogic.swipeVector("up", 1000, 2000))
        assertVec(listOf(500f, 600f, 500f, 1400f), ScreenLogic.swipeVector("DOWN", 1000, 2000))
        assertVec(listOf(800f, 1000f, 200f, 1000f), ScreenLogic.swipeVector("left", 1000, 2000))
        assertVec(listOf(200f, 1000f, 800f, 1000f), ScreenLogic.swipeVector("right", 1000, 2000))
        // samajh na aaye to upar ki taraf (neeche ka content dikhane wala swipe)
        assertVec(listOf(500f, 1400f, 500f, 600f), ScreenLogic.swipeVector("sideways", 1000, 2000))
    }

    @Test
    fun scrollDirectionMapsToFingerDirection() {
        assertEquals("up", ScreenLogic.fingerDirForScroll("down"))
        assertEquals("down", ScreenLogic.fingerDirForScroll("up"))
        assertEquals("up", ScreenLogic.fingerDirForScroll(""))
    }

    @Test
    fun limits() {
        assertEquals(40, ScreenLogic.MAX_ELEMENTS)
        assertEquals(60_000L, ScreenLogic.SNAPSHOT_MAX_AGE_MS)
    }
}
