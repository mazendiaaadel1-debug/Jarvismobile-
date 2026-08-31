package com.jarvis.mobile.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The ONLY component in this app allowed to touch the screen on the user's
 * behalf. Every action here maps to a public, documented Android
 * AccessibilityService API — no exploits, no shell commands, no root.
 *
 * The user must enable this manually in Settings > Accessibility; it is
 * never auto-enabled, and MainActivity only deep-links to that settings
 * screen — it cannot flip the toggle itself.
 *
 * This class exposes a plain synchronous API (dispatchTap, dispatchSwipe,
 * typeText, pressBack/Home/RecentApps, findNodeByText) that
 * CommandDispatcher calls; it does not know about the WebSocket or JSON
 * protocol at all, so it can be unit-tested independently.
 */
class JarvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    // Required override — we don't react to every window-content-changed
    // event, we only read the tree on demand when a command asks for it.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    // ── Gestures ─────────────────────────────────────────────────────────

    fun dispatchTap(x: Int, y: Int, callback: (Boolean) -> Unit) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = callback(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = callback(false)
        }, null)
    }

    fun dispatchSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long, callback: (Boolean) -> Unit) {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = callback(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = callback(false)
        }, null)
    }

    /**
     * Types into whatever node currently has input focus. Returns false if
     * nothing focused/editable is found — caller should report that back
     * as a spoken error rather than silently doing nothing.
     */
    fun typeIntoFocusedField(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val arguments = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecentApps(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun pressLockScreen(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        else false   // GLOBAL_ACTION_LOCK_SCREEN needs API 28+; report as unsupported below that

    /**
     * Depth-first search for a visible node whose text or content-description
     * contains [query] (case-insensitive). Used as the Accessibility-tree
     * path in the vision fallback chain described in the spec: "يفضل
     * استخدام Accessibility tree عندما يكون ذلك ممكنًا، ثم Vision كـ
     * fallback" — this is the "Accessibility tree" half; the Vision half is
     * out of scope for this app and lives on the PC side (see
     * IMPLEMENTATION SUMMARY: Known Android limitations).
     */
    fun findNodeCenterByText(query: String): Pair<Int, Int>? {
        val root = rootInActiveWindow ?: return null
        val needle = query.trim().lowercase()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val text = node.text?.toString()?.lowercase()
            val desc = node.contentDescription?.toString()?.lowercase()
            if ((text != null && text.contains(needle)) || (desc != null && desc.contains(needle))) {
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    return Pair(bounds.centerX(), bounds.centerY())
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return null
    }

    companion object {
        /** Null when the user hasn't enabled the service in Settings. Every
         * caller must check for null and return a clear "enable Accessibility"
         * error rather than crash — this is the expected, common case. */
        var instance: JarvisAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }
}
