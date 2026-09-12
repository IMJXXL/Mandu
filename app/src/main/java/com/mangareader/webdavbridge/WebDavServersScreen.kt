package com.mangareader.webdavbridge


import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add

import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehviewer.core.database.model.WebDavSourceEntity
import com.mangareader.smbbridge.SmbDownloadQueueScreen
import com.mangareader.smbbridge.SmbErrors
import com.hippo.ehviewer.webdav.WebDavGateway
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WebDAV 服务器管理 UI（design.md 2.2.2 WebDavServersScreen / spec 5.7 / 任务组12、13）。
 * **无 API 守卫**：API 26+ 全部可用。
 * ★ 编辑走向导模式（对齐 SmbServersScreen，2026-09-11）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavServersScreen(onBack: () -> Unit, onOpenBook: ((Long) -> Unit)? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources by WebDavRepository.sourcesFlow().collectAsState(initial = emptyList())

    var editing by remember { mutableStateOf<WebDavSourceEntity?>(null) }
    var browsingId by remember { mutableStateOf<Long?>(null) }
    var showWizard by remember { mutableStateOf(false) }

    var deleting by remember { mutableStateOf<WebDavSourceEntity?>(null) }
    var busyId by remember { mutableStateOf<Long?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showDownloadQueue by remember { mutableStateOf(false) }


    browsingId?.let { id ->
        WebDavBrowserScreen(serverId = id, onBack = { browsingId = null }, onOpenBook = onOpenBook ?: {}, onOpenSettings = { browsingId = null; showSettings = true })
        return
    }

    if (showDownloadQueue) {
        SmbDownloadQueueScreen(onBack = { showDownloadQueue = false }, onOpenBook = onOpenBook)
        return
    }

    if (showSettings) {
        WebDavSettingsScreen(onBack = { showSettings = false }, onOpenDownloadQueue = { showSettings = false; showDownloadQueue = true })
        return
    }

    if (showWizard) {
        WebDavAddWizardScreen(onBack = { showWizard = false; editing = null }, onSaved = { showWizard = false; editing = null }, initial = editing)
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WebDAV 服务器") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(onClick = { showDownloadQueue = true }) { Icon(Icons.Default.Download, "下载队列") }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "WebDAV 设置") }
                    IconButton(onClick = { showWizard = true }) { Icon(Icons.Default.Add, "添加服务器（向导）") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (sources.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无服务器，点击右上角 + 添加", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(sources, key = { it.id }) { s ->
                        val busy = busyId == s.id
                        val hiddenCount = remember(s.id) { mutableStateOf(0) }
                        LaunchedEffect(s.id) {
                            hiddenCount.value = withContext(Dispatchers.IO) {
                                WebDavDbBridge.mangaDatabase?.mangaDao()?.countWebDavHiddenBooks(s.id) ?: 0
                            }
                        }
                        val isHidden = hiddenCount.value > 0
                        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Column(Modifier.padding(14.dp)) {
                                Text(s.displayName, fontSize = 16.sp)
                                Text(
                                    s.baseUrl.trimEnd('/') + "/" + s.pathPrefix,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val statusColor: Color = when {
                                    s.lastError != null -> MaterialTheme.colorScheme.error
                                    s.lastOkAt != null -> Color(0xFF3A9D5D)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                val statusText = when {
                                    s.lastError != null -> "● 错误：${s.lastError.take(80)}"
                                    s.lastOkAt != null -> "● 正常 · ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(s.lastOkAt))}"
                                    else -> "● 未测试"
                                }
                                Text(statusText, fontSize = 12.sp, color = statusColor)
                                @OptIn(ExperimentalLayoutApi::class)
                                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(
                                        enabled = !busy,
                                        onClick = {
                                            scope.launch {
                                                busyId = s.id
                                                try {
                                                    val n = WebDavScanner.scanWebDavServer(s.id)
                                                    val msg = if (n == 0) "扫描完成，书架无变化" else "扫描成功：新增 $n 本，封面后台加载中"
                                                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                                                } catch (t: Throwable) {
                                                    Toast.makeText(ctx, SmbErrors.humanizeWebDav(t), Toast.LENGTH_LONG).show()
                                                } finally {
                                                    busyId = null
                                                }
                                            }
                                        },
                                    ) { Text(if (busy) "扫描中…" else "扫描") }
                                    TextButton(
                                        enabled = !busy,
                                        onClick = {
                                            scope.launch {
                                                busyId = s.id
                                                try {
                                                    val password = try { WebDavPasswordStore.get(s.id) } catch (_: Throwable) { "" }
                                                    val ok = kotlinx.coroutines.withTimeoutOrNull(5000) {
                                                        runCatching { WebDavGateway.listDirectory(s, password, s.pathPrefix, useCache = false) }.isSuccess
                                                    } ?: false
                                                    if (ok) {
                                                        WebDavRepository.markOk(s.id)
                                                        Toast.makeText(ctx, "连接正常", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        WebDavRepository.markError(s.id, "连接超时或失败")
                                                        Toast.makeText(ctx, "连接失败", Toast.LENGTH_SHORT).show()
                                                    }
                                                } catch (t: Throwable) {
                                                    WebDavRepository.markError(s.id, t.message ?: "连接失败")
                                                    Toast.makeText(ctx, SmbErrors.humanizeWebDav(t), Toast.LENGTH_LONG).show()
                                                } finally {
                                                    busyId = null
                                                }
                                            }
                                        },
                                    ) { Text("测试") }
                                    TextButton(onClick = { browsingId = s.id }) { Text("浏览") }
                                    TextButton(onClick = { editing = s; showWizard = true }) {
                                        Icon(Icons.Default.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("编辑")
                                    }
                                    TextButton(onClick = { deleting = s }) {
                                        Icon(Icons.Default.Delete, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("删除")
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (isHidden) "已隐藏" else "已显示", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Switch(
                                        checked = !isHidden,
                                        onCheckedChange = { show ->
                                            scope.launch(Dispatchers.IO) {
                                                WebDavDbBridge.mangaDatabase?.mangaDao()?.setWebDavBooksHidden(s.id, !show)
                                                hiddenCount.value = WebDavDbBridge.mangaDatabase?.mangaDao()?.countWebDavHiddenBooks(s.id) ?: 0
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除服务器") },
            text = { Text("将删除「${s.displayName}」的配置、密码、目录索引、页缓存与封面缩略图，并移除其书架中的全部 WebDAV 书籍。确认删除？") },
            confirmButton = {
                TextButton(onClick = {
                    val target = s
                    deleting = null
                    scope.launch(Dispatchers.IO) {
                        // ★ 对应清理：先按书籍清单清除该书源的磁盘缓存
                        val src = runCatching { WebDavRepository.load(target.id) }.getOrNull()
                        val books = runCatching { WebDavDbBridge.mangaDatabase?.mangaDao()?.getWebDavBooks(target.id) }.getOrNull().orEmpty()
                        clearServerBookCachesWebDav(target.id, src, books)
                        runCatching { WebDavRepository.delete(target) }
                        WebDavDbBridge.mangaDatabase?.mangaDao()?.deleteWebDavBooks(target.id)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, "已删除（含缓存）", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

