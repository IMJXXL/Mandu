package com.mangareader.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenBook: ((Long, String?, Int) -> Unit)? = null) {
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
    var showReadingSettings by remember { mutableStateOf(false) }
    var showReadingPrefs by remember { mutableStateOf(false) }
    var showKeyMap by remember { mutableStateOf(false) }
    // ★ 外观设置子页：rememberSaveable——子页内改暗色/主题会 recreate Activity，saveable 保证回到本页而不是弹回设置首页
    var showAppearanceSettings by rememberSaveable { mutableStateOf(false) }
    var showShelfManage by remember { mutableStateOf(false) }
    var showFolderManage by remember { mutableStateOf(false) }
    // 超分队列页（全屏页，不嵌在滚动 Column 里渲染）
    var showSrQueue by remember { mutableStateOf(false) }
    // ★ SMB/WebDAV 服务器管理入口（smb_embed_lv119 任务2.4/12.5）
    var showSmbServers by rememberSaveable { mutableStateOf(false) }
    var showWebDavServers by rememberSaveable { mutableStateOf(false) }
    var showKomgaServers by remember { mutableStateOf(false) }
    var showOpdsServers by remember { mutableStateOf(false) }


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

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } })
        }
    ) { padding ->
        var showAbout by remember { mutableStateOf(false) }
        var diagExportMsg by remember { mutableStateOf<String?>(null) }
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = CONTENT_MAX_WIDTH_DP).padding(16.dp)) {
            item {
            Spacer(Modifier.height(12.dp))
            }
            item {
            // 【临时功能，用完整体删除】全量日志导出（含本进程 logcat，已脱敏）——远端排障用
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Card(Modifier.fillMaxWidth().clickable {
                diagExportMsg = com.mangareader.sr.DiagLogExporter.export(ctx)
            }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("导出全部日志（临时）", fontSize = 16.sp)
                        Text(diagExportMsg ?: "含引擎初始化细节，供排障分析", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
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
                        Text("书架与标签", fontSize = 16.sp)
                        Text("新建、重命名、拖动排序书架；标签的增删与配色", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text("外观", style = MaterialTheme.typography.titleMedium)
            }
            item {
            Spacer(Modifier.height(8.dp))
            }
            item {
            Card(Modifier.fillMaxWidth().clickable { showAppearanceSettings = true }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Palette, null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("外观设置", fontSize = 16.sp)
                        Text("主题颜色、暗色模式、自定义主色", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, null)
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
                        Text("背景颜色、墨水屏、PDF 质量、自动翻页等", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        Text("增强队列", fontSize = 16.sp)
                        Text("批量离线增强进度、已完成产物与缓存管理", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Card(Modifier.fillMaxWidth().clickable { showKomgaServers = true }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Dns, null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Komga 服务器管理", fontSize = 16.sp)
                        Text("配置 Komga 自建漫画服务器并在线阅读", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, null)
                }
            }
            }
            item {
            Card(Modifier.fillMaxWidth().clickable { showOpdsServers = true }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Dns, null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("OPDS 服务器管理 Beta", fontSize = 16.sp)
                        Text("配置 OPDS 漫画服务器（Kavita/LANraragi/Calibre 等）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                Text("32 位系统，部分功能性能可能有所下降", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                            } else {
                                Text("64 位系统，可使用全部功能", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                            }

                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))
                            Text("开源许可", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(4.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {

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
                                LicenseLink("PageCurl (oleksandrbalan)", "https://github.com/oleksandrbalan/pagecurl")
                                LicenseLink("LocalViewer (GPL v3)", "https://github.com/zmz125000/LocalViewer")
                                LicenseLink("TachiyomiSY", "https://github.com/NightstarTeam/TachiyomiSY")
                                LicenseLink("Mihon", "https://github.com/mihonapp/mihon")
                                LicenseLink("ncnn (Tencent)", "https://github.com/Tencent/ncnn")
                                LicenseLink("Real-CUGAN 模型 (bilibili/ailab)", "https://github.com/bilibili/ailab")
                                LicenseLink("Real-ESRGAN 模型 (xinntao)", "https://github.com/xinntao/Real-ESRGAN")
                                LicenseLink("ncnn (nihui)", "https://github.com/nihui/realcugan-ncnn-vulkan")
                                LicenseLink("waifu2x (nagadomi / nihui)", "https://github.com/nihui/waifu2x-ncnn-vulkan")
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("特别感谢", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("· MHark — 读取机制与性能的重要参考", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("· 感谢 FooIbar 与 zmz125000 允许暂不开源使用相关成果", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Text("感谢所有开源项目的贡献者，让这一切成为可能。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(8.dp))
                            // ★ 日志导出（2026-09-25 排查无日志闪退新增）：汇总崩溃/ANR/签名校验
                            //   诊断 + 设备信息，写入公共 Download，用户经文件管理器直接取用
                            // 2026-09-30：导出目录可选（有的设备写不了公共 Download），SAF 授权持久化
                            var exportMsg by remember { mutableStateOf<String?>(null) }
                            var exportDirName by remember { mutableStateOf(com.mangareader.DiagExporter.chosenDirLabel(ctx)) }
                            val pickExportDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                                if (uri != null) {
                                    com.mangareader.DiagExporter.saveChosenDir(ctx, uri)
                                    exportDirName = com.mangareader.DiagExporter.chosenDirLabel(ctx)
                                }
                            }
                            TextButton(onClick = {
                                exportMsg = com.mangareader.DiagExporter.export(ctx)
                            }) { Text("导出诊断日志（排障用）") }
                            TextButton(onClick = { pickExportDir.launch(null) }) {
                                Text(
                                    if (exportDirName == null) "选择日志导出目录（默认下载目录）"
                                    else "更换导出目录（当前：$exportDirName）",
                                    fontSize = 13.sp
                                )
                            }
                            if (exportDirName != null) {
                                TextButton(onClick = {
                                    com.mangareader.DiagExporter.clearChosenDir(ctx)
                                    exportDirName = null
                                }) { Text("恢复默认（导出到下载）", fontSize = 13.sp) }
                            }
                            exportMsg?.let {
                                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showAbout = false }) { Text("知道了") }
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
                if (onOpenBook != null) onOpenBook(id, path, -1)
            },
        )
    }

    if (showReadingSettings) {
        ReadingSettingsScreen(
            onBack = { showReadingSettings = false },
            onOpenKeyMap = { showKeyMap = true },
            onOpenPrefs = { showReadingPrefs = true },
        )
    }
    if (showReadingPrefs) {
        ReadingPrefsScreen(
            onBack = { showReadingPrefs = false; showReadingSettings = true },
        )
    }
    if (showKeyMap) {
        KeyMapScreen(onBack = { showKeyMap = false })
    }
    if (showAppearanceSettings) {
        AppearanceSettingsScreen(onBack = { showAppearanceSettings = false })
    }
    if (showShelfManage) {
        ShelfManageScreen(onBack = { showShelfManage = false })
    }
    if (showFolderManage) {
        FolderManageScreen(onBack = { showFolderManage = false })
    }
    if (showSmbServers) {
        com.mangareader.smbbridge.SmbServersScreen(onBack = { showSmbServers = false }, onOpenBook = { id, page -> onOpenBook?.invoke(id, null, page) })
    }
    if (showWebDavServers) {
        com.mangareader.webdavbridge.WebDavServersScreen(onBack = { showWebDavServers = false }, onOpenBook = { id, page -> onOpenBook?.invoke(id, null, page) })
    }
    if (showKomgaServers) {
        com.mangareader.komgabridge.ui.KomgaServersScreen(onBack = { showKomgaServers = false }, onOpenBook = { id -> onOpenBook?.invoke(id, null, -1) })
    }
    if (showOpdsServers) {
        com.mangareader.opdsbridge.ui.OpdsServersScreen(onBack = { showOpdsServers = false }, onOpenBook = { id -> onOpenBook?.invoke(id, null, -1) })
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


