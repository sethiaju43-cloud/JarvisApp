package com.jarvis.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        var instance: JarvisAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)

    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)

    fun openRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    // Neeche scroll = ungli neeche se upar swipe
    fun scrollDown() = swipe(0.7f, 0.3f)

    // Upar scroll = ungli upar se neeche swipe
    fun scrollUp() = swipe(0.3f, 0.7f)

    private fun swipe(fromY: Float, toY: Float) {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val path = Path().apply {
            moveTo(w / 2f, h * fromY)
            lineTo(w / 2f, h * toY)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 400))
            .build()
        dispatchGesture(g, null, null)
    }

    fun tapCenter() {
        val dm = resources.displayMetrics
        val path = Path().apply {
            moveTo(dm.widthPixels / 2f, dm.heightPixels / 2f)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(g, null, null)
    }

    /** Screen par jis text ka button/item dikhe use click karo */
    fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (n in nodes) {
            var cur: AccessibilityNodeInfo? = n
            while (cur != null) {
                if (cur.isClickable) {
                    cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
                cur = cur.parent
            }
        }
        return false
    }

    /** Screen par jo kuch likha hai wo sab text lautao */
    fun readScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        collectText(root, sb, 0)
        return sb.toString().trim()
    }

    private fun collectText(n: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (n == null || depth > 40 || sb.length > 6000) return
        val t = n.text
        if (t != null && t.toString().isNotBlank()) {
            sb.append(t.toString().trim()).append("\n")
        } else {
            val d = n.contentDescription
            if (d != null && d.toString().isNotBlank()) {
                sb.append(d.toString().trim()).append("\n")
            }
        }
        for (i in 0 until n.childCount) {
            collectText(n.getChild(i), sb, depth + 1)
        }
    }
}
