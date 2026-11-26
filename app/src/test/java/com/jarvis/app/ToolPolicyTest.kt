package com.jarvis.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPolicyTest {

    private fun call(name: String, json: String = "{}", argsError: Boolean = false) =
        ToolCall("c1", name, JSONObject(json), argsError)

    private fun allow(name: String, json: String = "{}"): ToolCall {
        val d = ToolPolicy.check(call(name, json))
        assertTrue("Allow nahi mila: $name $json", d is ToolDecision.Allow)
        return (d as ToolDecision.Allow).call
    }

    private fun rejectCode(name: String, json: String = "{}", offered: Set<String>? = null, argsError: Boolean = false): String {
        val d = ToolPolicy.check(call(name, json, argsError), offered)
        assertTrue("Reject nahi mila: $name $json", d is ToolDecision.Reject)
        return (d as ToolDecision.Reject).code
    }

    @Test
    fun safeToolsAreAllowed() {
        allow("go_home")
        allow("get_battery_status")
        allow("open_app", "{\"name\":\"YouTube\"}")
        assertEquals(true, allow("set_torch", "{\"on\":\"off\"}").args.getBoolean("on").not())
    }

    @Test
    fun unknownAndBlockedAreRejected() {
        assertEquals("unknown_tool", rejectCode("hack_the_planet"))
        for (n in ToolPolicy.BLOCKED_NAMES) assertEquals("blocked", rejectCode(n))
    }

    @Test
    fun notOfferedToolIsRejected() {
        assertEquals("not_offered", rejectCode("take_photo", "{}", setOf("go_home")))
        val d = ToolPolicy.check(call("go_home"), setOf("go_home"))
        assertTrue(d is ToolDecision.Allow)
    }

    @Test
    fun badJsonArgsRejected() {
        assertEquals("bad_args", rejectCode("open_app", "{}", argsError = true))
    }

    @Test
    fun missingAndEmptyArgs() {
        assertEquals("missing_arg", rejectCode("open_app"))
        assertEquals("bad_arg", rejectCode("open_app", "{\"name\":\"   \"}"))
        assertEquals("missing_arg", rejectCode("open_app", "{\"name\":null}"))
    }

    @Test
    fun enumIsCaseInsensitiveAndChecked() {
        assertEquals("down", allow("scroll_screen", "{\"direction\":\"DOWN\"}").args.getString("direction"))
        assertEquals("bad_arg", rejectCode("scroll_screen", "{\"direction\":\"left\"}"))
    }

    @Test
    fun integerRangesAndCoercion() {
        assertEquals(60, allow("set_volume", "{\"percent\":60.0}").args.getInt("percent"))
        assertEquals(45, allow("set_volume", "{\"percent\":\"45\"}").args.getInt("percent"))
        assertEquals("bad_arg", rejectCode("set_volume", "{\"percent\":150}"))
        assertEquals("bad_arg", rejectCode("set_volume", "{\"percent\":-1}"))
        assertEquals("bad_arg", rejectCode("set_volume", "{\"percent\":50.5}"))
        assertEquals("bad_arg", rejectCode("set_timer", "{\"seconds\":0}"))
        assertEquals("bad_arg", rejectCode("set_timer", "{\"seconds\":\"abc\"}"))
    }

    @Test
    fun stringLengthLimit() {
        val long = "a".repeat(200)
        assertEquals("bad_arg", rejectCode("open_app", "{\"name\":\"$long\"}"))
    }

    @Test
    fun alarmTimeIsValidatedAndPadded() {
        assertEquals("07:05", allow("set_alarm", "{\"time\":\"7:05\"}").args.getString("time"))
        assertEquals("23:59", allow("set_alarm", "{\"time\":\"23:59\"}").args.getString("time"))
        assertEquals("bad_arg", rejectCode("set_alarm", "{\"time\":\"25:00\"}"))
        assertEquals("bad_arg", rejectCode("set_alarm", "{\"time\":\"7:5\"}"))
        assertEquals("bad_arg", rejectCode("set_alarm", "{\"time\":\"subah 7 baje\"}"))
    }

    @Test
    fun eventDateTimeIsNormalized() {
        val c = allow("add_calendar_event", "{\"title\":\"Meeting\",\"datetime\":\"2026-12-01T10:30\"}")
        assertEquals("2026-12-01 10:30", c.args.getString("datetime"))
        assertEquals("bad_arg", rejectCode("add_calendar_event", "{\"title\":\"M\",\"datetime\":\"kal\"}"))
    }

    @Test
    fun urlMustBeHttp() {
        allow("open_url", "{\"url\":\"https://example.com/a?b=1\"}")
        assertEquals("bad_arg", rejectCode("open_url", "{\"url\":\"javascript:alert(1)\"}"))
        assertEquals("bad_arg", rejectCode("open_url", "{\"url\":\"file:///sdcard/x\"}"))
        assertEquals("bad_arg", rejectCode("open_url", "{\"url\":\"example.com\"}"))
    }

    @Test
    fun phoneNumberGivenAsNumberBecomesText() {
        val d = ToolPolicy.check(call("make_call", "{\"target\":9876543210}"))
        assertTrue(d is ToolDecision.Confirm)
        assertEquals("9876543210", (d as ToolDecision.Confirm).call.args.getString("target"))
    }

    @Test
    fun confirmToolsNeverRunDirectly() {
        val d1 = ToolPolicy.check(call("make_call", "{\"target\":\"Rahul\"}"))
        assertTrue(d1 is ToolDecision.Confirm)
        assertTrue((d1 as ToolDecision.Confirm).question.contains("Rahul"))

        val d2 = ToolPolicy.check(call("send_sms", "{\"to\":\"Rahul\",\"message\":\"kal milte hain\"}"))
        assertTrue(d2 is ToolDecision.Confirm)
        assertTrue((d2 as ToolDecision.Confirm).question.contains("kal milte hain"))

        val d3 = ToolPolicy.check(call("whatsapp_send_message", "{\"to\":\"Rahul\",\"message\":\"hi\"}"))
        assertTrue(d3 is ToolDecision.Confirm)
        // galat arguments ho to confirm tak pahunchta hi nahi
        assertEquals("missing_arg", rejectCode("send_sms", "{\"to\":\"Rahul\"}"))
    }

    @Test
    fun extraArgsAreDropped() {
        val c = allow("go_home", "{\"sneaky\":\"value\"}")
        assertTrue(!c.args.has("sneaky"))
    }

    @Test
    fun resultTruncation() {
        assertEquals("short", ToolPolicy.truncateResult("short"))
        val t = ToolPolicy.truncateResult("x".repeat(2000))
        assertEquals(ToolPolicy.MAX_RESULT_CHARS + 3, t.length)
    }
}
