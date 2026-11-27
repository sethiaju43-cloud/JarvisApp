package com.jarvis.app

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.accessibilityservice.GestureDescription
import android.view.accessibility.AccessibilityNodeInfo

/*
 * Screen control ke naye tools ka Android hissa: elements padhna, number se tap, swipe, scroll_to, wait.
 * Sab Accessibility service se, bina AI call ke. Private app (bank, UPI, password) mein ye sab band hain.
 */
object ScreenTools {

    @Volatile
    private var last: List<ScreenElement> = emptyList()

    @Volatile
    private var lastAt = 0L

    private const val NO_SVC = "Accessibility band ya atak gayi hai. Jarvis Phone Control OFF phir ON karna padega."
    private const val PRIVATE = "Ye private app hai (bank, UPI, message ya password), isliye yahan screen control nahi karungi."

    private fun nap(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
        }
    }

    private fun firstText(n: AccessibilityNodeInfo?, depth: Int): String {
        if (n == null || depth > 3) return ""
        val t = n.text?.toString() ?: ""
        if (t.isNotBlank()) return t
        val d = n.contentDescription?.toString() ?: ""
        if (d.isNotBlank()) return d
        for (i in 0 until n.childCount) {
            val r = firstText(n.getChild(i), depth + 1)
            if (r.isNotBlank()) return r
        }
        return ""
    }

    private fun collect(svc: JarvisAccessibilityService): List<ScreenElement> {
        val root = svc.rootInActiveWindow ?: return emptyList()
        val out = ArrayList<RawElement>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 30 || out.size > 400) return
            try {
                if (n.isVisibleToUser && (n.isClickable || n.isCheckable || n.isEditable)) {
                    var label = n.text?.toString() ?: ""
                    if (label.isBlank()) label = n.contentDescription?.toString() ?: ""
                    if (label.isBlank()) label = firstText(n, 0)
                    if (label.isBlank()) label = n.hintText?.toString() ?: ""
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    out.add(RawElement(label, if (n.isEditable) "field" else "button", r.left, r.top, r.right, r.bottom))
                }
                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
            } catch (e: Exception) {
            }
        }
        walk(root, 0)
        return ScreenLogic.assign(out)
    }

    private fun blocked(ctx: Context, svc: JarvisAccessibilityService?): ToolOutput? {
        if (svc == null) return ToolOutput(NO_SVC, silent = true)
        if (svc.isPrivateApp()) return ToolOutput(PRIVATE, silent = true)
        return null
    }

    fun readElements(ctx: Context): ToolOutput {
        val svc = JarvisAccessibilityService.instance
        blocked(ctx, svc)?.let { return it }
        if (!Prefs.screenToAi(ctx)) {
            return ToolOutput("User ne Privacy setting mein screen text AI ko bhejna band kiya hai, isliye elements nahi padh sakti.", silent = true)
        }
        val els = collect(svc!!)
        last = els
        lastAt = System.currentTimeMillis()
        if (els.isEmpty()) {
            return ToolOutput("Screen par tap karne layak koi cheez nahi mili (screen khali, video ya game ho sakta hai). read_screen_text ya swipe_screen try karo.", silent = true)
        }
        return ToolOutput(ToolPrompt.untrusted("SCREEN ELEMENTS", ScreenLogic.format(els, svc.currentAppLabel())), silent = true)
    }

    private fun sendConfirm(label: String): ToolOutput =
        ToolOutput(
            "Is par '" + label + "' likha hai: bina user ki haan ke nahi dabaungi. User se poochha ja raha hai.",
            confirmQuestion = "'" + label + "' dabaun? (haan / nahi)",
            confirmLine = "ACTION: CLICK " + label
        )

    fun tapElement(ctx: Context, id: Int): ToolOutput {
        val svc = JarvisAccessibilityService.instance
        blocked(ctx, svc)?.let { return it }
        val snap = last
        if (snap.isEmpty()) return ToolOutput("Pehle read_screen_elements chalao, phir uske number se tap karo.")
        if (System.currentTimeMillis() - lastAt > ScreenLogic.SNAPSHOT_MAX_AGE_MS) {
            return ToolOutput("Elements ki list purani ho gayi. Dobara read_screen_elements chalao.")
        }
        val old = snap.firstOrNull { it.id == id } ?: return ToolOutput("Is number (#" + id + ") ka element nahi hai. Dobara read_screen_elements dekho.")
        val now = ScreenLogic.sameElement(old, collect(svc!!))
            ?: return ToolOutput("Screen badal gayi hai, '" + old.label + "' ab wahan nahi dikh raha. Dobara read_screen_elements chalao.")
        if (ToolPolicy.isSendLabel(now.label)) return sendConfirm(now.label)
        svc.tapAt(now.cx.toFloat(), now.cy.toFloat())
        nap(1200)
        return ToolOutput("Tap kar diya #" + id + ": " + now.label)
    }

    fun tapCoordinates(ctx: Context, x: Int, y: Int): ToolOutput {
        val svc = JarvisAccessibilityService.instance
        blocked(ctx, svc)?.let { return it }
        val under = ScreenLogic.hit(collect(svc!!), x, y)
        if (under != null && ToolPolicy.isSendLabel(under.label)) return sendConfirm(under.label)
        svc.tapAt(x.toFloat(), y.toFloat())
        nap(1200)
        return ToolOutput("Tap kar diya (" + x + "," + y + ")")
    }

    private fun swipeNow(ctx: Context, svc: JarvisAccessibilityService, dir: String) {
        val dm = ctx.resources.displayMetrics
        val v = ScreenLogic.swipeVector(dir, dm.widthPixels, dm.heightPixels)
        val p = Path()
        p.moveTo(v[0], v[1])
        p.lineTo(v[2], v[3])
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0L, 350L)).build()
        svc.dispatchGesture(g, null, null)
        nap(800)
    }

    fun swipe(ctx: Context, dir: String): ToolOutput {
        val svc = JarvisAccessibilityService.instance
        blocked(ctx, svc)?.let { return it }
        swipeNow(ctx, svc!!, dir)
        return ToolOutput("Swipe kiya: " + dir)
    }

    fun scrollTo(ctx: Context, text: String, direction: String, maxScrolls: Int, stop: () -> Boolean): ToolOutput {
        val svc = JarvisAccessibilityService.instance
        blocked(ctx, svc)?.let { return it }
        val finger = ScreenLogic.fingerDirForScroll(direction)
        for (i in 0..maxScrolls) {
            if (ScreenLogic.textVisible(svc!!.readScreenText(), text)) {
                return ToolOutput("Mil gaya '" + text + "'" + (if (i > 0) " (" + i + " scroll ke baad)" else ""))
            }
            if (stop()) return ToolOutput("Ruk gayi 🛑")
            if (i == maxScrolls) break
            swipeNow(ctx, svc, finger)
        }
        return ToolOutput("'" + text + "' nahi mila (" + maxScrolls + " scroll ke baad) 😕")
    }

    fun waitForText(ctx: Context, text: String, timeoutSec: Int, stop: () -> Boolean): ToolOutput {
        val svc = JarvisAccessibilityService.instance
        blocked(ctx, svc)?.let { return it }
        val end = System.currentTimeMillis() + timeoutSec * 1000L
        while (true) {
            if (ScreenLogic.textVisible(svc!!.readScreenText(), text)) return ToolOutput("Screen par '" + text + "' dikh gaya ✅")
            if (stop()) return ToolOutput("Ruk gayi 🛑")
            if (System.currentTimeMillis() >= end) break
            nap(700)
        }
        return ToolOutput("'" + text + "' " + timeoutSec + " second mein nahi dikha 😕")
    }

    fun pause(seconds: Int, stop: () -> Boolean): ToolOutput {
        var left = seconds * 1000L
        while (left > 0L) {
            if (stop()) return ToolOutput("Ruk gayi 🛑")
            val slice = if (left > 500L) 500L else left
            nap(slice)
            left -= slice
        }
        return ToolOutput(seconds.toString() + " second ruki ⏱", silent = true)
    }

    fun lockState(ctx: Context): ToolOutput {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return ToolOutput(if (km.isKeyguardLocked) "Phone locked hai 🔒" else "Phone unlocked hai ✅", silent = true)
    }
}
