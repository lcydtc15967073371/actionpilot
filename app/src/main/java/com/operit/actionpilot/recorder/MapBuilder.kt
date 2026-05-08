package com.operit.actionpilot.recorder

import com.operit.actionpilot.model.MutableOpMap
import com.operit.actionpilot.model.OpAction
import com.operit.actionpilot.model.OpEdge
import com.operit.actionpilot.model.AppSelection
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

    fun onWindowChanged(appPackage: String, appName: String, screenName: String) {
        if (!_recording) return
        // Skip if app filter is active and this package is not selected
        if (AppSelection.isFiltering() && !AppSelection.isSelected(appPackage)) return

        val nodeId = nodeId(appPackage, screenName)
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
                val label = if (now - _lastClickTime < 1500) _lastClickLabel else ""
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

    companion object {
        fun nodeId(appPackage: String, screenName: String): String {
            return "$appPackage#$screenName"
        }
    }
}
