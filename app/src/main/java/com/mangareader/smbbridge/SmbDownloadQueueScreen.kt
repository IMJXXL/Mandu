package com.mangareader.smbbridge

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmbDownloadQueueScreen(onBack: () -> Unit, onOpenBook: ((Long) -> Unit)? = null) {
    val tasks by SmbDownloadQueue.tasks.collectAsState()
    var taskToDelete by remember { mutableStateOf<DownloadTask?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showInvalidDialog by remember { mutableStateOf(false) }
    var invalidCount by remember { mutableStateOf(0) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val dao = remember { com.mangareader.data.MangaDatabase.getDatabase(ctx).mangaDao() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("下载队列") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    val hasFinished = tasks.any { it.status == DownloadStatus.Completed || it.status == DownloadStatus.Failed }
                    if (hasFinished) {
                        TextButton(onClick = { showClearDialog = true }) {
                            Text("清空已完成")
                        }
                    }
                    val hasInvalid = tasks.any { it.status == DownloadStatus.Completed && it.localPath != null && it.bookId > 0 && !java.io.File(it.localPath).exists() }
                    if (hasInvalid) {
                        TextButton(onClick = {

                            val bookIds = SmbDownloadQueue.removeInvalid(dao)
                            invalidCount = bookIds.size
                            showInvalidDialog = true
                        }) {
                            Text("移除失效")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (tasks.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Download, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(12.dp))
                    Text("队列为空", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("在浏览页多选书籍后点击下载", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }
        } else {
            LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(tasks, key = { it.id }) { task ->
                    DownloadTaskCard(
                        task = task,
                        onPause = { SmbDownloadQueue.pause(task.id) },
                        onResume = { SmbDownloadQueue.resume(task.id) },
                        onDelete = { taskToDelete = task },
                        onOpen = if (task.status == DownloadStatus.Completed && task.bookId > 0) {
                            { onOpenBook?.invoke(task.bookId) }
                        } else null,
                    )
                }
            }
        }
    }

    taskToDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { taskToDelete = null },
            title = { Text("删除任务") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("「${task.title}」")
                    if (task.status == DownloadStatus.Completed && task.localPath != null) {
                        Text("同时删除已下载的文件？", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (task.status == DownloadStatus.Completed && task.localPath != null) {
                        TextButton(onClick = {
                            SmbDownloadQueue.remove(task.id, deleteFile = true)
                            taskToDelete = null
                        }) { Text("删除文件", color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = {
                        SmbDownloadQueue.remove(task.id, deleteFile = false)
                        taskToDelete = null
                    }) { Text("仅删任务") }
                }
            },
            dismissButton = { TextButton(onClick = { taskToDelete = null }) { Text("取消") } },
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空已完成") },
            text = { Text("将清空所有已完成和失败的下载任务。\n\n选择是否同时删除已下载的文件。") },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        SmbDownloadQueue.clearCompleted(deleteFile = true)
                        showClearDialog = false
                    }) { Text("删除文件+任务", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = {
                        SmbDownloadQueue.clearCompleted(deleteFile = false)
                        showClearDialog = false
                    }) { Text("仅删任务") }
                }
            },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("取消") } },
        )
    }

    if (showInvalidDialog) {
        AlertDialog(
            onDismissRequest = { showInvalidDialog = false },
            title = { Text("移除失效任务") },
            text = { Text("已移除 $invalidCount 本本地文件不存在的书籍及其下载任务。") },
            confirmButton = { TextButton(onClick = { showInvalidDialog = false }) { Text("确定") } },
        )
    }
}

@Composable
private fun DownloadTaskCard(
    task: DownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
    onOpen: (() -> Unit)? = null,
) {
    val statusColor = when (task.status) {
        DownloadStatus.Downloading -> Color(0xFF4CAF50)
        DownloadStatus.Paused -> Color(0xFFFF9800)
        DownloadStatus.Completed -> Color(0xFF2196F3)
        DownloadStatus.Failed -> Color(0xFFE57373)
        DownloadStatus.Queued -> Color(0xFF9E9E9E)
    }
    val statusText = when (task.status) {
        DownloadStatus.Downloading -> "下载中"
        DownloadStatus.Paused -> "已暂停"
        DownloadStatus.Completed -> "已完成"
        DownloadStatus.Failed -> "失败"
        DownloadStatus.Queued -> "排队中"
    }

    Card(
        Modifier.fillMaxWidth().then(if (onOpen != null) Modifier.clickable { onOpen() } else Modifier),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val statusIcon = when (task.status) {
                    DownloadStatus.Downloading, DownloadStatus.Queued -> Icons.Default.Download
                    DownloadStatus.Completed -> Icons.Default.CheckCircle
                    DownloadStatus.Failed -> Icons.Default.Error
                    DownloadStatus.Paused -> Icons.Default.Pause
                }
                Icon(statusIcon, null, tint = statusColor, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        task.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = statusColor.copy(alpha = 0.15f),
                        ) {
                            Text(
                                statusText,
                                fontSize = 11.sp,
                                color = statusColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                if (task.sourceKind == "smb") "SMB" else "WebDAV",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        if (task.totalBytes > 0) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${formatBytes(task.downloadedBytes)} / ${formatBytes(task.totalBytes)}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                // Action buttons
                when (task.status) {
                    DownloadStatus.Downloading, DownloadStatus.Queued -> {
                        IconButton(onClick = onPause, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Pause, "暂停", modifier = Modifier.size(18.dp))
                        }
                    }
                    DownloadStatus.Paused, DownloadStatus.Failed -> {
                        IconButton(onClick = onResume, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.PlayArrow, "继续", modifier = Modifier.size(18.dp))
                        }
                    }
                    else -> {}
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Delete, "删除", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Progress bar
            if (task.status == DownloadStatus.Downloading || task.status == DownloadStatus.Paused) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(2.dp)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${(task.progress * 100).toInt()}%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (task.status == DownloadStatus.Queued) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)),
                )
            }
            if (task.error != null) {
                Spacer(Modifier.height(4.dp))
                Text(task.error, fontSize = 11.sp, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var unit = 0
    while (size >= 1024 && unit < units.size - 1) { size /= 1024; unit++ }
    return "${"%.1f".format(size)} ${units[unit]}"
}