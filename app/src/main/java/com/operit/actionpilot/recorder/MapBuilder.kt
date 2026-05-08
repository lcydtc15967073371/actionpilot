package com.operit.actionpilot.recorder

import com.operit.actionpilot.model.MutableOpMap
import com.operit.actionpilot.model.OpEdge
import com.operit.actionpilot.model.OpMap
import com.operit.actionpilot.model.OpNode

class MapBuilder {

    private var _map = MutableOpMap()
    private var _recording = false

    val recording: Boolean get() = _recording

    fun start() {
        _map = MutableOpMap(startedAt = System.currentTimeMillis())
        _recording = true
    }

    fun stop(): OpMap {
        _recording = false
        return _map.toImmutable()
    }

    fun onWindowChanged(appPackage: String, appName: String, screenName: String) {
        if (!_recording) return

        val nodeId = nodeId(appPackage, screenName)
        val now = System.currentTimeMillis()
        val prevId = _map.currentId

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

        if (prevId != null && prevId != nodeId) {
            val existingEdge = _map.edges.find { it.fromId == prevId && it.toId == nodeId }
            val idx = _map.edges.indexOf(existingEdge)
            if (idx >= 0) {
                _map.edges[idx] = existingEdge!!.copy(
                    count = existingEdge.count + 1,
                    lastTime = now
                )
            } else {
                _map.edges.add(
                    OpEdge(
                        fromId = prevId,
                        toId = nodeId,
                        actionType = "TRANSITION",
                        elementLabel = "",
                        count = 1,
                        lastTime = now
                    )
                )
            }
        }

        _map.currentId = nodeId
    }

    fun onAction(actionType: String, elementLabel: String) {
        if (!_recording) return
        _map.totalActions++
        if (_map.currentId == null) return

        if (_map.edges.isNotEmpty()) {
            val last = _map.edges.last()
            if (last.actionType == "TRANSITION" || last.lastTime == System.currentTimeMillis()) {
                val idx = _map.edges.size - 1
                _map.edges[idx] = last.copy(
                    actionType = actionType,
                    elementLabel = elementLabel
                )
            }
        }
    }

    fun getSnapshot(): OpMap = _map.toImmutable()

    companion object {
        fun nodeId(appPackage: String, screenName: String): String {
            return "$appPackage#$screenName"
        }
    }
}
