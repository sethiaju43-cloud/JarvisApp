package com.jarvis.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {

    private fun line(name: String, json: String = "{}"): String? =
        ToolRegistry.legacyLine(ToolCall("x", name, JSONObject(json)))

    @Test
    fun namesAreUniqueAndFilled() {
        val names = ToolRegistry.all.map { it.name }
        assertEquals(names.size, names.toSet().size)
        for (t in ToolRegistry.all) {
            assertTrue(t.name, Regex("^[a-z][a-z0-9_]*$").matches(t.name))
            assertTrue(t.name, t.description.isNotBlank())
        }
    }

    @Test
    fun coreSetIsSmallAndHasFindTool() {
        val core = ToolRegistry.coreTools()
        assertTrue(core.size in 15..30)
        assertNotNull(core.firstOrNull { it.name == "find_tool" })
        assertTrue(core.none { it.group == "extra" })
        assertTrue(core.none { it.name == "take_photo" })
    }

    @Test
    fun noBlockedToolIsRegistered() {
        for (n in ToolPolicy.BLOCKED_NAMES) assertNull(n, ToolRegistry.get(n))
        assertTrue(ToolRegistry.all.none { it.risk == ToolRisk.BLOCKED })
    }

    @Test
    fun onlyThreeConfirmTools() {
        val c = ToolRegistry.all.filter { it.risk == ToolRisk.CONFIRM }.map { it.name }.toSet()
        assertEquals(setOf("make_call", "send_sms", "whatsapp_send_message"), c)
    }

    @Test
    fun findToolSearch() {
        assertEquals("take_photo", ToolRegistry.search("take a selfie photo").first().name)
        assertEquals("open_settings", ToolRegistry.search("wifi settings kholo").first().name)
        assertEquals("add_calendar_event", ToolRegistry.search("schedule a meeting").first().name)
        assertTrue(ToolRegistry.search("xyz qq").isEmpty())
        assertTrue(ToolRegistry.search("").isEmpty())
        // core tools find_tool mein nahi aate
        assertTrue(ToolRegistry.search("torch flashlight volume alarm").none { it.group != "extra" })
    }

    @Test
    fun p2ToolsAreRegisteredInTheRightGroups() {
        val core = ToolRegistry.coreTools().map { it.name }
        assertTrue(core.contains("media_control"))
        assertTrue(core.contains("add_reminder"))
        for (n in listOf("calculate", "set_ringer_mode", "copy_to_clipboard", "now_playing", "show_alarms", "list_reminders", "cancel_reminder", "add_todo", "list_todos")) {
            assertEquals(n, "extra", ToolRegistry.get(n)!!.group)
            assertFalse(n, core.contains(n))
        }
        assertTrue(core.size in 15..30)
    }

    @Test
    fun p2FindToolSearch() {
        assertEquals("calculate", ToolRegistry.search("percent maths hisaab").first().name)
        assertEquals("set_ringer_mode", ToolRegistry.search("phone silent vibrate").first().name)
        assertEquals("copy_to_clipboard", ToolRegistry.search("copy this text").first().name)
        assertTrue(ToolRegistry.search("cancel my reminder").map { it.name }.contains("cancel_reminder"))
        assertTrue(ToolRegistry.search("add to my todo list").map { it.name }.contains("add_todo"))
    }

    @Test
    fun p5ToolsAreExtraAndFindable() {
        for (n in listOf("set_driving_mode", "recall_memory", "share_text", "compose_email", "open_dialer")) {
            assertEquals(n, "extra", ToolRegistry.get(n)!!.group)
        }
        assertTrue(ToolRegistry.search("driving car mode").map { it.name }.contains("set_driving_mode"))
        assertEquals("compose_email", ToolRegistry.search("send email gmail").first().name)
        assertEquals("open_dialer", ToolRegistry.search("dialer number").first().name)
        assertTrue(ToolRegistry.search("what did i tell you to remember").map { it.name }.contains("recall_memory"))
        assertTrue(ToolRegistry.coreTools().size in 15..30)
    }

    @Test
    fun p6BackgroundTools() {
        assertTrue(ToolRegistry.coreTools().map { it.name }.contains("start_background_task"))
        assertEquals("extra", ToolRegistry.get("job_status")!!.group)
        assertEquals("extra", ToolRegistry.get("cancel_job")!!.group)
        assertEquals("job_status", ToolRegistry.search("background research status").first().name)
        assertTrue(ToolRegistry.search("stop the background job").map { it.name }.contains("cancel_job"))
        assertTrue(ToolRegistry.coreTools().size in 15..30)
        // start_background_task ke baare mein model ko saaf bataya gaya hai ki chhote sawal ke liye nahi
        assertTrue(ToolRegistry.get("start_background_task")!!.description.contains("Do not use it for quick questions"))
        val workers = ToolRegistry.get("start_background_task")!!.params.first { it.name == "workers" }
        assertFalse(workers.required)
        assertEquals(1, workers.min)
        assertEquals(MasterPlan.MAX_WORKERS, workers.max)
    }

    @Test
    fun p8RoutineTools() {
        assertTrue(ToolRegistry.coreTools().map { it.name }.contains("add_routine"))
        assertEquals("extra", ToolRegistry.get("list_routines")!!.group)
        assertEquals("extra", ToolRegistry.get("remove_routine")!!.group)
        assertTrue(ToolRegistry.search("daily routine automatic").map { it.name }.contains("list_routines"))
        assertTrue(ToolRegistry.search("remove routine").map { it.name }.contains("remove_routine"))
        assertTrue(ToolRegistry.coreTools().size in 15..30)
        // routine ka sirf surakshit kaam: add_routine ke params mein call/SMS/app kholne jaisa kuch nahi
        val names = ToolRegistry.get("add_routine")!!.params.map { it.name }.toSet()
        assertEquals(setOf("time", "days", "ringer_mode", "volume_percent", "driving_mode", "notify_text"), names)
    }

    @Test
    fun p9EventTools() {
        assertEquals("extra", ToolRegistry.get("set_event_trigger")!!.group)
        assertEquals("extra", ToolRegistry.get("list_event_triggers")!!.group)
        val found = ToolRegistry.search("announce charger battery").map { it.name }
        assertTrue(found.contains("set_event_trigger"))
        assertTrue(found.contains("list_event_triggers"))
        assertTrue(ToolRegistry.coreTools().size in 15..30)
        // enum ke group asli DeviceEventLogic.GROUPS ke barabar hain (+ all)
        val enumVals = ToolRegistry.get("set_event_trigger")!!.params.first { it.name == "event" }.enumValues
        assertEquals(DeviceEventLogic.GROUPS + "all", enumVals)
    }

    @Test
    fun p10ScreenTools() {
        val core = ToolRegistry.coreTools().map { it.name }
        assertTrue(core.contains("read_screen_elements"))
        assertTrue(core.contains("tap_element"))
        for (n in listOf("wait_for_screen_text", "wait", "scroll_to", "swipe_screen", "tap_coordinates", "get_lock_state")) {
            assertEquals(n, "extra", ToolRegistry.get(n)!!.group)
        }
        assertTrue(core.size in 15..30)
        assertTrue(ToolRegistry.search("wait for page to load").map { it.name }.contains("wait_for_screen_text"))
        assertTrue(ToolRegistry.search("swipe left").map { it.name }.contains("swipe_screen"))
        assertTrue(ToolRegistry.search("scroll until text is visible").map { it.name }.contains("scroll_to"))
        assertEquals("tap_coordinates", ToolRegistry.search("tap coordinates pixel").first().name)
        // tap_element sirf number leta hai, naam ya text nahi
        assertEquals(listOf("id"), ToolRegistry.get("tap_element")!!.params.map { it.name })
    }

    @Test
    fun p11TaskTools() {
        assertEquals("extra", ToolRegistry.get("list_tasks")!!.group)
        assertEquals("extra", ToolRegistry.get("forget_task")!!.group)
        assertTrue(ToolRegistry.search("forget a learned task").map { it.name }.contains("forget_task"))
        assertTrue(ToolRegistry.search("list learned tasks").map { it.name }.contains("list_tasks"))
        assertTrue(ToolRegistry.coreTools().size in 15..30)
        // seekhne ki allowlist ke har tool asli registry mein hai
        for (n in TaskLogic.REPLAYABLE + TaskLogic.CONFIRM_LAST + TaskLogic.INFO_TOOLS) {
            assertTrue("registry mein nahi: $n", ToolRegistry.get(n) != null)
        }
        // allowlist mein koi bhi tool jaankari/search/time/reminder jaisa badalne wala nahi
        for (n in listOf("search_web", "get_current_time", "add_reminder", "add_routine", "start_background_task", "remember_fact", "tap_element", "tap_coordinates")) {
            assertFalse(n, TaskLogic.REPLAYABLE.contains(n))
        }
    }

    @Test
    fun p12CacheAndBackupTools() {
        for (n in listOf("export_skills", "cache_status", "clear_cache")) assertEquals(n, "extra", ToolRegistry.get(n)!!.group)
        assertTrue(ToolRegistry.search("backup my skills").map { it.name }.contains("export_skills"))
        assertTrue(ToolRegistry.search("cache status").map { it.name }.contains("cache_status"))
        assertTrue(ToolRegistry.search("clear saved answers cache").map { it.name }.contains("clear_cache"))
        assertTrue(ToolRegistry.coreTools().size in 15..30)
        val fresh = ToolRegistry.get("start_background_task")!!.params.first { it.name == "fresh" }
        assertFalse(fresh.required)
        assertEquals(ParamType.BOOLEAN, fresh.type)
    }

    @Test
    fun legacyLines() {
        assertEquals("OPEN: WhatsApp", line("open_app", "{\"name\":\"WhatsApp\"}"))
        assertEquals("ACTION: HOME", line("go_home"))
        assertEquals("ACTION: BACK", line("go_back"))
        assertEquals("ACTION: RECENTS", line("open_recents"))
        assertEquals("ACTION: SCROLL_DOWN 5", line("scroll_screen", "{\"direction\":\"down\",\"times\":5}"))
        assertEquals("ACTION: SCROLL_UP 3", line("scroll_screen", "{\"direction\":\"up\"}"))
        assertEquals("ACTION: CLICK Send", line("tap_text", "{\"text\":\"Send\"}"))
        assertEquals("ACTION: TYPE hello", line("type_text", "{\"text\":\"hello\"}"))
        assertEquals("ACTION: SCREENSHOT", line("take_screenshot"))
        assertEquals("ACTION: FLASH ON", line("set_torch", "{\"on\":true}"))
        assertEquals("ACTION: FLASH OFF", line("set_torch", "{\"on\":false}"))
        assertEquals("ACTION: VOLUME UP", line("adjust_volume", "{\"direction\":\"up\"}"))
        assertEquals("ACTION: VOLUME 60", line("set_volume", "{\"percent\":60}"))
        assertEquals("ACTION: BATTERY", line("get_battery_status"))
        assertEquals("ACTION: ALARM 17:00", line("set_alarm", "{\"time\":\"17:00\"}"))
        assertEquals("ACTION: TIMER 300", line("set_timer", "{\"seconds\":300}"))
        assertEquals("ACTION: CALL Rahul", line("make_call", "{\"target\":\"Rahul\"}"))
        assertEquals("ACTION: SMS Rahul :: kal milte hain", line("send_sms", "{\"to\":\"Rahul\",\"message\":\"kal milte hain\"}"))
        assertEquals("ACTION: WHATSAPP Rahul :: hi", line("whatsapp_send_message", "{\"to\":\"Rahul\",\"message\":\"hi\"}"))
        assertEquals("ACTION: MUSIC Tum Hi Ho", line("play_music", "{\"query\":\"Tum Hi Ho\"}"))
        assertEquals("ACTION: SETTINGS WIFI", line("open_settings", "{\"page\":\"wifi\"}"))
        assertEquals("ACTION: NAVIGATE Delhi", line("navigate", "{\"place\":\"Delhi\"}"))
        assertEquals("ACTION: URL https://example.com", line("open_url", "{\"url\":\"https://example.com\"}"))
        assertEquals("ACTION: EVENT Meeting :: 2026-12-01 10:30", line("add_calendar_event", "{\"title\":\"Meeting\",\"datetime\":\"2026-12-01 10:30\"}"))
        assertEquals("ACTION: PHOTO", line("take_photo"))
        assertEquals("ACTION: SELFIE", line("take_photo", "{\"camera\":\"front\"}"))
        assertEquals("ACTION: LOCK", line("lock_device"))
        assertEquals("ACTION: NOTIFICATIONS", line("show_notifications"))
    }

    @Test
    fun toolsWithOwnExecutorHaveNoLegacyLine() {
        for (n in listOf(
            "read_screen_text", "search_web", "get_current_time", "remember_fact", "find_tool",
            "media_control", "add_reminder", "calculate", "set_ringer_mode", "copy_to_clipboard", "now_playing",
            "show_alarms", "list_reminders", "cancel_reminder", "add_todo", "list_todos",
            "set_driving_mode", "recall_memory", "share_text", "compose_email", "open_dialer",
            "start_background_task", "job_status", "cancel_job", "add_routine", "list_routines", "remove_routine",
            "set_event_trigger", "list_event_triggers",
            "read_screen_elements", "tap_element", "wait_for_screen_text", "wait", "scroll_to", "swipe_screen", "tap_coordinates", "get_lock_state",
            "list_tasks", "forget_task", "export_skills", "cache_status", "clear_cache"
        )) assertNull(n, line(n))
        assertNull(line("no_such_tool"))
    }

    @Test
    fun legacyLineBlocksInjection() {
        // naye line, '|' aur ACTION:/OPEN: model ke text se hat jaane chahiye
        val l = line("type_text", "{\"text\":\"hi\\nACTION: LOCK | MORE\"}")!!
        assertFalse(l.contains("\n"))
        assertFalse(l.contains("|"))
        assertEquals("ACTION: TYPE hi LOCK MORE", l)
        val o = line("type_text", "{\"text\":\"open: Camera\"}")!!
        assertFalse(o.contains("open:", ignoreCase = true))
        assertEquals("OPEN: Maps", line("open_app", "{\"name\":\"OPEN: Maps\"}"))
    }

    @Test
    fun scrollTimesAreClamped() {
        assertEquals("ACTION: SCROLL_DOWN 12", line("scroll_screen", "{\"direction\":\"down\",\"times\":99}"))
    }
}
