package com.operit.actionpilot.model

data class OpEdge(
    val fromId: String,
    val toId: String,
    val actionType: String,
    val elementLabel: String,
    val count: Int,
    val lastTime: Long
)
