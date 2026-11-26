package com.jarvis.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ToolAdaptersTest {

    private fun spec(name: String) = ToolRegistry.get(name)!!

    // ---------- Gemini ----------

    @Test
    fun geminiToolsShape() {
        val arr = GeminiToolAdapter.toolsJson(listOf(spec("get_battery_status"), spec("set_volume"), spec("scroll_screen")))
        val decls = arr.getJSONObject(0).getJSONArray("functionDeclarations")
        assertEquals(3, decls.length())
        // bina params wale tool mein "parameters" field hona hi nahi chahiye
        assertFalse(decls.getJSONObject(0).has("parameters"))
        val vol = decls.getJSONObject(1).getJSONObject("parameters")
        assertEquals("OBJECT", vol.getString("type"))
        assertEquals("INTEGER", vol.getJSONObject("properties").getJSONObject("percent").getString("type"))
        assertEquals("percent", vol.getJSONArray("required").getString(0))
        val sc = decls.getJSONObject(2).getJSONObject("parameters")
        assertEquals(2, sc.getJSONObject("properties").getJSONObject("direction").getJSONArray("enum").length())
        // optional param required list mein nahi
        assertEquals(1, sc.getJSONArray("required").length())
    }

    @Test
    fun geminiRequestHasSystemToolsAndAutoMode() {
        val turns = listOf<ToolTurn>(ToolTurn.User("torch on karo"))
        val r = GeminiToolAdapter.buildRequest("Tum Jarvi ho", turns, listOf(spec("set_torch")))
        assertEquals("Tum Jarvi ho", r.getJSONObject("system_instruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals("AUTO", r.getJSONObject("tool_config").getJSONObject("function_calling_config").getString("mode"))
        val c0 = r.getJSONArray("contents").getJSONObject(0)
        assertEquals("user", c0.getString("role"))
        assertEquals("torch on karo", c0.getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test
    fun geminiRequestWithoutToolsHasNoToolFields() {
        val r = GeminiToolAdapter.buildRequest("", listOf(ToolTurn.User("hi")), emptyList())
        assertFalse(r.has("tools"))
        assertFalse(r.has("tool_config"))
        assertFalse(r.has("system_instruction"))
    }

    private val geminiCallReply = """
        {"candidates":[{"content":{"role":"model","parts":[
          {"text":"thinking...","thought":true},
          {"functionCall":{"name":"set_torch","args":{"on":true}},"thoughtSignature":"SIG123"}
        ]},"finishReason":"STOP"}]}
    """.trimIndent()

    @Test
    fun geminiParsesFunctionCall() {
        val m = GeminiToolAdapter.parse(geminiCallReply)
        assertEquals(1, m.calls.size)
        assertEquals("set_torch", m.calls[0].name)
        assertTrue(m.calls[0].args.getBoolean("on"))
        assertEquals("", m.text) // thought wala text chhod diya
        assertTrue(m.calls[0].id.startsWith("synth_"))
    }

    @Test
    fun geminiKeepsRawContentWithThoughtSignature() {
        val m = GeminiToolAdapter.parse(geminiCallReply)
        val turns = listOf(
            ToolTurn.User("torch on"),
            m,
            ToolTurn.Results(listOf(ToolResult(m.calls[0].id, "set_torch", "ok")))
        )
        val contents = GeminiToolAdapter.contents(turns)
        assertEquals(3, contents.length())
        val modelParts = contents.getJSONObject(1).getJSONArray("parts")
        assertEquals("SIG123", modelParts.getJSONObject(1).getString("thoughtSignature"))
        val fr = contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
        assertEquals("set_torch", fr.getString("name"))
        assertEquals("ok", fr.getJSONObject("response").getString("result"))
        // banavati id Gemini ko mat bhejo
        assertFalse(fr.has("id"))
        assertEquals("user", contents.getJSONObject(2).getString("role"))
    }

    @Test
    fun geminiKeepsRealCallId() {
        val raw = """{"candidates":[{"content":{"parts":[{"functionCall":{"id":"abc1","name":"go_home","args":{}}}]}}]}"""
        val m = GeminiToolAdapter.parse(raw)
        assertEquals("abc1", m.calls[0].id)
        val contents = GeminiToolAdapter.contents(listOf(m, ToolTurn.Results(listOf(ToolResult("abc1", "go_home", "ok")))))
        assertEquals("model", contents.getJSONObject(0).getString("role"))
        assertEquals("abc1", contents.getJSONObject(1).getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse").getString("id"))
    }

    @Test
    fun geminiRebuildsModelTurnWithoutRaw() {
        val m = ToolTurn.Model("ok", listOf(ToolCall("synth_g0", "go_home", JSONObject())), null)
        val parts = GeminiToolAdapter.contents(listOf(m)).getJSONObject(0).getJSONArray("parts")
        assertEquals("ok", parts.getJSONObject(0).getString("text"))
        assertEquals("go_home", parts.getJSONObject(1).getJSONObject("functionCall").getString("name"))
        assertFalse(parts.getJSONObject(1).getJSONObject("functionCall").has("id"))
    }

    @Test
    fun geminiParsesPlainText() {
        val m = GeminiToolAdapter.parse("""{"candidates":[{"content":{"parts":[{"text":"Namaste "},{"text":"boss"}]}}]}""")
        assertEquals("Namaste boss", m.text)
        assertTrue(m.calls.isEmpty())
    }

    @Test
    fun geminiBadRepliesThrow() {
        for (raw in listOf(
            "not json",
            """{"error":{"message":"API key not valid"}}""",
            """{"candidates":[]}""",
            """{"candidates":[{"finishReason":"SAFETY"}]}"""
        )) {
            try {
                GeminiToolAdapter.parse(raw)
                fail("Exception aani chahiye thi: $raw")
            } catch (e: ToolAdapterException) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
    }

    @Test
    fun geminiErrorMessageHidesKeyLikeText() {
        val key = "AIzaSyA1234567890abcdefghijklmnopqrstuv"
        try {
            GeminiToolAdapter.parse("""{"error":{"message":"bad key $key"}}""")
            fail()
        } catch (e: ToolAdapterException) {
            assertFalse(e.message!!.contains(key))
        }
    }

    // ---------- OpenAI compatible ----------

    @Test
    fun openAiToolsShape() {
        val arr = OpenAiToolAdapter.toolsJson(listOf(spec("get_battery_status"), spec("set_alarm")))
        val t0 = arr.getJSONObject(0)
        assertEquals("function", t0.getString("type"))
        val p0 = t0.getJSONObject("function").getJSONObject("parameters")
        assertEquals("object", p0.getString("type"))
        assertEquals(0, p0.getJSONObject("properties").length())
        val p1 = arr.getJSONObject(1).getJSONObject("function").getJSONObject("parameters")
        assertEquals("string", p1.getJSONObject("properties").getJSONObject("time").getString("type"))
        assertEquals("time", p1.getJSONArray("required").getString(0))
    }

    @Test
    fun openAiRequestMessages() {
        val call = ToolCall("call_1", "set_torch", JSONObject("{\"on\":true}"))
        val turns = listOf(
            ToolTurn.User("torch on"),
            ToolTurn.Model("", listOf(call), null),
            ToolTurn.Results(listOf(ToolResult("call_1", "set_torch", "ok")))
        )
        val r = OpenAiToolAdapter.buildRequest("llama-x", "Tum Jarvi ho", turns, listOf(spec("set_torch")))
        assertEquals("llama-x", r.getString("model"))
        assertEquals("auto", r.getString("tool_choice"))
        val m = r.getJSONArray("messages")
        assertEquals("system", m.getJSONObject(0).getString("role"))
        assertEquals("user", m.getJSONObject(1).getString("role"))
        val a = m.getJSONObject(2)
        assertEquals("assistant", a.getString("role"))
        assertTrue(a.isNull("content"))
        val tc = a.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("call_1", tc.getString("id"))
        // arguments JSON *string* hona chahiye, object nahi
        assertTrue(tc.getJSONObject("function").get("arguments") is String)
        assertTrue(JSONObject(tc.getJSONObject("function").getString("arguments")).getBoolean("on"))
        val t = m.getJSONObject(3)
        assertEquals("tool", t.getString("role"))
        assertEquals("call_1", t.getString("tool_call_id"))
        assertEquals("ok", t.getString("content"))
    }

    @Test
    fun openAiRequestWithoutToolsHasNoToolFields() {
        val r = OpenAiToolAdapter.buildRequest("m", "", listOf(ToolTurn.User("hi")), emptyList())
        assertFalse(r.has("tools"))
        assertFalse(r.has("tool_choice"))
        assertEquals(1, r.getJSONArray("messages").length())
    }

    @Test
    fun openAiParsesToolCalls() {
        val raw = """
            {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
              {"id":"call_9","type":"function","function":{"name":"set_volume","arguments":"{\"percent\": 60}"}},
              {"id":"call_10","type":"function","function":{"name":"go_home","arguments":""}}
            ]}}]}
        """.trimIndent()
        val m = OpenAiToolAdapter.parse(raw)
        assertEquals(2, m.calls.size)
        assertEquals("call_9", m.calls[0].id)
        assertEquals(60, m.calls[0].args.getInt("percent"))
        assertFalse(m.calls[0].argsError)
        assertEquals("go_home", m.calls[1].name)
        assertEquals(0, m.calls[1].args.length())
        assertFalse(m.calls[1].argsError)
        assertEquals("", m.text)
        assertNull(m.raw)
    }

    @Test
    fun openAiBadArgumentsAreFlagged() {
        val raw = """{"choices":[{"message":{"content":"","tool_calls":[{"id":"c1","function":{"name":"open_app","arguments":"{name: WhatsApp"}}]}}]}"""
        val m = OpenAiToolAdapter.parse(raw)
        assertTrue(m.calls[0].argsError)
        val d = ToolPolicy.check(m.calls[0])
        assertTrue(d is ToolDecision.Reject)
        assertEquals("bad_args", (d as ToolDecision.Reject).code)
    }

    @Test
    fun openAiMissingIdGetsSyntheticOne() {
        val raw = """{"choices":[{"message":{"content":null,"tool_calls":[{"function":{"name":"go_back","arguments":"{}"}}]}}]}"""
        val m = OpenAiToolAdapter.parse(raw)
        assertTrue(m.calls[0].id.startsWith("synth_"))
        // wapas bhejte waqt wahi id use ho
        val msgs = OpenAiToolAdapter.messages("", listOf(m, ToolTurn.Results(listOf(ToolResult(m.calls[0].id, "go_back", "ok")))))
        assertEquals(m.calls[0].id, msgs.getJSONObject(0).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals(m.calls[0].id, msgs.getJSONObject(1).getString("tool_call_id"))
    }

    @Test
    fun openAiArgumentsAlreadyObject() {
        val raw = """{"choices":[{"message":{"content":null,"tool_calls":[{"id":"c","function":{"name":"set_torch","arguments":{"on":false}}}]}}]}"""
        val m = OpenAiToolAdapter.parse(raw)
        assertFalse(m.calls[0].args.getBoolean("on"))
    }

    @Test
    fun openAiParsesPlainTextAndArrayContent() {
        assertEquals("Hello", OpenAiToolAdapter.parse("""{"choices":[{"message":{"content":"Hello"}}]}""").text)
        val arr = OpenAiToolAdapter.parse("""{"choices":[{"message":{"content":[{"type":"text","text":"Hi "},{"type":"text","text":"there"}]}}]}""")
        assertEquals("Hi there", arr.text)
    }

    @Test
    fun openAiBadRepliesThrow() {
        for (raw in listOf(
            "<html>502</html>",
            """{"error":{"message":"rate limit"}}""",
            """{"error":"quota"}""",
            """{"choices":[]}""",
            """{"choices":[{"message":{"content":null}}]}"""
        )) {
            try {
                OpenAiToolAdapter.parse(raw)
                fail("Exception aani chahiye thi: $raw")
            } catch (e: ToolAdapterException) {
                assertNotNull(e.message)
            }
        }
    }

    @Test
    fun fullLoopWithPolicyAndLegacyLine() {
        // model ne make_call maanga -> policy Confirm deta hai, seedha nahi chalta
        val raw = """{"choices":[{"message":{"content":null,"tool_calls":[{"id":"k","function":{"name":"make_call","arguments":"{\"target\":\"Rahul\"}"}}]}}]}"""
        val m = OpenAiToolAdapter.parse(raw)
        val d = ToolPolicy.check(m.calls[0], ToolRegistry.coreTools().map { it.name }.toSet())
        assertTrue(d is ToolDecision.Confirm)
        assertEquals("ACTION: CALL Rahul", ToolRegistry.legacyLine((d as ToolDecision.Confirm).call))
    }
}
