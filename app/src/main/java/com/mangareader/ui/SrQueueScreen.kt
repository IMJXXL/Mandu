package com.mangareader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.security.MessageDigest
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.sr.SRQueueItem
import com.mangareader.sr.SrBatchRunner
import com.mangareader.sr.SrCore
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 超分队列页：进行中（含进度/取消）+ 已完成（可删除产物）+ 缓存统计。
 * 数据来自 SRQueueStore（JSON 持久化），进度由 SrCore.queueProgress 驱动刷新。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SrQueueScreen(onBack: () -> Unit, onOpenBook: ((Long, String) -> Unit)? = null) {
    BackHandler { onBack() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // 封面缓存：bookId -> 封面文件（历史页同款 md5 规则，优先卷、退书架封面）
    val coverCache = remember { mutableStateMapOf<Long, File?>() }
    fun keyOf(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    @Composable
    fun srCover(bookId: Long, chPath: String?) {
        val model = coverCache[bookId]
        LaunchedEffect(bookId, chPath) {
            if (!coverCache.containsKey(bookId)) {
                withContext(Dispatchers.IO) {
                    try {
                        val db = com.mangareader.data.MangaDatabase.getDatabase(ctx)
                        val coverPath = db.mangaDao().getBookById(bookId)?.coverPath
                        val chFile = chPath?.let { File(ctx.filesDir, "covers/${keyOf(it)}.thumb") }
                        coverCache[bookId] = when {
                            chFile != null && chFile.exists() -> chFile
                            coverPath != null -> File(ctx.filesDir, "covers/${keyOf(coverPath)}.thumb")
                            else -> null
                        }
                    } catch (_: Exception) {
                        coverCache[bookId] = null
                    }
                }
            }
        }
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 40.dp, height = 56.dp)
                .clip(RoundedCornerShape(4.dp)),
        )
    }
    LaunchedEffect(Unit) { if (!SrCore.initialized) SrCore.init(ctx) }

    var queue by remember { mutableStateOf(emptyList<SRQueueItem>()) }
    var completed by remember { mutableStateOf(emptyList<com.mangareader.sr.SRDiskCache.BatchCompletedChapter>()) }
    var realtimeText by remember { mutableStateOf("") }
    var batchText by remember { mutableStateOf("") }
    // 暂停状态必须是 Compose 状态：直接读普通 var 时，若其它状态内容相等不触发重组，按钮文案会永远停在"暂停"
    var pausedState by remember { mutableStateOf(com.mangareader.sr.SrBatchService.paused) }

    fun refresh() {
        if (!SrCore.initialized) return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val q = SrCore.queueStore.load()
            val c = SrCore.diskCache.getCompletedBatchChapters()
            val (rc, rb) = SrCore.diskCache.getRealtimeUsage()
            val (bc, bb) = SrCore.diskCache.getBatchUsage()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                queue = q; completed = c
                realtimeText = "共 $rc 张 / ${rb / 1024 / 1024} MB"
                batchText = "共 $bc 张 / ${bb / 1024 / 1024} MB"
            }
        }
    }

    LaunchedEffect(Unit) {
        refresh()
        if (SrCore.initialized) {
            launch { SrCore.queueProgress.collect { refresh() } }
            // 暂停/继续状态实时刷新按钮文案
            launch {
                SrCore.batchPausedFlow.collect {
                    pausedState = com.mangareader.sr.SrBatchService.paused
                    refresh()
                }
            }
        }
    }

    // ── 书架级批量入队（2026-10-03 重度读者走查：囤书整库增强不应一本本点）──
    // "未增强" = 不在队列 + 无批量成品 + 无页级产物树 + 本地来源
    var scanningAll by remember { mutableStateOf(false) }
    var unenhancedBooks by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    var showAllDialog by remember { mutableStateOf(false) }

    fun scanUnenhancedBooks() {
        scope.launch(Dispatchers.IO) {
            scanningAll = true
            try {
                val dao = com.mangareader.data.MangaDatabase.getDatabase(ctx).mangaDao()
                val queuedIds = SrCore.queueStore.load().map { it.bookId }.toSet()
                val doneIds = com.mangareader.sr.SrCore.diskCache.getCompletedBatchChapters().map { it.bookId }.toSet()
                // getVisibleBooks 过滤 isHidden——书架管理里隐藏的书不参与批量入队
                val found = dao.getVisibleBooks().first()
                    .filter { b ->
                        !b.filePath.startsWith("smb:") && !b.filePath.startsWith("webdav:") &&
                            b.id !in queuedIds && b.id !in doneIds &&
                            !SrCore.diskCache.hasArtifactsForBook(b.id)
                    }
                    .map { it.id to it.title }
                unenhancedBooks = found
                if (found.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(ctx, "书架里的书都增强过或已在队列中", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) { showAllDialog = true }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(ctx, "扫描失败，请稍后再试", android.widget.Toast.LENGTH_SHORT).show()
                }
            } finally {
                scanningAll = false
            }
        }
    }

    if (showAllDialog) {
        AlertDialog(
            onDismissRequest = { showAllDialog = false },
            title = { Text("加入 ${unenhancedBooks.size} 本未增强的书") },
            text = {
                Text(
                    "处理在空闲时后台自动进行，整库一次跑完较久；产物受「增强产物上限」控制，超限自动清最旧的。" +
                        "增强用的档位/降噪/引擎就是「本次阅读」页当前选的那套。",
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        showAllDialog = false
                        SrBatchRunner.enqueueBooks(ctx, unenhancedBooks, false) { r ->
                            (ctx as? android.app.Activity)?.runOnUiThread {
                                android.widget.Toast.makeText(
                                    ctx,
                                    if (r.chapters > 0) "已加入队列：${r.chapters} 章 ${r.pages} 页" else "没有找到可处理的页面",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                        refresh()
                    }) { Text("仅已读章节") }
                    TextButton(onClick = {
                        showAllDialog = false
                        SrBatchRunner.enqueueBooks(ctx, unenhancedBooks, true) { r ->
                            (ctx as? android.app.Activity)?.runOnUiThread {
                                android.widget.Toast.makeText(
                                    ctx,
                                    if (r.chapters > 0) "已加入队列：${r.chapters} 章 ${r.pages} 页，空闲时自动逐章处理" else "没有找到可处理的页面",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                        refresh()
                    }) { Text("整本（含未读章）") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showAllDialog = false }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("增强队列") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    // 暂停/继续批量（通知栏也有此按钮）
                    TextButton(onClick = {
                        pausedState = !pausedState // 先本地翻转立即反馈，服务回包后再校准
                        ctx.startService(
                            android.content.Intent(ctx, com.mangareader.sr.SrBatchService::class.java)
                                .setAction(com.mangareader.sr.SrBatchService.ACTION_TOGGLE_PAUSE),
                        )
                    }) { Text(if (pausedState) "继续" else "暂停") }
                    TextButton(onClick = {
                        SrBatchRunner.clear(ctx)
                        refresh()
                    }) { Text("清空队列") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = CONTENT_MAX_WIDTH_DP).padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("缓存管理", fontWeight = FontWeight.Bold)

                        // ── 实时缓存 ──
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("实时缓存", fontWeight = FontWeight.Medium, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    SrCore.dispatcher.clearCache()
                                    refresh()
                                }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("清空", fontSize = 12.sp) }
                            }
                            Text(realtimeText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("阅读时临时算的增强图，自动限额、自动清最旧，清了无影响", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        HorizontalDivider()

                        // ── 批量产物 ──
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("批量产物", fontWeight = FontWeight.Medium, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    SrCore.diskCache.clearBatch()
                                    // 队列里条目进度同步归零（产物都没了，进度数字不能停在中段）
                                    SrCore.queueStore.save(
                                        SrCore.queueStore.load().map { it.copy(processedPages = 0) },
                                    )
                                    refresh()
                                }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Text("清空", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                            Text(batchText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("批量增强的离线成品，永久保留，打开书直接高清；清了要重新跑批量", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // 书架级批量入队（重度读者走查 2026-10-03）
            item {
                OutlinedButton(
                    onClick = { scanUnenhancedBooks() },
                    enabled = !scanningAll,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (scanningAll) "正在扫描书架…" else "把书架里未增强的书全部加入队列")
                }
            }

            // 进行中：只显示真正还在处理的；批量服务跑完的会被自动移到下方"已完成"
            item {
                Text("进行中 / 排队中（${queue.size} 章）", fontWeight = FontWeight.Bold)
            }
            if (queue.isEmpty()) {
                item {
                    Text("队列为空。可点上方按钮把整书架未增强的书一次性排进来；也可以在书架长按多选批量加，或在阅读器画质增强面板里入队本书。",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(queue) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        srCover(item.bookId, item.path)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.bookTitle, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            val chapterShort = item.path.substringAfterLast("//").ifBlank { item.path.takeLast(40) }
                            Text(chapterShort, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val total = item.totalPages.coerceAtLeast(1)
                            LinearProgressIndicator(
                                progress = { (item.processedPages.toFloat() / total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("${item.processedPages}/${item.totalPages} 页", fontSize = 12.sp)
                        }
                        IconButton(onClick = {
                            SrBatchRunner.remove(ctx, item.chapterKey)
                            refresh()
                        }) { Icon(Icons.Default.Delete, "取消该章") }
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("已完成（${completed.size} 章）", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("点任意章直接去看", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
            items(completed) { ch ->
                Card(
                    Modifier.fillMaxWidth().clickable(enabled = onOpenBook != null) {
                        // 直接进入这本已超分书的阅读（离线产物阅读时自动生效）
                        onOpenBook?.invoke(ch.bookId, ch.path)
                    },
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        srCover(ch.bookId, ch.path)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ch.bookTitle, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text(
                                "${ch.pageCount} 页 · ${if (ch.modelKey.isEmpty()) "旧版产物" else com.mangareader.sr.SRModel.fromKey(ch.modelKey).displayName}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "${formatTime(ch.completedAt)} · 点按直接阅读",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        IconButton(onClick = {
                            SrCore.diskCache.removeBatchChapter(ch.bookTitle, ch.chapterKey)
                            refresh()
                        }) { Icon(Icons.Default.Delete, "删除产物") }
                    }
                }
            }
        }
    }
}

private fun formatTime(ts: Long): String {
    if (ts <= 0) return ""
    return try {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    } catch (_: Exception) {
        ""
    }
}
