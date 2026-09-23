package com.mangareader.updatecheck

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

@Composable
fun UpdateCheckPanel(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var downloadInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var noUpdateMsg by remember { mutableStateOf<String?>(null) }
    var showDownloadDialog by remember { mutableStateOf(false) }
    val autoCheck = remember { mutableStateOf(UpdateCheckConfig.isAutoCheckEnabled(ctx)) }
    val intervalDays = remember { mutableStateOf(UpdateCheckConfig.getIntervalDays(ctx)) }

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text("检查更新", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (checking) return@Button
                checking = true
                noUpdateMsg = null
                scope.launch {
                    val info = UpdateChecker.checkForUpdate(ctx)
                    checking = false
                    if (info != null) updateInfo = info
                    else noUpdateMsg = "已是最新版本"
                }
            },
            enabled = !checking
        ) {
            Text(if (checking) "检查中…" else "检查更新")
        }
        noUpdateMsg?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("自动检查", fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(
                checked = autoCheck.value,
                onCheckedChange = {
                    autoCheck.value = it
                    UpdateCheckConfig.setAutoCheckEnabled(ctx, it)
                }
            )
        }
        if (autoCheck.value) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("检查间隔（天）", fontSize = 14.sp, modifier = Modifier.weight(1f))
                val intervals = listOf(1, 3, 7, 14, 30)
                var expanded by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { expanded = true }) {
                        Text("${intervalDays.value} 天")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        intervals.forEach { d ->
                            DropdownMenuItem(
                                text = { Text("$d 天") },
                                onClick = {
                                    intervalDays.value = d
                                    UpdateCheckConfig.setIntervalDays(ctx, d)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    updateInfo?.let { info ->
        UpdateDialog(
            info = info,
            onConfirm = {
                downloadInfo = info
                updateInfo = null
                showDownloadDialog = true
            },
            onDismiss = {
                updateInfo = null
            }
        )
    }

    if (showDownloadDialog) {
        downloadInfo?.let { info ->
            UpdateDownloadDialog(
                info = info,
                onDismiss = { showDownloadDialog = false }
            )
        }
    }
}

@Composable
private fun UpdateDialog(
    info: UpdateInfo,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 400.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("发现新版本", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(info.version, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                if (info.apkSize > 0) {
                    Text("大小：${info.apkSize / 1048576} MB", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    info.changelog,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).heightIn(max = 200.dp)
                )
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("稍后再说") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onConfirm) { Text("立即更新") }
                }
            }
        }
    }
}

@Composable
private fun UpdateDownloadDialog(
    info: UpdateInfo,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf("准备下载…") }
    var done by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var slowHintShown by remember { mutableStateOf(false) }
    var selectedMirror by remember { mutableStateOf(0) }
    val startTime = remember { System.currentTimeMillis() }

    LaunchedEffect(selectedMirror) {
        if (done) return@LaunchedEffect
        scope.launch {
            progress = 0f
            status = "正在连接…"
            failed = false
            var downloading = false
            val mirror = UpdateConstants.MIRRORS.getOrNull(selectedMirror)?.first ?: ""
            val file = ApkDownloader.download(info.downloadUrl, ctx, info.version, info.apkSize, mirror) { p ->
                progress = p
                if (!downloading) {
                    downloading = true
                    status = "下载中…"
                }
                if (!slowHintShown && p < 0.2f &&
                    System.currentTimeMillis() - startTime > 30_000
                ) {
                    slowHintShown = true
                    status = "下载较慢，可切换线路或用浏览器下载"
                }
            }
            if (file != null) {
                status = "下载完成，正在调起安装…"
                done = true
                val ok = ApkInstaller.install(ctx, file)
                if (!ok) {
                    Toast.makeText(ctx, "安装失败，请重试", Toast.LENGTH_SHORT).show()
                }
                onDismiss()
            } else {
                status = "下载失败，可换线路重试"
                failed = true
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 400.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("正在更新", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                if (!done && !failed) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("${(progress * 100).toInt()}%", fontSize = 13.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(status, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!done) {
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        UpdateConstants.MIRRORS.forEachIndexed { idx, (_, label) ->
                            FilterChip(
                                selected = selectedMirror == idx,
                                onClick = { selectedMirror = idx },
                                label = { Text(label, fontSize = 11.sp) },
                                modifier = Modifier.padding(horizontal = 2.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        try {
                            ctx.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(info.downloadUrl))
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: Exception) {
                            Toast.makeText(ctx, "无法打开浏览器", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("用浏览器下载") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onDismiss) { Text(if (failed) "关闭" else "后台下载") }
                }
            }
        }
    }
}
