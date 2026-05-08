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
import com.operit.actionpilot.model.AppSelection
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
    var a11yEnabled by remember { mutableStateOf(RecordService.isA11yServiceEnabled()) }

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
                title = { Text("操作导航") },
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
                    label = { Text("录制", fontSize = 12.sp) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Text("🗺", fontSize = 18.sp) },
                    label = { Text("应用", fontSize = 12.sp) }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Text("📋", fontSize = 18.sp) },
                    label = { Text("列表", fontSize = 12.sp) }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Text("📤", fontSize = 18.sp) },
                    label = { Text("导出", fontSize = 12.sp) }
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
                    a11yEnabled = a11yEnabled,
                    opMap = opMap,
                    onToggleRecording = { start ->
                        if (!start) {
                            val intent = Intent(context, RecordService::class.java).apply {
                                action = RecordService.ACTION_STOP
                            }
                            context.startService(intent)
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
                            Toast.makeText(context, "Shizuku 未运行", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                1 -> AppSelectScreen()
                2 -> RecordListTab(opMap = opMap)
                3 -> ExportTab(context = context, repository = repository, opMap = opMap)
            }
        }
    }

    // Auto-enable accessibility on startup
    LaunchedEffect(Unit) {
        delay(2000)
        val perm = ShizukuShell.isPermissionGranted()
        val a11y = RecordService.isA11yServiceEnabled()
        if (perm && !a11y) {
            ShizukuShell.enableAccessibilityService()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            recording = RecordService.isRunning
            shizukuPerm = ShizukuShell.isPermissionGranted()
            a11yEnabled = RecordService.isA11yServiceEnabled()
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
    a11yEnabled: Boolean,
    opMap: OpMap?,
    onToggleRecording: (Boolean) -> Unit,
    onRequestShizuku: () -> Unit
) {
    val byPackage = opMap?.nodes?.values?.groupBy { it.appPackage }
    val hasRichMode = a11yEnabled

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))

        // 状态卡片
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
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (recording) "● 录制中" else "已停止",
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
                        recording && hasRichMode -> "无障碍服务 + Shizuku（详细）"
                        recording -> "仅 Shizuku 轮询（基础）"
                        !shizukuGranted -> "需要 Shizuku 授权"
                        else -> "点「开始录制」启动"
                    },
                    fontSize = 14.sp
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 录制中实时统计
        if (recording && opMap != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "录制统计",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        StatItem("应用", byPackage?.size?.toString() ?: "0", Modifier.weight(1f))
                        StatItem("页面", opMap.nodes.size.toString(), Modifier.weight(1f))
                        StatItem("跳转", opMap.edges.size.toString(), Modifier.weight(1f))
                        StatItem("操作", opMap.totalActions.toString(), Modifier.weight(1f))
                    }

                    if (opMap.actions.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        val lastAction = opMap.actions.last()
                        Text(
                            text = "最新: ${if (lastAction.actionType == "CLICK") "点击" else if (lastAction.actionType == "TRANSITION") "跳转" else lastAction.actionType} \"${lastAction.elementLabel.take(30)}\"",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                        )
                    }

                    if (byPackage != null && byPackage.size > 1) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        byPackage.forEach { (_, nodes) ->
                            val appName = nodes.first().appName
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(appName, fontSize = 12.sp)
                                Text(
                                    "${nodes.size} 个页面",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (!shizukuGranted && !recording) {
            Button(
                onClick = onRequestShizuku,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text("授权 Shizuku", fontSize = 14.sp)
            }
            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = {
                if (!shizukuGranted) {
                    Toast.makeText(context, "请先授权 Shizuku", Toast.LENGTH_SHORT).show()
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
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = shizukuGranted || recording,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (recording)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                if (recording) "停止录制" else "开始录制",
                fontSize = 15.sp
            )
        }

        Spacer(Modifier.height(8.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("系统状态", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Shizuku: ${if (shizukuGranted) "✅ 已授权" else "❌ 未授权"}",
                    fontSize = 12.sp
                )
                Text(
                    text = "无障碍: ${if (a11yEnabled) "✅ 已启用（详细模式）" else "⚠ 未启用（基础模式）"}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun RecordListTab(opMap: OpMap?) {
    if (opMap == null || opMap.nodes.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无数据",
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
