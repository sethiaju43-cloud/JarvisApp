package com.jarvis.app

import android.content.Context

/*
 * Seekhe hue kaam ka Android hissa: store (SharedPreferences), seekhna, dobara chalana, list, bhulana.
 * Model (API) ke bina chalta hai. Data sirf phone par rehta hai.
 */
object TaskMemory {

    private const val PREFS = "jarvis_tasks"

    class Outcome(val text: String, val pendingLine: String?)

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun learningOn(ctx: Context): Boolean = Prefs.bool(ctx, "learn_tasks", true)

    private fun load(ctx: Context): MacroBook = MacroBook(TaskLogic.fromJson(sp(ctx).getString("book", "") ?: ""))

    private fun save(ctx: Context, book: MacroBook) {
        sp(ctx).edit().putString("book", TaskLogic.toJson(book.all())).apply()
    }

    @Synchronized
    fun all(ctx: Context): List<Macro> = load(ctx).all()

    /** Backup se aaye kaam jodo (naye hi jodte hain). Kitne jude, wo lauta do. */
    @Synchronized
    fun merge(ctx: Context, incoming: List<Macro>): Int {
        val book = load(ctx)
        val n = BackupLogic.mergeTasks(book, incoming)
        if (n > 0) save(ctx, book)
        return n
    }

    /** Command pehle seekha ja chuka ho to bina API ke chalao. Na mile ya beech mein fail ho to null (purana tareeka chale). */
    @Synchronized
    fun tryReplay(ctx: Context, command: String, stop: () -> Boolean): Outcome? {
        val book = load(ctx)
        val m = TaskLogic.match(book.all(), command) ?: return null
        val calls = TaskLogic.instantiate(m.macro, m.values)
        val confirm = ctx.applicationContext.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getBoolean("confirm_send", true)
        val res = MacroPlayer(AndroidToolRunner(ctx, stop), confirm, stop).run(calls)
        val now = System.currentTimeMillis()
        return when (res) {
            is AgentResult.Done -> {
                book.record(m.macro.id, true, now)
                save(ctx, book)
                Outcome(res.text, null)
            }
            is AgentResult.NeedConfirm -> {
                book.record(m.macro.id, true, now)
                save(ctx, book)
                Outcome(res.question, res.line)
            }
            is AgentResult.Failed -> {
                book.record(m.macro.id, false, now)
                save(ctx, book)
                null
            }
        }
    }

    /** Kaam poora ho gaya: seekhne layak ho to yaad rakho. Naya seekha to true. */
    @Synchronized
    fun learn(ctx: Context, command: String, calls: List<ToolCall>, hadFailure: Boolean): Boolean {
        if (!learningOn(ctx) || hadFailure || command.isBlank()) return false
        val book = load(ctx)
        val m = TaskLogic.build(book.nextId(), command, calls, System.currentTimeMillis()) ?: return false
        if (!book.add(m)) return false
        save(ctx, book)
        return true
    }

    @Synchronized
    fun listText(ctx: Context): String {
        val all = load(ctx).all()
        if (all.isEmpty()) return "Abhi tak koi kaam yaad nahi kiya."
        return all.withIndex().joinToString("\n") { (i, m) -> (i + 1).toString() + ") " + TaskLogic.describe(m) }
    }

    @Synchronized
    fun forget(ctx: Context, number: Int): String {
        val book = load(ctx)
        val gone = book.forget(number) ?: return "Is number ka yaad kiya hua kaam nahi mila. Pehle list_tasks dekho."
        save(ctx, book)
        return "Bhula diya 🗑: " + TaskLogic.describe(gone)
    }
}
