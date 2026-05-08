package com.operit.actionpilot.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import com.operit.actionpilot.recorder.MapBuilder

/**
 * Optional AccessibilityService that provides click-level recording detail.
 * Works alongside RecordService (Shizuku polling) for enhanced data.
 *
 * Safe design: START_NOT_STICKY, no auto-start, no overlay, only low-frequency events.
 */
class RecordAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "ActionPilot-A11y"

        /**
         * Set by MainActivity when user enables enhanced recording.
         * The MapBuilder from RecordService is shared here.
         */
        var mapBuilder: MapBuilder? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "AccessibilityService connected (enhanced recording)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val builder = mapBuilder ?: return
        if (!builder.recording) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                val screen = event.className?.toString()
                    ?.substringAfterLast('.')?.substringBefore('$') ?: "unknown"
                builder.onWindowChanged(pkg, pkg, screen)
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val label = extractLabel(event.source) ?: ""
                builder.onAction("CLICK", label)
                event.source?.recycle()
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "AccessibilityService interrupted")
    }

    private fun extractLabel(source: AccessibilityNodeInfo?): String? {
        if (source == null) return null
        source.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        source.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        source.viewIdResourceName?.let { return it.substringAfterLast('/') }
        return null
    }
}
