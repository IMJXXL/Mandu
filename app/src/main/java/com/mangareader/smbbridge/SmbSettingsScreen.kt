package com.mangareader.smbbridge

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.smb.SmbCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SMB 设置页（任务B补全：参考项目 Settings 中被 SMB/WebDAV 栈消费的字段全部接入 UI）。
 * 响应式：PrefDelegate.valueFlow() collectAsState；写入走 value setter（DataStore 持久化）。
 * 缓存管理卡片：分类容量统计 + 一键清理（原服务器页顶栏入口迁移至此，更具美感）。
 */
private val SMB_CACHE_ENTRIES = listOf(
    CacheEntry("SMB 页缓存", "整档下载与单页缓存", "cache/smb_cache"),
    CacheEntry("SMB 浏览缩略图", "浏览目录时图片文件的缩略图", "cache/smb_thumb_cache"),
    CacheEntry("流式页缓存", "ZIP/TAR 归档页缓存（本地/SMB/WebDAV 共用）", "cache/archive_pages"),
    CacheEntry("归档封面缩略图", "归档第一页封面（本地/SMB/WebDAV 共享）", "cache/archive_thumb"),
    CacheEntry("RAR/7z 提取缓存", "RAR/CBR/7z 归档提取的页文件", "cache/solid_extract"),
    CacheEntry("PDF/EPUB 提取缓存", "PDF/EPUB 文档提取的页文件", "cache/document_extract"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmbSettingsScreen(onBack: () -> Unit, onOpenDownloadQueue: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SMB 设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = onOpenDownloadQueue) {
                        Icon(Icons.Default.Download, "下载队列")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {

            item {
            SectionCard("连接与协议") {
                SwitchRow("仅使用 SMB3 协议", "只协商 SMB3（部分老 NAS/路由器共享需关闭）", Settings.smb3Only)
                SwitchRow("加密数据传输", "SMB 报文加密，更安全但速度略降", Settings.smbEncryptData)
                SwitchRow("异步传输模式", "网络收发异步化，高延迟网络更稳", Settings.smbAsyncTransport)
                SwitchRow("阅读安全并发", "低配设备建议开：降低并发连接与操作数，更稳但略慢", Settings.smbReaderSafeConcurrency)
            }
            }

            item {
            SectionCard("浏览行为") {
                SwitchRow("ZIP 当文件夹浏览", "在浏览界面把 zip 归档当文件夹逐页查看", Settings.browseZipAsDir)
                SwitchRow("显示隐藏文件", "列出以 . 开头或服务端标记隐藏的条目", Settings.browseShowHiddenFiles)
                SwitchRow("扫描隐藏文件", "扫描时也纳入隐藏文件/目录", Settings.scanHiddenFiles)
                SwitchRow("显示虚拟书库", "浏览中显示 @S 虚拟画廊等虚拟条目", Settings.browseShowVirtualGalleries)
                SwitchRow("浏览显示归档页数", "目录列表中显示每个归档的总页数（需要额外读取）", Settings.browseArchivePageCount)
                SwitchRow("返回时回到目录", "从阅读退出后回到浏览目录而不是书架", Settings.alwaysExitToDir)
            }
            }

            item {
            SectionCard("目录索引与扫描") {
                SwitchRow("目录索引缓存", "缓存目录列表与页数信息，二次进入/扫描更快", Settings.networkFolderIndexCache)
                SwitchRow("快速扫描", "增量扫描时跳过深度 peek，更快但新目录信息可能不全", Settings.networkFolderIndexQuickScan)
                SwitchRow("保存文件标记", "在共享目录写入标记文件辅助识别", Settings.saveFileMarkers)
            }
            }

            item {
            SectionCard("远程阅读") {
                // 阅读页缓存上限见下方 CacheLimitRow（预设/自定义/无上限）
                SwitchRow("禁用阅读网络缓存", "阅读页不落盘缓存（更省空间，但重读需重新下载）", Settings.disableReaderNetworkCache)
                IntRow("阅读页缓存预算 (MB)", "流式页缓存的总容量上限", Settings.readCacheSize)
                IntRow("预加载页数", "当前页之外预取的页数", Settings.preloadImage)
                IntRow("下载线程数", "整档下载使用的并发连接数", Settings.multiThreadDownload)
            }
            }


            item {
            SectionCard("下载目录") {
                val downloadPath by Settings.smbDownloadDirPath.collectAsState()
                val pickDirLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree(),
                ) { uri: Uri? ->
                    if (uri != null) {
                        runCatching {
                            ctx.contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                            )
                        }
                        Settings.smbDownloadDirTreeUri.value = uri.toString()
                        val docId = DocumentsContract.getTreeDocumentId(uri)
                        val path = if (docId.startsWith("primary:")) {
                            android.os.Environment.getExternalStorageDirectory().absolutePath + "/" + docId.substringAfter("primary:")
                        } else {
                            uri.toString()
                        }
                        Settings.smbDownloadDirPath.value = path
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clickable { pickDirLauncher.launch(null) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Folder, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("全量下载保存路径", fontSize = 16.sp)
                        Text(
                            text = downloadPath ?: "未设置（点击选择）",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (downloadPath != null) {
                        TextButton(onClick = {
                            Settings.smbDownloadDirTreeUri.value = null
                            Settings.smbDownloadDirPath.value = null
                        }) { Text("清除", fontSize = 13.sp) }
                    }
                }
            }
            }

            item {
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
            }

            item {
            CacheManagementCard(ctx, scope, SMB_CACHE_ENTRIES)
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ── 共用组件 ──

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String, pref: com.ehviewer.core.preferences.PrefDelegate<Boolean>) {
    val state by pref.valueFlow().collectAsState(initial = pref.value)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text(title, fontSize = 16.sp)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("$state", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
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

data class CacheEntry(val label: String, val subtitle: String, val dirRel: String)

@Composable
fun CacheManagementCard(
    ctx: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    entries: List<CacheEntry>,
) {
    var sizes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var clearingDir by remember { mutableStateOf<String?>(null) }
    var clearingAll by remember { mutableStateOf(false) }
    val imageLoader = coil3.SingletonImageLoader.get(ctx)

    suspend fun refreshSizes() = withContext(Dispatchers.IO) {
        val map = HashMap<String, Long>(entries.size)
        for (e in entries) map[e.dirRel] = dirSize(ctx, e.dirRel)
        sizes = map
    }
    LaunchedEffect(entries) { refreshSizes() }
    LaunchedEffect(clearingDir, clearingAll) {
        if (clearingDir == null && !clearingAll) refreshSizes()
    }

    SectionCard("缓存管理") {
        entries.forEach { e ->
            val size = sizes[e.dirRel] ?: 0L
            val isClearing = clearingDir == e.dirRel
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(e.label, fontSize = 16.sp)
                    Text(e.subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    formatSize(size),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            clearingDir = e.dirRel
                            // ★ 即时反馈：先设为 0
                            sizes = sizes.toMutableMap().also { it[e.dirRel] = 0L }
                            withContext(Dispatchers.IO) {
                                val ok = java.io.File(ctx.applicationInfo.dataDir, e.dirRel).deleteRecursively()
                                if (!ok) android.util.Log.w("CacheMgr", "deleteRecursively failed for ${e.dirRel}")
                            }
                            imageLoader.memoryCache?.clear()

                            clearingDir = null
                        }
                    },
                    enabled = !isClearing && !clearingAll,
                ) { Text(if (isClearing) "…" else "清理", fontSize = 13.sp) }
            }
        }
        Button(
            onClick = {
                scope.launch {
                    clearingAll = true
                    // ★ 即时反馈：全部设为 0
                    sizes = entries.associate { it.dirRel to 0L }
                    withContext(Dispatchers.IO) {
                        entries.forEach { e ->
                            val ok = java.io.File(ctx.applicationInfo.dataDir, e.dirRel).deleteRecursively()
                            if (!ok) android.util.Log.w("CacheMgr", "deleteRecursively failed for ${e.dirRel}")
                        }
                    }
                    imageLoader.memoryCache?.clear()

                    clearingAll = false
                }
            },
            enabled = !clearingAll && clearingDir == null,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { Text(if (clearingAll) "清理中…" else "全部清理") }
        Text(
            "正在阅读的书不受影响，退出阅读后缓存会按需重建",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}


// ── 删除服务器时对应清理该书源的全部磁盘缓存（对齐本地删除行为，用户 2026-09-06 要求） ──
suspend fun clearServerBookCachesSmb(serverId: Long, src: com.ehviewer.core.database.model.SmbSourceEntity?, books: List<com.mangareader.data.Book>) {
    books.forEach { b ->
        val ref = SmbPaths.parseBookPath(b.filePath) ?: return@forEach
        val remoteRaw = if (src?.share?.isBlank() == false) ref.rel else ref.fullRel
        val zipSplit = com.hippo.ehviewer.library.ZipAsDirListing.splitZipBrowsePath(remoteRaw)
        val remote = if (zipSplit != null && zipSplit.second.isNotEmpty()) zipSplit.first else remoteRaw
        runCatching {
            val pagePath = SmbCache.cachePathForRemoteFile(serverId, remote)
            java.io.File(pagePath.toString()).delete()
            val uhdrPath = com.hippo.ehviewer.image.hdr.HdrConvertCache.uhdrSiblingOf(pagePath)
            if (uhdrPath.toString() != pagePath.toString()) java.io.File(uhdrPath.toString()).delete()
            SmbCache.markAbsent(pagePath)
            SmbCache.markAbsent(uhdrPath)
            val cacheKey = "smb:$serverId:$remote"
            java.io.File(com.hippo.ehviewer.library.ArchiveStreamPageCache.dirFor(cacheKey).toString()).deleteRecursively()
            java.io.File(com.hippo.ehviewer.library.SolidExtractCache.dirFor(cacheKey).toString()).deleteRecursively()
            java.io.File(com.hippo.ehviewer.library.DocumentExtractCache.dirFor(cacheKey).toString()).deleteRecursively()
        }
    }
}
