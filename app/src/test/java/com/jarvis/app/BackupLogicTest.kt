package com.jarvis.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupLogicTest {

    private val day = AnswerCacheLogic.DAY_MS
    private val now = 100L * day

    private fun macro(id: Int, pattern: String): Macro =
        Macro(
            id, pattern.split(" "), emptyList(),
            listOf(MacroStep("open_app", JSONObject("{\"name\":\"WhatsApp\"}")), MacroStep("go_home", JSONObject())), 4, 0, 50L
        )

    private fun ans(q: String, hits: Int = 0, text: String = "Ye ek achha lamba jawab hai jo kaam ka hai.") =
        CacheEntry(AnswerCacheLogic.key(q), q, text, "chat", now, now, hits)

    private fun ok(r: BackupLogic.ParseResult): BackupLogic.Parsed {
        assertTrue("Ok chahiye tha", r is BackupLogic.ParseResult.Ok)
        return (r as BackupLogic.ParseResult.Ok).parsed
    }

    private fun bad(r: BackupLogic.ParseResult): String {
        assertTrue("Bad chahiye tha", r is BackupLogic.ParseResult.Bad)
        return (r as BackupLogic.ParseResult.Bad).message
    }

    @Test
    fun exportThenParseRoundTrip() {
        val json = BackupLogic.export(
            listOf(macro(1, "a b c")), listOf(ans("photosynthesis kya hota hai aur kaise hota hai", 2)), "Pixel 7", "1.2.3", now
        )
        assertTrue(BackupLogic.looksLikeBackup(json))
        val p = ok(BackupLogic.parse(json))
        assertEquals(1, p.tasks.size)
        assertEquals(listOf("a", "b", "c"), p.tasks[0].pattern)
        assertEquals(4, p.tasks[0].uses)
        assertEquals(1, p.answers.size)
        assertEquals(2, p.answers[0].hits)
        assertEquals("Pixel 7", p.device)
        assertEquals(now, p.createdAt)
    }

    @Test
    fun exportHasFormatVersionAndNoSecrets() {
        val json = BackupLogic.export(listOf(macro(1, "a b c")), emptyList(), "Phone", "1.0", now)
        val o = JSONObject(json)
        assertEquals(BackupLogic.FORMAT, o.getString("format"))
        assertEquals(BackupLogic.VERSION, o.getInt("version"))
        for (k in o.keys()) assertTrue(k, k in setOf("format", "version", "appVersion", "device", "createdAt", "tasks", "answers"))
        assertFalse(json.lowercase().contains("api_key"))
        assertFalse(json.lowercase().contains("apikey"))
    }

    @Test
    fun exportKeepsAllTasksAndTheMostUsedAnswersWithinBudget() {
        val answers = (1..20).map { ans("sawaal number $it kya hai batao zara", hits = it, text = "y".repeat(3000)) }
        val json = BackupLogic.export(listOf(macro(1, "a b c"), macro(2, "d e f")), answers, "P", "1", now, budget = 10_000)
        assertTrue(json.length <= 10_500)
        val p = ok(BackupLogic.parse(json))
        assertEquals(2, p.tasks.size) // kaam hamesha poore
        assertTrue(p.answers.size in 1..3)
        // sabse zyada hits wale pehle
        assertTrue(p.answers.any { it.hits == 20 })
        assertFalse(p.answers.any { it.hits == 1 })
    }

    @Test
    fun looksLikeBackupNeedsMarkers() {
        assertFalse(BackupLogic.looksLikeBackup("namaste kaise ho"))
        assertFalse(BackupLogic.looksLikeBackup("{\"format\":\"x\"}"))
        assertFalse(BackupLogic.looksLikeBackup("jarvi-backup"))
    }

    @Test
    fun parseWorksWithTextAroundTheJson() {
        val json = BackupLogic.export(listOf(macro(1, "a b c")), emptyList(), "P", "1", now)
        val p = ok(BackupLogic.parse("restore karo ye lo:\n" + json + "\nthanks"))
        assertEquals(1, p.tasks.size)
    }

    @Test
    fun parseRefusesBadInput() {
        assertTrue(bad(BackupLogic.parse("x".repeat(BackupLogic.MAX_BYTES + 1))).contains("bahut badi"))
        assertTrue(bad(BackupLogic.parse("koi json nahi")).contains("nahi lag raha"))
        assertTrue(bad(BackupLogic.parse("{\"format\": \"jarvi-backup\", \"version\": 1, ")).isNotBlank())
        assertTrue(bad(BackupLogic.parse("{\"format\":\"maya-backup\",\"version\":1}")).contains("nahi hai"))
        assertTrue(bad(BackupLogic.parse("{\"format\":\"jarvi-backup\",\"version\":99}")).contains("update"))
        assertTrue(bad(BackupLogic.parse("{\"format\":\"jarvi-backup\",\"version\":0}")).contains("version"))
        assertTrue(bad(BackupLogic.parse("{\"format\":\"jarvi-backup\"}")).contains("version"))
    }

    @Test
    fun tamperedTasksAreDroppedOnParse() {
        val tampered = "{\"format\":\"jarvi-backup\",\"version\":1,\"device\":\"P\",\"createdAt\":5,\"tasks\":[" +
            "{\"id\":1,\"p\":[\"a\",\"b\",\"c\"],\"e\":[],\"s\":[{\"t\":\"open_app\",\"a\":{\"name\":\"x\"}},{\"t\":\"search_web\",\"a\":{\"query\":\"x\"}}],\"u\":0,\"f\":0,\"l\":0}," +
            "{\"id\":2,\"p\":[\"d\",\"e\",\"f\"],\"e\":[],\"s\":[{\"t\":\"open_app\",\"a\":{\"name\":\"x\"}},{\"t\":\"go_home\",\"a\":{}}],\"u\":0,\"f\":0,\"l\":0}" +
            "],\"answers\":[]}"
        val p = ok(BackupLogic.parse(tampered))
        assertEquals(1, p.tasks.size)
        assertEquals(listOf("d", "e", "f"), p.tasks[0].pattern)
    }

    @Test
    fun mergeTasksIsAdditiveAndIdempotent() {
        val book = MacroBook(listOf(macro(7, "x y z")))
        val incoming = listOf(macro(1, "a b c"), macro(2, "x y z"))
        assertEquals(1, BackupLogic.mergeTasks(book, incoming)) // "x y z" pehle se hai
        assertEquals(2, book.all().size)
        assertEquals(0, BackupLogic.mergeTasks(book, incoming)) // wahi file dobara: kuch nahi
        // naye id mile, purane nahi tute
        assertEquals(listOf(7, 8), book.all().map { it.id })
        assertEquals(0, book.all()[1].fails)
    }

    @Test
    fun fullCycleBackupAndRestoreToAFreshPhone() {
        val tasks = listOf(macro(1, "a b c"), macro(2, "d e f"))
        val answers = listOf(ans("photosynthesis kya hota hai aur kaise hota hai", 5), ans("python mein list aur tuple ka antar kya hai", 1))
        val json = BackupLogic.export(tasks, answers, "Old phone", "1", now)

        // naya phone: khaali
        val book = MacroBook(emptyList())
        val store = AnswerBook(emptyList())
        val p = ok(BackupLogic.parse(json))
        assertEquals(2, BackupLogic.mergeTasks(book, p.tasks))
        assertEquals(2, store.merge(p.answers, now))
        assertEquals(5, store.get(AnswerCacheLogic.key("photosynthesis kya hota hai aur kaise hota hai"))!!.hits)

        // wahi backup dobara: kuch nahi badla
        assertEquals(0, BackupLogic.mergeTasks(book, p.tasks))
        assertEquals(0, store.merge(p.answers, now))
        assertEquals(2, book.all().size)
        assertEquals(2, store.size())
    }

    @Test
    fun summaryTexts() {
        assertTrue(BackupLogic.summary(2, 3, "Pixel").contains("2 seekhe hue kaam"))
        assertTrue(BackupLogic.summary(2, 3, "Pixel").contains("3 yaad kiye jawab"))
        assertTrue(BackupLogic.summary(2, 3, "Pixel").contains("Pixel"))
        assertTrue(BackupLogic.summary(0, 0, "Pixel").contains("kuch naya nahi"))
    }
}
