package com.operit.actionpilot.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.operit.actionpilot.recorder.MapBuilder

class RecordAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "ActionPilot-A11y"

        var mapBuilder: MapBuilder? = null
        var isRunning: Boolean = false
            private set
    }

    private var lastContentTime = 0L
    private var lastContentText = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SELECTED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        this.serviceInfo = info
        Log.d(TAG, "AccessibilityService connected (rich recording mode)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val builder = mapBuilder ?: return
        if (!builder.recording) return

        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    val pkg = event.packageName?.toString() ?: return
                    val screen = event.className?.toString()
                        ?.substringAfterLast('.')?.substringBefore('$') ?: "unknown"
                    val appName = resolveAppName(pkg)
                    val changed = builder.onWindowChanged(pkg, appName, screen)
                    if (changed) {
                        lastContentTime = 0 // reset throttle, force capture
                        captureScreenContent(builder)
                        Log.d(TAG, "Window: $appName/$screen")
                    }
                }

                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    // Throttle: only capture content at most once every 2s for same-window changes
                    if (System.currentTimeMillis() - lastContentTime < 2000) return
                    captureScreenContent(builder)
                }

                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    val (label, viewId) = extractClickInfo(event)
                    if (label.isNotBlank()) {
                        builder.onAction("CLICK", label, viewId)
                        Log.d(TAG, "Click: '$label' ($viewId)")
                    }
                }

                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                    val (label, viewId) = extractClickInfo(event)
                    if (label.isNotBlank()) {
                        builder.onAction("LONG_CLICK", label, viewId)
                        Log.d(TAG, "LongClick: '$label' ($viewId)")
                    }
                }

                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    val text = event.text?.joinToString("")?.trim()
                    if (!text.isNullOrBlank()) {
                        builder.onAction("TEXT_INPUT", text, "")
                        Log.d(TAG, "TextInput: '$text'")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Event error: ${e.message}")
        } finally {
            event.source?.recycle()
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "AccessibilityService interrupted")
        isRunning = false
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    /**
     * Traverse the active window's view hierarchy and collect all visible text.
     * Used to capture "what's on screen" even when the user doesn't click anything.
     */
    private fun captureVisibleText(): String {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return ""

        try {
            val texts = linkedSetOf<String>()
            collectVisibleText(root, texts, 0)
            return texts.joinToString(" | ").take(5000)
        } finally {
            root.recycle()
        }
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo, texts: MutableSet<String>, depth: Int) {
        if (depth > 25 || node == null) return
        if (!node.isVisibleToUser) return

        // Collect text from this node
        node.text?.toString()?.trim()?.takeIf { it.length >= 2 }?.let { texts.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.length >= 2 }?.let { texts.add(it) }

        // Recurse children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectVisibleText(child, texts, depth + 1)
            child.recycle()
        }
    }

    /**
     * Capture visible screen content and record it if different from last capture.
     */
    private fun captureScreenContent(builder: MapBuilder) {
        val content = captureVisibleText()
        if (content.isNotBlank() && content != lastContentText) {
            builder.onAction("SCREEN_CONTENT", content)
            lastContentText = content
            lastContentTime = System.currentTimeMillis()
        }
    }

    private fun resolveAppName(pkg: String): String {
        return try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    /**
     * Extract click label and viewId from an accessibility event.
     * Priority: contentDescription -> text -> hintText -> viewId resource name -> child text
     */
    private fun extractClickInfo(event: AccessibilityEvent): Pair<String, String> {
        // First try event text (often contains button label)
        val eventText = event.text?.joinToString(" ")?.takeIf { it.isNotBlank() }
        if (eventText != null) return eventText to ""

        val source = event.source ?: return "" to ""
        val label = extractBestLabel(source)
        val viewId = source.viewIdResourceName ?: ""
        return label to viewId
    }

    private fun extractBestLabel(node: AccessibilityNodeInfo): String {
        // 1. contentDescription (primary accessibility label)
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        // 2. text (visible button text)
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        // 3. hintText (for input fields)
        node.hintText?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        // 4. viewId resource name (last resort)
        node.viewIdResourceName?.let { return it.substringAfterLast('/') }
        // 5. Check children for text
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                child.text?.toString()?.takeIf { it.isNotBlank() }?.let {
                    child.recycle()
                    return it
                }
                child.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let {
                    child.recycle()
                    return it
                }
                child.recycle()
            }
        }
        return ""
    }
}
