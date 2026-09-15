package com.mangareader.backup.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.backup.export.BackupExporter
import com.mangareader.backup.export.ExportResult
import com.mangareader.backup.format.BackupContentSelection
import com.mangareader.backup.format.ConflictStrategy
import com.mangareader.backup.`import`.BackupImporter
import com.mangareader.backup.`import`.ImportResult
import com.mangareader.backup.scheduled.*

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selection by remember { mutableStateOf(BackupContentSelection()) }
    var strategy by remember { mutableStateOf(ConflictStrategy.OVERWRITE) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showScheduledSettings by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = BackupExporter.export(context, uri, selection)
                statusMessage = when (result) {
                    is ExportResult.Success -> "导出成功：${result.fileName}（${result.fileSize / 1024}KB，${result.durationMs}ms）"
                    is ExportResult.Failure -> "导出失败：${result.message}"
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingImportUri = uri
            scope.launch {
                val result = BackupImporter.`import`(context, uri, selection, strategy)
                statusMessage = when (result) {
                    is ImportResult.Success -> "恢复成功：导入 ${result.importedCount} 条（${result.durationMs}ms）"
                    is ImportResult.PartialSuccess -> "部分恢复：导入 ${result.importedCount} 条，失败：${result.failedCategories.joinToString()}"
                    is ImportResult.Failure -> "恢复失败：${result.message}"
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("备份与恢复") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(Modifier.fillMaxWidth().clickable { showExportDialog = true }) {
                Column(Modifier.padding(16.dp)) {
                    Text("导出备份", fontSize = 16.sp)
                    Text("将阅读进度、设置、书架等导出为文件", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Card(Modifier.fillMaxWidth().clickable { showImportDialog = true }) {
                Column(Modifier.padding(16.dp)) {
                    Text("导入恢复", fontSize = 16.sp)
                    Text("从备份文件恢复数据", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Card(Modifier.fillMaxWidth().clickable { showScheduledSettings = true }) {
                Column(Modifier.padding(16.dp)) {
                    Text("定时备份", fontSize = 16.sp)
                    val config = remember { ScheduledBackupConfigStore.load(context) }
                    Text(
                        if (config.enabled) "已启用（${when (config.frequency) { BackupFrequency.DAILY -> "每天"; BackupFrequency.WEEKLY -> "每周" }}）"
                        else "未启用",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            statusMessage?.let {
                Card(Modifier.fillMaxWidth()) {
                    Text(it, modifier = Modifier.padding(16.dp), fontSize = 14.sp)
                }
            }
        }
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("导出备份") },
            text = {
                Column {
                    BackupContentSelector(selection) { selection = it }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showExportDialog = false; exportLauncher.launch(null) },
                    enabled = selection.progress || selection.settings || selection.shelf || selection.connections
                ) { Text("选择目录并导出") }
            },
            dismissButton = { TextButton(onClick = { showExportDialog = false }) { Text("取消") } }
        )
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("导入恢复") },
            text = {
                Column {
                    BackupContentSelector(selection) { selection = it }
                    Spacer(Modifier.height(12.dp))
                    Text("冲突处理策略", fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                    ConflictStrategyPicker(strategy) { strategy = it }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showImportDialog = false; importLauncher.launch(arrayOf("application/gzip", "application/octet-stream", "*/*")) },
                    enabled = selection.progress || selection.settings || selection.shelf || selection.connections
                ) { Text("选择文件并恢复") }
            },
            dismissButton = { TextButton(onClick = { showImportDialog = false }) { Text("取消") } }
        )
    }

    if (showScheduledSettings) {
        ScheduledBackupSettingsScreen(onBack = { showScheduledSettings = false })
    }
}