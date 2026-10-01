package com.mangareader.ui

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mangareader.data.ProgressHistoryRow
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** ★ v1.13 A'：每分卷一条独立阅读记忆（数据源 reading_progress，(bookId,path) 唯一天然每卷一行） */
private data class HistorySession(
    val bookId: Long,
    val title: String?,
    val coverPath: String?,
    val chPath: String,
    val lastPage: Int,
    val totalPages: Int,
    val updatedAt: Long,
    val completed: Boolean
)

private fun ProgressHistoryRow.toSession() = HistorySession(
    bookId = bookId, title = bookTitle, coverPath = coverPath, chPath = chPath,
    lastPage = lastPage, totalPages = totalPages, updatedAt = updatedAt, completed = completed
)

/**
 * ★ F 历史记录页：阅读时间线 + 点击续读。
 *  - v1.13 A'：progress 主导——downloads 多分卷书每卷独立展示，"共 N 页"取卷级真实页数
 *  - 时间分桶组头：今天/昨天/本周/更早
 *  - 点击列表项 → openBook(bookId, chPath) 复用 reader/{id}?jump={jump} 精确续读
 *  - 清空/长按移除 = updatedAt 归零隐藏（不物理删行，续读进度与动图选择全保留）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit, onOpen: (Long, String?) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as com.mangareader.App
    val dao = remember { app.db.mangaDao() }
    var sessions by remember { mutableStateOf<List<HistorySession>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            sessions = dao.getProgressHistoryPage(limit = 500, offset = 0).map { it.toSession() }
        } catch (_: Exception) {}
        loaded = true
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空阅读历史") },
            text = { Text("将把全部记录移出阅读历史。\n各卷的阅读进度与「继续读」位置会保留，不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    scope.launch {
                        try { dao.hideAllProgress() } catch (_: Exception) {}
                        sessions = emptyList()
                    }
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("阅读历史") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showClearDialog = true }, enabled = sessions.isNotEmpty()) {
                        Icon(Icons.Default.Delete, "清空历史")
                    }
                }
            )
        }
    ) { pad ->
        if (loaded && sessions.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有阅读记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                Modifier.padding(pad).fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = CONTENT_MAX_WIDTH_DP),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                var lastBucket = -1
                for ((i, s) in sessions.withIndex()) {
                    val bucket = bucketOf(s.updatedAt)
                    if (bucket != lastBucket) {
                        lastBucket = bucket
                        item(key = "header_$bucket") {
                            Text(
                                text = bucketLabel(bucket),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                            )
                        }
                    }
                    item(key = "p_${s.bookId}_${s.chPath.hashCode()}_$i") {
                        HistoryItemRow(
                            s,
                            onDelete = {
                                scope.launch {
                                    try { dao.hideProgress(s.bookId, s.chPath) } catch (_: Exception) {}
                                    sessions = sessions.filterNot { it === s }
                                }
                            }
                        ) { onOpen(s.bookId, s.chPath) }
                    }
                }
            }
        }
    }
}

/** 同一本书只聚合成一条：时间取最近一次，路径/页码取最新在读卷，histIds 收齐供移除 */
// v1.13 A'：数据源已切 reading_progress（每卷一行），聚合函数不再需要

// —— 时间分桶 ——

private const val BUCKET_TODAY = 0
private const val BUCKET_YESTERDAY = 1
private const val BUCKET_THISWEEK = 2
private const val BUCKET_OLDER = 3

private fun bucketOf(ts: Long): Int {
    val cal = java.util.Calendar.getInstance()
    val todayStart = cal.apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
    return when {
        ts >= todayStart -> BUCKET_TODAY
        ts >= todayStart - 24L * 3600 * 1000 -> BUCKET_YESTERDAY
        ts >= todayStart - 6L * 24 * 3600 * 1000 -> BUCKET_THISWEEK
        else -> BUCKET_OLDER
    }
}

private fun bucketLabel(bucket: Int): String = when (bucket) {
    BUCKET_TODAY -> "今天"
    BUCKET_YESTERDAY -> "昨天"
    BUCKET_THISWEEK -> "本周"
    else -> "更早"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun HistoryItemRow(s: HistorySession, onDelete: () -> Unit, onClick: () -> Unit) {
    var showDelete by remember { mutableStateOf(false) }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("移除这条记录") },
            text = { Text("将把「${s.title ?: "已删除书籍"}」的这一卷移出阅读历史。\n该卷的阅读进度与「继续读」位置会保留。") },
            confirmButton = {
                TextButton(onClick = { showDelete = false; onDelete() }) { Text("移除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } }
        )
    }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .combinedClickable(onClick = onClick, onLongClick = { showDelete = true })
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverThumb(s.coverPath, s.chPath)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                s.title ?: "已删除书籍",
                fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                chapterLabel(s.chPath),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    append(if (s.completed) "读完 · " else "读 ")
                    if (s.totalPages > 0) append("第 ${s.lastPage + 1}/${s.totalPages} 页")
                    else append("第 ${s.lastPage + 1} 页")
                    append(" · ${relativeTime(s.updatedAt)}")
                },
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun CoverThumb(coverPath: String?, chPath: String? = null) {
    val ctx = LocalContext.current
    // ★ v1.13 回退重做：优先卷级封面（Reader enterReader 后台生成的 md5(chPath).thumb），无则退书架封面
    var model by remember(coverPath, chPath) { mutableStateOf<Any?>(null) }
    LaunchedEffect(coverPath, chPath) {
        withContext(Dispatchers.IO) {
            fun keyOf(s: String): String = MessageDigest.getInstance("MD5").digest(s.toByteArray())
                .joinToString("") { "%02x".format(it) }
            try {
                val chapterThumb = chPath?.let { File(ctx.filesDir, "covers/${keyOf(it)}.thumb") }
                model = when {
                    chapterThumb != null && chapterThumb.exists() -> chapterThumb
                    coverPath != null -> File(ctx.filesDir, "covers/${keyOf(coverPath)}.thumb")
                    else -> null
                }
            } catch (_: Exception) {}
        }
    }
    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(width = 46.dp, height = 62.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x22888888))
    )
}

/** 卷名提取：z:a:...//subPath/ → subPath 尾段（去扩展名）；d:/a: → doc 段文件名 */
private fun chapterLabel(path: String): String {
    return try {
        if (path.startsWith("z:")) {
            val inner = path.removePrefix("z:")
            val subPath = inner.substringAfter("//", "").trimEnd('/')
            if (subPath.isNotEmpty()) subPath.substringAfterLast('/').substringBeforeLast('.')
            else inner.substringBefore("//").substringAfterLast('/').substringBeforeLast('.')
        } else {
            val doc = path.split("|").getOrNull(2) ?: path
            doc.substringAfterLast('/').substringBeforeLast('.')
        }
    } catch (_: Exception) { path.takeLast(24) }
}

private fun relativeTime(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    val m = diff / 60000
    return when {
        m < 1 -> "刚刚"
        m < 60 -> "$m 分钟前"
        m < 60 * 24 -> "${m / 60} 小时前"
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    }
}
