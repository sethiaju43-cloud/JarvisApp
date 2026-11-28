package com.jarvis.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskLogicTest {

    private fun c(name: String, json: String = "{}") = ToolCall("x", name, JSONObject(json))

    private fun openApp(name: String) = c("open_app", "{\"name\":\"$name\"}")

    private fun tap(text: String) = c("tap_text", "{\"text\":\"$text\"}")

    private val whatsappRun = listOf(openApp("WhatsApp"), tap("Rahul"))

    private fun learned(): Macro = TaskLogic.build(1, "WhatsApp kholo aur Rahul ki chat kholo", whatsappRun, 100L)!!

    // ---------- shabd ----------

    @Test
    fun tokenizeKeepsRawAndDropsFillers() {
        val t = TaskLogic.tokenize("Please WhatsApp kholo, Rahul ki chat!")
        assertEquals(listOf("whatsapp", "kholo", "rahul", "ki", "chat"), t.map { it.norm })
        assertEquals("Rahul", t[2].raw)
        assertEquals("whatsapp kholo rahul", TaskLogic.normalize("Jarvi WhatsApp kholo bhai Rahul"))
        assertTrue(TaskLogic.tokenize("   ").isEmpty())
    }

    @Test
    fun sensitiveCommands() {
        assertTrue(TaskLogic.sensitiveCommand("password type karo"))
        assertTrue(TaskLogic.sensitiveCommand("bank app kholo aur login karo"))
        assertTrue(TaskLogic.sensitiveCommand("OTP bhejo"))
        assertFalse(TaskLogic.sensitiveCommand("WhatsApp kholo aur Rahul ki chat kholo"))
    }

    // ---------- seekhne layak steps ----------

    @Test
    fun learnableNeedsTwoReplayableSteps() {
        assertEquals(2, TaskLogic.learnableSteps(whatsappRun)!!.size)
        assertNull(TaskLogic.learnableSteps(listOf(openApp("WhatsApp"))))
        assertNull(TaskLogic.learnableSteps(emptyList()))
    }

    @Test
    fun nonReplayableToolsBlockLearning() {
        assertNull(TaskLogic.learnableSteps(listOf(openApp("X"), c("search_web", "{\"query\":\"a\"}"))))
        assertNull(TaskLogic.learnableSteps(listOf(openApp("X"), c("get_current_time"))))
        assertNull(TaskLogic.learnableSteps(listOf(openApp("X"), c("tap_element", "{\"id\":3}")))) // number badalte hain
        assertNull(TaskLogic.learnableSteps(listOf(openApp("X"), c("start_background_task", "{\"kind\":\"research\",\"brief\":\"x\"}"))))
        assertNull(TaskLogic.learnableSteps(listOf(openApp("X"), c("add_reminder", "{\"message\":\"m\",\"datetime\":\"2026-12-01 10:00\"}"))))
    }

    @Test
    fun infoToolsAreSkipped() {
        val s = TaskLogic.learnableSteps(listOf(c("find_tool", "{\"query\":\"x\"}"), c("read_screen_text"), openApp("X"), tap("Y"), c("get_battery_status")))!!
        assertEquals(listOf("open_app", "tap_text"), s.map { it.tool })
    }

    @Test
    fun typingAfterReadingScreenIsNotLearned() {
        assertNull(TaskLogic.learnableSteps(listOf(openApp("X"), c("read_screen_text"), c("type_text", "{\"text\":\"hi\"}"))))
        assertNotNull(TaskLogic.learnableSteps(listOf(openApp("X"), c("type_text", "{\"text\":\"hi\"}"))))
    }

    @Test
    fun longDigitTypingIsNotLearned() {
        assertNull(TaskLogic.learnableSteps(listOf(openApp("X"), c("type_text", "{\"text\":\"123456\"}"))))
        assertNotNull(TaskLogic.learnableSteps(listOf(openApp("X"), c("type_text", "{\"text\":\"12345\"}"))))
    }

    @Test
    fun confirmToolsOnlyAsLastStep() {
        assertNotNull(TaskLogic.learnableSteps(listOf(openApp("X"), c("send_sms", "{\"to\":\"Rahul\",\"message\":\"hi\"}"))))
        assertNotNull(TaskLogic.learnableSteps(listOf(openApp("X"), c("make_call", "{\"target\":\"Rahul\"}"))))
        assertNull(TaskLogic.learnableSteps(listOf(c("send_sms", "{\"to\":\"Rahul\",\"message\":\"hi\"}"), openApp("X"))))
    }

    @Test
    fun consecutiveDuplicatesAreRemoved() {
        val s = TaskLogic.learnableSteps(listOf(openApp("X"), openApp("X"), tap("Y")))!!
        assertEquals(2, s.size)
        // sirf ek hi step bacha to seekhne layak nahi
        assertNull(TaskLogic.learnableSteps(listOf(openApp("X"), openApp("X"))))
    }

    @Test
    fun tooManyStepsNotLearned() {
        val many = (1..13).map { tap("btn $it") }
        assertNull(TaskLogic.learnableSteps(many))
        assertNotNull(TaskLogic.learnableSteps((1..12).map { tap("btn $it") }))
    }

    // ---------- banana ----------

    @Test
    fun buildMakesSlotsFromWordsThatAppearInSteps() {
        val m = learned()
        assertEquals(listOf("{1}", "kholo", "aur", "{2}", "ki", "chat", "kholo"), m.pattern)
        assertEquals(listOf("WhatsApp", "Rahul"), m.examples)
        assertEquals("{1}", m.steps[0].args.getString("name"))
        assertEquals("{2}", m.steps[1].args.getString("text"))
        assertEquals(0, m.uses)
    }

    @Test
    fun buildDoesNotChangeTheOriginalCalls() {
        learned()
        assertEquals("WhatsApp", whatsappRun[0].args.getString("name"))
    }

    @Test
    fun substringOfAWordIsNotASlot() {
        // "app" WhatsApp ke andar hai par poora shabd nahi
        val m = TaskLogic.build(1, "app kholo phir whatsapp chalao hai", listOf(openApp("WhatsApp"), tap("Chats")), 0L)!!
        assertEquals(listOf("app", "kholo", "phir", "{1}", "chalao", "hai"), m.pattern)
    }

    @Test
    fun atMostThreeSlots() {
        val calls = listOf(c("type_text", "{\"text\":\"alpha beta gamma delta\"}"), tap("alpha"))
        val m = TaskLogic.build(1, "likho alpha beta gamma delta phir bhejo", calls, 0L)!!
        assertEquals(3, m.pattern.count { it.startsWith("{") })
        assertEquals("delta", m.pattern[4])
    }

    @Test
    fun buildRefusesRiskyOrWeakCommands() {
        assertNull(TaskLogic.build(1, "bank app kholo aur login karo", whatsappRun, 0L))
        assertNull(TaskLogic.build(1, "kholo chat", whatsappRun, 0L)) // bahut chhota
        assertNull(TaskLogic.build(1, "whatsapp rahul amit", listOf(openApp("whatsapp"), tap("rahul"), tap("amit")), 0L)) // koi pakka shabd nahi
        assertNull(TaskLogic.build(1, "WhatsApp kholo aur Rahul ki chat kholo", listOf(openApp("WhatsApp")), 0L)) // 1 step
    }

    @Test
    fun numbersAreNeverSlots() {
        val m = TaskLogic.build(1, "volume 30 karo aur torch on karo", listOf(c("set_volume", "{\"percent\":30}"), c("set_torch", "{\"on\":true}")), 0L)!!
        assertEquals(listOf("volume", "30", "karo", "aur", "torch", "on", "karo"), m.pattern)
    }

    // ---------- milana ----------

    @Test
    fun matchFillsSlotsAndInstantiates() {
        val m = learned()
        val r = TaskLogic.match(listOf(m), "Instagram kholo aur Amit ki chat kholo")!!
        assertEquals(listOf("Instagram", "Amit"), r.values.take(2))
        val calls = TaskLogic.instantiate(r.macro, r.values)
        assertEquals("open_app", calls[0].name)
        assertEquals("Instagram", calls[0].args.getString("name"))
        assertEquals("Amit", calls[1].args.getString("text"))
    }

    @Test
    fun sameCommandMatchesItself() {
        val m = learned()
        val r = TaskLogic.match(listOf(m), "please WhatsApp kholo aur rahul ki chat kholo")!!
        val calls = TaskLogic.instantiate(r.macro, r.values)
        assertEquals("WhatsApp", calls[0].args.getString("name"))
        assertEquals("rahul", calls[1].args.getString("text")) // naye command jaisa likha hua
    }

    @Test
    fun matchNeedsSameShape() {
        val m = learned()
        assertNull(TaskLogic.match(listOf(m), "WhatsApp kholo aur Rahul ki chat band karo"))
        assertNull(TaskLogic.match(listOf(m), "WhatsApp kholo aur Rahul ki chat"))
        assertNull(TaskLogic.match(listOf(m), "WhatsApp kholo aur Rahul Sharma ki chat kholo")) // do shabd ka naam: ye slot nahi
        assertNull(TaskLogic.match(listOf(m), ""))
        assertNull(TaskLogic.match(emptyList(), "WhatsApp kholo aur Rahul ki chat kholo"))
    }

    @Test
    fun matchPrefersMoreFixedWords() {
        val steps = listOf(MacroStep("open_app", JSONObject("{\"name\":\"X\"}")), MacroStep("tap_text", JSONObject("{\"text\":\"Y\"}")))
        val loose = Macro(1, listOf("{1}", "kholo", "aur", "{2}"), listOf("a", "b"), steps, 5, 0, 0L)
        val tight = Macro(2, listOf("whatsapp", "kholo", "aur", "rahul"), emptyList(), steps, 0, 0, 0L)
        assertEquals(2, TaskLogic.match(listOf(loose, tight), "whatsapp kholo aur rahul")!!.macro.id)
        assertEquals(1, TaskLogic.match(listOf(loose, tight), "youtube kholo aur amit")!!.macro.id)
    }

    @Test
    fun sameSlotTwiceNeedsSameValue() {
        val steps = listOf(MacroStep("open_app", JSONObject("{\"name\":\"{1}\"}")), MacroStep("tap_text", JSONObject("{\"text\":\"{1}\"}")))
        val m = Macro(1, listOf("{1}", "aur", "phir", "{1}"), listOf("x"), steps, 0, 0, 0L)
        assertNotNull(TaskLogic.match(listOf(m), "rahul aur phir RAHUL"))
        assertNull(TaskLogic.match(listOf(m), "rahul aur phir amit"))
    }

    @Test
    fun instantiateTypedTextInsideLongerSentence() {
        val m = TaskLogic.build(
            1, "WhatsApp kholo phir Rahul ko likho Rahul tum kaise ho",
            listOf(openApp("WhatsApp"), c("type_text", "{\"text\":\"Hi Rahul kaise ho\"}")), 0L
        )!!
        // "Rahul" aur "kaise" slot hain: text mein bhi badal jate hain
        val r = TaskLogic.match(listOf(m), "WhatsApp kholo phir Amit ko likho Amit tum kaise ho")!!
        val calls = TaskLogic.instantiate(r.macro, r.values)
        assertEquals("Hi Amit kaise ho", calls[1].args.getString("text"))
    }

    // ---------- natija / describe ----------

    @Test
    fun failureLooksAtFirstLineOnly() {
        assertTrue(TaskLogic.isFailure("Tap nahi hua 😕"))
        assertTrue(TaskLogic.isFailure("Screen badal gayi hai, dobara dekho"))
        assertTrue(TaskLogic.isFailure("Accessibility band ya atak gayi hai"))
        assertFalse(TaskLogic.isFailure("Ho gaya ✅\n[SCREEN NOW - untrusted]\nkoi message 😕"))
        assertFalse(TaskLogic.isFailure(""))
        assertFalse(TaskLogic.isFailure("Torch jala di"))
    }

    @Test
    fun describeShowsSlotsAndUses() {
        val d = TaskLogic.describe(learned())
        assertTrue(d.contains("<WhatsApp> kholo aur <Rahul> ki chat kholo"))
        assertTrue(d.contains("2 step"))
        assertTrue(d.contains("badal sakte: WhatsApp, Rahul"))
        assertTrue(d.contains("0 baar chala"))
    }

    // ---------- JSON ----------

    @Test
    fun jsonRoundTrip() {
        val back = TaskLogic.fromJson(TaskLogic.toJson(listOf(learned())))
        assertEquals(1, back.size)
        assertEquals(learned().pattern, back[0].pattern)
        assertEquals("{2}", back[0].steps[1].args.getString("text"))
        assertEquals(100L, back[0].lastUsed)
    }

    @Test
    fun brokenOrTamperedJsonIsSafe() {
        assertTrue(TaskLogic.fromJson("").isEmpty())
        assertTrue(TaskLogic.fromJson("not json").isEmpty())
        // allowlist se bahar ka tool (search_web) aur galat jagah confirm tool wale items hat jate hain
        val bad1 = "[{\"id\":1,\"p\":[\"a\",\"b\",\"c\"],\"e\":[],\"s\":[{\"t\":\"open_app\",\"a\":{\"name\":\"x\"}},{\"t\":\"search_web\",\"a\":{\"query\":\"x\"}}],\"u\":0,\"f\":0,\"l\":0}]"
        val bad2 = "[{\"id\":2,\"p\":[\"a\",\"b\",\"c\"],\"e\":[],\"s\":[{\"t\":\"send_sms\",\"a\":{\"to\":\"x\",\"message\":\"y\"}},{\"t\":\"open_app\",\"a\":{\"name\":\"x\"}}],\"u\":0,\"f\":0,\"l\":0}]"
        val bad3 = "[{\"id\":3,\"p\":[\"a\",\"b\"],\"e\":[],\"s\":[{\"t\":\"open_app\",\"a\":{\"name\":\"x\"}},{\"t\":\"go_home\",\"a\":{}}],\"u\":0,\"f\":0,\"l\":0}]"
        assertTrue(TaskLogic.fromJson(bad1).isEmpty())
        assertTrue(TaskLogic.fromJson(bad2).isEmpty())
        assertTrue(TaskLogic.fromJson(bad3).isEmpty()) // pattern bahut chhota
        val good = "[{\"id\":4,\"p\":[\"a\",\"b\",\"c\"],\"e\":[],\"s\":[{\"t\":\"open_app\",\"a\":{\"name\":\"x\"}},{\"t\":\"send_sms\",\"a\":{\"to\":\"x\",\"message\":\"y\"}}],\"u\":3,\"f\":1,\"l\":7}]"
        val g = TaskLogic.fromJson(good)
        assertEquals(1, g.size)
        assertEquals(3, g[0].uses)
        assertEquals(1, g[0].fails)
    }

    // ---------- MacroBook ----------

    private fun macro(id: Int, pattern: String, lastUsed: Long = 0L, fails: Int = 0, uses: Int = 0): Macro =
        Macro(
            id, pattern.split(" "), emptyList(),
            listOf(MacroStep("open_app", JSONObject("{\"name\":\"x\"}")), MacroStep("go_home", JSONObject())), uses, fails, lastUsed
        )

    @Test
    fun bookAddsAndRejectsHealthyDuplicates() {
        val b = MacroBook(emptyList())
        assertEquals(1, b.nextId())
        assertTrue(b.add(macro(1, "a b c")))
        assertFalse(b.add(macro(2, "a b c")))
        assertEquals(1, b.all().size)
        assertEquals(2, b.nextId())
    }

    @Test
    fun bookReplacesBrokenDuplicate() {
        val b = MacroBook(listOf(macro(1, "a b c", fails = 1)))
        assertTrue(b.add(macro(2, "a b c")))
        assertEquals(listOf(2), b.all().map { it.id })
    }

    @Test
    fun bookEvictsOldestWhenFull() {
        val b = MacroBook((1..TaskLogic.MAX_MACROS).map { macro(it, "p$it x y", lastUsed = it.toLong() * 10) })
        assertTrue(b.add(macro(99, "new one here", lastUsed = 5000L)))
        assertEquals(TaskLogic.MAX_MACROS, b.all().size)
        assertTrue(b.all().none { it.id == 1 }) // sabse purana gaya
        assertTrue(b.all().any { it.id == 99 })
    }

    @Test
    fun bookRecordsResults() {
        val b = MacroBook(listOf(macro(1, "a b c", fails = 1, uses = 2)))
        b.record(1, true, 500L)
        val m = b.all()[0]
        assertEquals(3, m.uses)
        assertEquals(0, m.fails)
        assertEquals(500L, m.lastUsed)
        b.record(1, false, 600L)
        assertEquals(1, b.all()[0].fails)
        b.record(1, false, 700L) // lagaataar doosri baar: hata do
        assertTrue(b.all().isEmpty())
        assertFalse(b.record(1, true, 800L))
    }

    @Test
    fun bookForgetByNumber() {
        val b = MacroBook(listOf(macro(1, "a b c"), macro(2, "d e f")))
        assertNull(b.forget(0))
        assertNull(b.forget(3))
        assertEquals(2, b.forget(2)!!.id)
        assertEquals(1, b.all().size)
        b.clear()
        assertTrue(b.all().isEmpty())
    }

    // ---------- MacroPlayer ----------

    private class Runner(val failOn: String = "", val confirmOn: String = "") : ToolRunner {
        val ran = ArrayList<String>()
        override fun run(call: ToolCall): ToolOutput {
            ran.add(call.name)
            if (call.name == failOn) return ToolOutput("Nahi hua 😕")
            if (call.name == confirmOn) return ToolOutput("x", confirmQuestion = "Pakka?", confirmLine = "ACTION: CLICK Pay")
            return ToolOutput("done " + call.name + "\n[SCREEN NOW]\nkuch")
        }
    }

    private fun calls(vararg c: ToolCall) = c.toList()

    @Test
    fun playerRunsAllStepsWithoutAModel() {
        val r = Runner()
        val res = MacroPlayer(r).run(calls(openApp("WhatsApp"), tap("Rahul"), c("go_back")))
        assertTrue(res is AgentResult.Done)
        assertEquals(listOf("open_app", "tap_text", "go_back"), r.ran)
        assertTrue((res as AgentResult.Done).text.contains("bina API"))
        assertTrue(res.text.contains("done go_back"))
    }

    @Test
    fun playerStopsOnFailureSoModelCanTakeOver() {
        val r = Runner(failOn = "tap_text")
        val res = MacroPlayer(r).run(calls(openApp("WhatsApp"), tap("Rahul"), c("go_back")))
        assertTrue(res is AgentResult.Failed)
        assertTrue((res as AgentResult.Failed).reason.startsWith("step 2"))
        assertEquals(listOf("open_app", "tap_text"), r.ran)
    }

    @Test
    fun playerAsksBeforeSend() {
        val r = Runner()
        val res = MacroPlayer(r).run(calls(openApp("WhatsApp"), tap("Send")))
        assertTrue(res is AgentResult.NeedConfirm)
        assertEquals("ACTION: CLICK Send", (res as AgentResult.NeedConfirm).line)
        assertEquals(listOf("open_app"), r.ran) // Send chala hi nahi
    }

    @Test
    fun playerAsksBeforeCallOrSms() {
        val r = Runner()
        val res = MacroPlayer(r).run(calls(openApp("WhatsApp"), c("send_sms", "{\"to\":\"Rahul\",\"message\":\"hi\"}")))
        assertTrue(res is AgentResult.NeedConfirm)
        assertEquals("ACTION: SMS Rahul :: hi", (res as AgentResult.NeedConfirm).line)
        assertEquals(listOf("open_app"), r.ran)
    }

    @Test
    fun playerRunsConfirmStepsWhenUserTurnedConfirmationOff() {
        val r = Runner()
        val res = MacroPlayer(r, confirmCalls = false).run(calls(openApp("WhatsApp"), c("make_call", "{\"target\":\"Rahul\"}")))
        assertTrue(res is AgentResult.Done)
        assertEquals(listOf("open_app", "make_call"), r.ran)
    }

    @Test
    fun playerRespectsRunnerConfirmation() {
        val r = Runner(confirmOn = "tap_text")
        val res = MacroPlayer(r).run(calls(openApp("X"), tap("Pay")))
        assertTrue(res is AgentResult.NeedConfirm)
        assertEquals("Pakka?", (res as AgentResult.NeedConfirm).question)
    }

    @Test
    fun playerRejectsTamperedSteps() {
        val r = Runner()
        // galat argument (volume 500): policy rok deti hai, kuch chalta nahi
        val res = MacroPlayer(r).run(calls(openApp("X"), c("set_volume", "{\"percent\":500}")))
        assertTrue(res is AgentResult.Failed)
        assertEquals(listOf("open_app"), r.ran)
        // allowlist se bahar ka tool chalne se pehle hi roka jata hai (blocked)
        val r2 = Runner()
        val res2 = MacroPlayer(r2).run(calls(c("wipe_device"), openApp("X")))
        assertTrue(res2 is AgentResult.Failed)
        assertTrue(r2.ran.isEmpty())
    }

    @Test
    fun playerHonoursStop() {
        val r = Runner()
        val res = MacroPlayer(r, stop = { r.ran.size >= 1 }).run(calls(openApp("X"), tap("Y")))
        assertEquals("Ruk gayi 🛑", (res as AgentResult.Done).text)
        assertEquals(listOf("open_app"), r.ran)
    }

    @Test
    fun learnThenReplayEndToEnd() {
        val m = learned()
        val r = Runner()
        val match = TaskLogic.match(listOf(m), "Instagram kholo aur Amit ki chat kholo")!!
        val res = MacroPlayer(r).run(TaskLogic.instantiate(match.macro, match.values))
        assertTrue(res is AgentResult.Done)
        assertEquals(listOf("open_app", "tap_text"), r.ran)
    }
}
