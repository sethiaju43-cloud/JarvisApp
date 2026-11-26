package com.jarvis.app

import org.json.JSONObject

/*
 * Jarvi ke tools ki list. Model ko sirf "core" tools hamesha dikhte hain (token bachane ke liye),
 * baaki "extra" tools find_tool(query) se milte hain.
 * legacyLine(): tool call ko purani ACTION:/OPEN: line mein badalta hai, taaki PhoneActions ka purana code hi chale.
 * null = is tool ka apna alag executor hoga (search, memory, time, screen padhna).
 */
object ToolRegistry {

    private fun strP(
        name: String, desc: String, required: Boolean = true,
        enumValues: List<String> = emptyList(), maxLen: Int = 500, pattern: String? = null
    ) = ToolParam(name, ParamType.STRING, desc, required, enumValues, null, null, maxLen, pattern)

    private fun intP(name: String, desc: String, min: Int, max: Int, required: Boolean = true) =
        ToolParam(name, ParamType.INTEGER, desc, required, emptyList(), min, max)

    private fun boolP(name: String, desc: String, required: Boolean = true) =
        ToolParam(name, ParamType.BOOLEAN, desc, required)

    private fun tool(
        name: String, desc: String, params: List<ToolParam> = emptyList(),
        risk: ToolRisk = ToolRisk.SAFE, group: String = "core", kw: List<String> = emptyList()
    ) = ToolSpec(name, desc, params, risk, group, kw)

    const val TIME_PATTERN = "^([01]?[0-9]|2[0-3]):[0-5][0-9]$"
    const val EVENT_PATTERN = "^[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}$"
    const val URL_PATTERN = "^https?://\\S+$"
    const val DIAL_PATTERN = "^[+0-9 ()-]{3,20}$"
    const val EMAIL_PATTERN = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"

