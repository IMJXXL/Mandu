package com.mangareader.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import coil.compose.AsyncImage
import com.mangareader.data.HistoryRow
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class HistorySession(
    val bookId: Long,
    val title: String?,
    val coverPath: String?,
    val lastPath: String?,
    val pagesRead: Int,
    val latestTimestamp: Long
)

/**
 * ★ F 历史记录页：阅读时间线 + 点击续读。
 *  - ②同书 <30min 的连续记录内存聚合为一个"阅读会话"，防逐页刷屏
 *  - 时间分桶组头：今天/昨天/本周/更早
 *  - 点击列表项 → openBook(bookId, lastPath) 复用 reader/{id}?jump={jump} 精确续读
 *  - 顶栏清空按钮（二次确认），不做自动清理
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
            val rows = dao.getHistoryPage(limit = 500, offset = 0)
            sessions = aggregateSessions(rows)
        } catch (_: Exception) {}
        loaded = true
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空阅读历史") },
            text = { Text("将删除全部历史记录，此操作不可恢复。确定继续吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    scope.launch {
                        try { dao.clearAllHistory() } catch (_: Exception) {}
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
                Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                var lastBucket = -1
                for ((i, s) in sessions.withIndex()) {
                    val bucket = bucketOf(s.latestTimestamp)
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
                    item(key = "s_${s.bookId}_${s.latestTimestamp}_$i") {
                        HistoryItemRow(s) { onOpen(s.bookId, s.lastPath) }
                    }
                }
            }
        }
    }
}

/** ②同书相邻 <30min 记录聚合为会话；行已按 timestamp 倒序 */
private fun aggregateSessions(rows: List<HistoryRow>): List<HistorySession> {
    val out = mutableListOf<HistorySession>()
    val sessionGapMs = 30L * 60 * 1000
    for (r in rows) {
        val last = out.lastOrNull()
        if (last != null && last.bookId == r.bookId &&
            last.latestTimestamp - r.timestamp < sessionGapMs) {
            out[out.size - 1] = last.copy(
                pagesRead = last.pagesRead + r.pagesRead,
                latestTimestamp = maxOf(last.latestTimestamp, r.timestamp),
                lastPath = last.lastPath ?: r.lastPath
            )
        } else {
            out.add(HistorySession(r.bookId, r.bookTitle, r.coverPath, r.lastPath, r.pagesRead, r.timestamp))
        }
    }
    return out
}

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

@Composable
private fun HistoryItemRow(s: HistorySession, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverThumb(s.coverPath)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                s.title ?: "已删除书籍",
                fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (!s.lastPath.isNullOrEmpty()) {
                Text(
                    chapterLabel(s.lastPath),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                "读 ${s.pagesRead} 页 · ${relativeTime(s.latestTimestamp)}",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text("继续读", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun CoverThumb(coverPath: String?) {
    val ctx = LocalContext.current
    val model = remember(coverPath) {
        coverPath?.let { cp ->
            try {
                val key = MessageDigest.getInstance("MD5").digest(cp.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                File(ctx.filesDir, "covers/$key.thumb")
            } catch (_: Exception) { null }
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

/** 卷名提取：z:a:...//subPath/ → subPath 尾段；d:/a: → doc 段文件名 */
private fun chapterLabel(path: String): String {
    return try {
        if (path.startsWith("z:")) {
            val inner = path.removePrefix("z:")
            val subPath = inner.substringAfter("//", "").trimEnd('/')
            if (subPath.isNotEmpty()) subPath.substringAfterLast('/')
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
