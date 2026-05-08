package com.operit.actionpilot.model

data class OpAction(
    val nodeId: String,
    val actionType: String,
    val elementLabel: String,
    val viewId: String,
    val timestamp: Long
)
