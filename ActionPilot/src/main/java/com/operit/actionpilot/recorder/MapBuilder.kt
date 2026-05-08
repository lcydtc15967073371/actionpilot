package com.operit.actionpilot.recorder

import com.operit.actionpilot.model.MutableOpMap
import com.operit.actionpilot.model.OpAction
import com.operit.actionpilot.model.OpEdge
import com.operit.actionpilot.model.OpMap
import com.operit.actionpilot.model.OpNode

class MapBuilder {

    private var _map = MutableOpMap()
    private var _recording = false
    private var _lastClickLabel = ""
    private var _lastClickTime = 0L

    val recording: Boolean get() = _recording

    fun start() {
        _map = MutableOpMap(startedAt = System.currentTimeMillis())
        _recording = true
        _lastClickLabel = ""
        _lastClickTime = 0L
    }

    fun stop(): OpMap {
        _recording = false
        return _map.toImmutable()
    }

    /**
     * @return true if the window change was applied, false if skipped (dedup/filtered)
     */
    fun onWindowChanged(appPackage: String, appName: String, screenName: String): Boolean {
        if (!_recording) return false
        val nodeId = nodeId(appPackage, screenName)

        // Dedup: A11y + Shizuku both report the same transition
        if (nodeId == _map.currentId) return false
        val now = System.currentTimeMillis()
        val prevId = _map.currentId

        // Update or create node
        val existing = _map.nodes[nodeId]
        _map.nodes[nodeId] = if (existing != null) {
            existing.copy(
                lastSeen = now,
                visitCount = existing.visitCount + 1
            )
        } else {
            OpNode(
                id = nodeId,
                appPackage = appPackage,
                appName = appName,
                screenName = screenName,
                firstSeen = now,
                lastSeen = now,
                visitCount = 1
            )
        }

        // Record transition edge from previous screen
        if (prevId != null && prevId != nodeId) {
            val existingEdge = _map.edges.find { it.fromId == prevId && it.toId == nodeId }
            val idx = _map.edges.indexOf(existingEdge)
            if (idx >= 0) {
                _map.edges[idx] = existingEdge!!.copy(
                    count = existingEdge.count + 1,
                    lastTime = now
                )
            } else {
                // Use last click label as transition trigger if within 1.5s
                // Consume after first use to prevent mislabeling subsequent transitions
                val label = if (now - _lastClickTime < 1500) {
                    val lbl = _lastClickLabel
                    _lastClickLabel = ""
                    _lastClickTime = 0L
                    lbl
                } else ""
                _map.edges.add(
                    OpEdge(
                        fromId = prevId,
                        toId = nodeId,
                        actionType = "CLICK",
                        elementLabel = label,
                        count = 1,
                        lastTime = now
                    )
                )
            }
        }

        // Record transition action
        _map.actions.add(
            OpAction(
                nodeId = prevId ?: nodeId,
                actionType = "TRANSITION",
                elementLabel = "${appName}/${screenName}",
                viewId = "",
                timestamp = now
            )
        )

        _map.currentId = nodeId
        _map.totalActions++
        return true
    }

    fun onAction(actionType: String, elementLabel: String, viewId: String = "") {
        if (!_recording) return
        val now = System.currentTimeMillis()
        _map.totalActions++

        _map.actions.add(
            OpAction(
                nodeId = _map.currentId ?: "",
                actionType = actionType,
                elementLabel = elementLabel,
                viewId = viewId,
                timestamp = now
            )
        )

        // Remember last click label for potential transition correlation
        if (actionType == "CLICK" || actionType == "LONG_CLICK") {
            _lastClickLabel = elementLabel
            _lastClickTime = now
        }
    }

    fun getSnapshot(): OpMap = _map.toImmutable()

    /**
     * Create a virtual sub-page node based on screen content (e.g. H5 pages within the same Activity).
     * @return true if applied, false if skipped (dedup/filtered)
     */
    fun onContentPage(
        appPackage: String,
        appName: String,
        pageLabel: String,
        pageId: String
    ): Boolean {
        if (!_recording) return false
        if (pageId == _map.currentId) return false

        val now = System.currentTimeMillis()
        val prevId = _map.currentId

        val existing = _map.nodes[pageId]
        _map.nodes[pageId] = if (existing != null) {
            existing.copy(lastSeen = now, visitCount = existing.visitCount + 1)
        } else {
            OpNode(
                id = pageId,
                appPackage = appPackage,
                appName = appName,
                screenName = pageLabel,
                firstSeen = now,
                lastSeen = now,
                visitCount = 1
            )
        }

        if (prevId != null && prevId != pageId) {
            val existingEdge = _map.edges.find { it.fromId == prevId && it.toId == pageId }
            val idx = _map.edges.indexOf(existingEdge)
            if (idx >= 0) {
                _map.edges[idx] = existingEdge!!.copy(
                    count = existingEdge.count + 1,
                    lastTime = now
                )
            } else {
                val label = if (now - _lastClickTime < 1500) {
                    val lbl = _lastClickLabel
                    _lastClickLabel = ""
                    _lastClickTime = 0L
                    lbl
                } else ""
                _map.edges.add(
                    OpEdge(
                        fromId = prevId,
                        toId = pageId,
                        actionType = "CLICK",
                        elementLabel = label,
                        count = 1,
                        lastTime = now
                    )
                )
            }
        }

        _map.currentId = pageId
        _map.totalActions++
        return true
    }

    companion object {
        fun nodeId(appPackage: String, screenName: String): String {
            return "$appPackage#$screenName"
        }
    }
}
