package com.mangareader.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hippo.ehviewer.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("manga_reader", Context.MODE_PRIVATE) }
    var config by remember { mutableStateOf(com.mangareader.data.ReaderConfig.load(ctx).let {
        if (prefs.getString("readBackground", null) == null) {
            prefs.edit().putString("readBackground", "WHITE").apply()
            it.copy(readBackground = com.mangareader.data.ReaderConfig.ReadBackground.WHITE)
        } else it
    }) }
    var autoRefresh by remember { mutableStateOf(prefs.getBoolean("auto_refresh", false)) }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("阅读设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            item {
                Text("背景颜色", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("黑色" to "DARK", "白色" to "WHITE").forEach { (label, value) ->
                        val bgColor = if (value == "DARK") Color(0xFF111111) else Color(0xFFE8E8E8)
                        val isSelected = config.readBackground.name == value
                        Surface(
                            modifier = Modifier.weight(1f).clickable {
                                config = config.copy(readBackground = com.mangareader.data.ReaderConfig.ReadBackground.valueOf(value))
                                config.save(ctx)
                            },
                            shape = MaterialTheme.shapes.medium,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            tonalElevation = if (isSelected) 4.dp else 0.dp
                        ) {
                            Box(Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(Modifier.size(20.dp).background(bgColor, MaterialTheme.shapes.small))
                                    Text(label, fontSize = 14.sp, color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            item {
                var defaultReadModeOrdinal by remember { mutableStateOf(prefs.getInt("default_read_mode", -1)) }
                var showDefaultModeDialog by remember { mutableStateOf(false) }
                val defaultModeLabel =
                    if (defaultReadModeOrdinal in Mode.entries.indices) Mode.entries[defaultReadModeOrdinal].label
                    else "未设置"
                Row(
                    Modifier.fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .clickable { showDefaultModeDialog = true },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("默认阅读模式", fontSize = 16.sp)
                        Text(
                            "新书首次打开的初始阅读方式",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        defaultModeLabel,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 4.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (showDefaultModeDialog) {
                    val options: List<Pair<String, Int>> =
                        listOf("不设置（按书籍自动）" to -1) + Mode.entries.map { it.label to it.ordinal }
                    AlertDialog(
                        onDismissRequest = { showDefaultModeDialog = false },
                        title = { Text("默认阅读模式") },
                        text = {
                            Column {
                                options.forEach { (label, ord) ->
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable {
                                                defaultReadModeOrdinal = ord
                                                prefs.edit().putInt("default_read_mode", ord).apply()
                                                showDefaultModeDialog = false
                                            }
                                            .padding(vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(selected = defaultReadModeOrdinal == ord, onClick = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(label, fontSize = 15.sp)
                                    }
                                }
                            }
                        },
                        confirmButton = {},
                        dismissButton = {
                            TextButton(onClick = { showDefaultModeDialog = false }) { Text("取消") }
                        }
                    )
                }
            }

            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("启动时自动刷新", fontSize = 16.sp)
                        Text("检查已导入文件夹是否有新文件", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = autoRefresh, onCheckedChange = { autoRefresh = it; prefs.edit().putBoolean("auto_refresh", it).apply() })
                }
            }

            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("沉浸式阅读", fontSize = 16.sp)
                        Text("阅读时隐藏状态栏和导航栏", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = config.isImmersiveMode, onCheckedChange = { config = config.copy(isImmersiveMode = it); config.save(ctx) })
                }
            }

            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("墨水屏模式", fontSize = 16.sp)
                        Text("适配墨水屏设备：降低图片解码分辨率、减少预加载、延长UI自动隐藏时间", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = config.isEinkMode, onCheckedChange = { config = config.copy(isEinkMode = it); config.save(ctx) })
                }
            }

            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("动态封面", fontSize = 16.sp)
                        Text("开启后书架与文件夹子项的动图封面会播放动画；关闭（默认）全部显示静态首帧，更流畅省电。重新进入书架后生效", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    var dynamicCovers by remember { mutableStateOf(prefs.getBoolean("dynamic_covers", false)) }
                    Switch(
                        checked = dynamicCovers,
                        onCheckedChange = {
                            dynamicCovers = it
                            prefs.edit().putBoolean("dynamic_covers", it).apply()
                        },
                    )
                }
            }

            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("长按保存图片", fontSize = 16.sp)
                        Text("开启后翻页阅读时长按页面可将当前图保存到相册；默认关闭，避免翻页时误触", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    var longPressSave by remember { mutableStateOf(prefs.getBoolean("long_press_save", false)) }
                    Switch(
                        checked = longPressSave,
                        onCheckedChange = {
                            longPressSave = it
                            prefs.edit().putBoolean("long_press_save", it).apply()
                        },
                    )
                }
            }

            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("HDR 显示", fontSize = 16.sp)
                        Text("开启后阅读 HDR 图片时屏幕切换到 HDR 模式，展现完整亮度范围；需 HDR 屏支持，非 HDR 屏自动回退", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    var hdrDisplay by remember { mutableStateOf(Settings.readerHdrDisplay.value) }
                    Switch(
                        checked = hdrDisplay,
                        onCheckedChange = {
                            hdrDisplay = it
                            Settings.readerHdrDisplay.value = it
                        },
                    )
                }
            }

            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text("PDF加载质量", fontSize = 16.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("当前: ${String.format("%.2f", config.pdfQuality)}x", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Slider(
                        value = config.pdfQuality,
                        onValueChange = { config = config.copy(pdfQuality = it) },
                        onValueChangeFinished = { config.save(ctx) },
                        valueRange = 1.0f..5.0f,
                        steps = 15,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("1x\n72DPI\n速度最快", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp)
                        Text("3x\n216DPI\n推荐默认", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Text("5x\n360DPI\n原图质量", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("倍率越高越清晰，加载越慢越占内存。手机屏幕一般300-500DPI，3x-4x已足够清晰", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text("自动翻页间隔", fontSize = 16.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(if (config.autoFlipInterval == 0) "当前: 关闭" else "当前: ${config.autoFlipInterval / 1000.0}秒", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                    Slider(
                        value = config.autoFlipInterval.toFloat(),
                        onValueChange = { config = config.copy(autoFlipInterval = it.toInt()) },
                        onValueChangeFinished = { config.save(ctx) },
                        valueRange = 0f..30000f,
                        steps = 29,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("关闭", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("30秒", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
