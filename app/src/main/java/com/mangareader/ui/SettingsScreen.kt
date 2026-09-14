package com.mangareader.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenBook: ((Long, String?) -> Unit)? = null) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as App
    val dao = remember { app.db.mangaDao() }
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("manga_reader", Context.MODE_PRIVATE) }
    var config by remember { mutableStateOf(com.mangareader.data.ReaderConfig.load(ctx)) }
    var totalBooks by remember { mutableIntStateOf(0) }
    var readBooks by remember { mutableIntStateOf(0) }
    var showDonation by remember { mutableStateOf(false) }

    var showScrollModeDialog by remember { mutableStateOf(false) }
    var showClickFlipDialog by remember { mutableStateOf(false) }
    var showReadingSettings by remember { mutableStateOf(false) }
    var showShelfManage by remember { mutableStateOf(false) }
    var showFolderManage by remember { mutableStateOf(false) }
    // 超分队列页（全屏页，不嵌在滚动 Column 里渲染）
    var showSrQueue by remember { mutableStateOf(false) }
    // ★ SMB/WebDAV 服务器管理入口（smb_embed_lv119 任务2.4/12.5）
    var showSmbServers by remember { mutableStateOf(false) }
    var showWebDavServers by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { readBooks = dao.countReadBooks() }
    }
    // ★ 漫画总数改为 Room Flow 观察——后台导入入库后自动更新，删除/导入全程实时
    LaunchedEffect(Unit) { dao.observeTotalBooks().collect { totalBooks = it } }


    if (showScrollModeDialog) {
        AlertDialog(onDismissRequest = { showScrollModeDialog = false }, title = { Text("翻页模式") },
            text = {
                Column {
                    listOf(
                        com.mangareader.data.ReaderConfig.ScrollMode.VERTICAL to "竖向滚动",
                        com.mangareader.data.ReaderConfig.ScrollMode.HORIZONTAL_LEFT to "翻页 (左→右)",
                        com.mangareader.data.ReaderConfig.ScrollMode.HORIZONTAL_RIGHT to "翻页 (右→左/RTL)"
                    ).forEach { (mode, label) ->
                        Row(Modifier.fillMaxWidth().clickable { config = config.copy(scrollMode = mode); config.save(ctx); showScrollModeDialog = false }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = config.scrollMode == mode, onClick = { config = config.copy(scrollMode = mode); config.save(ctx); showScrollModeDialog = false })
                            Spacer(Modifier.width(8.dp))
                            Text(label, fontSize = 16.sp)
                        }
                    }
                }
            }, confirmButton = {})
    }

    if (showClickFlipDialog) {
        val accentColor = MaterialTheme.colorScheme.primary
        val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
        AlertDialog(onDismissRequest = { showClickFlipDialog = false }, title = { Text("点击翻页区域") },
            text = {
                Column {
                    listOf(
                        com.mangareader.data.ReaderConfig.ClickFlipType.NO to "关闭" to "所有区域均切换UI",
                        com.mangareader.data.ReaderConfig.ClickFlipType.LEFT_RIGHT to "左右分区" to "左35%上一页, 右35%下一页, 中间切换UI",
                        com.mangareader.data.ReaderConfig.ClickFlipType.DIAGONAL_LB_RT to "对角线 (左下→右上)" to "左下区域上一页, 右上区域下一页",
                        com.mangareader.data.ReaderConfig.ClickFlipType.DIAGONAL_LT_RB to "对角线 (左上→右下)" to "左上区域上一页, 右下区域下一页"
                    ).forEach { (item, desc) ->
                        val (type, label) = item
                        Row(Modifier.fillMaxWidth().clickable { config = config.copy(clickFlipType = type); config.save(ctx); showClickFlipDialog = false }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = config.clickFlipType == type, onClick = { config = config.copy(clickFlipType = type); config.save(ctx); showClickFlipDialog = false })
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(label, fontSize = 16.sp)
                                Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Box(Modifier.size(48.dp, 36.dp).clip(RoundedCornerShape(4.dp)).background(surfaceColor), contentAlignment = Alignment.Center) {
                                androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(4.dp)) {
                                    val w = size.width
                                    val h = size.height
                                    when (type) {
                                        com.mangareader.data.ReaderConfig.ClickFlipType.NO -> {
                                            drawRect(Color.Gray, style = Stroke(width = 1.dp.toPx()))
                                        }
                                        com.mangareader.data.ReaderConfig.ClickFlipType.LEFT_RIGHT -> {
                                            drawRect(Color.Gray, style = Stroke(width = 1.dp.toPx()))
                                            drawLine(Color.Gray, Offset(w * 0.35f, 0f), Offset(w * 0.35f, h), strokeWidth = 1.dp.toPx())
                                            drawLine(Color.Gray, Offset(w * 0.65f, 0f), Offset(w * 0.65f, h), strokeWidth = 1.dp.toPx())
                                            drawLine(accentColor, Offset(w * 0.17f, h * 0.5f), Offset(w * 0.05f, h * 0.5f), strokeWidth = 2.dp.toPx())
                                            drawLine(accentColor, Offset(w * 0.83f, h * 0.5f), Offset(w * 0.95f, h * 0.5f), strokeWidth = 2.dp.toPx())
                                        }
                                        com.mangareader.data.ReaderConfig.ClickFlipType.DIAGONAL_LB_RT -> {
                                            drawRect(Color.Gray, style = Stroke(width = 1.dp.toPx()))
                                            drawLine(Color.Gray, Offset(0f, h), Offset(w, 0f), strokeWidth = 1.dp.toPx())
                                            drawLine(accentColor, Offset(w * 0.15f, h * 0.85f), Offset(w * 0.05f, h * 0.95f), strokeWidth = 2.dp.toPx())
                                            drawLine(accentColor, Offset(w * 0.85f, h * 0.15f), Offset(w * 0.95f, h * 0.05f), strokeWidth = 2.dp.toPx())
                                        }
                                        com.mangareader.data.ReaderConfig.ClickFlipType.DIAGONAL_LT_RB -> {
                                            drawRect(Color.Gray, style = Stroke(width = 1.dp.toPx()))
                                            drawLine(Color.Gray, Offset(0f, 0f), Offset(w, h), strokeWidth = 1.dp.toPx())
                                            drawLine(accentColor, Offset(w * 0.15f, h * 0.15f), Offset(w * 0.05f, h * 0.05f), strokeWidth = 2.dp.toPx())
                                            drawLine(accentColor, Offset(w * 0.85f, h * 0.85f), Offset(w * 0.95f, h * 0.95f), strokeWidth = 2.dp.toPx())
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }, confirmButton = {})
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } })
        }
    ) { padding ->
        var showAbout by remember { mutableStateOf(false) }
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            item {
            Spacer(Modifier.height(12.dp))
            }
            item {
            // ★ 临时调试：一键导出运行日志（崩溃/ANR/logcat），分享给开发者远程排查（smb_embed_lv119 用户要求）
            Card(Modifier.fillMaxWidth().clickable {
                scope.launch {
                    try {
                        val ts = java.text.SimpleDateFormat("MMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                        val out = java.io.File(ctx.getExternalFilesDir(null), "log_export_$ts.txt")
                        withContext(Dispatchers.IO) {
                            val sb = StringBuilder()
                            sb.append("=== MangaReader 日志包 $ts ===\n")
                            runCatching {
                                val pm = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                                sb.append("version: ${pm.versionName} (${pm.longVersionCode})\n")
                                sb.append("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} API ${android.os.Build.VERSION.SDK_INT}\n")
                            }
                            java.io.File(ctx.getExternalFilesDir(null), "crash_logs").listFiles()?.sortedBy { it.name }?.forEach { f ->
                                sb.append("\n===== crash/${f.name} =====\n").append(runCatching { f.readText() }.getOrDefault("<unreadable>")).append('\n')
                            }
                            java.io.File(ctx.getExternalFilesDir(null), "anr_log.txt").takeIf { it.isFile }?.let { f ->
                                sb.append("\n===== anr_log.txt (tail 400 lines) =====\n")
                                runCatching { f.readText().lines().takeLast(400).joinToString("\n") }.getOrNull()?.let { sb.append(it).append('\n') }
                            }
                            runCatching {
                                val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "3000"))
                                sb.append("\n===== logcat (last 3000 lines) =====\n")
                                sb.append(p.inputStream.readBytes().toString(Charsets.UTF_8))
                                p.destroy()
                            }
                            out.writeText(sb.toString())
                        }
                        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "com.mangareader.fileprovider", out)
                        val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(android.content.Intent.createChooser(share, "分享日志包"))
                        android.widget.Toast.makeText(ctx, "已生成：${out.absolutePath}", android.widget.Toast.LENGTH_LONG).show()
                    } catch (e: Throwable) {
                        android.widget.Toast.makeText(ctx, "导出失败：${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BugReport, null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("导出运行日志（临时）", fontSize = 16.sp)
                        Text("打包崩溃/ANR/logcat 并弹出分享，用于远程排查", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            }
            item {
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth().clickable { showFolderManage = true }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("本地漫画管理", fontSize = 16.sp)
                        Text("添加文件夹、扫描刷新、显示隐藏", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, null)
                }
            }
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth().clickable { showShelfManage = true }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bookmarks, null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("书架管理", fontSize = 16.sp)
                        Text("新建、重命名、拖动排序书架", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, null)
                }
            }
            }

            item {
            Spacer(Modifier.height(16.dp))
            }
            item {
            Text("显示", style = MaterialTheme.typography.titleMedium)
            }
            item {
            Spacer(Modifier.height(8.dp))
            }
            item {
            var globalHide by remember { mutableStateOf(prefs.getBoolean("global_hide", false)) }
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VisibilityOff, null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("全局隐藏", fontSize = 16.sp)
                        Text("开启后首页不显示任何漫画", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = globalHide, onCheckedChange = { newHide ->
                        globalHide = newHide
                        prefs.edit().putBoolean("global_hide", newHide).apply()
                        Toast.makeText(ctx, if (newHide) "已全局隐藏" else "已取消全局隐藏", Toast.LENGTH_SHORT).show()
                    })
                }
            }
            }

            item {
            Card(Modifier.fillMaxWidth().clickable { showDonation = true }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Favorite, null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("请作者喝杯咖啡", fontSize = 16.sp)
                        Text("随心就好，不看也没关系", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, null)
                }
            }
            }

            item {
            Spacer(Modifier.height(16.dp))
            }
            item {
            Text("外观（待完善）", style = MaterialTheme.typography.titleMedium)
            }
            item {
            Spacer(Modifier.height(8.dp))
            }

            item {
            // 暗色模式切换
            var darkMode by remember { mutableStateOf(prefs.getString("dark_mode", "SYSTEM") ?: "SYSTEM") }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("亮色" to "LIGHT", "暗色" to "DARK", "系统" to "SYSTEM").forEach { (label, value) ->
                            FilterChip(
                                selected = darkMode == value,
                                onClick = {
                                    darkMode = value
                                    prefs.edit().putString("dark_mode", value).apply()
                                    // 通过 recompose 生效，需要重启 activity
                                    (ctx as? android.app.Activity)?.recreate()
                                },
                                label = { Text(label, fontSize = 13.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))

                    Spacer(Modifier.height(8.dp))
                    val currentThemeOrdinal = remember { prefs.getInt("app_theme", 0) }
                    val dynamicColorEnabled = remember { prefs.getBoolean("dynamic_color", false) }
                    val currentDarkMode = remember { prefs.getString("dark_mode", "SYSTEM") ?: "SYSTEM" }
                    val previewIsDark = when (currentDarkMode) {
                        "LIGHT" -> false
                        "DARK" -> true
                        else -> {
                            val nightMode = ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                            nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
                        }
                    }
                    androidx.compose.foundation.lazy.LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
            ) {
                item {
                    val isSelected = dynamicColorEnabled
                    val dynAvailable = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                    Card(
                        modifier = Modifier.width(72.dp).clickable {
                            if (dynAvailable) {
                                prefs.edit().putBoolean("dynamic_color", true).apply()
                                (ctx as? android.app.Activity)?.recreate()
                            } else {
                                android.widget.Toast.makeText(ctx, "动态色彩需 Android 12+", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                    ) {
                        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.size(32.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                    .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(
                                        androidx.compose.ui.graphics.Color(0xFF6750A4),
                                        androidx.compose.ui.graphics.Color(0xFF6200EE),
                                        androidx.compose.ui.graphics.Color(0xFF03DAC6)
                                    )))
                            ) {
                                if (isSelected) {
                                    androidx.compose.material3.Icon(
                                        androidx.compose.material.icons.Icons.Default.Check,
                                        null,
                                        modifier = Modifier.size(20.dp).align(Alignment.Center),
                                        tint = androidx.compose.ui.graphics.Color.White
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("动态色彩", fontSize = 10.sp, maxLines = 1, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                items(com.mangareader.ui.AppTheme.entries.size) { index ->
                    val theme = com.mangareader.ui.AppTheme.entries[index]
                    val isSelected = !dynamicColorEnabled && currentThemeOrdinal == index
                    val themePrimary = com.mangareader.ui.getThemePrimaryColor(theme, previewIsDark)
                    Card(
                        modifier = Modifier.width(72.dp).clickable {
                            prefs.edit().putInt("app_theme", index).putBoolean("dynamic_color", false).apply()
                            (ctx as? android.app.Activity)?.recreate()
                        },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) themePrimary.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, themePrimary) else null
                    ) {
                        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(32.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(themePrimary))
                            Spacer(Modifier.height(4.dp))
                            Text(theme.label, fontSize = 10.sp, maxLines = 1, color = if (isSelected) themePrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
                }
            }
            }

            item {

            Spacer(Modifier.height(16.dp))
            }
            item {
            Text("阅读", style = MaterialTheme.typography.titleMedium)
            }
            item {
            Spacer(Modifier.height(8.dp))
            }
            item {
            Card(Modifier.fillMaxWidth().clickable { showReadingSettings = true }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MenuBook, null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("阅读设置", fontSize = 16.sp)
                        Text("背景颜色、墨水屏、PDF质量、自动翻页等", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, null)
                }
            }
            }
            item {
            // 超分队列（批量离线超分进度与产物管理；超分主开关在阅读器底栏，不在阅读设置）
            Card(Modifier.fillMaxWidth().clickable { showSrQueue = true }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoFixHigh, null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("超分队列", fontSize = 16.sp)
                        Text("批量离线超分进度、已完成产物与缓存管理", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, null)
                }
            }
            }

            item {
            // ★ SMB 服务器管理（spec 5.1；扫描/阅读需 Android 12+，低版本入口可见可配置）
            Card(Modifier.fillMaxWidth().clickable { showSmbServers = true }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Storage, null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("SMB 服务器管理", fontSize = 16.sp)
                        Text("配置局域网共享并扫描阅读漫画", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, null)
                }
            }
            }
            item {
            // ★ WebDAV 服务器管理（spec 5.7；API 26+ 全可用，无版本限制）
            Card(Modifier.fillMaxWidth().clickable { showWebDavServers = true }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Cloud, null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("WebDAV 服务器管理", fontSize = 16.sp)
                        Text("配置 WebDAV 网盘并在线阅读远端漫画", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, null)
                }
            }
            }

            item {
            Spacer(Modifier.height(16.dp))
            }


            item {


            Card(Modifier.fillMaxWidth().clickable { showAbout = true }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("关于", fontSize = 16.sp)
                        Text("漫读 v${com.mangareader.BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, null)
                }
            }
            }

            item {
            if (showAbout) {
                AlertDialog(
                    onDismissRequest = { showAbout = false },
                    title = { Text("关于") },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            Text("漫读 v${com.mangareader.BuildConfig.VERSION_NAME}", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text("支持格式", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("漫画: ZIP/CBZ · RAR/CBR(含RAR5) · 7Z/CB7 · TAR/CBT · PDF · EPUB · MOBI/AZW/AZW3/PDB/PRC", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("图片: JPG · PNG · WEBP · GIF · BMP · HEIC · HEIF · AVIF · SVG · JXL", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Text("版本兼容性", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            val sdk = android.os.Build.VERSION.SDK_INT
                            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
                            val is64Bit = abi.contains("arm64") || abi.contains("x86_64")
                            Text("当前设备: Android ${android.os.Build.VERSION.RELEASE} (API $sdk)", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            Text("处理器: $abi", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!is64Bit) {
                                Text("32位系统，部分功能性能可能有所下降", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                            } else {
                                Text("64位系统，可使用全部功能", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                            }

                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))
                            Text("开源许可", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(4.dp))
                            LicenseLink("SubsamplingScaleImageView (Tachiyomi)", "https://github.com/niceSaber/SubsamplingScaleImagePicker")
                            LicenseLink("Coil", "https://github.com/coil-kt/coil")
                            LicenseLink("Apache Commons Compress", "https://github.com/apache/commons-compress")
                            LicenseLink("junrar", "https://github.com/junrar/junrar")
                            LicenseLink("libarchive-android (zhanghai)", "https://github.com/niceSaber/libarchive-android")
                            LicenseLink("pdfium", "https://github.com/niceSaber/pdfium")
                            LicenseLink("stb_image", "https://github.com/niceSaber/stb")
                            LicenseLink("libwebp", "https://github.com/niceSaber/libwebp")
                            LicenseLink("libmobi", "https://github.com/niceSaber/libmobi")
                            LicenseLink("libzip", "https://github.com/niceSaber/libzip")
                            LicenseLink("APNG4Android (penfeizhou)", "https://github.com/penfeizhou/APNG4Android")
                            LicenseLink("LocalViewer (GPL v3)", "https://github.com/zmz125000/LocalViewer")
                            // 超分栈（模型随包内置，BSD 系许可）
                            LicenseLink("ncnn (Tencent)", "https://github.com/Tencent/ncnn")
                            LicenseLink("Real-CUGAN 模型 (bilibili/ailab)", "https://github.com/bilibili/ailab")
                            LicenseLink("Real-ESRGAN 模型 (xinntao)", "https://github.com/xinntao/Real-ESRGAN")
                            LicenseLink("ncnn (nihui)", "https://github.com/nihui/realcugan-ncnn-vulkan")
                            LicenseLink("waifu2x (nagadomi / nihui)", "https://github.com/nihui/waifu2x-ncnn-vulkan")
                            Spacer(Modifier.height(8.dp))
                            Text("特别感谢", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("· TachiyomiSY — 阅读器架构与功能设计的重要参考", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("· MHark — 读取机制与性能的重要参考", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("· LocalViewer — SMB/WebDAV 的重要参考", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Text("感谢所有开源项目的贡献者，让这一切成为可能。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showAbout = false }) { Text("确定") }
                    }
                )
            }
            }
        }
    }

    if (showSrQueue) {
        com.mangareader.ui.SrQueueScreen(
            onBack = { showSrQueue = false },
            onOpenBook = { id, path ->
                if (onOpenBook != null) onOpenBook(id, path)
            },
        )
    }

    if (showReadingSettings) {
        ReadingSettingsScreen(onBack = { showReadingSettings = false })
    }
    if (showShelfManage) {
        ShelfManageScreen(onBack = { showShelfManage = false })
    }
    if (showFolderManage) {
        FolderManageScreen(onBack = { showFolderManage = false })
    }
    if (showSmbServers) {
        com.mangareader.smbbridge.SmbServersScreen(onBack = { showSmbServers = false }, onOpenBook = { id -> onOpenBook?.invoke(id, null) })
    }
    if (showWebDavServers) {
        com.mangareader.webdavbridge.WebDavServersScreen(onBack = { showWebDavServers = false }, onOpenBook = { id -> onOpenBook?.invoke(id, null) })
    }
    if (showDonation) DonationDialog(onDismiss = { showDonation = false })
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
        else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
    }
}

@Composable
private fun LicenseLink(name: String, url: String) {
    val ctx = LocalContext.current
    Text(
        text = name,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {}
        }
    )
}
