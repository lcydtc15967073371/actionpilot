package com.operit.actionpilot.export

import com.operit.actionpilot.model.OpMap

/**
 * Exports the operation map in an AI-friendly JSON format.
 * Groups nodes by app, lists flows between screens.
 */
class AiExporter {

    fun export(map: OpMap): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("  \"version\": \"1.0\",")
        sb.appendLine("  \"exportedAt\": ${System.currentTimeMillis()},")
        sb.appendLine("  \"totalActions\": ${map.totalActions},")
        sb.appendLine("  \"apps\": {")

        val byPackage = map.nodes.values.groupBy { it.appPackage }
        val appEntries = byPackage.entries.toList()

        appEntries.forEachIndexed { ai, (pkg, nodes) ->
            val appName = nodes.first().appName
            sb.appendLine("    \"$pkg\": {")
            sb.appendLine("      \"name\": \"$appName\",")

            // Screens
            sb.appendLine("      \"screens\": {")
            nodes.forEachIndexed { ni, node ->
                val actions = map.edges
                    .filter { it.fromId == node.id }
                    .groupBy { it.toId }
                    .map { (toId, edges) ->
                        val targetNode = map.nodes[toId]
                        val targetScreen = targetNode?.screenName ?: toId
                        val firstEdge = edges.first()
                        """{"action": "${firstEdge.actionType}", "target": "${firstEdge.elementLabel}", "leadsTo": "$targetScreen", "count": ${edges.sumOf { it.count }}}"""
                    }

                val comma = if (ni < nodes.size - 1) "," else ""
                sb.appendLine("        \"${node.screenName}\": {")
                sb.appendLine("          \"visits\": ${node.visitCount},")
                sb.appendLine("          \"actions\": [${actions.joinToString(", ")}]")
                sb.appendLine("        }$comma")
            }
            sb.appendLine("      },")

            // Flows
            sb.appendLine("      \"flows\": [")
            val flows = map.edges
                .filter { it.fromId.startsWith(pkg) && it.toId.startsWith(pkg) }
            flows.forEachIndexed { fi, edge ->
                val from = map.nodes[edge.fromId]?.screenName ?: edge.fromId
                val to = map.nodes[edge.toId]?.screenName ?: edge.toId
                val comma = if (fi < flows.size - 1) "," else ""
                sb.appendLine("        {\"from\": \"$from\", \"action\": \"${edge.actionType}\", \"target\": \"${edge.elementLabel}\", \"to\": \"$to\", \"count\": ${edge.count}}$comma")
            }
            sb.appendLine("      ]")

            val appComma = if (ai < appEntries.size - 1) "," else ""
            sb.appendLine("    }$appComma")
        }

        sb.appendLine("  }")
        sb.appendLine("}")
        return sb.toString()
    }
}
