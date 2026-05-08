package com.operit.actionpilot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.operit.actionpilot.model.OpMap
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun RecordListScreen(map: OpMap) {
    val byPackage = map.nodes.values.groupBy { it.appPackage }
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 概要
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "概要",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("应用: ${byPackage.size}")
                    Text("页面: ${map.nodes.size}")
                    Text("跳转: ${map.edges.size}")
                    Text("操作总数: ${map.totalActions}")
                }
            }
        }

        // 按应用分组
        byPackage.forEach { (pkg, nodes) ->
            item {
                val appName = nodes.first().appName
                Text(
                    text = appName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }
            items(nodes.sortedByDescending { it.visitCount }) { node ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = node.screenName,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "访问 ${node.visitCount} 次",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 最近操作（点击级）
        if (map.actions.isNotEmpty()) {
            item {
                Text(
                    text = "最近操作",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
            }
            items(map.actions.takeLast(60).reversed()) { action ->
                val screen = map.nodes[action.nodeId]?.screenName ?: action.nodeId
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                            alpha = 0.7f
                        )
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val typeLabel = when (action.actionType) {
                            "CLICK" -> "点击"
                            "LONG_CLICK" -> "长按"
                            "TEXT_INPUT" -> "输入"
                            "TRANSITION" -> "跳转"
                            "SCREEN_CONTENT" -> "内容"
                            else -> action.actionType
                        }
                        val badgeColor = when (action.actionType) {
                            "CLICK" -> MaterialTheme.colorScheme.primary
                            "LONG_CLICK" -> MaterialTheme.colorScheme.error
                            "TEXT_INPUT" -> MaterialTheme.colorScheme.tertiary
                            "TRANSITION" -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.outline
                        }
                        Surface(
                            color = badgeColor.copy(alpha = 0.15f),
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                text = typeLabel,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = badgeColor
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            if (action.elementLabel.isNotBlank()) {
                                Text(
                                    text = action.elementLabel,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = screen,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Text(
                            text = dateFormat.format(Date(action.timestamp)),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        // 导航跳转
        if (map.edges.isNotEmpty()) {
            item {
                Text(
                    text = "页面跳转",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
            }
            items(map.edges.takeLast(20).reversed().take(50)) { edge ->
                val from = map.nodes[edge.fromId]?.screenName ?: edge.fromId
                val to = map.nodes[edge.toId]?.screenName ?: edge.toId
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(from, fontSize = 13.sp)
                                Text(
                                    " → ",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 13.sp
                                )
                                Text(to, fontSize = 13.sp)
                            }
                            if (edge.elementLabel.isNotBlank()) {
                                Text(
                                    text = "触发: ${edge.elementLabel}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            text = "×${edge.count}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(80.dp)) }
    }
}
