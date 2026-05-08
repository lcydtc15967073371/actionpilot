package com.operit.actionpilot.model

data class OpMap(
    val nodes: Map<String, OpNode> = emptyMap(),
    val edges: List<OpEdge> = emptyList(),
    val actions: List<OpAction> = emptyList(),
    val currentId: String? = null,
    val startedAt: Long = 0L,
    val totalActions: Int = 0
) {
    fun toMutable(): MutableOpMap = MutableOpMap(
        nodes = nodes.toMutableMap(),
        edges = edges.toMutableList(),
        actions = actions.toMutableList(),
        currentId = currentId,
        startedAt = startedAt,
        totalActions = totalActions
    )
}

data class MutableOpMap(
    val nodes: MutableMap<String, OpNode> = mutableMapOf(),
    val edges: MutableList<OpEdge> = mutableListOf(),
    val actions: MutableList<OpAction> = mutableListOf(),
    var currentId: String? = null,
    var startedAt: Long = 0L,
    var totalActions: Int = 0
) {
    fun toImmutable(): OpMap = OpMap(
        nodes = nodes.toMap(),
        edges = edges.toList(),
        actions = actions.toList(),
        currentId = currentId,
        startedAt = startedAt,
        totalActions = totalActions
    )
}
