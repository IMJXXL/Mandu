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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LOCAL_CACHE_ENTRIES = listOf(

    CacheEntry("归档封面缩略图", "归档第一页封面（本地/SMB/WebDAV 共享）", "cache/archive_thumb"),
    CacheEntry("本地封面缓存", "本地导入书籍的封面缩略图", "files/covers"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderManageScreen(onBack: () -> Unit) {
    var showSettings by remember { mutableStateOf(false) }
    BackHandler { if (showSettings) showSettings = false else onBack() }
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
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {

            if (folders.isEmpty()) {
                Text("还没有导入文件夹", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (folders.size >= 2) {
                Button(onClick = {
                    scope.launch {
                        scanning = true; scanTarget = "一键扫描所有文件夹…"; scanCount = 0
                        var totalAdded = 0
                        var totalRemoved = 0
                        withContext(Dispatchers.IO) {
                            folders.forEach { f ->
                                val (a, r) = scanner.rescanAndCommit(f) { count -> scanCount = count }
                                totalAdded += a; totalRemoved += r
                            }
                        }
                        scanCount = totalAdded
                        scanning = false
                        val msg = when {
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
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = {
                                scope.launch {
                                    scanning = true; scanTarget = "正在扫描「${f.displayName}」…"; scanCount = 0
                                    val (addedN, removedN) = withContext(Dispatchers.IO) { scanner.rescanAndCommit(f) { count -> scanCount = count } }
                                    scanCount = addedN
                                    scanning = false
                                    val msg = when {
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
            Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
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
                    var smartTitle by remember { mutableStateOf(prefs.getBoolean("smart_title", true)) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("智能解析文件名", fontSize = 16.sp)
                            Text("扫描入库时提取干净书名与卷号（如 v01）；关闭则显示原文件名。仅影响新扫描的书，已入库书名不变", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = smartTitle, onCheckedChange = { v ->
                            smartTitle = v
                            prefs.edit().putBoolean("smart_title", v).apply()
                        })
                    }
                    var t2sEnabled by remember { mutableStateOf(prefs.getBoolean("t2s_enabled", true)) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("繁体标题转简体", fontSize = 16.sp)
                            Text("全局生效（本地/WebDAV/SMB 的智能解析结果都会应用）；关 = 保留繁体原字。仅影响新扫描的书", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                Text("将按当前文件名重新解析全部书籍的书名（含手动改过的），解析不出结构时保持原名。原始文件名仍可在长按详情中查看。")
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showRetitle = false
                                    scope.launch {
                                        val changed = withContext(Dispatchers.IO) {
                                            val d = (ctx.applicationContext as com.mangareader.App).db.mangaDao()
                                            var n = 0
                                            d.getAllBooksList().forEach { bk ->
                                                val raw = bk.filePath.substringAfterLast('|').substringAfterLast('/')
                                                val newTitle = com.mangareader.util.BookTitleParser.displayTitle(raw)
                                                if (newTitle.isNotBlank() && newTitle != bk.title) {
                                                    d.updateBook(bk.copy(title = newTitle)); n++
                                                }
                                            }
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
                CacheManagementCard(
                    ctx = ctx,
                    scope = scope,
                    entries = LOCAL_CACHE_ENTRIES,
                )
            }
        }
    }
}