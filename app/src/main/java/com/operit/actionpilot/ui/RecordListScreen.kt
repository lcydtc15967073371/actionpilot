package com.operit.actionpilot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.operit.actionpilot.model.OpMap

@Composable
fun RecordListScreen(map: OpMap) {
    val byPackage = map.nodes.values.groupBy { it.appPackage }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Summary
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Summary",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("Apps: ${byPackage.size}")
                    Text("Screens: ${map.nodes.size}")
                    Text("Transitions: ${map.edges.size}")
                    Text("Total Actions: ${map.totalActions}")
                }
            }
        }

        // Per-app list
        byPackage.forEach { (pkg, nodes) ->
            item {
                Text(
                    text = nodes.first().appName,
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
                            text = "Visited ${node.visitCount} times",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Edge list
        if (map.edges.isNotEmpty()) {
            item {
                Text(
                    text = "Recent Transitions",
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
                        Text(
                            text = from,
                            fontSize = 13.sp
                        )
                        Text(
                            text = " → ",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 13.sp
                        )
                        Text(
                            text = to,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.weight(1f))
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
