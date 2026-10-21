package com.jarvis.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
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

    override fun onDestroy() {
        instance = null
        super.onDestroy()
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

    private fun activeRoot(): AccessibilityNodeInfo? {
        try {
            val r = rootInActiveWindow
            if (r != null) return r
            for (w in windows) {
                if (w.isActive) {
                    val x = w.root
                    if (x != null) return x
                }
            }
        } catch (e: Exception) {
        }
        return null
    }

    /** Abhi jo app screen par hai uska package naam */
    fun currentPackage(): String {
        return try {
            activeRoot()?.packageName?.toString() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /** Abhi jo app screen par hai uska naam (jaise WhatsApp) */
    fun currentAppLabel(): String {
        val pkg = currentPackage()
        if (pkg.isEmpty()) return ""
        if (pkg == packageName) return "Jarvis"
        return try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    /** Screen par jis text ka button/item dikhe use click karo */
    fun clickByText(text: String): Boolean {
        try {
            val root = activeRoot() ?: return false
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
        } catch (e: Exception) {
        }
        return false
    }

    /** Jahan cursor hai (ya pehla likhne wala dabba) wahan text likho */
    fun typeText(text: String): Boolean {
        try {
            val root = activeRoot() ?: return false
            var target: AccessibilityNodeInfo? = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (target == null || !target.isEditable) {
                target = findEditable(root)
            }
            if (target == null) return false
            val args = Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
            )
            return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            return false
        }
    }

    private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable) return n
        for (i in 0 until n.childCount) {
            val r = findEditable(n.getChild(i))
            if (r != null) return r
        }
        return null
    }

    /** Screen par jo kuch dikh raha hai wo sab text lautao (sabse upar app ka naam) */
    fun readScreenText(): String {
        try {
            val root = activeRoot() ?: return ""
            val sb = StringBuilder()
            sb.append("APP: ").append(currentAppLabel()).append("\n")
            collectText(root, sb, 0)
            return sb.toString().trim()
        } catch (e: Exception) {
            return ""
        }
    }

    private fun collectText(n: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (n == null || depth > 40 || sb.length > 5000) return
        if (!n.isVisibleToUser) return
        var line = ""
        val t = n.text
        if (t != null && t.toString().isNotBlank()) {
            line = t.toString().trim()
        } else {
            val d = n.contentDescription
            if (d != null && d.toString().isNotBlank()) {
                line = d.toString().trim()
            }
        }
        if (line.isNotEmpty() && !sb.endsWith(line + "\n")) {
            sb.append(line).append("\n")
        }
        for (i in 0 until n.childCount) {
            collectText(n.getChild(i), sb, depth + 1)
        }
    }
}