    val all: List<ToolSpec> = listOf(
        // ---- core ----
        tool("open_app", "Open an installed app by its name (e.g. WhatsApp, YouTube, Camera).", listOf(strP("name", "App name", maxLen = 80))),
        tool("go_home", "Go to the phone home screen."),
        tool("go_back", "Press the Back button."),
        tool("open_recents", "Open the recent apps screen."),
        tool(
            "scroll_screen", "Scroll the current screen up or down.",
            listOf(strP("direction", "Scroll direction", enumValues = listOf("up", "down")), intP("times", "How many scrolls", 1, 12, required = false))
        ),
        tool("tap_text", "Tap a button or item on the screen by its visible text or name.", listOf(strP("text", "Visible text or name of the element", maxLen = 120))),
        tool("type_text", "Type text into the currently focused text field.", listOf(strP("text", "Text to type", maxLen = 1000))),
        tool("read_screen_text", "Read the text currently visible on the screen."),
        tool("take_screenshot", "Take a screenshot of the screen."),
        tool("set_torch", "Turn the flashlight on or off.", listOf(boolP("on", "true = on, false = off"))),
        tool(
            "adjust_volume", "Change the media volume: up, down, mute or max.",
            listOf(strP("direction", "Volume change", enumValues = listOf("up", "down", "mute", "max")))
        ),
        tool("set_volume", "Set the media volume to a percentage.", listOf(intP("percent", "Volume percent", 0, 100))),
        tool("get_battery_status", "Tell the battery level and charging state."),
        tool(
            "set_alarm", "Set an alarm at a time of day (24-hour clock).",
            listOf(strP("time", "Time as HH:MM in 24-hour format, e.g. 07:30", maxLen = 5, pattern = TIME_PATTERN), strP("label", "Short label", required = false, maxLen = 60))
        ),
        tool("set_timer", "Start a countdown timer.", listOf(intP("seconds", "Duration in seconds", 1, 86400))),
        tool(
            "make_call", "Call a person by contact name or phone number. The user is asked to confirm first.",
            listOf(strP("target", "Contact name or phone number", maxLen = 80)), ToolRisk.CONFIRM
        ),
        tool(
            "send_sms", "Send an SMS text message. The user is asked to confirm first.",
            listOf(strP("to", "Contact name or phone number", maxLen = 80), strP("message", "Message text", maxLen = 600)), ToolRisk.CONFIRM
        ),
        tool(
            "whatsapp_send_message", "Send a WhatsApp message. The user is asked to confirm first.",
            listOf(strP("to", "Contact name or phone number", maxLen = 80), strP("message", "Message text", maxLen = 600)), ToolRisk.CONFIRM
        ),
        tool(
            "media_control", "Control the music or video that is already playing: play, pause, next or previous.",
            listOf(strP("action", "What to do", enumValues = listOf("play", "pause", "next", "previous")))
        ),
        tool(
            "add_reminder", "Set a reminder notification at a date and time. Use the current time given in the system message to work out the date.",
            listOf(
                strP("message", "What to remind about", maxLen = 200),
                strP("datetime", "When, as YYYY-MM-DD HH:MM (24-hour, phone local time)", maxLen = 16, pattern = EVENT_PATTERN),
                boolP("repeat_daily", "true to repeat every day", required = false)
            )
        ),
        tool(
            "start_background_task",
            "Start a big task in a separate background agent: deep research, long writing, or a detailed plan. It runs for several minutes while the user keeps chatting, and the user gets a notification when it is done. Do not use it for quick questions.",
            listOf(
                strP("kind", "Type of task", enumValues = listOf("research", "writing", "plan")),
                strP("brief", "Full description of what to research, write or plan, with all details the user gave", maxLen = 600)
            )
        ),
        tool("play_music", "Play a song or artist.", listOf(strP("query", "Song or artist name", maxLen = 120))),
        tool("search_web", "Search the web for current information and return a short answer.", listOf(strP("query", "Search query", maxLen = 200))),
        tool("get_current_time", "Get the current date and time on the phone."),
        tool("remember_fact", "Save a fact about the user to long-term memory.", listOf(strP("fact", "The fact to remember", maxLen = 300))),
        tool(
            "find_tool", "Find more tools when none of the current tools can do the job. Describe the task in a few words.",
            listOf(strP("query", "What you want to do", maxLen = 120)), group = "meta"
        ),
        // ---- extra (find_tool se milte hain) ----
        tool(
            "open_settings", "Open a phone settings page.",
            listOf(strP("page", "Settings page", enumValues = listOf("wifi", "bluetooth", "display", "sound", "battery", "location", "apps", "airplane", "main"))),
            group = "extra", kw = listOf("wifi", "bluetooth", "brightness", "hotspot", "airplane", "settings")
        ),
        tool("navigate", "Start navigation (directions) to a place.", listOf(strP("place", "Destination", maxLen = 150)), group = "extra", kw = listOf("route", "directions", "raasta", "maps", "drive")),
        tool("open_url", "Open a website link in the browser.", listOf(strP("url", "Full link starting with http:// or https://", maxLen = 500, pattern = URL_PATTERN)), group = "extra", kw = listOf("website", "link", "browser", "site")),
        tool(
            "add_calendar_event", "Add an event or meeting to the calendar.",
            listOf(strP("title", "Event title", maxLen = 120), strP("datetime", "Start as YYYY-MM-DD HH:MM", maxLen = 16, pattern = EVENT_PATTERN)),
            group = "extra", kw = listOf("meeting", "schedule", "calendar", "appointment")
        ),
        tool(
            "take_photo", "Take a photo with the back or front camera.",
            listOf(strP("camera", "Which camera", enumValues = listOf("back", "front"), required = false)),
            group = "extra", kw = listOf("camera", "selfie", "picture", "photo", "pic")
        ),
        tool("lock_device", "Lock the phone screen.", group = "extra", kw = listOf("lock", "screen off")),
        tool("show_notifications", "Open the notification shade.", group = "extra", kw = listOf("notification", "notifications")),
        tool(
            "calculate", "Calculate an arithmetic expression exactly (+ - * / % and brackets).",
            listOf(strP("expression", "Expression such as 25*18/100", maxLen = 100)),
            group = "extra", kw = listOf("math", "maths", "percent", "calculator", "hisaab", "sum", "multiply", "divide")
        ),
        tool(
            "set_ringer_mode", "Set the phone ringer mode.",
            listOf(strP("mode", "Ringer mode", enumValues = listOf("normal", "vibrate", "silent"))),
            group = "extra", kw = listOf("silent", "vibrate", "ringer", "sound", "mute", "ring")
        ),
        tool(
            "copy_to_clipboard", "Copy text to the clipboard.",
            listOf(strP("text", "Text to copy", maxLen = 1000)),
            group = "extra", kw = listOf("copy", "clipboard", "paste")
        ),
        tool("now_playing", "Tell which song is playing right now.", group = "extra", kw = listOf("song", "music", "playing", "gaana")),
        tool("show_alarms", "Show the list of alarms in the clock app.", group = "extra", kw = listOf("alarm", "alarms", "clock")),
        tool("list_reminders", "List the reminders that are set, numbered.", group = "extra", kw = listOf("reminder", "reminders", "yaad")),
        tool(
            "cancel_reminder", "Cancel a reminder by its number from list_reminders.",
            listOf(intP("number", "Reminder number as listed", 1, 50)),
            group = "extra", kw = listOf("reminder", "reminders", "remove", "delete", "hatao")
        ),
        tool(
            "add_todo", "Add an item to the to-do list.",
            listOf(strP("text", "The task", maxLen = 200)),
            group = "extra", kw = listOf("todo", "task", "list", "kaam")
        ),
        tool("list_todos", "Read the pending to-do items.", group = "extra", kw = listOf("todo", "tasks", "list", "kaam")),
        tool(
            "set_driving_mode", "Turn driving mode on or off (short spoken answers, new messages read aloud).",
            listOf(boolP("on", "true = on, false = off")),
            group = "extra", kw = listOf("driving", "drive", "car", "gaadi")
        ),
        tool(
            "recall_memory", "Look up what the user asked you to remember earlier.",
            listOf(strP("query", "What to look for", maxLen = 120)),
            group = "extra", kw = listOf("memory", "remember", "yaad", "recall", "told")
        ),
        tool(
            "share_text", "Open the share sheet to send text through any app. The user picks the app and sends it themselves.",
            listOf(strP("text", "Text to share", maxLen = 1000)),
            group = "extra", kw = listOf("share", "send", "forward")
        ),
        tool(
            "compose_email", "Open an email draft in the mail app. Nothing is sent until the user presses send.",
            listOf(
                strP("subject", "Email subject", maxLen = 150),
                strP("body", "Email text", maxLen = 2000),
                strP("to", "Recipient email address", required = false, maxLen = 120, pattern = EMAIL_PATTERN)
            ),
            group = "extra", kw = listOf("email", "mail", "gmail")
        ),
        tool(
            "job_status", "Check how a background task is going, or read its result when it is done. Without a number it shows the latest one.",
            listOf(intP("id", "Task number", 1, 9999, required = false)),
            group = "extra", kw = listOf("job", "status", "background", "progress", "research", "result", "hua")
        ),
        tool(
            "cancel_job", "Stop a running background task. Without a number it stops the latest running one.",
            listOf(intP("id", "Task number", 1, 9999, required = false)),
            group = "extra", kw = listOf("cancel", "stop", "job", "background", "research", "band")
        ),
        tool(
            "open_dialer", "Open the phone dialer with a number filled in, without calling.",
            listOf(strP("number", "Phone number", maxLen = 20, pattern = DIAL_PATTERN)),
            group = "extra", kw = listOf("dial", "dialer", "number", "keypad")
        )
    )

