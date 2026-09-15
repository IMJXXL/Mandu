package com.mangareader.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
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
    BackHandler { onBack() }
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("manga_reader", Context.MODE_PRIVATE) }
    var config by remember { mutableStateOf(com.mangareader.data.ReaderConfig.load(ctx).let {
        if (prefs.getString("readBackground", null) == null) {
            prefs.edit().putString("readBackground", "WHITE").apply()
            it.copy(readBackground = com.mangareader.data.ReaderConfig.ReadBackground.WHITE)
        } else it
    }) }
    var autoRefresh by remember { mutableStateOf(prefs.getBoolean("auto_refresh", false)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("阅读设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
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
                        Text("书签备注", fontSize = 16.sp)
                        Text("开启后点击书签时弹出备注输入框，备注显示在目录书签条目中", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    var bookmarkNote by remember { mutableStateOf(prefs.getBoolean("bookmark_note_enabled", false)) }
                    Switch(
                        checked = bookmarkNote,
                        onCheckedChange = {
                            bookmarkNote = it
                            prefs.edit().putBoolean("bookmark_note_enabled", it).apply()
                        },
                    )
                }
            }

            item {
                var volKeys by remember { mutableStateOf(prefs.getBoolean("reader_volume_keys", false)) }
                var volInvert by remember { mutableStateOf(prefs.getBoolean("reader_volume_keys_inverted", false)) }
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("音量键翻页", fontSize = 16.sp)
                        Text("使用音量键翻页，菜单显示时不拦截", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = volKeys, onCheckedChange = {
                        volKeys = it
                        prefs.edit().putBoolean("reader_volume_keys", it).apply()
                        if (!it && volInvert) {
                            volInvert = false
                            prefs.edit().putBoolean("reader_volume_keys_inverted", false).apply()
                        }
                    })
                }
                if (volKeys) {
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("翻转音量键", fontSize = 15.sp)
                            Text("交换音量上/下键的翻页方向", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = volInvert, onCheckedChange = { volInvert = it; prefs.edit().putBoolean("reader_volume_keys_inverted", it).apply() })
                    }
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
                        Text("墨水屏模式", fontSize = 16.sp)
                        Text("降低分辨率解码、减少预加载、缩短UI自动隐藏时间以减少残影", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = config.isEinkMode,
                        onCheckedChange = {
                            config = config.copy(isEinkMode = it)
                            config.save(ctx)
                        },
                    )
                }
            }

            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("强制全分辨率解码", fontSize = 16.sp)
                        Text(
                            if (config.isEinkMode) "墨水屏模式开启时此项无效" else "除封面外不降采样，内存增大可能卡顿，不推荐开启",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.forceFullDecode,
                        enabled = !config.isEinkMode,
                        onCheckedChange = {
                            config = config.copy(forceFullDecode = it)
                            config.save(ctx)
                        },
                    )
                }
            }

            if (config.isEinkMode) {
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text("翻页闪烁", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("启用翻页闪烁（降低残影）", fontSize = 14.sp)
                            Switch(
                                checked = config.isFlipFlashEnabled,
                                onCheckedChange = { config = config.copy(isFlipFlashEnabled = it); config.save(ctx) },
                            )
                        }
                        if (config.isFlipFlashEnabled) {
                            Spacer(Modifier.height(4.dp))
                            Text("闪烁颜色", fontSize = 14.sp)
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("白色" to 0, "黑色" to 1, "黑白两段" to 2).forEach { (label, value) ->
                                    Surface(
                                        modifier = Modifier.weight(1f).clickable {
                                            config = config.copy(flipFlashColor = value)
                                            config.save(ctx)
                                        },
                                        shape = MaterialTheme.shapes.medium,
                                        color = if (config.flipFlashColor == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        tonalElevation = if (config.flipFlashColor == value) 4.dp else 0.dp,
                                    ) {
                                        Text(label, fontSize = 13.sp, modifier = Modifier.padding(12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = if (config.flipFlashColor == value) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("持续时间: ${config.flipFlashDuration}ms", fontSize = 14.sp)
                            Slider(
                                value = config.flipFlashDuration.toFloat(),
                                onValueChange = { config = config.copy(flipFlashDuration = it.toInt()) },
                                onValueChangeFinished = { config.save(ctx) },
                                valueRange = 50f..1500f,
                                steps = 14,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("间隔页数: ${config.flipFlashInterval}页", fontSize = 14.sp)
                            Slider(
                                value = config.flipFlashInterval.toFloat(),
                                onValueChange = { config = config.copy(flipFlashInterval = it.toInt()) },
                                onValueChangeFinished = { config.save(ctx) },
                                valueRange = 1f..10f,
                                steps = 8,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }

                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text("解码分辨率", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text("降低分辨率可加快解码速度、减少内存占用", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("1072\n(6寸)" to 1072, "1440\n(8寸)" to 1440, "1920\n(默认)" to 1920, "原图\n(全量)" to 4096).forEach { (label, value) ->
                                Surface(
                                    modifier = Modifier.weight(1f).clickable {
                                        config = config.copy(einkDecodeSize = value)
                                        config.save(ctx)
                                    },
                                    shape = MaterialTheme.shapes.medium,
                                    color = if (config.einkDecodeSize == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    tonalElevation = if (config.einkDecodeSize == value) 4.dp else 0.dp,
                                ) {
                                    Text(label, fontSize = 12.sp, modifier = Modifier.padding(12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 16.sp, color = if (config.einkDecodeSize == value) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }

                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text("UI自动隐藏时间", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text("缩短隐藏时间可减少残影（普通模式默认10秒）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("3秒" to 3000L, "6秒" to 6000L, "10秒" to 10000L, "不隐藏" to 0L).forEach { (label, value) ->
                                Surface(
                                    modifier = Modifier.weight(1f).clickable {
                                        config = config.copy(einkUiHideDelay = value)
                                        config.save(ctx)
                                    },
                                    shape = MaterialTheme.shapes.medium,
                                    color = if (config.einkUiHideDelay == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    tonalElevation = if (config.einkUiHideDelay == value) 4.dp else 0.dp,
                                ) {
                                    Text(label, fontSize = 13.sp, modifier = Modifier.padding(12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = if (config.einkUiHideDelay == value) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("HDR 显示", fontSize = 16.sp)
                        Text("需原生 HDR 屏支持，低亮度屏（<600nit）开启后可能发黄", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
