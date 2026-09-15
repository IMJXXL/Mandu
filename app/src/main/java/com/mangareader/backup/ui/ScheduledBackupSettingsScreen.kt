package com.mangareader.backup.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            webdavSources = MangaDatabase.getDatabase(context).mangaDao().exportAllWebDavSources()
        }
    }

    AlertDialog(
        onDismissRequest = onBack,
        title = { Text("定时备份设置") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(checked = config.enabled, onCheckedChange = {
                        config = config.copy(enabled = it)
                        ScheduledBackupConfigStore.save(context, config)
                        if (it) ScheduledBackupScheduler.schedule(context, config)
                        else ScheduledBackupScheduler.cancel(context)
                    })
                    Text("启用定时备份", fontSize = 14.sp)
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
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
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
                    onValueChange = { config = config.copy(localRetentionCount = it.toInt()) },
                    onValueChangeFinished = { ScheduledBackupConfigStore.save(context, config) },
                    valueRange = 1f..30f,
                    steps = 28,
                )
                Spacer(Modifier.height(12.dp))

                Text("远端路径", fontSize = 14.sp)
                OutlinedTextField(
                    value = config.remotePath,
                    onValueChange = { config = config.copy(remotePath = it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
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
}