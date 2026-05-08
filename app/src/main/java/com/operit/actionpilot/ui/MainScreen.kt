package com.operit.actionpilot.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.operit.actionpilot.model.OpMap
import com.operit.actionpilot.service.RecordService
import com.operit.actionpilot.service.ShizukuShell
import com.operit.actionpilot.storage.MapRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val repository = remember {
        (context.applicationContext as com.operit.actionpilot.ActionPilotApp).repository
    }

    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    var recording by remember { mutableStateOf(RecordService.isRunning) }
    var opMap by remember { mutableStateOf<OpMap?>(null) }
    var shizukuPerm by remember { mutableStateOf(ShizukuShell.isPermissionGranted()) }

    // Load data: use live MapBuilder data when recording, else load from disk
    fun refreshData() {
        opMap = if (recording && RecordService.mapBuilder != null) {
            RecordService.mapBuilder!!.getSnapshot()
        } else if (!recording) {
            repository.load()
        } else {
            null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ActionPilot") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Text("🎯", fontSize = 18.sp) },
                    label = { Text("Record", fontSize = 12.sp) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Text("🗺", fontSize = 18.sp) },
                    label = { Text("Map", fontSize = 12.sp) }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Text("📋", fontSize = 18.sp) },
                    label = { Text("List", fontSize = 12.sp) }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Text("📤", fontSize = 18.sp) },
                    label = { Text("Export", fontSize = 12.sp) }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            when (selectedTab) {
                0 -> RecordTab(
                    context = context,
                    recording = recording,
                    shizukuGranted = shizukuPerm,
                    onToggleRecording = { start ->
                        if (!start) {
                            val intent = Intent(context, RecordService::class.java).apply {
                                action = RecordService.ACTION_STOP
                            }
                            context.startService(intent)
                            // Give it a moment to save, then reload
                            scope.launch {
                                delay(1000)
                                refreshData()
                            }
                        }
                        recording = start
                    },
                    onRequestShizuku = {
                        try {
                            Shizuku.requestPermission(1001)
                        } catch (_: Exception) {
                            Toast.makeText(context, "Shizuku not running", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                1 -> MapViewTab(opMap = opMap)
                2 -> RecordListTab(opMap = opMap)
                3 -> ExportTab(context = context, repository = repository, opMap = opMap)
            }
        }
    }

    // Poll for state changes and live data
    LaunchedEffect(Unit) {
        while (true) {
            recording = RecordService.isRunning
            shizukuPerm = ShizukuShell.isPermissionGranted()
            refreshData()
            delay(2000)
        }
    }
}

@Composable
private fun RecordTab(
    context: Context,
    recording: Boolean,
    shizukuGranted: Boolean,
    onToggleRecording: (Boolean) -> Unit,
    onRequestShizuku: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (recording)
                    MaterialTheme.colorScheme.errorContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (recording) "● Recording" else "Stopped",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (recording)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when {
                        recording -> "Polling dumpsys via Shizuku"
                        !shizukuGranted -> "Need Shizuku permission"
                        else -> "Tap Start to begin recording"
                    }
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        if (!shizukuGranted && !recording) {
            Button(
                onClick = onRequestShizuku,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text("AUTHORIZE SHIZUKU", fontSize = 16.sp)
            }
            Spacer(Modifier.height(12.dp))
        }

        Button(
            onClick = {
                if (!shizukuGranted) {
                    Toast.makeText(context, "Authorize Shizuku first", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                val intent = Intent(context, RecordService::class.java).apply {
                    action = if (!recording) RecordService.ACTION_START
                    else RecordService.ACTION_STOP
                }
                if (!recording) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                onToggleRecording(!recording)
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            enabled = shizukuGranted || recording,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (recording)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                if (recording) "STOP RECORDING" else "START RECORDING",
                fontSize = 16.sp
            )
        }

        Spacer(Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("System", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Shizuku: ${if (shizukuGranted) "✅ Authorized" else "❌ Not authorized"}",
                    fontSize = 14.sp
                )
                Text(
                    text = "Mode: Shizuku polling (dumpsys)",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MapViewTab(opMap: OpMap?) {
    if (opMap == null || opMap.nodes.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No data yet. Start recording first!",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        MapView(map = opMap)
    }
}

@Composable
private fun RecordListTab(opMap: OpMap?) {
    if (opMap == null || opMap.nodes.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No data yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        RecordListScreen(map = opMap)
    }
}

@Composable
private fun ExportTab(context: Context, repository: MapRepository, opMap: OpMap?) {
    ExportScreen(context = context, repository = repository, currentMap = opMap)
}
