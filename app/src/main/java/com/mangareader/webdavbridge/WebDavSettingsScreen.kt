package com.mangareader.webdavbridge

import com.mangareader.smbbridge.IntRow
import com.mangareader.smbbridge.SectionCard
import com.mangareader.smbbridge.SwitchRow
import com.mangareader.smbbridge.dirSize
import com.mangareader.smbbridge.formatSize
import com.mangareader.smbbridge.showArchivePasswdsDialog
import com.mangareader.smbbridge.unwrapActivity

import android.app.Activity
import android.content.ContextWrapper
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.webdav.WebDavCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SMB 设置页（任务B补全：参考项目 Settings 中被 SMB/WebDAV 栈消费的字段全部接入 UI）。
 * 响应式：PrefDelegate.valueFlow() collectAsState；写入走 value setter（DataStore 持久化）。
 * 缓存管理卡片：分类容量统计 + 一键清理（原服务器页顶栏入口迁移至此，更具美感）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cacheSizes by remember { mutableStateOf<List<Triple<String, String, Long>>>(emptyList()) }
    var clearing by remember { mutableStateOf(false) }

    suspend fun refreshSizes() = withContext(Dispatchers.IO) {
        cacheSizes = listOf(
            Triple("webdav_cache", "页缓存 / 整档下载", dirSize(ctx, "cache/webdav_cache")),
            Triple("webdav_thumb_cache", "书架封面缩略图", dirSize(ctx, "cache/webdav_thumb_cache")),
            Triple("archive_pages", "流式页缓存（SMB/WebDAV 共用）", dirSize(ctx, "cache/archive_pages")),
        )
    }
    LaunchedEffect(clearing) { refreshSizes() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WebDAV 设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {

            SectionCard("连接与安全") {
                SwitchRow("允许自签名/不安全证书", "自签名 NAS 或内网 HTTPS 服务打开即可连接；关闭时使用系统证书校验（更安全）", Settings.webDavInsecureTls)
            }

            SectionCard("浏览行为") {
                SwitchRow("ZIP 当文件夹浏览", "在浏览界面把 zip 归档当文件夹逐页查看", Settings.browseZipAsDir)
                SwitchRow("显示隐藏文件", "列出以 . 开头或服务端标记隐藏的条目", Settings.browseShowHiddenFiles)
                SwitchRow("扫描隐藏文件", "扫描时也纳入隐藏文件/目录", Settings.scanHiddenFiles)
                SwitchRow("显示虚拟书库", "浏览中显示 @S 虚拟画廊等虚拟条目", Settings.browseShowVirtualGalleries)
                SwitchRow("浏览显示归档页数", "目录列表中显示每个归档的总页数（需要额外读取）", Settings.browseArchivePageCount)
                SwitchRow("返回时回到目录", "从阅读退出后回到浏览目录而不是书架", Settings.alwaysExitToDir)
            }

            SectionCard("目录索引与扫描") {
                SwitchRow("目录索引缓存", "缓存目录列表与页数信息，二次进入/扫描更快", Settings.networkFolderIndexCache)
                SwitchRow("快速扫描", "增量扫描时跳过深度 peek，更快但新目录信息可能不全", Settings.networkFolderIndexQuickScan)
                SwitchRow("保存文件标记", "在共享目录写入标记文件辅助识别", Settings.saveFileMarkers)
            }

            SectionCard("远程阅读") {
                SwitchRow("禁用阅读网络缓存", "阅读页不落盘缓存（更省空间，但重读需重新下载）", Settings.disableReaderNetworkCache)
                IntRow("阅读页缓存预算 (MB)", "流式页缓存的总容量上限", Settings.readCacheSize)
                IntRow("预加载页数", "当前页之外预取的页数", Settings.preloadImage)
                IntRow("下载线程数", "整档下载使用的并发连接数", Settings.multiThreadDownload)
            }

            SectionCard("归档密码") {
                var passwds by remember { mutableStateOf(Settings.archivePasswds) }
                Row(
                    Modifier.fillMaxWidth().clickable {
                        (ctx.unwrapActivity())?.let { activity ->
                            showArchivePasswdsDialog(activity, passwds.sorted()) { newList ->
                                Settings.archivePasswds = newList.toSet()
                                passwds = newList.toSet()
                            }
                        }
                    }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("归档密码 (${passwds.size} 条)", fontSize = 16.sp)
                        Text("加密归档打开时按列表自动尝试", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("编辑 ›", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
            }

            SectionCard("缓存管理") {
                cacheSizes.forEach { (dir, label, size) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(dir, fontSize = 14.sp)
                            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(formatSize(size), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Button(
                    onClick = {
                        scope.launch {
                            clearing = true
                            withContext(Dispatchers.IO) {
                                listOf("cache/webdav_cache", "cache/webdav_thumb_cache", "cache/archive_pages").forEach { rel ->
                                    java.io.File(ctx.applicationInfo.dataDir, rel).deleteRecursively()
                                }
                            }
                            clearing = false
                        }
                    },
                    enabled = !clearing,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text(if (clearing) "清理中…" else "清理全部 SMB 缓存") }
                Text(
                    "正在阅读的书不受影响，退出阅读后缓存会按需重建",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── 共用组件 ──

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(10.dp))
    Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) { content() }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String, pref: com.ehviewer.core.preferences.PrefDelegate<Boolean>) {
    val state by pref.valueFlow().collectAsState(initial = pref.value)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = state, onCheckedChange = { pref.value = it })
    }
}

@Composable
fun IntRow(title: String, subtitle: String, pref: com.ehviewer.core.preferences.PrefDelegate<Int>) {
    val ctx = LocalContext.current
    val state by pref.valueFlow().collectAsState(initial = pref.value)
    Row(Modifier.fillMaxWidth().clickable {
        (ctx.unwrapActivity())?.let { activity ->
            showIntInput(activity, title, state) { pref.value = it }
        }
    }.padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("$state", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
    }
}

/** 原生输入对话框（与服务器表单同思路，绕开 MIUI Compose 输入兼容问题） */
fun showIntInput(activity: Activity, title: String, current: Int, onDone: (Int) -> Unit) {
    val input = EditText(activity).apply {
        setText(current.toString())
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine(true)
        val d = (12 * activity.resources.displayMetrics.density).toInt()
        setPadding(d, d, d, d)
    }
    android.app.AlertDialog.Builder(activity)
        .setTitle(title)
        .setView(input)
        .setPositiveButton("确定") { _, _ -> input.text.toString().toIntOrNull()?.let(onDone) }
        .setNegativeButton("取消", null)
        .show()
}

fun showArchivePasswdsDialog(activity: Activity, current: List<String>, onDone: (List<String>) -> Unit) {
    fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
    val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), 0) }
    val input = EditText(activity).apply {
        hint = "每行一条密码"
        setText(current.joinToString("\n"))
        minLines = 3
        gravity = android.view.Gravity.TOP
        val d = dp(12)
        setPadding(d, d, d, d)
    }
    box.addView(input)
    box.addView(android.widget.TextView(activity).apply {
        text = "提示：逐行编辑，清空即全部删除"
        val d = dp(8)
        setPadding(0, d, 0, 0)
        textSize = 11f
    })
    android.app.AlertDialog.Builder(activity)
        .setTitle("归档密码")
        .setView(ScrollView(activity).apply { addView(box) })
        .setPositiveButton("保存") { _, _ ->
            onDone(input.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() })
        }
        .setNegativeButton("取消", null)
        .show()
}

/** 解包 ContextWrapper 链拿 Activity（Compose LocalContext 可能被 ThemeWrapper 包裹） */
fun android.content.Context?.unwrapActivity(): Activity? {
    var c = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

suspend fun dirSize(ctx: android.content.Context, rel: String): Long = withContext(Dispatchers.IO) {
    val dir = java.io.File(ctx.applicationInfo.dataDir, rel)
    if (!dir.exists()) 0L else dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
}

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
    else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
}

// ── 阅读页缓存上限：预设/自定义/无上限（native 单选，与表单同思路） ──
private val CACHE_LIMIT_OPTIONS = listOf(
    0L to "无上限（不自动清理）",
    5120L to "5 GB",
    2048L to "2 GB",
    1024L to "1 GB",
    640L to "640 MB（默认）",
    -1L to "自定义 (MB)",
)

fun cacheLimitLabel(v: Int): String = when {
    v <= 0 -> "无上限"
    v == 640 -> "640 MB（默认）"
    else -> "$v MB"
}

@Composable
fun CacheLimitRow() {
    val ctx = LocalContext.current
    val state by Settings.readCacheSize.valueFlow().collectAsState(initial = Settings.readCacheSize.value)
    Row(Modifier.fillMaxWidth().clickable {
        (ctx.unwrapActivity())?.let { activity ->
            showCacheLimitDialog(activity, state) { Settings.readCacheSize.value = it }
        }
    }.padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("阅读页缓存上限", fontSize = 15.sp)
            Text("流式页缓存/整档下载的磁盘预算，超限自动清理最旧内容", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(cacheLimitLabel(state), fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
    }
}

fun showCacheLimitDialog(activity: Activity, current: Int, onPick: (Int) -> Unit) {
    val checked = intArrayOf(CACHE_LIMIT_OPTIONS.firstOrNull { it.first.toLong() == current.toLong() }?.second?.let { CACHE_LIMIT_OPTIONS.indexOfFirst { o -> o.first.toLong() == current.toLong() } } ?: CACHE_LIMIT_OPTIONS.size - 1)
    val labels = CACHE_LIMIT_OPTIONS.map {
        if (it.first == current.toLong() && it.first != -1L) "${it.second}（当前）" else it.second
    }.toTypedArray()
    android.app.AlertDialog.Builder(activity)
        .setTitle("阅读页缓存上限")
        .setSingleChoiceItems(labels, checked[0]) { d, which ->
            checked[0] = which
            if (CACHE_LIMIT_OPTIONS[which].first != -1L) {
                onPick(CACHE_LIMIT_OPTIONS[which].first.toInt())
                d.dismiss()
            }
        }
        .setPositiveButton(if (CACHE_LIMIT_OPTIONS.getOrNull(checked[0])?.first == -1L) "下一步" else "确定") { d, _ ->
            if (CACHE_LIMIT_OPTIONS.getOrNull(checked[0])?.first == -1L) {
                val input = EditText(activity).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setSingleLine(true)
                    hint = "320 - 5120 (MB)"
                    val d2 = (12 * activity.resources.displayMetrics.density).toInt()
                    setPadding(d2, d2, d2, d2)
                }
                android.app.AlertDialog.Builder(activity)
                    .setTitle("自定义缓存上限 (MB)")
                    .setView(input)
                    .setPositiveButton("确定") { _, _ ->
                        input.text.toString().toIntOrNull()?.let { v ->
                            if (v > 0) onPick(v.coerceIn(320, 5120)) else onPick(0)
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
        .setNegativeButton("取消", null)
        .show()
}

// ── 删除服务器时对应清理该书源的全部磁盘缓存（对齐本地删除行为，用户 2026-09-06 要求） ──
suspend fun clearServerBookCachesWebDav(serverId: Long, src: com.ehviewer.core.database.model.WebDavSourceEntity?, books: List<com.mangareader.data.Book>) {
    books.forEach { b ->
        val ref = WebDavPaths.parseBookPath(b.filePath) ?: return@forEach
        val remote = ref.remotePath
        val rel = remote.substringBefore(':')
        runCatching {
            java.io.File(WebDavCache.thumbCachePath(serverId, rel).toString()).delete()
            java.io.File(WebDavCache.cachePathForRemoteFile(serverId, remote).toString()).delete()
            java.io.File(com.hippo.ehviewer.library.ArchiveStreamPageCache.dirFor("webdav:$serverId:$remote").toString()).deleteRecursively()
        }
    }
}
