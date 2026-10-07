package com.mangareader.webdavbridge

import com.hippo.ehviewer.util.LocalNetworkPermissionEffect
import com.mangareader.smbbridge.CacheEntry
import com.mangareader.smbbridge.CacheManagementCard
import com.mangareader.smbbridge.IntRow
import com.mangareader.smbbridge.SectionCard
import com.mangareader.smbbridge.SwitchRow
import com.mangareader.smbbridge.dirSize
import com.mangareader.smbbridge.formatSize
import com.mangareader.smbbridge.showArchivePasswdsDialog
import com.mangareader.smbbridge.showIntInput
import com.mangareader.smbbridge.unwrapActivity

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import com.mangareader.ui.CONTENT_MAX_WIDTH_DP
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.webdav.WebDavCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SMB 设置页（任务B补全：参考项目 Settings 中被 SMB/WebDAV 栈消费的字段全部接入 UI）。
 * 响应式：PrefDelegate.valueFlow() collectAsState；写入走 value setter（DataStore 持久化）。
 * 缓存管理卡片：分类容量统计 + 一键清理（原服务器页顶栏入口迁移至此，更具美感）。
 */
private val WEBDAV_CACHE_ENTRIES = listOf(
    CacheEntry("WebDAV 页缓存", "整档下载与单页缓存", "cache/webdav_cache"),
    CacheEntry("WebDAV 浏览缩略图", "浏览目录时图片文件的缩略图", "cache/webdav_thumb_cache"),
    CacheEntry("流式页缓存", "ZIP/TAR 归档页缓存（本地/SMB/WebDAV 共用）", "cache/archive_pages"),
    CacheEntry("归档封面缩略图", "归档第一页封面（本地/SMB/WebDAV 共享）", "cache/archive_thumb"),
    CacheEntry("RAR/7z 提取缓存", "RAR/CBR/7z 归档提取的页文件", "cache/solid_extract"),
    CacheEntry("PDF/EPUB 提取缓存", "PDF/EPUB 文档提取的页文件", "cache/document_extract"),

)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavSettingsScreen(onBack: () -> Unit, onOpenDownloadQueue: () -> Unit = {}) {
    LocalNetworkPermissionEffect()  // Android 17 局域网 socket 运行时授权（本地 WebDAV 同受管制）
    BackHandler { onBack() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WebDAV 设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = onOpenDownloadQueue) {
                        Icon(Icons.Default.Download, "下载队列")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = CONTENT_MAX_WIDTH_DP).padding(16.dp)) {

            item {
            SectionCard("连接与安全") {
                SwitchRow("允许自签名/不安全证书", "自签名 NAS 或内网 HTTPS 服务打开即可连接；关闭时使用系统证书校验（更安全）", Settings.webDavInsecureTls)
            }
            }

            item {
            SectionCard("书名解析") {
                val ctx = LocalContext.current
                var smartTitle by remember { mutableStateOf(runCatching { ctx.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE).getBoolean("smart_title_webdav", false) }.getOrDefault(false)) }
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("智能解析文件名", fontSize = 16.sp)
                        Text("扫描入库时提取干净书名与卷号（如 Vol.01）；关闭则显示原文件名。固有规则可能误判，改错可长按书名核对原名。仅影响新扫描的书", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = smartTitle, onCheckedChange = { v ->
                        smartTitle = v
                        ctx.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE).edit().putBoolean("smart_title_webdav", v).apply()
                    })
                }
                var t2sTitle by remember { mutableStateOf(runCatching { ctx.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE).getBoolean("t2s_enabled", false) }.getOrDefault(false)) }
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("繁体标题转简体", fontSize = 16.sp)
                        Text("全局开关（本地/WebDAV/SMB 统一生效）；关 = 保留繁体原字。仅影响新扫描的书", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = t2sTitle, onCheckedChange = { v ->
                        t2sTitle = v
                        ctx.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE).edit().putBoolean("t2s_enabled", v).apply()
                    })
                }
            }
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
                // ★ 启动自动刷新（方案 A）：每源一次设置，进书架自动扫描一次
                val ctx2 = LocalContext.current
                var autoRefreshDav by remember { mutableStateOf(runCatching { ctx2.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE).getBoolean("auto_refresh_webdav", false) }.getOrDefault(false)) }
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("启动自动刷新", fontSize = 16.sp)
                        Text("打开应用进入书架时自动扫描本源：发现新书、清理服务器上已删除的书。完整网络遍历，书库大时较慢", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = autoRefreshDav, onCheckedChange = { v ->
                        autoRefreshDav = v
                        ctx2.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE).edit().putBoolean("auto_refresh_webdav", v).apply()
                    })
                }
                SwitchRow("目录索引缓存", "缓存目录列表与页数信息，二次进入/扫描更快", Settings.networkFolderIndexCache)
                SwitchRow("快速扫描", "增量扫描时跳过深度 peek，更快但新目录信息可能不全", Settings.networkFolderIndexQuickScan)
                SwitchRow("保存文件标记", "在共享目录写入标记文件辅助识别", Settings.saveFileMarkers)
            }
            }

            item {
            SectionCard("远程阅读") {
                SwitchRow("禁用阅读网络缓存", "阅读页不落盘缓存（更省空间，但重读需重新下载）", Settings.disableReaderNetworkCache)
                IntRow("阅读页缓存预算 (MB)", "流式页缓存的总容量上限", Settings.readCacheSize)
                IntRow("预加载页数", "当前页之外预取的页数", Settings.preloadImage)
                IntRow("下载线程数", "整档下载使用的并发连接数", Settings.multiThreadDownload)
                val pdfFastMode by Settings.webDavPdfFastMode.collectAsState()
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        Triple("标准模式", false, "均能解析，受限于网络速度"),
                        Triple("快速模式", true, "仅部分能解析，但速度会快"),
                    ).forEach { (label, value, desc) ->
                        val isSelected = pdfFastMode == value
                        Surface(
                            modifier = Modifier.weight(1f).clickable { Settings.webDavPdfFastMode.value = value },
                            shape = MaterialTheme.shapes.medium,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            tonalElevation = if (isSelected) 4.dp else 0.dp
                        ) {
                            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(label, fontSize = 14.sp, color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                Text(desc, fontSize = 11.sp, color = if (isSelected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
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
            CacheManagementCard(ctx, scope, WEBDAV_CACHE_ENTRIES)
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}


// ── 删除服务器时对应清理该书源的全部磁盘缓存（对齐本地删除行为，用户 2026-09-06 要求） ──
suspend fun clearServerBookCachesWebDav(serverId: Long, src: com.ehviewer.core.database.model.WebDavSourceEntity?, books: List<com.mangareader.data.Book>) {
    books.forEach { b ->
        val ref = WebDavPaths.parseBookPath(b.filePath) ?: return@forEach
        val remoteRaw = ref.remotePath
        val zipSplit = com.hippo.ehviewer.library.ZipAsDirListing.splitZipBrowsePath(remoteRaw)
        val remote = if (zipSplit != null && zipSplit.second.isNotEmpty()) zipSplit.first else remoteRaw
        runCatching {
            val pagePath = WebDavCache.cachePathForRemoteFile(serverId, remote)
            java.io.File(pagePath.toString()).delete()
            val uhdrPath = com.hippo.ehviewer.image.hdr.HdrConvertCache.uhdrSiblingOf(pagePath)
            if (uhdrPath.toString() != pagePath.toString()) java.io.File(uhdrPath.toString()).delete()
            WebDavCache.markAbsent(pagePath)
            WebDavCache.markAbsent(uhdrPath)
            val cacheKey = "webdav:$serverId:$remote"
            java.io.File(com.hippo.ehviewer.library.ArchiveStreamPageCache.dirFor(cacheKey).toString()).deleteRecursively()
            java.io.File(com.hippo.ehviewer.library.SolidExtractCache.dirFor(cacheKey).toString()).deleteRecursively()
            java.io.File(com.hippo.ehviewer.library.DocumentExtractCache.dirFor(cacheKey).toString()).deleteRecursively()
        }
    }
}
