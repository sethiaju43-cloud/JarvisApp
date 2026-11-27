package com.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceEventLogicTest {

    private fun say(d: GateDecision): String {
        assertTrue("Say chahiye tha", d is GateDecision.Say)
        return (d as GateDecision.Say).text
    }

    private fun skip(d: GateDecision): String {
        assertTrue("Skip chahiye tha", d is GateDecision.Skip)
        return (d as GateDecision.Skip).reason
    }

    private fun ev(k: EventKind, detail: String = "", pct: Int = -1) = DeviceEvent(k, detail, pct)

    // ---------- settings ----------

    @Test
    fun groupWords() {
        assertEquals("charging", DeviceEventLogic.groupFromText("Charging"))
        assertEquals("charging", DeviceEventLogic.groupFromText("charger"))
        assertEquals("power_saver", DeviceEventLogic.groupFromText("power saver"))
        assertEquals("power_saver", DeviceEventLogic.groupFromText("battery-saver"))
        assertEquals("headset", DeviceEventLogic.groupFromText("earphones"))
        assertEquals("all", DeviceEventLogic.groupFromText("sab"))
        assertEquals("airplane", DeviceEventLogic.groupFromText("flight mode"))
        assertNull(DeviceEventLogic.groupFromText("wifi hotspot"))
        assertNull(DeviceEventLogic.groupFromText(""))
    }

    @Test
    fun applyAllAndGroups() {
        val off = EventSettings(false, emptySet(), false, 20)
        val all = DeviceEventLogic.apply(off, "all", true)
        assertTrue(all.master)
        assertEquals(DeviceEventLogic.GROUPS.toSet(), all.groups)

        val one = DeviceEventLogic.apply(off, "charging", true)
        assertTrue(one.master)
        assertEquals(setOf("charging"), one.groups)

        val two = DeviceEventLogic.apply(one, "battery", true)
        assertEquals(setOf("charging", "battery"), two.groups)

        val minus = DeviceEventLogic.apply(two, "charging", false)
        assertTrue(minus.master)
        assertEquals(setOf("battery"), minus.groups)

        // aakhri group hatate hi master band
        val none = DeviceEventLogic.apply(minus, "battery", false)
        assertFalse(none.master)
        assertTrue(none.groups.isEmpty())

        // "all" off: master band, par group yaad rahe
        val allOff = DeviceEventLogic.apply(all, "all", false)
        assertFalse(allOff.master)
        assertEquals(all.groups, allOff.groups)
    }

    @Test
    fun speakAndLowAreKept() {
        val s = EventSettings(true, setOf("battery"), true, 30)
        val t = DeviceEventLogic.apply(s, "charging", true)
        assertTrue(t.speak)
        assertEquals(30, t.lowPct)
    }

    @Test
    fun isOnNeedsMasterAndGroup() {
        val s = EventSettings(true, setOf("charging"), false, 20)
        assertTrue(DeviceEventLogic.isOn(s, EventKind.CHARGER_ON))
        assertFalse(DeviceEventLogic.isOn(s, EventKind.BATTERY_LOW))
        assertFalse(DeviceEventLogic.isOn(EventSettings(false, setOf("charging"), false, 20), EventKind.CHARGER_ON))
    }

    @Test
    fun groupsEncodeDecode() {
        assertEquals("charging,storage", DeviceEventLogic.encodeGroups(setOf("storage", "charging")))
        assertEquals(setOf("charging", "storage"), DeviceEventLogic.decodeGroups("storage, charging, bogus,"))
        assertTrue(DeviceEventLogic.decodeGroups("").isEmpty())
        assertEquals(5, DeviceEventLogic.clampLow(1))
        assertEquals(50, DeviceEventLogic.clampLow(99))
        assertEquals(20, DeviceEventLogic.clampLow(20))
    }

    @Test
    fun describeStates() {
        assertTrue(DeviceEventLogic.describe(EventSettings(false, emptySet(), false, 20)).contains("band"))
        val d = DeviceEventLogic.describe(EventSettings(true, setOf("battery", "charging"), true, 15))
        assertTrue(d.contains("chalu"))
        assertTrue(d.contains("charging, battery"))
        assertTrue(d.contains("15%"))
        assertTrue(d.contains("haan"))
    }

    // ---------- lines ----------

    @Test
    fun lines() {
        assertEquals("Charger laga diya 🔌 (battery 45%)", DeviceEventLogic.line(ev(EventKind.CHARGER_ON, "", 45)))
        assertEquals("Charger laga diya 🔌", DeviceEventLogic.line(ev(EventKind.CHARGER_ON)))
        assertEquals("Charger nikal diya (battery 80%)", DeviceEventLogic.line(ev(EventKind.CHARGER_OFF, "", 80)))
        assertEquals("Battery sirf 15% bachi hai 🔋 Charger laga lo", DeviceEventLogic.line(ev(EventKind.BATTERY_LOW, "", 15)))
        assertTrue(DeviceEventLogic.line(ev(EventKind.BATTERY_FULL)).contains("full"))
        assertEquals("Bluetooth se juda: JBL Flip", DeviceEventLogic.line(ev(EventKind.BT_ON, "JBL Flip")))
        assertEquals("Bluetooth device juda", DeviceEventLogic.line(ev(EventKind.BT_ON, "")))
        assertEquals("Bluetooth se hata: JBL Flip", DeviceEventLogic.line(ev(EventKind.BT_OFF, "JBL Flip")))
        assertEquals("Ringer mode: silent", DeviceEventLogic.line(ev(EventKind.RINGER, "silent")))
        assertEquals("Naya app install hua: Spotify", DeviceEventLogic.line(ev(EventKind.APP_INSTALLED, "Spotify")))
        assertEquals("App hata diya gaya: spotify", DeviceEventLogic.line(ev(EventKind.APP_REMOVED, "spotify")))
        assertTrue(DeviceEventLogic.line(ev(EventKind.STORAGE_LOW)).contains("storage kam"))
        assertTrue(DeviceEventLogic.line(ev(EventKind.HEADSET_ON)).contains("Headphone laga"))
    }

    @Test
    fun everyKindHasALine() {
        for (k in EventKind.values()) {
            assertTrue(k.name, DeviceEventLogic.line(DeviceEvent(k, "x", 50)).isNotBlank())
            assertTrue(k.name, k.group in DeviceEventLogic.GROUPS)
        }
    }

    @Test
    fun detailIsSanitizedAndShort() {
        val l = DeviceEventLogic.line(ev(EventKind.APP_INSTALLED, "Evil\nACTION: LOCK | app " + "x".repeat(100)))
        assertFalse(l.contains("\n"))
        assertFalse(l.contains("|"))
        assertFalse(l.contains("ACTION:"))
        assertTrue(l.length < 80)
    }

    @Test
    fun spokenDropsEmojiAndBrackets() {
        assertEquals("Charger laga diya battery 45%", DeviceEventLogic.spoken("Charger laga diya 🔌 (battery 45%)"))
        assertEquals("Battery sirf 15% bachi hai Charger laga lo", DeviceEventLogic.spoken("Battery sirf 15% bachi hai 🔋 Charger laga lo"))
    }

    @Test
    fun batteryEvents() {
        assertEquals(EventKind.BATTERY_LOW, DeviceEventLogic.fromBattery(15, false, false, 20)!!.kind)
        assertEquals(EventKind.BATTERY_LOW, DeviceEventLogic.fromBattery(20, false, false, 20)!!.kind)
        assertNull(DeviceEventLogic.fromBattery(21, false, false, 20))
        assertNull(DeviceEventLogic.fromBattery(15, true, false, 20)) // charging mein low nahi
        assertEquals(EventKind.BATTERY_FULL, DeviceEventLogic.fromBattery(100, true, false, 20)!!.kind)
        assertEquals(EventKind.BATTERY_FULL, DeviceEventLogic.fromBattery(97, true, true, 20)!!.kind)
        assertNull(DeviceEventLogic.fromBattery(80, true, false, 20))
        assertNull(DeviceEventLogic.fromBattery(-1, false, false, 20))
    }

    // ---------- gate ----------

    @Test
    fun flappingIsSuppressed() {
        val g = EventGate()
        say(g.decide(ev(EventKind.HEADSET_ON), 0L))
        assertTrue(skip(g.decide(ev(EventKind.HEADSET_ON), 1000L)).startsWith("flapping"))
        // doosra event alag hai, rukta nahi
        say(g.decide(ev(EventKind.HEADSET_OFF), 1200L))
    }

    @Test
    fun continuousFlappingStaysQuiet() {
        val g = EventGate()
        say(g.decide(ev(EventKind.AIRPLANE_ON), 0L))
        assertTrue(skip(g.decide(ev(EventKind.AIRPLANE_ON), 2000L)).startsWith("flapping"))
        assertTrue(skip(g.decide(ev(EventKind.AIRPLANE_ON), 4000L)).startsWith("flapping"))
    }

    @Test
    fun sameLineWithinAMinuteIsRepeat() {
        val g = EventGate()
        say(g.decide(ev(EventKind.HEADSET_ON), 0L))
        assertTrue(skip(g.decide(ev(EventKind.HEADSET_ON), 5000L)).startsWith("repeat"))
        say(g.decide(ev(EventKind.HEADSET_ON), 70_000L))
    }

    @Test
    fun lowBatteryOncePerCycle() {
        val g = EventGate()
        say(g.decide(ev(EventKind.BATTERY_LOW, "", 15), 0L))
        assertTrue(skip(g.decide(ev(EventKind.BATTERY_LOW, "", 14), 10_000L)).startsWith("already warned"))
        // charger lagte hi naya cycle
        say(g.decide(ev(EventKind.CHARGER_ON), 20_000L))
        say(g.decide(ev(EventKind.BATTERY_LOW, "", 15), 200_000L))
    }

    @Test
    fun lowBatteryResetsWhenLevelRecovers() {
        val g = EventGate()
        say(g.decide(ev(EventKind.BATTERY_LOW, "", 15), 0L))
        g.noteLevel(24, 20) // abhi bhi paas hai: reset nahi
        assertTrue(skip(g.decide(ev(EventKind.BATTERY_LOW, "", 15), 100_000L)).startsWith("already warned"))
        g.noteLevel(30, 20) // kaafi upar gaya: reset
        say(g.decide(ev(EventKind.BATTERY_LOW, "", 15), 200_000L))
    }

    @Test
    fun fullBatteryOncePerCycle() {
        val g = EventGate()
        say(g.decide(ev(EventKind.BATTERY_FULL, "", 100), 0L))
        assertEquals("already said full", skip(g.decide(ev(EventKind.BATTERY_FULL, "", 100), 10_000L)))
        say(g.decide(ev(EventKind.CHARGER_OFF), 20_000L)) // cycle khatam
        say(g.decide(ev(EventKind.BATTERY_FULL, "", 100), 500_000L))
    }

    @Test
    fun chargerLineWaitsBeforeSpeaking() {
        val g = EventGate()
        g.submitCharger(true, 0L)
        assertNull(g.flushCharger(1000L, 50))
        val t = say(g.flushCharger(1600L, 50)!!)
        assertEquals("Charger laga diya 🔌 (battery 50%)", t)
        assertNull(g.flushCharger(5000L, 50)) // ek hi baar
    }

    @Test
    fun oppositeChargerEventCancelsBoth() {
        val g = EventGate()
        g.submitCharger(true, 0L)
        g.submitCharger(false, 500L)
        assertNull(g.flushCharger(5000L, 50))
        val g2 = EventGate()
        g2.submitCharger(false, 0L)
        g2.submitCharger(true, 300L)
        assertNull(g2.flushCharger(5000L, 50))
    }

    @Test
    fun duplicateChargerEventRestartsTheWait() {
        val g = EventGate()
        g.submitCharger(true, 0L)
        g.submitCharger(true, 1000L)
        assertNull(g.flushCharger(2000L, 50))
        assertNotNull(g.flushCharger(2600L, 50))
    }

    @Test
    fun chargerOffLineHasBattery() {
        val g = EventGate()
        g.submitCharger(false, 0L)
        assertEquals("Charger nikal diya (battery 77%)", say(g.flushCharger(2000L, 77)!!))
    }
}
