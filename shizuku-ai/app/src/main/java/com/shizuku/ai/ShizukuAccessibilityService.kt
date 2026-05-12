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

        /** 详细屏幕结构信息（无文本时备用） */
        @JvmField
        var lastScreenStructure: String = ""

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

        /** 从 Java 触发的深度屏幕捕获——带结构信息（无文本时自动补充） */
        @JvmStatic
        fun requestDetailedScreenCapture() {
            val instance = serviceInstance ?: return
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                instance.captureDetailedScreen()
            } else {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    instance.captureDetailedScreen()
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
                    Log.d(TAG, "事件 WINDOW_STATE_CHANGED → $currentAppName/$pkg cls=${event.className}")
                    captureScreen()
                    // 通知录制器
                    val screen = event.className?.toString()
                        ?.substringAfterLast('.')?.substringBefore('$') ?: "unknown"
                    uiMapRecorder?.onWindowChanged(pkg, currentAppName, screen)
                }

                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    if (System.currentTimeMillis() - lastContentTime < 1500) return
                    val pkg = event.packageName?.toString() ?: "?"
                    Log.d(TAG, "事件 WINDOW_CONTENT_CHANGED pkg=$pkg")
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
                        Log.d(TAG, "事件 CLICK: '$label' $bounds")
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

    /** 捕获当前屏幕所有可见文字（事件驱动，轻量快速） */
    private fun captureScreen() {
        val allTexts = linkedSetOf<String>()

        // 主方法: rootInActiveWindow
        val root = try { rootInActiveWindow } catch (_: Exception) { null }
        val rootPkg = root?.packageName?.toString() ?: ""
        Log.d(TAG, "captureScreen: rootInActiveWindow=${root != null} rootPkg=$rootPkg expected=$currentPackage")
        // vivo bug: rootInActiveWindow 有时返回 systemui/微信 而非前台 App
        val rootIsCorrect = root != null && (rootPkg == currentPackage || rootPkg.isEmpty())
        if (rootIsCorrect) {
            try {
                val r = Rect(); root.getBoundsInScreen(r)
                Log.d(TAG, "  root: class=${root.className} childCount=${root.childCount} bounds=[${r.left},${r.top},${r.right},${r.bottom}]")
                val before = allTexts.size
                collectVisibleText(root, allTexts, 0)
                Log.d(TAG, "  collectVisibleText: found ${allTexts.size - before} texts")
            } finally { root.recycle() }
        } else {
            if (root != null) {
                Log.w(TAG, "  rootInActiveWindow 包名不匹配！rootPkg=$rootPkg expected=$currentPackage, 放弃此 root 走 getWindows")
                root.recycle()
            } else {
                Log.w(TAG, "  rootInActiveWindow = null (vivo launcher 特征)")
            }
        }

        // 兜底: getWindows() 遍历所有窗口
        if (!rootIsCorrect || allTexts.isEmpty()) {
            val windows = try { getWindows() } catch (_: Exception) { null }
            if (windows != null) {
                Log.d(TAG, "captureScreen: getWindows() returns ${windows.size} windows:")
                var wi = 0
                for (w in windows) {
                    try {
                        val r = w.root; val t = w.type; val p = r?.packageName
                        val rect = Rect(); w.getBoundsInScreen(rect)
                        Log.d(TAG, "  win[$wi]: type=$t root=${r != null} pkg=$p bounds=[${rect.left},${rect.top},${rect.right},${rect.bottom}]")
                        // 过滤：排除自身浮窗（package 为 null 说明是 WindowManager 直接添加的视图层）
                        if (r != null && p != null && p.toString() != "com.shizuku.ai"
                            && t != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                            try {
                                val before = allTexts.size
                                collectVisibleText(r, allTexts, 0)
                                if (allTexts.size > before)
                                    Log.d(TAG, "    collected ${allTexts.size - before} texts from win[$wi]")
                            } finally { r.recycle() }
                        }
                    } catch (_: Exception) {
                    } finally {
                        try { w.recycle() } catch (_: Exception) {}
                    }
                    wi++
                }
            } else {
                Log.w(TAG, "captureScreen: getWindows() returned null")
            }
        }

        if (allTexts.isEmpty()) {
            Log.d(TAG, "captureScreen: 全链路无文字，清除 lastScreenText")
            lastScreenText = ""
            return
        }
        val content = allTexts.joinToString(" | ").take(8000)
        if (content != lastContentText) {
            lastScreenText = content
            lastContentText = content
            lastContentTime = System.currentTimeMillis()
            uiMapRecorder?.onScreenContent(content.take(500))
            Log.d(TAG, "captureScreen: 更新屏幕内容 (${content.length} chars): ${content.take(200)}")
        } else {
            Log.d(TAG, "captureScreen: 内容未变，跳过")
        }
    }

    /**
     * 深度捕获：无论是否有文字，都收集控件结构信息（class name + bounds + clickable），
     * 让 AI 能读屏幕文字的同时也知道控件位置和可点击性。
     * 包含 getWindows() 兜底，解决 vivo 桌面 rootInActiveWindow 返回 null 的问题。
     */
    private fun captureDetailedScreen() {
        // 先刷新普通文字捕获
        captureScreen()
        lastScreenStructure = ""

        // 尝试1: rootInActiveWindow（需校验包名匹配，vivo 有时返回 systemui/微信）
        val root1 = try { rootInActiveWindow } catch (_: Exception) { null }
        val root1Pkg = root1?.packageName?.toString() ?: ""
        val root1Valid = root1 != null && (root1Pkg == currentPackage || root1Pkg.isEmpty())
        if (root1 != null && !root1Valid) {
            Log.w(TAG, "captureDetailedScreen: rootInActiveWindow 包名=$root1Pkg 不匹配 $currentPackage, 跳过")
        }
        if (root1Valid) {
            try {
                val collected = mutableListOf<String>()
                collectNodeStructure(root1, collected, 0)
                if (collected.isNotEmpty()) {
                    Log.d(TAG, "captureDetailedScreen: 尝试1 成功，${collected.size} 个元素")
                    saveStructure(collected)
                    return
                }
            } finally { root1!!.recycle() }
            Log.w(TAG, "captureDetailedScreen: 尝试1 空")
        } else {
            root1?.recycle()
        }

        // 尝试2: getWindows() 遍历（解决 vivo rootInActiveWindow 不可靠的问题）
        Log.d(TAG, "captureDetailedScreen: 尝试2 getWindows() 目标=$currentPackage")
        val windows = try { getWindows() } catch (_: Exception) { null }
        if (windows != null) {
            Log.d(TAG, "  getWindows() = ${windows.size} windows")

            // 先找完全匹配当前前台 App 的窗口
            for (pass in 0..1) { // pass0=精确匹配, pass1=任意合法窗口
                val collected = mutableListOf<String>()
                var wi = 0
                for (window in windows) {
                    try {
                        val winRoot = window.root
                        if (winRoot == null) { wi++; continue }
                        try {
                            val pkg = winRoot.packageName?.toString() ?: ""
                            if (pkg == "com.shizuku.ai" || pkg.isEmpty()) { wi++; continue }
                            if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) { wi++; continue }
                            if (pass == 0 && pkg != currentPackage) { wi++; continue } // pass0: 只要精确匹配
                            val before = collected.size
                            collectNodeStructure(winRoot, collected, 0)
                            Log.d(TAG, "  win[$wi]: pass=$pass type=${window.type} pkg=$pkg nodes=${collected.size - before}")
                        } finally { winRoot.recycle() }
                    } catch (_: Exception) {
                    } finally {
                        try { window.recycle() } catch (_: Exception) {}
                    }
                    wi++
                }
                if (collected.isNotEmpty()) {
                    Log.d(TAG, "captureDetailedScreen: 尝试2 pass=$pass 成功，${collected.size} 个元素")
                    saveStructure(collected)
                    return
                }
            }
            Log.w(TAG, "captureDetailedScreen: 尝试2 所有 pass 无有效元素")
        } else {
            Log.w(TAG, "captureDetailedScreen: getWindows() = null")
        }

        // 尝试3: 扫描 root 根节点自身
        Log.d(TAG, "captureDetailedScreen: 尝试3 root自身")
        val fallbackRoot = try { rootInActiveWindow } catch (_: Exception) { null }
        if (fallbackRoot != null) {
            try {
                val cls = fallbackRoot.className?.toString()?.substringAfterLast('.') ?: "?"
                val pkg = fallbackRoot.packageName?.toString() ?: ""
                val rect = Rect()
                fallbackRoot.getBoundsInScreen(rect)
                Log.d(TAG, "  fallbackRoot: class=$cls pkg=$pkg bounds=[${rect.left},${rect.top},${rect.right},${rect.bottom}]")
                if (pkg.isNotEmpty()) {
                    lastScreenStructure = "${cls} | ${pkg} ${if (!rect.isEmpty) "[${rect.left},${rect.top},${rect.right},${rect.bottom}]" else ""}"
                }
            } finally { fallbackRoot.recycle() }
        } else {
            Log.w(TAG, "captureDetailedScreen: rootInActiveWindow 一直为 null")
        }

        Log.w(TAG, "捕获完成: lastScreenText='${lastScreenText.take(100)}' lastScreenStructure='${lastScreenStructure.take(100)}'")
    }

    /** 从指定根节点收集结构信息，返回列表或 null */
    private fun collectWindowsStructure(rootProvider: () -> AccessibilityNodeInfo?): MutableList<String>? {
        val root = try { rootProvider() } catch (_: Exception) { null } ?: return null
        try {
            val elements = mutableListOf<String>()
            collectNodeStructure(root, elements, 0)
            return if (elements.isNotEmpty()) elements else null
        } finally {
            root.recycle()
        }
    }

    private fun saveStructure(elements: MutableList<String>) {
        val detail = elements.take(300).joinToString("\n")
        lastScreenStructure = detail
        Log.d(TAG, "屏幕结构: ${elements.size} 个元素")
    }

    /**
     * 收集节点层级结构信息：class name + text/label + bounds + clickable
     */
    private fun collectNodeStructure(node: AccessibilityNodeInfo, elements: MutableList<String>, depth: Int) {
        if (depth > 30) return
        if (!node.isVisibleToUser) return

        val isClickable = node.isClickable
        val hasText = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()

        // 收集中间关键节点（clickable 或有文字）和叶子节点
        if (isClickable || hasText || depth == 0 || node.childCount == 0) {
            val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
            val text = node.text?.toString()?.trim()?.take(60) ?: ""
            val cd = node.contentDescription?.toString()?.trim()?.take(60) ?: ""
            val label = when {
                text.isNotBlank() && cd.isNotBlank() && text != cd -> "$text / $cd"
                text.isNotBlank() -> text
                cd.isNotBlank() -> cd
                else -> ""
            }

            val rect = Rect()
            node.getBoundsInScreen(rect)
            val bounds = if (rect.isEmpty) "" else "中点(${(rect.left+rect.right)/2},${(rect.top+rect.bottom)/2}) [${rect.left},${rect.top},${rect.right},${rect.bottom}]"

            val parts = mutableListOf(cls)
            if (label.isNotBlank()) parts.add("\"$label\"")
            if (bounds.isNotBlank()) parts.add(bounds)
            if (isClickable) parts.add("clickable")

            elements.add("  ".repeat(depth.coerceAtMost(3)) + parts.joinToString(" "))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodeStructure(child, elements, depth + 1)
            child.recycle()
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
