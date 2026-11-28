package com.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerCacheLogicTest {

    private val day = AnswerCacheLogic.DAY_MS
    private val now = 100L * day

    private fun entry(q: String, kind: String = "chat", created: Long = now, hits: Int = 0, last: Long = created, answer: String = "Ye ek achha lamba jawab hai jo kaam ka hai.") =
        CacheEntry(AnswerCacheLogic.key(q), q, answer, kind, created, last, hits)

    // ---------- kya cache ho sakta hai ----------

    @Test
    fun informationQuestionsAreCacheable() {
        assertTrue(AnswerCacheLogic.cacheableChat("photosynthesis kya hota hai aur kaise hota hai"))
        assertTrue(AnswerCacheLogic.cacheableChat("python mein list aur tuple ka antar kya hai"))
        assertTrue(AnswerCacheLogic.cacheableChat("What is the difference between TCP and UDP"))
        assertTrue(AnswerCacheLogic.cacheableChat("how does a transformer neural network work"))
        // sawaal ka nishaan bhi nishaani hai
        assertTrue(AnswerCacheLogic.cacheableChat("python ke decorators mein closure hota hai?"))
    }

    @Test
    fun phoneCommandsAreNeverCached() {
        // "torch jala do" ka purana jawab dena galat hota: torch jalti hi nahi
        for (q in listOf(
            "torch jala do kya aap kar sakte ho", "wifi kaise on karte hain bataiye", "alarm kaise lagate hain kya bata do",
            "whatsapp par message kaise bhejo kya", "volume kitna karna chahiye kya batao", "kya aap call kar sakte ho Rahul ko",
            "screenshot kaise lete hain kya batao", "phone ko silent kaise karte hain kya"
        )) assertFalse(q, AnswerCacheLogic.cacheableChat(q))
    }

    @Test
    fun timeSensitiveQuestionsAreNeverCached() {
        for (q in listOf(
            "aaj ka mausam kaisa hai delhi mein", "latest iphone kya hai aur kaisa hai", "bitcoin ka price kya hai abhi",
            "2025 mein kya kya hua tha duniya mein", "kal ka match kaun jeeta kya", "news kya hai desh ki aaj", "ipl score kya hai live"
        )) assertFalse(q, AnswerCacheLogic.cacheableChat(q))
    }

    @Test
    fun contextAndPersonalQuestionsAreNeverCached() {
        for (q in listOf(
            "iske fayde kya hain batao zara", "mera favourite color kya hoga batao", "ye kya hota hai samjhao mujhe", "uska matlab kya hai aur kaise",
            "mere paas kaunsi dukan hai kya batao", "pichla jawab kya tha explain karo"
        )) assertFalse(q, AnswerCacheLogic.cacheableChat(q))
    }

    @Test
    fun weakOrRiskyQuestionsAreNotCached() {
        assertFalse(AnswerCacheLogic.cacheableChat("python kya hai")) // bahut chhota
        assertFalse(AnswerCacheLogic.cacheableChat("ek kahani sunao jisme ek raja ho")) // jaankari ka sawaal nahi
        assertFalse(AnswerCacheLogic.cacheableChat("bank account kaise khole kya process hai")) // sensitive
        assertFalse(AnswerCacheLogic.cacheableChat("naya jawab do is sawaal ka kya hai")) // refresh maang
        assertFalse(AnswerCacheLogic.cacheableChat("what is " + "x".repeat(400)))
        assertFalse(AnswerCacheLogic.cacheableChat(""))
    }

    @Test
    fun jobCommandsRules() {
        assertTrue(AnswerCacheLogic.cacheableJob("electric cars ke future par research karo"))
        assertFalse(AnswerCacheLogic.cacheableJob("latest ai news par research karo"))
        assertFalse(AnswerCacheLogic.cacheableJob("mere liye research karo bhai"))
        assertFalse(AnswerCacheLogic.cacheableJob("bank loan par research karo"))
        assertFalse(AnswerCacheLogic.cacheableJob("research kar do")) // topic hai hi nahi: pichli baat se aayega
        assertFalse(AnswerCacheLogic.cacheableJob("is topic par detailed research karo"))
        assertFalse(AnswerCacheLogic.cacheableJob("deep research karo background mein"))
        assertFalse(AnswerCacheLogic.cacheableJob("ev par research karo")) // topic bahut chhota/vague
        assertTrue(AnswerCacheLogic.cacheableJob("blockchain par research karo"))
    }

    // ---------- refresh / key / storable ----------

    @Test
    fun refreshRequests() {
        assertTrue(AnswerCacheLogic.isRefresh("naya jawab do"))
        assertTrue(AnswerCacheLogic.isRefresh("Dobara batao please"))
        assertTrue(AnswerCacheLogic.isRefresh("ye purana hai"))
        assertFalse(AnswerCacheLogic.isRefresh("photosynthesis kya hai"))
        assertFalse(AnswerCacheLogic.isRefresh("new york kya hai"))
    }

    @Test
    fun keyIgnoresCaseSymbolsAndFillers() {
        assertEquals("python kya hai", AnswerCacheLogic.key("Python  KYA hai?"))
        assertEquals(AnswerCacheLogic.key("please Python kya hai"), AnswerCacheLogic.key("python kya hai jarvi"))
        assertEquals("job:research:ev ka future", AnswerCacheLogic.jobKey("research", "EV ka future!"))
    }

    @Test
    fun storableReplies() {
        assertTrue(AnswerCacheLogic.storable("Photosynthesis woh prakriya hai jisme paudhe dhoop se khana banate hain."))
        assertFalse(AnswerCacheLogic.storable("ok"))
        assertFalse(AnswerCacheLogic.storable("x".repeat(AnswerCacheLogic.MAX_ANSWER + 1)))
        assertTrue(AnswerCacheLogic.storable("x".repeat(AnswerCacheLogic.MAX_ANSWER)))
        assertFalse(AnswerCacheLogic.storable("Aap kis shehar ke baare mein jaanna chahte hain bataiye?"))
        assertFalse(AnswerCacheLogic.storable("Theek hai kar diya ACTION: FLASH ON"))
        assertFalse(AnswerCacheLogic.storable("OPEN: WhatsApp kholne ja rahi hoon abhi"))
        assertFalse(AnswerCacheLogic.storable("Ruk gayi 🛑 abhi tak ka kaam adhura hai"))
    }

    // ---------- lookup / umar ----------

    @Test
    fun lookupFindsFreshEntryOnly() {
        val e = entry("photosynthesis kya hota hai aur kaise hota hai", created = now - 2 * day)
        assertNotNull(AnswerCacheLogic.lookup(listOf(e), "Photosynthesis kya hota hai aur kaise hota hai?", now))
        assertNull(AnswerCacheLogic.lookup(listOf(e), "photosynthesis kya hota hai", now)) // alag sawaal
        assertNull(AnswerCacheLogic.lookup(emptyList(), "photosynthesis kya hota hai aur kaise hota hai", now))
    }

    @Test
    fun entriesExpireByKind() {
        val chat = entry("a b c d kya", "chat", created = now - 15 * day)
        assertNull(AnswerCacheLogic.lookupKey(listOf(chat), chat.key, now)) // chat 14 din
        val chatOk = entry("a b c d kya", "chat", created = now - 13 * day)
        assertNotNull(AnswerCacheLogic.lookupKey(listOf(chatOk), chatOk.key, now))
        val research = CacheEntry("job:research:x y z", "x y z", "r", "research", now - 8 * day, now, 0)
        assertNull(AnswerCacheLogic.lookupKey(listOf(research), research.key, now)) // research 7 din
        val writing = CacheEntry("job:writing:x y z", "x y z", "r", "writing", now - 4 * day, now, 0)
        assertNull(AnswerCacheLogic.lookupKey(listOf(writing), writing.key, now)) // likhai 3 din
        assertEquals(14L * day, AnswerCacheLogic.ttlMs("chat"))
        assertEquals(7L * day, AnswerCacheLogic.ttlMs("research"))
        assertEquals(14L * day, AnswerCacheLogic.ttlMs("plan"))
        assertEquals(3L * day, AnswerCacheLogic.ttlMs("writing"))
    }

    @Test
    fun entryFromTheFutureIsIgnored() {
        val e = entry("photosynthesis kya hota hai aur kaise", created = now + 5 * day)
        assertNull(AnswerCacheLogic.lookupKey(listOf(e), e.key, now))
    }

    @Test
    fun footerShowsAge() {
        assertTrue(AnswerCacheLogic.footer(entry("a b c d kya", created = now - 3600_000L), now).contains("aaj ka"))
        val f = AnswerCacheLogic.footer(entry("a b c d kya", created = now - 3 * day), now)
        assertTrue(f.contains("3 din purana"))
        assertTrue(f.contains("bina API"))
        assertTrue(f.contains("naya jawab do"))
    }

    // ---------- valid / JSON ----------

    @Test
    fun validEntries() {
        val good = entry("photosynthesis kya hota hai aur kaise hota hai")
        assertTrue(AnswerCacheLogic.valid(good, now))
        assertTrue(AnswerCacheLogic.valid(CacheEntry("job:research:ev ka future", "ev ka future", "ek lamba nateeja", "research", now, now, 0), now))
        // key aur sawaal mel nahi khate
        assertFalse(AnswerCacheLogic.valid(CacheEntry("kuch aur", good.question, good.answer, "chat", now, now, 0), now))
        // chat entry jo asal mein cache hone layak sawaal hi nahi (command)
        assertFalse(AnswerCacheLogic.valid(entry("torch jala do kya aap kar sakte ho"), now))
        assertFalse(AnswerCacheLogic.valid(CacheEntry(good.key, good.question, good.answer, "weird", now, now, 0), now))
        assertFalse(AnswerCacheLogic.valid(CacheEntry(good.key, good.question, "x".repeat(AnswerCacheLogic.MAX_ANSWER + 1), "chat", now, now, 0), now))
        assertFalse(AnswerCacheLogic.valid(CacheEntry(good.key, good.question, good.answer, "chat", now - 30 * day, now, 0), now)) // expire
        assertFalse(AnswerCacheLogic.valid(CacheEntry(good.key, good.question, good.answer, "chat", now + 10 * day, now, 0), now)) // future
        assertFalse(AnswerCacheLogic.valid(CacheEntry("", good.question, good.answer, "chat", now, now, 0), now))
    }

    @Test
    fun jsonRoundTripAndBrokenInput() {
        val items = listOf(entry("photosynthesis kya hota hai aur kaise hota hai", hits = 3), entry("python mein list aur tuple ka antar kya hai"))
        val back = AnswerCacheLogic.fromJson(AnswerCacheLogic.toJson(items))
        assertEquals(2, back.size)
        assertEquals(3, back[0].hits)
        assertEquals(items[0].answer, back[0].answer)
        assertTrue(AnswerCacheLogic.fromJson("").isEmpty())
        assertTrue(AnswerCacheLogic.fromJson("not json").isEmpty())
        assertTrue(AnswerCacheLogic.fromJson("[{\"k\":\"\",\"a\":\"x\"},{\"a\":\"y\"}]").isEmpty())
    }

    // ---------- AnswerBook ----------

    @Test
    fun bookPutReplacesSameKey() {
        val b = AnswerBook(emptyList())
        b.put(entry("photosynthesis kya hota hai aur kaise hota hai", answer = "purana jawab jo kaafi lamba hai yahan"))
        b.put(entry("photosynthesis kya hota hai aur kaise hota hai", answer = "naya jawab jo kaafi lamba hai yahan par"))
        assertEquals(1, b.size())
        assertTrue(b.all()[0].answer.startsWith("naya"))
    }

    @Test
    fun bookEvictsLeastUsedWhenFull() {
        val items = (1..AnswerCacheLogic.MAX_ENTRIES).map { entry("sawaal number $it kya hai batao", hits = if (it == 1) 0 else 5, last = it.toLong()) }
        val b = AnswerBook(items)
        b.put(entry("bilkul naya sawaal kya hai batao", hits = 0, last = 9999L))
        assertEquals(AnswerCacheLogic.MAX_ENTRIES, b.size())
        assertNull(b.get(AnswerCacheLogic.key("sawaal number 1 kya hai batao"))) // sabse kam hits wala gaya
        assertNotNull(b.get(AnswerCacheLogic.key("bilkul naya sawaal kya hai batao")))
    }

    @Test
    fun bookHitsRemoveClearPrune() {
        val k1 = entry("photosynthesis kya hota hai aur kaise hota hai")
        val k2 = entry("python mein list aur tuple ka antar kya hai", created = now - 20 * day)
        val b = AnswerBook(listOf(k1, k2))
        assertTrue(b.hit(k1.key, now + 5))
        assertEquals(1, b.get(k1.key)!!.hits)
        assertEquals(now + 5, b.get(k1.key)!!.lastHit)
        assertFalse(b.hit("nahi hai", now))
        assertEquals(1, b.totalHits())
        assertEquals(1, b.prune(now)) // k2 14 din se purana
        assertEquals(1, b.size())
        assertTrue(b.remove(k1.key))
        assertFalse(b.remove(k1.key))
        b.put(k1)
        assertEquals(1, b.clear())
        assertEquals(0, b.size())
    }

    @Test
    fun bookMergeAddsOnlyValidAndIsIdempotent() {
        val b = AnswerBook(emptyList())
        val good = entry("photosynthesis kya hota hai aur kaise hota hai")
        val bad = entry("torch jala do kya aap kar sakte ho") // command: cache layak nahi
        val old = entry("python mein list aur tuple ka antar kya hai", created = now - 30 * day)
        assertEquals(1, b.merge(listOf(good, bad, old), now))
        assertEquals(0, b.merge(listOf(good), now)) // dobara wahi file: kuch nahi badla
        assertEquals(1, b.size())
        // naya wala purane ko badal deta hai, purana naye ko nahi
        val newer = entry("photosynthesis kya hota hai aur kaise hota hai", created = now + 1000L, answer = "aur naya jawab jo lamba hai yahan par bhi")
        assertEquals(1, b.merge(listOf(newer), now))
        assertTrue(b.get(good.key)!!.answer.startsWith("aur naya"))
        assertEquals(0, b.merge(listOf(good), now))
    }
}
