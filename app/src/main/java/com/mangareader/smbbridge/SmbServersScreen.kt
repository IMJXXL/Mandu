package com.mangareader.smbbridge

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehviewer.core.database.model.SmbSourceEntity
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SMB 服务器管理 UI（design.md 2.2.2 SmbServersScreen / spec 5.1 / 任务组2、3）。
 *
 * ★ 表单用原生 AlertDialog.Builder + EditText（2026-09-05）：Compose 对话框在测试设备
 *   （Redmi/MIUI Android 12）上键盘弹出但无法输入（日志无崩溃栈，判定焦点兼容问题），
 *   原生 View 表单绕开该兼容层。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmbServersScreen(onBack: () -> Unit, onOpenBook: (Long, Int) -> Unit = { _, _ -> }) {
    BackHandler { onBack() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources by SmbRepository.sourcesFlow().collectAsState(initial = emptyList())

    var editing by remember { mutableStateOf<SmbSourceEntity?>(null) }
    var browsingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showWizard by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SmbSourceEntity?>(null) }
    var busyId by remember { mutableStateOf<Long?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showDownloadQueue by remember { mutableStateOf(false) }

    browsingId?.let { id ->
        SmbBrowserScreen(serverId = id, onBack = { browsingId = null }, onOpenBook = onOpenBook, onOpenSettings = { browsingId = null; showSettings = true })
        return
    }

    if (showDownloadQueue) {
        SmbDownloadQueueScreen(onBack = { showDownloadQueue = false }, onOpenBook = { id, page -> onOpenBook(id, page) })
        return
    }

    if (showSettings) {
        SmbSettingsScreen(onBack = { showSettings = false }, onOpenDownloadQueue = { showDownloadQueue = true })
        return
    }

    if (showWizard) {
        SmbAddWizardScreen(onBack = { showWizard = false }, onSaved = { showWizard = false }, initial = editing)
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SMB 服务器") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(onClick = { showDownloadQueue = true }) { Icon(Icons.Default.Download, "下载队列") }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "SMB 设置") }
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
                        // ★ 任务5：隐藏状态读取（对齐本地 Switch + 状态文字）
                        val hiddenCount = remember(s.id) { mutableStateOf(0) }
                        LaunchedEffect(s.id) {
                            hiddenCount.value = withContext(Dispatchers.IO) {
                                SmbDbBridge.mangaDatabase?.mangaDao()?.countSmbHiddenBooks(s.id) ?: 0
                            }
                        }
                        val isHidden = hiddenCount.value > 0
                        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(s.displayName, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                        Text(
                                            "smb://${s.host}:${s.port}/${s.share}/${s.pathPrefix}".trimEnd('/'),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                val statusColor: Color = when {
                                    s.lastError != null -> MaterialTheme.colorScheme.error
                                    s.lastOkAt != null -> Color(0xFF3A9D5D)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                val statusText = when {
                                    s.lastError != null -> "● 错误：${s.lastError.take(60)}"
                                    s.lastOkAt != null -> "● 正常 · ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(s.lastOkAt))}"
                                    else -> "● 未测试"
                                }
                                Text(statusText, fontSize = 12.sp, color = statusColor)
                                Spacer(Modifier.height(10.dp))
                                @OptIn(ExperimentalLayoutApi::class)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(
                                        enabled = !busy,
                                        onClick = {
                                            scope.launch {
                                                busyId = s.id
                                                try {
                                                    val n = SmbScanner.scanSmbServer(s.id)
                                                    val msg = if (n == 0) "扫描完成，书架无变化" else "扫描成功：新增 $n 本，封面后台加载中"
                                                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                                                } catch (t: Throwable) {
                                                    Toast.makeText(ctx, SmbErrors.humanizeSmb(t), Toast.LENGTH_LONG).show()
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
                                                    val password = try { SmbPasswordStore.get(s.id) } catch (_: Throwable) { "" }
                                                    val ok = kotlinx.coroutines.withTimeoutOrNull(5000) {
                                                        runCatching { SmbGateway.listDirectory(s, password, s.pathPrefix, useCache = false) }.isSuccess
                                                    } ?: false
                                                    if (ok) {
                                                        SmbRepository.markOk(s.id)
                                                        Toast.makeText(ctx, "连接正常", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        SmbRepository.markError(s.id, "连接超时或失败")
                                                        Toast.makeText(ctx, "连接失败", Toast.LENGTH_SHORT).show()
                                                    }
                                                } catch (t: Throwable) {
                                                    SmbRepository.markError(s.id, t.message ?: "连接失败")
                                                    Toast.makeText(ctx, SmbErrors.humanizeSmb(t), Toast.LENGTH_LONG).show()
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
                                                SmbDbBridge.mangaDatabase?.mangaDao()?.setSmbBooksHidden(s.id, !show)
                                                hiddenCount.value = SmbDbBridge.mangaDatabase?.mangaDao()?.countSmbHiddenBooks(s.id) ?: 0
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
            text = { Text("将删除「${s.displayName}」的配置、密码、目录索引、页缓存与封面缩略图，并移除其书架中的全部 SMB 书籍。确认删除？") },
            confirmButton = {
                TextButton(onClick = {
                    val target = s
                    deleting = null
                    scope.launch(Dispatchers.IO) {
                        // ★ 删除链不可取消：清缓存是长 IO，用户离开页面会取消 composition scope，
                        //   中途取消会留下"源已删/书还在"或反过来的孤儿（对齐上游 removeRoot 教训）
                        withContext(Dispatchers.IO + NonCancellable) {
                            // ★ 对应清理（用户 2026-09-06 要求）：先按书籍清单清除该书源的磁盘缓存
                            val src = runCatching { SmbRepository.load(target.id) }.getOrNull()
                            val books = runCatching { SmbDbBridge.mangaDatabase?.mangaDao()?.getSmbBooks(target.id) }.getOrNull().orEmpty()
                            clearServerBookCachesSmb(target.id, src, books)
                            runCatching { SmbRepository.delete(target) }
                            SmbDbBridge.mangaDatabase?.mangaDao()?.deleteSmbBooks(target.id)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(ctx, "已删除（含缓存）", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

