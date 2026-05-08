package com.operit.actionpilot.model

data class OpNode(
    val id: String,
    val appPackage: String,
    val appName: String,
    val screenName: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val visitCount: Int
)
