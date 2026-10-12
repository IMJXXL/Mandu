package com.mangareader.ui

import android.content.Context
import android.content.Intent
import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.App
import com.mangareader.Scanner
import com.mangareader.data.Folder
import com.mangareader.smbbridge.CacheEntry
import com.mangareader.smbbridge.CacheManagementCard
import com.mangareader.smbbridge.SectionCard

import com.mangareader.smbbridge.unwrapActivity
import com.hippo.ehviewer.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LOCAL_CACHE_ENTRIES = listOf(
    CacheEntry("流式页缓存", "ZIP/TAR 归档页缓存（本地/SMB/WebDAV 共用）", "cache/archive_pages"),
    CacheEntry("归档封面缩略图", "归档第一页封面（本地/SMB/WebDAV 共享）", "cache/archive_thumb"),
    CacheEntry("RAR/7z 提取缓存", "RAR/CBR/7z 归档提取的页文件", "cache/solid_extract"),
    CacheEntry("PDF/EPUB 提取缓存", "PDF/EPUB 文档提取的页文件", "cache/document_extract"),
    CacheEntry("ZIP 文件夹页缓存", "Zip-as-dir 解出的成员页文件", "cache/zip_folder_pages"),
    CacheEntry("HDR/UltraHDR 缓存", "本地 HDR 转 Ultra HDR 派生文件", "cache/hdr_ultrahdr"),
    CacheEntry("视频缩略图缓存", "视频文件缩略图", "cache/video_thumb_cache"),
    CacheEntry("SMB 页缓存", "整档下载与单页缓存", "cache/smb_cache"),
    CacheEntry("WebDAV 页缓存", "整档下载与单页缓存", "cache/webdav_cache"),
    CacheEntry("本地封面缓存", "本地导入书籍的封面缩略图", "files/covers"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderManageScreen(onBack: () -> Unit) {
    var showSettings by remember { mutableStateOf(false) }
    var showArchivePassword by remember { mutableStateOf(false) }
    var archivePasswds by remember { mutableStateOf(Settings.archivePasswds) }
    BackHandler { if (showArchivePassword) showArchivePassword = false else if (showSettings) showSettings = false else onBack() }
    val ctx = LocalContext.current
    val app = ctx.applicationContext as App
    val dao = remember { app.db.mangaDao() }
    val scanner = remember { Scanner(ctx, dao) }
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("manga_reader", Context.MODE_PRIVATE) }
    var folders by remember { mutableStateOf(listOf<Folder>()) }
    var scanning by remember { mutableStateOf(false) }
    var scanTarget by remember { mutableStateOf("") }
    var scanCount by remember { mutableIntStateOf(0) }
    var showDeleteFolder by remember { mutableStateOf<Folder?>(null) }
    var showRenameFolder by remember { mutableStateOf<Folder?>(null) }
    var showNomediaDialog by remember { mutableStateOf<android.net.Uri?>(null) }
    var folderHiddenMap by remember { mutableStateOf<Map<Long, Boolean>>(emptyMap()) }
    // ★ 文件夹整批归架/打标签（2026-10-04 用户定稿）："已导入"里每个文件夹整体归入书架/加标签
    var shelfPickerBooks by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showFolderShelfPicker by remember { mutableStateOf(false) }
    var tagPickerFolderBooks by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showFolderTagPicker by remember { mutableStateOf(false) }
    var folderTagsWithCounts by remember { mutableStateOf(listOf<com.mangareader.data.TagWithCount>()) }
    var verifyZipContent by remember { mutableStateOf(prefs.getBoolean("scan_verify_zip_content", false)) }
    var autoRefresh by remember { mutableStateOf(prefs.getBoolean("auto_refresh", false)) }


    LaunchedEffect(folders) {
        if (folders.isNotEmpty()) {
            val map = withContext(Dispatchers.IO) {
                folders.associate { f ->
                    val treeUri = android.net.Uri.parse(f.treeUri)
                    val auth = treeUri.authority ?: ""
                    val treeDocId = android.net.Uri.decode(treeUri.lastPathSegment ?: "").removePrefix("tree:")
                    f.id to dao.getBooksByFolderParams("a:$auth|$treeDocId|%", "d:$auth|$treeDocId%", "z:a:$auth|$treeDocId|%").any { it.isHidden }
                }
            }
            folderHiddenMap = map
        }
    }
    LaunchedEffect(Unit) { dao.getAllFolders().collect { folders = it } }

    // 文件夹 → 该文件夹下全部可见书 ids（与隐藏开关同一套 SAF 前缀参数口径）
    suspend fun collectFolderBookIds(f: Folder): Set<Long> = withContext(Dispatchers.IO) {
        val treeUri = android.net.Uri.parse(f.treeUri)
        val auth = treeUri.authority ?: ""
        val treeDocId = android.net.Uri.decode(treeUri.lastPathSegment ?: "").removePrefix("tree:")
        dao.getBooksByFolderParams("a:$auth|$treeDocId|%", "d:$auth|$treeDocId|%", "z:a:$auth|$treeDocId|%")
            .filter { !it.isHidden }.map { it.id }.toSet()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.data?.let { uri ->
            scope.launch {
                try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
                scanning = true; scanTarget = "扫描中…"; scanCount = 0
                val n = withContext(Dispatchers.IO) { scanner.scanAndCommit(uri) { count -> scanCount = count } }
                scanning = false
                Toast.makeText(ctx, if (n > 0) "导入成功，共 $n 本漫画，封面后台加载中" else "未找到漫画文件", Toast.LENGTH_SHORT).show()
                if (n > 0) {
                    try {
                        val hasNomedia = withContext(Dispatchers.IO) { scanner.checkNomedia(uri) }
                        if (!hasNomedia) {
                            showNomediaDialog = uri
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("本地漫画管理") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, "本地漫画设置")
                    }
                    IconButton(onClick = { picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply { putExtra("android.content.extra.SHOW_ADVANCED", true) }) }) {
                        Icon(Icons.Default.Add, "导入文件夹")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = CONTENT_MAX_WIDTH_DP).verticalScroll(rememberScrollState()).padding(16.dp)) {

            if (folders.isEmpty()) {
                Text("还没有导入文件夹", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (folders.size >= 2) {
                Button(onClick = {
                    scope.launch {
                        scanning = true; scanTarget = "一键扫描所有文件夹…"; scanCount = 0
                        var totalAdded = 0
                        var totalRemoved = 0
                        var anyIncomplete = false
                        withContext(Dispatchers.IO) {
                            folders.forEach { f ->
                                val outcome = scanner.rescanAndCommit(f) { count -> scanCount = count }
                                totalAdded += outcome.added; totalRemoved += outcome.removed
                                if (outcome.manifestIncomplete) anyIncomplete = true
                            }
                        }
                        scanCount = totalAdded
                        scanning = false
                        // ★ U1：清单不完整（SAF 瞬断）不得谎报"无变化"
                        val msg = when {
                            anyIncomplete -> "存储瞬时不稳定，部分文件夹本次跳过检查，稍后可重试"
                            totalAdded == 0 && totalRemoved == 0 -> "扫描完成，书架无变化"
                            else -> "扫描成功：新增 $totalAdded 本、移除 $totalRemoved 本，封面后台加载中"
                        }
                        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                        scanning = false
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Refresh, null)
                    Spacer(Modifier.width(8.dp))
                    Text("一键扫描全部 (${folders.size}个文件夹)")
                }
                Spacer(Modifier.height(8.dp))
            }
            folders.forEach { f ->
                val dateFormat = remember(f.id) { java.text.SimpleDateFormat("MM-dd HH:mm") }
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.displayName, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text("扫描于 ${if (f.lastScanAt > 0) dateFormat.format(java.util.Date(f.lastScanAt)) else "未扫描"}",
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val folderHidden = folderHiddenMap[f.id] ?: false
                            Switch(checked = !folderHidden, onCheckedChange = { show ->
                                folderHiddenMap = folderHiddenMap + (f.id to !show)
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        val treeUri = android.net.Uri.parse(f.treeUri)
                                        val auth = treeUri.authority ?: ""
                                        val treeDocId = android.net.Uri.decode(treeUri.lastPathSegment ?: "").removePrefix("tree:")
                                        dao.setHiddenByFolderParams("a:$auth|$treeDocId|%", "d:$auth|$treeDocId%", "z:a:$auth|$treeDocId|%", !show)
                                    }
                                    Toast.makeText(ctx, if (show) "已显示「${f.displayName}」的漫画" else "已隐藏「${f.displayName}」的漫画", Toast.LENGTH_SHORT).show()
                                }
                            })
                        }
                        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                        androidx.compose.foundation.layout.FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                        ) {
                            TextButton(onClick = {
                                scope.launch {
                                    val ids = collectFolderBookIds(f)
                                    if (ids.isEmpty()) Toast.makeText(ctx, "该文件夹下没有漫画", Toast.LENGTH_SHORT).show()
                                    else { shelfPickerBooks = ids; showFolderShelfPicker = true }
                                }
                            }) { Text("书架") }
                            TextButton(onClick = {
                                scope.launch {
                                    val ids = collectFolderBookIds(f)
                                    if (ids.isEmpty()) { Toast.makeText(ctx, "该文件夹下没有漫画", Toast.LENGTH_SHORT).show(); return@launch }
                                    tagPickerFolderBooks = ids
                                    folderTagsWithCounts = dao.getAllTagsWithCounts().first()
                                    showFolderTagPicker = true
                                }
                            }) { Text("标签") }
                            TextButton(onClick = {
                                scope.launch {
                                    scanning = true; scanTarget = "正在扫描「${f.displayName}」…"; scanCount = 0
                                    val outcome = withContext(Dispatchers.IO) { scanner.rescanAndCommit(f) { count -> scanCount = count } }
                                    val addedN = outcome.added; val removedN = outcome.removed
                                    scanCount = addedN
                                    scanning = false
                                    val msg = when {
                                        outcome.manifestIncomplete -> "存储瞬时不稳定，本次跳过检查，稍后可重试"
                                        addedN == 0 && removedN == 0 -> "刷新完成，书架无变化"
                                        else -> "刷新成功：新增 $addedN 本、移除 $removedN 本，封面后台加载中"
                                    }
                                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                                    scanning = false
                                }
                            }) { Text("刷新") }
                            TextButton(onClick = { showRenameFolder = f }) { Text("重命名") }
                            TextButton(onClick = { showDeleteFolder = f }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }


        }
    }


    // ★ 文件夹整批归架：复用书架选择器（自带差量写库——勾选归入、取消勾选移出）
    if (showFolderShelfPicker) {
        ShelfPickerDialog(
            onDismiss = { showFolderShelfPicker = false },
            dao = dao,
            scope = scope,
            bookIds = shelfPickerBooks,
            onConfirm = {
                showFolderShelfPicker = false
                Toast.makeText(ctx, "已按勾选更新 ${shelfPickerBooks.size} 本的书架归属", Toast.LENGTH_SHORT).show()
            },
        )
    }
    // ★ 文件夹整批打标签：复用批量打标签面板（setBookTags 幂等追加，不动已有标签）
    if (showFolderTagPicker) {
        TagPickerSheet(
            visible = true,
            allTags = folderTagsWithCounts,
            selectedIds = emptySet(),
            andLogic = true,
            addMode = true,
            onToggle = {},
            onLogicChange = {},
            onClear = {},
            onApply = { ids ->
                val names = ids.mapNotNull { id -> folderTagsWithCounts.find { it.tag.id == id }?.tag?.name }
                if (names.isNotEmpty()) {
                    val targets = tagPickerFolderBooks
                    scope.launch {
                        withContext(Dispatchers.IO) { targets.forEach { bid -> dao.setBookTags(bid, names, 1) } }
                        Toast.makeText(ctx, "已为 ${targets.size} 本添加 ${names.size} 个标签", Toast.LENGTH_SHORT).show()
                    }
                }
                showFolderTagPicker = false
            },
            onDismiss = { showFolderTagPicker = false },
        )
    }

    if (showDeleteFolder != null) {
        val f = showDeleteFolder!!
        AlertDialog(onDismissRequest = { showDeleteFolder = null }, title = { Text("删除文件夹？") },
            text = { Text("将删除「${f.displayName}」及该文件夹下所有漫画记录；原始文件不受影响，已设置的自定义封面将一并删除。\n此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        // ★ 删除链不可取消：composition scope 在用户离开页面时会被取消，
                        //   中途取消会留下"书删了 Folder 行还在"的外键孤儿（对齐上游 removeRoot 教训）
                        withContext(Dispatchers.IO + NonCancellable) {
                            val treeUri = android.net.Uri.parse(f.treeUri)
                            val docId = treeUri.lastPathSegment ?: ""
                            val auth = treeUri.authority ?: ""
                            val td = docId.removePrefix("tree:")
                            val aPrefix = "a:$auth|$td|%"; val dPrefix = "d:$auth|$td%"; val zPrefix = "z:a:$auth|$td|%"
                            val books = dao.getBooksByFolderParams(aPrefix, dPrefix, zPrefix)
                            val coverDir = java.io.File(ctx.filesDir, "covers")
                            books.forEach { b ->
                                b.coverPath?.let { cp ->
                                    val md = java.security.MessageDigest.getInstance("MD5").digest(cp.toByteArray())
                                    val hash = md.joinToString("") { "%02x".format(it) }
                                    java.io.File(coverDir, "$hash.thumb").delete()
                                }
                                // 自定义封面文件一并删除
                                b.customCoverPath?.let { ccp ->
                                    java.io.File(coverDir, ccp.removePrefix("custom:")).delete()
                                }
                            }
                            dao.deleteBooksByFolderParams(aPrefix, dPrefix, zPrefix)
                            dao.deleteFolder(f)
                        }
                        showDeleteFolder = null
                        Toast.makeText(ctx, "已删除", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteFolder = null }) { Text("取消") } })
    }

    if (showNomediaDialog != null) {
        val uri = showNomediaDialog!!
        AlertDialog(onDismissRequest = { showNomediaDialog = null },
            title = { Text("添加 .nomedia 文件") },
            text = { Text("该文件夹中没有 .nomedia 文件。\n\n添加后可以防止系统相册扫描到漫画图片，避免漫画封面出现在相册中。\n\n是否添加？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { scanner.createNomedia(uri) }
                        showNomediaDialog = null
                        Toast.makeText(ctx, "已添加 .nomedia 文件", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { showNomediaDialog = null }) { Text("跳过") } })
    }

    if (showRenameFolder != null) {
        val f = showRenameFolder!!
        var renameText by remember(f.id) { mutableStateOf(f.displayName) }
        AlertDialog(
            onDismissRequest = { showRenameFolder = null },
            title = { Text("重命名文件夹") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("文件夹名称") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) {
                        scope.launch {
                            withContext(Dispatchers.IO) { dao.updateFolder(f.copy(displayName = renameText.trim())) }
                            showRenameFolder = null
                            Toast.makeText(ctx, "已重命名", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showRenameFolder = null }) { Text("取消") } }
        )
    }

    if (scanning) {
        val infiniteTransition = rememberInfiniteTransition(label = "scan")
        val animProgress by infiniteTransition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart),
            label = "runCycle"
        )
        AlertDialog(onDismissRequest = {}, title = { Text("导入漫画") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Canvas(Modifier.size(64.dp)) {
                        val w = size.width; val h = size.height
                        val cx = w * 0.5f; val ground = h * 0.85f
                        val phase = animProgress * 2f * Math.PI.toFloat()
                        drawCircle(Color(0xFF7C8CF8), radius = w * 0.08f, center = Offset(cx, h * 0.3f))
                        drawLine(Color(0xFF7C8CF8), Offset(cx, h * 0.38f), Offset(cx, h * 0.6f), strokeWidth = w * 0.06f)
                        val armSwing = kotlin.math.sin(phase.toDouble()).toFloat() * w * 0.12f
                        drawLine(Color(0xFF7C8CF8), Offset(cx, h * 0.42f), Offset(cx - armSwing, h * 0.55f), strokeWidth = w * 0.04f)
                        drawLine(Color(0xFF7C8CF8), Offset(cx, h * 0.42f), Offset(cx + armSwing, h * 0.55f), strokeWidth = w * 0.04f)
                        val legSwing = kotlin.math.sin(phase.toDouble()).toFloat() * w * 0.14f
                        drawLine(Color(0xFF7C8CF8), Offset(cx, h * 0.6f), Offset(cx - legSwing, ground), strokeWidth = w * 0.05f)
                        drawLine(Color(0xFF7C8CF8), Offset(cx, h * 0.6f), Offset(cx + legSwing, ground), strokeWidth = w * 0.05f)
                        drawLine(Color.Gray, Offset(w * 0.1f, ground), Offset(w * 0.9f, ground), strokeWidth = w * 0.02f)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(scanTarget, fontSize = 16.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("已发现 $scanCount 本漫画", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                }
            }, confirmButton = {})
    }

    if (showSettings) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("本地漫画设置") },
                    navigationIcon = { IconButton(onClick = { showSettings = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                )
            }
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = CONTENT_MAX_WIDTH_DP).verticalScroll(rememberScrollState()).padding(16.dp)) {
                SectionCard("扫描设置") {
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("扫描时校验压缩包内容", fontSize = 16.sp)
                            Text("开启后：.zip/.cbz 须内部含图才视为漫画\n防止非漫画 archive 被误识别\n代价：大书库扫描会变慢", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = verifyZipContent, onCheckedChange = { v ->
                            verifyZipContent = v
                            prefs.edit().putBoolean("scan_verify_zip_content", v).apply()
                        })
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("启动自动刷新", fontSize = 16.sp)
                            Text("打开应用时自动扫描本地文件夹发现新书", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = autoRefresh, onCheckedChange = { v ->
                            autoRefresh = v
                            prefs.edit().putBoolean("auto_refresh", v).apply()
                        })
                    }
                    var smartTitle by remember { mutableStateOf(prefs.getBoolean("smart_title", false)) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("智能解析文件名", fontSize = 16.sp)
                            Text("扫描入库时提取干净书名与卷号（如 Vol.01）；关闭则显示原文件名。固有规则可能误判，改错可长按书名核对原名。仅影响新扫描的书，已入库书名不变", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = smartTitle, onCheckedChange = { v ->
                            smartTitle = v
                            prefs.edit().putBoolean("smart_title", v).apply()
                        })
                    }
                    var t2sEnabled by remember { mutableStateOf(prefs.getBoolean("t2s_enabled", false)) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("繁体标题转简体", fontSize = 16.sp)
                            Text("全局生效（本地/WebDAV/SMB 的智能解析结果都会应用）；关 = 保留繁体原字。固有规则可能误判，改错可长按书名核对原名。仅影响新扫描的书", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = t2sEnabled, onCheckedChange = { v ->
                            t2sEnabled = v
                            prefs.edit().putBoolean("t2s_enabled", v).apply()
                        })
                    }
                    // ★ 批量应用：对已入库的书按文件名重新解析改名（解决老书/当时没勾选的情况）
                    var showRetitle by remember { mutableStateOf(false) }
                    TextButton(onClick = { showRetitle = true }) {
                        Text("对已入库的书应用解析改名", fontSize = 13.sp)
                    }
                    if (showRetitle) {
                        AlertDialog(
                            onDismissRequest = { showRetitle = false },
                            title = { Text("重新命名已入库的书？") },
                            text = {
                                Text("将按当前文件名重新解析全部书籍的书名（含手动改过的），解析不出结构时保持原名。固有规则可能误判，改错可长按书名核对原文件名。")
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showRetitle = false
                                    scope.launch {
                                        val changed = withContext(Dispatchers.IO) {
                                            val d = (ctx.applicationContext as com.mangareader.App).db.mangaDao()
                                            val t2s = ctx.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE)
                                                .getBoolean("t2s_enabled", false)
                                            var n = 0
                                            var total = 0
                                            d.getAllBooksList().forEach { bk ->
                                                // ★ 取原文件名必须走 fileNameOf：SAF 路径末段是 |ext 后缀，
                                                //   直接 substringAfterLast('|') 会把扩展名（rar/mobi/epub）当书名
                                                val raw = com.mangareader.util.BookTitleParser.fileNameOf(bk.filePath)
                                                // 子文件夹章节书（"…/哆啦A梦/第1话新室友"）：系列名沿祖先链找
                                                // （上级可能是分组夹 A/合集，逐级跳过，取第一个像系列名的）
                                                val ancestors = com.mangareader.util.BookTitleParser.ancestorNamesOf(bk.filePath)
                                                val newTitle = com.mangareader.util.BookTitleParser.displayTitle(raw, t2s, ancestors)
                                                total++
                                                // 诊断打点（tallSplit 同例）：逐书记录改名前后，排障用——用户日志里能对出哪本没解析好
                                                com.mangareader.DiagTracer.mark(
                                                    ctx,
                                                    "retitle #${bk.id} 「${bk.title}」→「$newTitle」 src=${raw.take(80)}"
                                                )
                                                if (newTitle.isNotBlank() && newTitle != bk.title) {
                                                    d.updateBook(bk.copy(title = newTitle)); n++
                                                }
                                            }
                                            com.mangareader.DiagTracer.mark(ctx, "retitle done changed=$n/$total")
                                            n
                                        }
                                        android.widget.Toast.makeText(ctx, "已重命名 $changed 本", Toast.LENGTH_SHORT).show()
                                    }
                                }) { Text("应用", color = MaterialTheme.colorScheme.error) }
                            },
                            dismissButton = { TextButton(onClick = { showRetitle = false }) { Text("取消") } },
                        )
                    }
                }
                // ★ 归档密码（0930）：本地/SMB/WebDAV 三路共用的记忆库，加密压缩包打开时
                //   自动尝试（命中的密码优先），封面提取同样静默尝试
                SectionCard("归档密码") {

                    Row(
                        Modifier.fillMaxWidth().clickable {
                            showArchivePassword = true
                        }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("归档密码 (${archivePasswds.size} 条)", fontSize = 16.sp)
                            Text("加密压缩包打开时自动尝试，命中的优先（本地/SMB/WebDAV 通用）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("编辑 ›", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
                CacheManagementCard(
                    ctx = ctx,
                    scope = scope,
                    entries = LOCAL_CACHE_ENTRIES,
                )
            }
        }
    }
    if (showArchivePassword) {
        ArchivePasswordScreen(onBack = { showArchivePassword = false; archivePasswds = Settings.archivePasswds })
    }
}