    private val byName: Map<String, ToolSpec> = all.associateBy { it.name }

    fun get(name: String): ToolSpec? = byName[name]

    /** Model ko hamesha dikhne wale tools (core + find_tool) */
    fun coreTools(): List<ToolSpec> = all.filter { it.group != "extra" }

    /** find_tool ka kaam: extra tools mein se sabse milte-julte, sabse zyada match pehle */
    fun search(query: String, limit: Int = 5): List<ToolSpec> {
        val toks = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
        if (toks.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<Int, ToolSpec>>()
        for (t in all) {
            if (t.group != "extra") continue
            val hay = (t.name.replace('_', ' ') + " " + t.description + " " + t.keywords.joinToString(" ")).lowercase()
            var score = 0
            for (k in toks) if (hay.contains(k)) score++
            if (score > 0) scored.add(Pair(score, t))
        }
        return scored.sortedWith(compareByDescending<Pair<Int, ToolSpec>> { it.first }.thenBy { it.second.name }).take(limit).map { it.second }
    }

    private val tagWords = Regex("(?i)(ACTION|OPEN|DONE)\\s*:")

    /** Model ke diye text ko ek saaf line banao: naye line, '|' aur ACTION:/OPEN:/DONE: hata do (injection se bachav) */
    fun oneLine(s: String): String =
        tagWords.replace(s.replace('|', ' '), " ").replace(Regex("\\s+"), " ").trim()

    /** Tool call -> purani text line. Call pehle ToolPolicy.check se guzar chuka hona chahiye. null = alag executor. */
    fun legacyLine(call: ToolCall): String? {
        val a: JSONObject = call.args
        fun s(k: String) = oneLine(a.optString(k, ""))
        return when (call.name) {
            "open_app" -> "OPEN: " + s("name")
            "go_home" -> "ACTION: HOME"
            "go_back" -> "ACTION: BACK"
            "open_recents" -> "ACTION: RECENTS"
            "scroll_screen" -> {
                val n = a.optInt("times", 3).coerceIn(1, 12)
                "ACTION: SCROLL_" + s("direction").uppercase() + " " + n
            }
            "tap_text" -> "ACTION: CLICK " + s("text")
            "type_text" -> "ACTION: TYPE " + s("text")
            "take_screenshot" -> "ACTION: SCREENSHOT"
            "set_torch" -> if (a.optBoolean("on", true)) "ACTION: FLASH ON" else "ACTION: FLASH OFF"
            "adjust_volume" -> "ACTION: VOLUME " + s("direction").uppercase()
            "set_volume" -> "ACTION: VOLUME " + a.optInt("percent", 50).coerceIn(0, 100)
            "get_battery_status" -> "ACTION: BATTERY"
            "set_alarm" -> "ACTION: ALARM " + s("time")
            "set_timer" -> "ACTION: TIMER " + a.optInt("seconds", 60).coerceIn(1, 86400)
            "make_call" -> "ACTION: CALL " + s("target")
            "send_sms" -> "ACTION: SMS " + s("to") + " :: " + s("message")
            "whatsapp_send_message" -> "ACTION: WHATSAPP " + s("to") + " :: " + s("message")
            "play_music" -> "ACTION: MUSIC " + s("query")
            "open_settings" -> "ACTION: SETTINGS " + s("page").uppercase()
            "navigate" -> "ACTION: NAVIGATE " + s("place")
            "open_url" -> "ACTION: URL " + s("url")
            "add_calendar_event" -> "ACTION: EVENT " + s("title") + " :: " + s("datetime")
            "take_photo" -> if (s("camera").lowercase() == "front") "ACTION: SELFIE" else "ACTION: PHOTO"
            "lock_device" -> "ACTION: LOCK"
            "show_notifications" -> "ACTION: NOTIFICATIONS"
            else -> null
        }
    }
}
