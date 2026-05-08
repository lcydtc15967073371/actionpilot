package com.operit.actionpilot.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.operit.actionpilot.export.AiExporter
import com.operit.actionpilot.model.OpMap
import com.operit.actionpilot.storage.MapRepository
import java.io.File

@Composable
fun ExportScreen(
    context: Context,
    repository: MapRepository,
    currentMap: OpMap?
) {
    var exportedJson by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "AI Export",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )

        Spacer(Modifier.height(16.dp))

        // Save current map
        Button(
            onClick = {
                if (currentMap != null) {
                    repository.save(currentMap)
                    Toast.makeText(context, "Map saved", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = currentMap != null
        ) {
            Text("Save Current Map")
        }

        Spacer(Modifier.height(8.dp))

        // Export for AI
        Button(
            onClick = {
                val map = currentMap ?: repository.load()
                if (map != null) {
                    exportedJson = AiExporter().export(map)
                } else {
                    Toast.makeText(context, "No data to export", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Generate AI Export")
        }

        Spacer(Modifier.height(8.dp))

        // Share
        if (exportedJson.isNotEmpty()) {
            Button(
                onClick = {
                    val file = File(context.cacheDir, "actionpilot_export.json")
                    file.writeText(exportedJson)
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    context.startActivity(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text("Share JSON")
            }
        }

        Spacer(Modifier.height(16.dp))

        // JSON preview
        if (exportedJson.isNotEmpty()) {
            Text("Preview:", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = exportedJson.take(2000),
                    modifier = Modifier.padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            }
        }
    }
}
