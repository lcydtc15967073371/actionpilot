package com.shizuku.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 无障碍服务 — 读屏幕、抓点击、感知页面变化
 * 从 ActionPilot 的 RecordAccessibilityService 移植
 */
class ShizukuAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "ShizukuA11y"

        /** 最后读取的屏幕文字 */
        @JvmField
        var lastScreenText: String = ""

        /** 当前前台 App 包名 */
        @JvmField
        var currentPackage: String = ""

        /** 当前前台 App 中文名 */
        @JvmField
        var currentAppName: String = ""

        /** 服务是否运行中（@JvmField 允许 Java 直接访问） */
        @JvmField
        var isRunning: Boolean = false

        /** 从 Java 获取运行状态 */
        @JvmStatic
        fun isServiceRunning(): Boolean = isRunning

        /** 最后点击的元素信息 */
        var lastClickLabel: String = ""
            private set
        var lastClickBounds: String = ""
            private set

        /** UI 操作录制器（由 FloatService 设置） */
        @JvmField
        var uiMapRecorder: UiMapRecorder? = null

        /** 当前服务实例（用于从 Java 调用实例方法） */
        @JvmField
        var serviceInstance: ShizukuAccessibilityService? = null

        /** 从 Java 强制刷新屏幕捕获（确保在主线程执行） */
        @JvmStatic
        fun requestScreenRefresh() {
            val instance = serviceInstance ?: return
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                instance.captureScreen()
            } else {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    instance.captureScreen()
                }
            }
        }
    }

    private var lastContentTime = 0L
    private var lastContentText = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        serviceInstance = this
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 200
        }
        this.serviceInfo = info
        Log.d(TAG, "无障碍服务已连接")
        // 连接后立即捕获当前屏幕，避免首次 read_screen 无数据
        postDelayed(100) { captureScreen() }
    }

    private fun postDelayed(delayMs: Long, action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(action, delayMs)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    val pkg = event.packageName?.toString() ?: return
                    currentPackage = pkg
                    currentAppName = resolveAppName(pkg)
                    lastContentTime = 0
                    lastContentText = ""
                    captureScreen()
                    // 通知录制器
                    val screen = event.className?.toString()
                        ?.substringAfterLast('.')?.substringBefore('$') ?: "unknown"
                    uiMapRecorder?.onWindowChanged(pkg, currentAppName, screen)
                    Log.d(TAG, "窗口切换: $currentAppName/$pkg")
                }

                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    if (System.currentTimeMillis() - lastContentTime < 1500) return
                    captureScreen()
                }

                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    val (label, bounds) = extractClickInfo(event)
                    if (label.isNotBlank()) {
                        lastClickLabel = label
                        lastClickBounds = bounds
                        val viewId = event.source?.viewIdResourceName ?: ""
                        uiMapRecorder?.onAction("CLICK", label,
                            if (bounds.isNotBlank()) "$bounds | $viewId" else viewId)
                        Log.d(TAG, "点击: '$label' $bounds")
                    }
                    if (System.currentTimeMillis() - lastContentTime >= 1000) {
                        captureScreen()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "事件处理错误: ${e.message}")
        }
    }

    override fun onInterrupt() {
        isRunning = false
    }

    override fun onDestroy() {
        isRunning = false
        serviceInstance = null
        super.onDestroy()
    }

    /** 强制刷新屏幕内容 */
    fun refreshScreen() {
        captureScreen()
    }

    /** 捕获当前屏幕所有可见文本 */
    private fun captureScreen() {
        val allTexts = linkedSetOf<String>()

        // 方法 1: getWindows() 遍历所有窗口（不受悬浮窗覆盖影响）
        val windows = try { getWindows() } catch (_: Exception) { null }
        if (windows != null) {
            for (window in windows) {
                try {
                    val pkg = window.root?.packageName?.toString() ?: ""
                    if (pkg == "com.shizuku.ai") continue // 跳过自己的浮窗
                    if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                    val root = window.root ?: continue
                    try { collectVisibleText(root, allTexts, 0) } finally { root.recycle() }
                } catch (_: Exception) {
                } finally {
                    try { window.recycle() } catch (_: Exception) {}
                }
            }
        }

        // 方法 2: rootInActiveWindow 兜底
        if (allTexts.isEmpty()) {
            try {
                val root = rootInActiveWindow ?: return
                try { collectVisibleText(root, allTexts, 0) } finally { root.recycle() }
            } catch (_: Exception) { return }
        }

        if (allTexts.isEmpty()) return
        val content = allTexts.joinToString(" | ").take(8000)
        if (content != lastContentText) {
            lastScreenText = content
            lastContentText = content
            lastContentTime = System.currentTimeMillis()
            uiMapRecorder?.onScreenContent(content.take(500))
            Log.d(TAG, "屏幕内容: ${content.take(200)}")
        }
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo, texts: MutableSet<String>, depth: Int) {
        if (depth > 30 || node == null) return
        if (!node.isVisibleToUser) return

        node.text?.toString()?.trim()?.takeIf { it.length >= 2 }?.let { texts.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.length >= 2 }?.let { texts.add(it) }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectVisibleText(child, texts, depth + 1)
            child.recycle()
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

    private fun extractClickInfo(event: AccessibilityEvent): Pair<String, String> {
        val source = event.source ?: return "" to ""
        val label = extractBestLabel(source)

        var bounds = ""
        val rect = Rect()
        source.getBoundsInScreen(rect)
        if (!rect.isEmpty) {
            bounds = "${rect.left},${rect.top},${rect.right},${rect.bottom}"
        }
        source.recycle()
        return label to bounds
    }

    private fun extractBestLabel(node: AccessibilityNodeInfo): String {
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        node.viewIdResourceName?.let { return it.substringAfterLast('/') }
        return ""
    }
}
