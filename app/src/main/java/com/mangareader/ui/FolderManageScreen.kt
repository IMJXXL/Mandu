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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.App
import com.mangareader.Scanner
import com.mangareader.data.Folder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderManageScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
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
    var showNomediaDialog by remember { mutableStateOf<android.net.Uri?>(null) }
    var folderHiddenMap by remember { mutableStateOf<Map<Long, Boolean>>(emptyMap()) }
    var verifyZipContent by remember { mutableStateOf(prefs.getBoolean("scan_verify_zip_content", false)) }

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
                scanning = true; scanTarget = "扫描中..."; scanCount = 0
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
            TopAppBar(title = { Text("已导入文件夹") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } })
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Button(onClick = { picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply { putExtra("android.content.extra.SHOW_ADVANCED", true) }) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("导入文件夹")
            }
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("扫描时校验压缩包内容", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "开启后：.zip/.cbz 须内部含图才视为漫画\n防止非漫画 archive 被误识别\n代价：大书库扫描会变慢",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 14.sp
                            )
                        }
                        Switch(checked = verifyZipContent, onCheckedChange = { v ->
                            verifyZipContent = v
                            prefs.edit().putBoolean("scan_verify_zip_content", v).apply()
                        })
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (folders.isEmpty()) {
                Text("还没有导入文件夹", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (folders.size >= 2) {
                Button(onClick = {
                    scope.launch {
                        scanning = true; scanTarget = "一键扫描所有文件夹..."; scanCount = 0
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
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.displayName, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text("扫描于 ${if (f.lastScanAt > 0) dateFormat.format(java.util.Date(f.lastScanAt)) else "未扫描"}",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = {
                        scope.launch {
                            scanning = true; scanTarget = "正在扫描「${f.displayName}」..."; scanCount = 0
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
                    val folderHidden = folderHiddenMap[f.id] ?: false
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (folderHidden) "隐藏" else "已显示", fontSize = 12.sp,
                            color = if (folderHidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
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
                    TextButton(onClick = { showDeleteFolder = f }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    if (showDeleteFolder != null) {
        val f = showDeleteFolder!!
        AlertDialog(onDismissRequest = { showDeleteFolder = null }, title = { Text("删除文件夹") },
            text = { Text("删除「${f.displayName}」及该文件夹下所有漫画记录？原始文件不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
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
                            }
                            dao.deleteBooksByFolderParams(aPrefix, dPrefix, zPrefix)
                            dao.deleteFolder(f)
                        }
                        showDeleteFolder = null
                        Toast.makeText(ctx, "已删除", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("确定", color = MaterialTheme.colorScheme.error) }
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
}