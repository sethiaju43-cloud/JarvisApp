package com.jarvis.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolAgentTest {

    private fun call(id: String, name: String, json: String = "{}") = ToolCall(id, name, JSONObject(json))

    private fun modelCalls(vararg c: ToolCall) = ToolTurn.Model("", c.toList(), null)

    private fun modelText(t: String) = ToolTurn.Model(t, emptyList(), null)

    /** Pehle se tay jawab dene wala nakli model. Fail karne ke liye null rakho. */
    private class FakeLlm(private val replies: List<ToolTurn.Model?>) : ToolLlm {
        var calls = 0
        val seenTools = ArrayList<List<String>>()
        val seenTurns = ArrayList<List<ToolTurn>>()
        override fun next(turns: List<ToolTurn>, tools: List<ToolSpec>): ToolTurn.Model {
            seenTools.add(tools.map { it.name })
            seenTurns.add(ArrayList(turns))
            val r = replies.getOrNull(calls)
            calls++
            return r ?: throw ToolAdapterException("fake fail")
        }
    }

    private class FakeRunner : ToolRunner {
        val ran = ArrayList<String>()
        override fun run(call: ToolCall): ToolOutput {
            ran.add(call.name)
            return when (call.name) {
                "find_tool" -> ToolOutput("Now available: take_photo", listOf("take_photo"), silent = true)
                "get_current_time" -> ToolOutput("2026-11-26 10:00", silent = true)
                else -> ToolOutput("done " + call.name)
            }
        }
    }

    private val hist = listOf(Pair("user", "torch on karo"))

    @Test
    fun plainChatAnswerNeedsNoTool() {
        val llm = FakeLlm(listOf(modelText("Namaste!")))
        val r = ToolAgent(llm, FakeRunner()).run(hist)
        assertTrue(r is AgentResult.Done)
        assertEquals("Namaste!", (r as AgentResult.Done).text)
        assertEquals(1, llm.calls)
    }

    @Test
    fun toolThenFinalAnswer() {
        val llm = FakeLlm(listOf(modelCalls(call("1", "set_torch", "{\"on\":true}")), modelText("Torch jala di 🔦")))
        val runner = FakeRunner()
        val r = ToolAgent(llm, runner).run(hist) as AgentResult.Done
        assertEquals("Torch jala di 🔦", r.text)
        assertEquals(listOf("set_torch"), runner.ran)
        assertEquals(listOf("done set_torch"), r.steps)
        // dusre round mein model ko tool ka nateeja dikha
        val last = llm.seenTurns[1].last()
        assertTrue(last is ToolTurn.Results)
        assertEquals("done set_torch", (last as ToolTurn.Results).results[0].content)
    }

    @Test
    fun confirmToolStopsAndAsksUser() {
        val llm = FakeLlm(listOf(modelCalls(call("1", "make_call", "{\"target\":\"Rahul\"}"))))
        val runner = FakeRunner()
        val r = ToolAgent(llm, runner).run(hist)
        assertTrue(r is AgentResult.NeedConfirm)
        r as AgentResult.NeedConfirm
        assertTrue(r.question.contains("Rahul"))
        assertEquals("ACTION: CALL Rahul", r.line)
        assertTrue(runner.ran.isEmpty())
    }

    @Test
    fun confirmTurnedOffRunsDirectly() {
        val llm = FakeLlm(listOf(modelCalls(call("1", "make_call", "{\"target\":\"Rahul\"}")), modelText("Call laga di")))
        val runner = FakeRunner()
        val r = ToolAgent(llm, runner, confirmCalls = false).run(hist)
        assertTrue(r is AgentResult.Done)
        assertEquals(listOf("make_call"), runner.ran)
    }

    @Test
    fun earlierStepsKeptWhenConfirmComesLater() {
        val llm = FakeLlm(listOf(
            modelCalls(call("1", "open_app", "{\"name\":\"WhatsApp\"}")),
            modelCalls(call("2", "tap_text", "{\"text\":\"Send\"}"))
        ))
        val runner = FakeRunner()
        val r = ToolAgent(llm, runner).run(hist)
        assertTrue(r is AgentResult.NeedConfirm)
        assertEquals(listOf("open_app"), runner.ran)
        assertEquals("ACTION: CLICK Send", (r as AgentResult.NeedConfirm).line)
        assertEquals(1, r.steps.size)
    }

    @Test
    fun rejectedCallGoesBackToModelWithReason() {
        val llm = FakeLlm(listOf(modelCalls(call("1", "set_volume", "{\"percent\":500}")), modelText("Theek hai, 50% kar diya")))
        val runner = FakeRunner()
        val r = ToolAgent(llm, runner).run(hist)
        assertTrue(r is AgentResult.Done)
        assertTrue(runner.ran.isEmpty())
        val res = llm.seenTurns[1].last() as ToolTurn.Results
        assertTrue(res.results[0].content.contains("percent"))
    }

    @Test
    fun blockedAndUnknownToolsNeverRun() {
        val llm = FakeLlm(listOf(modelCalls(call("1", "wipe_device"), call("2", "nope_tool")), modelText("Ye nahi kar sakti")))
        val runner = FakeRunner()
        val r = ToolAgent(llm, runner).run(hist)
        assertTrue(r is AgentResult.Done)
        assertTrue(runner.ran.isEmpty())
    }

    @Test
    fun findToolRevealsExtraToolForNextRound() {
        val llm = FakeLlm(listOf(
            modelCalls(call("1", "find_tool", "{\"query\":\"take a photo\"}")),
            modelCalls(call("2", "take_photo")),
            modelText("Photo kheench di 📸")
        ))
        val runner = FakeRunner()
        val r = ToolAgent(llm, runner).run(hist) as AgentResult.Done
        assertFalse(llm.seenTools[0].contains("take_photo"))
        assertTrue(llm.seenTools[1].contains("take_photo"))
        assertEquals(listOf("find_tool", "take_photo"), runner.ran)
        // find_tool user ka "step" nahi hai
        assertEquals(listOf("done take_photo"), r.steps)
    }

    @Test
    fun extraToolWithoutFindToolIsRejected() {
        val llm = FakeLlm(listOf(modelCalls(call("1", "take_photo")), modelText("Pehle tool dhundhna padega")))
        val runner = FakeRunner()
        ToolAgent(llm, runner).run(hist)
        assertTrue(runner.ran.isEmpty())
        val res = llm.seenTurns[1].last() as ToolTurn.Results
        assertTrue(res.results[0].content.contains("find_tool"))
    }

    @Test
    fun failBeforeAnyToolMeansFallback() {
        val r = ToolAgent(FakeLlm(listOf(null)), FakeRunner()).run(hist)
        assertTrue(r is AgentResult.Failed)
    }

    @Test
    fun emptyFirstAnswerMeansFallback() {
        val r = ToolAgent(FakeLlm(listOf(modelText(""))), FakeRunner()).run(hist)
        assertTrue(r is AgentResult.Failed)
    }

    @Test
    fun failAfterToolRanNeverFallsBack() {
        // Torch jal chuki hai: ab Failed dena galat hota (purana tareeka dobara torch chalata)
        val llm = FakeLlm(listOf(modelCalls(call("1", "set_torch", "{\"on\":true}")), null))
        val r = ToolAgent(llm, FakeRunner()).run(hist)
        assertTrue(r is AgentResult.Done)
        assertEquals("done set_torch", (r as AgentResult.Done).text)
    }

    @Test
    fun repeatedSameCallIsStopped() {
        val same = call("x", "go_back")
        val llm = FakeLlm(listOf(
            modelCalls(same), modelCalls(same), modelCalls(same), modelCalls(same), modelText("ok")
        ))
        val runner = FakeRunner()
        ToolAgent(llm, runner).run(hist)
        assertEquals(2, runner.ran.size)
    }

    @Test
    fun roundLimitEndsTheLoop() {
        val replies = ArrayList<ToolTurn.Model?>()
        for (i in 1..10) replies.add(modelCalls(call("c$i", "scroll_screen", "{\"direction\":\"down\",\"times\":$i}")))
        val llm = FakeLlm(replies)
        val r = ToolAgent(llm, FakeRunner(), maxRounds = 4).run(hist)
        assertTrue(r is AgentResult.Done)
        assertEquals(4, llm.calls)
    }

    @Test
    fun manyCallsInOneTurnAreCapped() {
        val cs = (1..7).map { call("c$it", "scroll_screen", "{\"direction\":\"up\",\"times\":$it}") }
        val llm = FakeLlm(listOf(ToolTurn.Model("", cs, null), modelText("ok")))
        val runner = FakeRunner()
        ToolAgent(llm, runner).run(hist)
        assertEquals(4, runner.ran.size)
        val res = llm.seenTurns[1].last() as ToolTurn.Results
        assertEquals(7, res.results.size)
    }

    @Test
    fun stopFlagEndsImmediately() {
        val llm = FakeLlm(listOf(modelText("hi")))
        val r = ToolAgent(llm, FakeRunner(), stop = { true }).run(hist) as AgentResult.Done
        assertEquals("Ruk gayi 🛑", r.text)
        assertEquals(0, llm.calls)
    }

    @Test
    fun historyIsPassedAsTurns() {
        val llm = FakeLlm(listOf(modelText("ok")))
        ToolAgent(llm, FakeRunner()).run(listOf(Pair("user", "hi"), Pair("model", "hello"), Pair("user", "torch")))
        val t = llm.seenTurns[0]
        assertEquals(3, t.size)
        assertTrue(t[0] is ToolTurn.User)
        assertTrue(t[1] is ToolTurn.Model)
        assertEquals("torch", (t[2] as ToolTurn.User).text)
    }

    @Test
    fun stopBetweenToolsInOneTurn() {
        val runner = FakeRunner()
        val llm = FakeLlm(listOf(
            modelCalls(call("1", "go_home"), call("2", "go_back")),
            modelText("ok")
        ))
        val r = ToolAgent(llm, runner, stop = { runner.ran.size >= 1 }).run(hist)
        assertTrue(r is AgentResult.Done)
        assertEquals("Ruk gayi 🛑", (r as AgentResult.Done).text)
        assertEquals(listOf("go_home"), runner.ran)
    }

    @Test
    fun longScreenSnapshotReachesModelButStepIsOneLine() {
        val snap = "Chat khuli 💬\n" + "x".repeat(1500)
        val runner = object : ToolRunner {
            override fun run(call: ToolCall) = ToolOutput(snap)
        }
        val llm = FakeLlm(listOf(modelCalls(call("1", "open_app", "{\"name\":\"WhatsApp\"}")), modelText("Khul gayi")))
        val r = ToolAgent(llm, runner).run(hist) as AgentResult.Done
        // model ko poora snapshot dikha (1800 ki hadd ke andar)
        val res = llm.seenTurns[1].last() as ToolTurn.Results
        assertEquals(snap, res.results[0].content)
        // user ke "step" mein sirf pehli line
        assertEquals(listOf("Chat khuli 💬"), r.steps)
    }

    @Test
    fun hugeResultIsCut() {
        val runner = object : ToolRunner {
            override fun run(call: ToolCall) = ToolOutput("y".repeat(5000))
        }
        val llm = FakeLlm(listOf(modelCalls(call("1", "go_home")), modelText("ok")))
        ToolAgent(llm, runner, resultLimit = 1000).run(hist)
        val res = llm.seenTurns[1].last() as ToolTurn.Results
        assertEquals(1003, res.results[0].content.length)
    }

    @Test
    fun traceRecordsWhatHappened() {
        val llm = FakeLlm(listOf(
            modelCalls(call("1", "set_torch", "{\"on\":true}"), call("2", "wipe_device")),
            modelText("done")
        ))
        val agent = ToolAgent(llm, FakeRunner())
        agent.run(hist)
        val t = agent.trace.joinToString("\n")
        assertTrue(t.contains("round 1"))
        assertTrue(t.contains("model -> set_torch"))
        assertTrue(t.contains("result set_torch: done set_torch"))
        assertTrue(t.contains("REJECT wipe_device"))
        assertTrue(t.contains("round 2"))
        assertFalse(t.contains("\n\n"))
    }

    @Test
    fun traceKeepsOnlyRecentLines() {
        val replies = ArrayList<ToolTurn.Model?>()
        for (i in 1..30) replies.add(modelCalls(call("c$i", "scroll_screen", "{\"direction\":\"down\",\"times\":${(i % 12) + 1}}"), call("d$i", "go_back")))
        val agent = ToolAgent(FakeLlm(replies), FakeRunner(), maxRounds = 30, maxCalls = 1000)
        agent.run(hist)
        assertTrue(agent.trace.size <= 60)
    }

    @Test
    fun modelFailMidwayIsTraced() {
        val agent = ToolAgent(FakeLlm(listOf(modelCalls(call("1", "go_home")), null)), FakeRunner())
        agent.run(hist)
        assertTrue(agent.trace.any { it.startsWith("MODEL FAIL") })
    }

    @Test
    fun rulesMentionRetryAndScreenNow() {
        assertTrue(ToolPrompt.RULES.contains("SCREEN NOW"))
        assertTrue(ToolPrompt.RULES.contains("😕"))
    }

    @Test
    fun untrustedWrapperAndRules() {
        val w = ToolPrompt.untrusted("SCREEN TEXT", "hello")
        assertTrue(w.contains("untrusted"))
        assertTrue(w.endsWith("hello"))
        assertTrue(ToolPrompt.RULES.contains("find_tool"))
    }
}
