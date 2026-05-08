package com.operit.actionpilot.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.operit.actionpilot.model.AppSelection
import com.operit.actionpilot.service.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppInfo(
    val packageName: String,
    val label: String,
)

@Composable
fun AppSelectScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var allApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var installedCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        val apps = withContext(Dispatchers.IO) {
            loadInstalledApps(context)
        }
        allApps = apps
        installedCount = apps.size
    }

    val filteredApps = remember(searchQuery, allApps) {
        if (searchQuery.isBlank()) allApps
        else allApps.filter {
            it.label.contains(searchQuery, ignoreCase = true) ||
            it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    val selectedCount = AppSelection.selectedPackages.size

    Column(modifier = Modifier.fillMaxSize()) {
        // 搜索栏
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索应用...", fontSize = 14.sp) },
            leadingIcon = { Text("🔍", fontSize = 16.sp) },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
        )

        Spacer(Modifier.height(8.dp))

        // 统计 + 操作栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (AppSelection.isFiltering()) "已选 $selectedCount 个应用" else "全部应用（$installedCount 个）",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row {
                TextButton(
                    onClick = {
                        AppSelection.selectAll(allApps.map { it.packageName })
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text("全选", fontSize = 12.sp)
                }
                TextButton(
                    onClick = { AppSelection.clear() },
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text("清除", fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // 提示
        if (searchQuery.isBlank() && !AppSelection.isFiltering()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                )
            ) {
                Text(
                    text = "如果不选，录制所有应用；勾选后只录制选中的应用",
                    modifier = Modifier.padding(12.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(Modifier.height(4.dp))
        } else if (AppSelection.isFiltering()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                )
            ) {
                Text(
                    text = "仅录制选中的 $selectedCount 个应用",
                    modifier = Modifier.padding(12.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Spacer(Modifier.height(4.dp))
        }

        // 应用列表
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(filteredApps, key = { it.packageName }) { app ->
                val isSelected = AppSelection.isSelected(app.packageName)
                Card(
                    onClick = { AppSelection.toggle(app.packageName, !isSelected) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { AppSelection.toggle(app.packageName, it) },
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = app.label,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = app.packageName,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

private fun loadInstalledApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val allApps = mutableMapOf<String, String>()

    // Method 1: PackageManager.getInstalledApplications (with QUERY_ALL_PACKAGES)
    try {
        val flags = if (android.os.Build.VERSION.SDK_INT >= 33)
            android.content.pm.PackageManager.ApplicationInfoFlags.of(0)
        else null
        val infos = if (flags != null)
            pm.getInstalledApplications(flags)
        else
            @Suppress("DEPRECATION") pm.getInstalledApplications(0)
        for (info in infos) {
            // Skip system apps
            if (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0) continue
            val label = info.loadLabel(pm)?.toString() ?: info.packageName
            if (info.packageName !in allApps) allApps[info.packageName] = label
        }
    } catch (_: Exception) {
        // fallback to Shizuku method
    }

    // Method 2: Shizuku cmd package list (fallback)
    if (allApps.size < 20) {
        try {
            val cmdPkg = ShizukuShell.exec("cmd package list packages -3 --user 0")
            val lines = cmdPkg.lines()
                .map { it.trim() }
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:") }
                .filter { it.isNotBlank() }
            for (pkg in lines) {
                if (pkg !in allApps) {
                    try {
                        val info = pm.getApplicationInfo(pkg, 0)
                        val label = info.loadLabel(pm).toString()
                        allApps[pkg] = label
                    } catch (_: Exception) {
                        allApps[pkg] = pkg
                    }
                }
            }
        } catch (_: Exception) {}
    }

    // Method 3: Shizuku ls /data/app (last resort)
    if (allApps.size < 30) {
        try {
            val dataApp = ShizukuShell.exec("ls -1 /data/app/")
            val lines = dataApp.lines().map { it.trim() }.filter { it.isNotBlank() }
            for (entry in lines) {
                // format: com.tencent.mm-xyz== or com.tencent.mm-1
                val pkg = entry.substringBefore('-').substringBefore("==")
                if (pkg.isNotBlank() && pkg !in allApps) {
                    try {
                        val info = pm.getApplicationInfo(pkg, 0)
                        val label = info.loadLabel(pm).toString()
                        allApps[pkg] = label
                    } catch (_: Exception) {
                        allApps[pkg] = pkg
                    }
                }
            }
        } catch (_: Exception) {}
    }

    return allApps.entries.map { (pkg, label) -> AppInfo(pkg, label) }.sortedBy { it.label }
}
