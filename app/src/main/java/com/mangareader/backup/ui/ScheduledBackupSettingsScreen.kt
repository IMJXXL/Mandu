package com.mangareader.backup.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalConfiguration
import com.mangareader.ui.dialogContentMaxHeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.webdav.WebDavGateway
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.mangareader.backup.scheduled.*
import com.mangareader.data.MangaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ScheduledBackupSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(ScheduledBackupConfigStore.load(context)) }
    var webdavSources by remember { mutableStateOf<List<com.ehviewer.core.database.model.WebDavSourceEntity>>(emptyList()) }
    var showPathBrowser by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            webdavSources = MangaDatabase.getDatabase(context).mangaDao().exportAllWebDavSources()
        }
    }

    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            com.hippo.ehviewer.Settings.backupDirUri.value = uri.toString()
        }
    }

    val backupDirUri = com.hippo.ehviewer.Settings.backupDirUri.value

    AlertDialog(
        onDismissRequest = onBack,
        title = { Text("定时备份设置") },
        text = {
            Column(modifier = Modifier.heightIn(max = dialogContentMaxHeight()).verticalScroll(rememberScrollState())) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = config.enabled, onCheckedChange = {
                        config = config.copy(enabled = it)
                        ScheduledBackupConfigStore.save(context, config)
                        if (it) ScheduledBackupScheduler.schedule(context, config)
                        else ScheduledBackupScheduler.cancel(context)
                    })
                    Text("启用定时备份", fontSize = 14.sp)
                }
                Spacer(Modifier.height(12.dp))

                Text("本地备份目录", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { dirPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (backupDirUri.isNullOrEmpty()) "选择备份目录" else "已选择（点击更换）", fontSize = 13.sp)
                }
                Spacer(Modifier.height(12.dp))

                Text("频率", fontSize = 14.sp)
                Row {
                    FilterChip(selected = config.frequency == BackupFrequency.DAILY, onClick = {
                        config = config.copy(frequency = BackupFrequency.DAILY)
                        ScheduledBackupConfigStore.save(context, config)
                        if (config.enabled) ScheduledBackupScheduler.schedule(context, config)
                    }, label = { Text("每天") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = config.frequency == BackupFrequency.WEEKLY, onClick = {
                        config = config.copy(frequency = BackupFrequency.WEEKLY)
                        ScheduledBackupConfigStore.save(context, config)
                        if (config.enabled) ScheduledBackupScheduler.schedule(context, config)
                    }, label = { Text("每周") })
                }
                Spacer(Modifier.height(12.dp))

                Text("WebDAV 连接", fontSize = 14.sp)
                if (webdavSources.isEmpty()) {
                    Text("请先在 WebDAV 设置中添加连接", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                } else {
                    webdavSources.forEach { source ->
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = config.webdavSourceId == source.id, onClick = {
                                config = config.copy(webdavSourceId = source.id)
                                ScheduledBackupConfigStore.save(context, config)
                                if (config.enabled) ScheduledBackupScheduler.schedule(context, config)
                            })
                            Text(source.displayName, fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                Text("本地保留份数：${config.localRetentionCount}", fontSize = 14.sp)
                Slider(
                    value = config.localRetentionCount.toFloat(),
                    onValueChange = { v -> config = config.copy(localRetentionCount = v.toInt()) },
                    onValueChangeFinished = { ScheduledBackupConfigStore.save(context, config) },
                    valueRange = 1f..30f,
                    steps = 28,
                )
                Spacer(Modifier.height(12.dp))

                Text("远端路径", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { showPathBrowser = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (config.remotePath.isEmpty()) "/" else config.remotePath, fontSize = 13.sp, maxLines = 1)
                }
                Spacer(Modifier.height(12.dp))

                if (config.lastBackupAt > 0) {
                    Text("上次备份：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(config.lastBackupAt))}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = {
                    scope.launch {
                        ScheduledBackupScheduler.triggerNow(context)
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("立即备份")
                }
            }
        },
        confirmButton = { TextButton(onClick = onBack) { Text("关闭") } },
        dismissButton = {}
    )

    if (showPathBrowser) {
        WebDavPathPickerDialog(
            webdavSources = webdavSources,
            selectedSourceId = config.webdavSourceId,
            initialPath = config.remotePath,
            onPick = { path ->
                config = config.copy(remotePath = path)
                ScheduledBackupConfigStore.save(context, config)
                showPathBrowser = false
            },
            onDismiss = { showPathBrowser = false }
        )
    }
}

@Composable
fun WebDavPathPickerDialog(
    webdavSources: List<com.ehviewer.core.database.model.WebDavSourceEntity>,
    selectedSourceId: Long,
    initialPath: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var currentPath by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<BrowseEntryRemote>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val source = webdavSources.find { it.id == selectedSourceId }
    val password = if (source != null) WebDavPasswordStore.get(source.id) ?: "" else ""

    LaunchedEffect(currentPath) {
        if (source != null) {
            loading = true
            error = null
            withContext(Dispatchers.IO) {
                runCatching { WebDavGateway.listDirectory(source, password, currentPath) }
                    .onSuccess { entries = it }
                    .onFailure { e ->
                        val msg = e.message.orEmpty()
                        error = if (msg.contains("404")) "路径不存在，请从根目录选择" else msg.take(100)
                    }
            }
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择远端路径") },
        text = {
            Column {
                Text("当前: ${if (currentPath.isEmpty()) "/" else currentPath}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                if (source == null) {
                    Text("请先选择 WebDAV 连接", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                } else if (loading) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    }
                } else if (error != null) {
                    Text(error!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                } else {
                    val dirs = entries.filterIsInstance<BrowseEntryRemote.Directory>()
                    if (dirs.isEmpty()) {
                        Text("无子目录", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(modifier = Modifier.height(minOf(280, LocalConfiguration.current.screenHeightDp - 220).coerceAtLeast(120).dp)) {
                            items(dirs) { dir ->
                                TextButton(onClick = {
                                    currentPath = if (currentPath.isEmpty()) dir.relativeName else "${currentPath}/${dir.relativeName}"
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Text(dir.name, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(currentPath) }) { Text("选择此目录") } },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    currentPath = currentPath.substringBeforeLast('/', "")
                }) { Text("返回上级") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}
