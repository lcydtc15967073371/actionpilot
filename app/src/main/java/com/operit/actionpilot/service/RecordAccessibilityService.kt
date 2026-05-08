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

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
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
                    builder.onWindowChanged(pkg, appName, screen)
                    Log.d(TAG, "Window: $appName/$screen")
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
