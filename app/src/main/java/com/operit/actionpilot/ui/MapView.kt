package com.operit.actionpilot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.operit.actionpilot.model.OpMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

@Composable
fun MapView(map: OpMap) {
    if (map.nodes.isEmpty()) return

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Layout nodes using simple force-directed algorithm
    val nodePositions = remember(map) { layoutNodes(map) }

    val nodeColor = MaterialTheme.colorScheme.primary
    val edgeColor = MaterialTheme.colorScheme.outline
    val textColor = MaterialTheme.colorScheme.onSurface

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.3f, 3f)
                    offset += pan
                }
            }
    ) {
        val center = Offset(size.width / 2, size.height / 2)

        // Draw edges
        map.edges.forEach { edge ->
            val from = nodePositions[edge.fromId] ?: return@forEach
            val to = nodePositions[edge.toId] ?: return@forEach
            val thickness = (edge.count.coerceIn(1, 10)).dp.toPx()

            drawLine(
                color = edgeColor,
                start = center + from * scale + offset,
                end = center + to * scale + offset,
                strokeWidth = thickness
            )
            // Draw arrowhead
            drawArrowhead(
                start = center + from * scale + offset,
                end = center + to * scale + offset,
                color = edgeColor,
                nodeRadius = 20f * scale
            )
        }

        // Draw nodes
        map.nodes.values.forEach { node ->
            val pos = nodePositions[node.id] ?: return@forEach
            val radius = (15f + node.visitCount * 3f).coerceIn(18f, 40f) * scale
            val centerPos = center + pos * scale + offset

            drawCircle(
                color = nodeColor,
                radius = radius
            )

            // Draw label
            drawContext.canvas.nativeCanvas.drawText(
                node.screenName.take(12),
                centerPos.x,
                centerPos.y + 4f,
                android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 10f * scale
                    textAlign = android.graphics.Paint.Align.CENTER
                }
            )
        }
    }
}

private fun layoutNodes(map: OpMap): Map<String, Offset> {
    if (map.nodes.isEmpty()) return emptyMap()

    val nodeIds = map.nodes.keys.toList()
    val positions = mutableMapOf<String, Offset>()

    // Place nodes in a circle initially
    val angleStep = 2 * Math.PI / nodeIds.size
    val radius = 150f

    nodeIds.forEachIndexed { i, id ->
        val angle = angleStep * i
        positions[id] = Offset(
            (radius * cos(angle)).toFloat(),
            (radius * sin(angle)).toFloat()
        )
    }

    // Simple force-directed iteration
    repeat(50) {
        val forces = mutableMapOf<String, Offset>()

        // Repulsion between all nodes
        for (i in nodeIds.indices) {
            for (j in i + 1 until nodeIds.size) {
                val a = positions[nodeIds[i]] ?: continue
                val b = positions[nodeIds[j]] ?: continue
                val dx = a.x - b.x
                val dy = a.y - b.y
                val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                val force = 5000f / (dist * dist)
                val fx = (dx / dist) * force
                val fy = (dy / dist) * force
                forces[nodeIds[i]] = (forces[nodeIds[i]] ?: Offset.Zero) + Offset(fx, fy)
                forces[nodeIds[j]] = (forces[nodeIds[j]] ?: Offset.Zero) - Offset(fx, fy)
            }
        }

        // Attraction along edges
        map.edges.forEach { edge ->
            val from = positions[edge.fromId] ?: return@forEach
            val to = positions[edge.toId] ?: return@forEach
            val dx = to.x - from.x
            val dy = to.y - from.y
            val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            val force = dist / 100f
            val fx = (dx / dist) * force
            val fy = (dy / dist) * force
            forces[edge.fromId] = (forces[edge.fromId] ?: Offset.Zero) + Offset(fx, fy)
            forces[edge.toId] = (forces[edge.toId] ?: Offset.Zero) - Offset(fx, fy)
        }

        // Apply forces with damping
        nodeIds.forEach { id ->
            val f = forces[id] ?: return@forEach
            positions[id] = Offset(
                positions[id]!!.x + f.x * 0.1f,
                positions[id]!!.y + f.y * 0.1f
            )
        }
    }

    // Center the layout
    val cx = positions.values.map { it.x }.average().toFloat()
    val cy = positions.values.map { it.y }.average().toFloat()
    return positions.mapValues { (_, pos) ->
        Offset(pos.x - cx, pos.y - cy)
    }
}

private fun DrawScope.drawArrowhead(
    start: Offset,
    end: Offset,
    color: Color,
    nodeRadius: Float = 20f
) {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val dist = sqrt(dx * dx + dy * dy)
    if (dist == 0f) return

    // Arrow tip (at the edge of the target node)
    val tipX = end.x - (dx / dist) * nodeRadius
    val tipY = end.y - (dy / dist) * nodeRadius

    val angle = atan2(dy, dx)
    val arrowSize = 12f

    val p1x = (tipX - arrowSize * cos(angle - Math.PI / 6)).toFloat()
    val p1y = (tipY - arrowSize * sin(angle - Math.PI / 6)).toFloat()
    val p2x = (tipX - arrowSize * cos(angle + Math.PI / 6)).toFloat()
    val p2y = (tipY - arrowSize * sin(angle + Math.PI / 6)).toFloat()

    drawLine(color, Offset(tipX.toFloat(), tipY.toFloat()), Offset(p1x, p1y), strokeWidth = 3f)
    drawLine(color, Offset(tipX.toFloat(), tipY.toFloat()), Offset(p2x, p2y), strokeWidth = 3f)
}
