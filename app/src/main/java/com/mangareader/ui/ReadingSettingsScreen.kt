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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hippo.ehviewer.Settings
import com.mangareader.smbbridge.SectionCard

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
    var resetTrigger by remember { mutableStateOf(0) }
    var showResetDialog by remember { mutableStateOf(false) }


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
                SectionCard("显示") {
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

                    var defaultReadModeOrdinal by remember(resetTrigger) { mutableStateOf(prefs.getInt("default_read_mode", -1)) }
                    var showDefaultModeDialog by remember { mutableStateOf(false) }
                    val defaultModeLabel =
                        if (defaultReadModeOrdinal in Mode.entries.indices) Mode.entries[defaultReadModeOrdinal].label
                        else "未设置"
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(vertical = 10.dp)
                            .clickable { showDefaultModeDialog = true },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("默认阅读模式", fontSize = 16.sp)
                            Text("新书首次打开的初始阅读方式", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(defaultModeLabel, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                        Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.padding(start = 4.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            dismissButton = { TextButton(onClick = { showDefaultModeDialog = false }) { Text("取消") } }
                        )
                    }

                    // ★ 继续阅读显隐开关（2026-09-19）：首页全局继续阅读 + 书内多层继续阅读
                    var homeContinue by remember(resetTrigger) { mutableStateOf(prefs.getBoolean("home_continue_read", true)) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("首页「继续阅读」", fontSize = 16.sp)
                            Text("书架右下角的继续阅读按钮（全局最近阅读的一本书）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = homeContinue, onCheckedChange = { homeContinue = it; prefs.edit().putBoolean("home_continue_read", it).apply() })
                    }
                    var inBookContinue by remember(resetTrigger) { mutableStateOf(prefs.getBoolean("inbook_continue_read", true)) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("书内「继续阅读」", fontSize = 16.sp)
                            Text("打开文件夹层级时显示继续阅读入口，直达当前层及以下最近阅读的那一卷", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = inBookContinue, onCheckedChange = { inBookContinue = it; prefs.edit().putBoolean("inbook_continue_read", it).apply() })
                    }
                }
            }

            item {
                SectionCard("翻页与操作") {
                    var volKeys by remember(resetTrigger) { mutableStateOf(prefs.getBoolean("reader_volume_keys", false)) }
                    var volInvert by remember(resetTrigger) { mutableStateOf(prefs.getBoolean("reader_volume_keys_inverted", false)) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("音量键翻页", fontSize = 16.sp)
                            Text("使用音量键翻页，菜单显示时不拦截", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = volKeys, onCheckedChange = {
                            volKeys = it
                            prefs.edit().putBoolean("reader_volume_keys", it).apply()
                            if (!it && volInvert) { volInvert = false; prefs.edit().putBoolean("reader_volume_keys_inverted", false).apply() }
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

                    var longPressSave by remember(resetTrigger) { mutableStateOf(prefs.getBoolean("long_press_save", false)) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("长按保存图片", fontSize = 16.sp)
                            Text("开启后翻页阅读时长按页面可将当前图保存到相册；默认关闭，避免翻页时误触", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = longPressSave, onCheckedChange = { longPressSave = it; prefs.edit().putBoolean("long_press_save", it).apply() })
                    }

                    var bookmarkNote by remember(resetTrigger) { mutableStateOf(prefs.getBoolean("bookmark_note_enabled", false)) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("书签备注", fontSize = 16.sp)
                            Text("开启后点击书签时弹出备注输入框，备注显示在目录书签条目中", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = bookmarkNote, onCheckedChange = { bookmarkNote = it; prefs.edit().putBoolean("bookmark_note_enabled", it).apply() })
                    }

                    Text("自动翻页间隔", fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
                    Text(if (config.autoFlipInterval == 0) "当前: 关闭" else "当前: ${config.autoFlipInterval / 1000.0}秒", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                    Slider(
                        value = config.autoFlipInterval.toFloat(),
                        onValueChange = { config = config.copy(autoFlipInterval = Math.round(it).toInt()) },
                        onValueChangeFinished = { config.save(ctx) },
                        valueRange = 0f..30000f, steps = 29, modifier = Modifier.fillMaxWidth()
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("关闭", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("30秒", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item {
                SectionCard("解码") {
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("按需解码", fontSize = 16.sp)
                            Text("开启后按可见区域局部解码，内存占用更低，适用于超长图片类漫画（PDF 条漫模糊请用条漫分割）；画质增强、旋转适配失效；裁剪白边开启时本功能自动停用", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = config.tileModeEnabled, onCheckedChange = { config = config.copy(tileModeEnabled = it); config.save(ctx) })
                    }

                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("条漫分割", fontSize = 16.sp)
                            Text("开启后对超长 PDF 页与长图片（本地文件夹/压缩包）切割分段渲染，长图不再因降采样而发糊", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = config.tallSplitEnabled, onCheckedChange = { config = config.copy(tallSplitEnabled = it); config.save(ctx) })
                    }

                    val tierDisabled = config.tileModeEnabled || config.isEinkMode
                    Text("降采样档位", fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
                    Text("大图自动降采样以减少内存占用，对 PDF 不生效", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth().alpha(if (tierDisabled) 0.4f else 1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("标准" to com.mangareader.data.ReaderConfig.DownsampleTier.STANDARD, "高清" to com.mangareader.data.ReaderConfig.DownsampleTier.HD, "超清" to com.mangareader.data.ReaderConfig.DownsampleTier.ULTRA_HD, "原图" to com.mangareader.data.ReaderConfig.DownsampleTier.ORIGINAL).forEach { (label, tier) ->
                            Surface(
                                modifier = Modifier.weight(1f).clickable { if (!tierDisabled) { config = config.copy(downsampleTier = tier); config.save(ctx) } },
                                shape = MaterialTheme.shapes.medium,
                                color = if (config.downsampleTier == tier) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                tonalElevation = if (config.downsampleTier == tier) 4.dp else 0.dp,
                            ) {
                                Text(label, fontSize = 13.sp, modifier = Modifier.padding(12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = if (config.downsampleTier == tier) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("PDF 加载质量", fontSize = 16.sp)
                    Text("当前: ${String.format("%.2f", config.pdfQuality)}x", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                    Slider(
                        value = config.pdfQuality,
                        onValueChange = { config = config.copy(pdfQuality = it) },
                        onValueChangeFinished = { config.save(ctx) },
                        valueRange = 1.0f..5.0f, steps = 15, modifier = Modifier.fillMaxWidth()
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("1x\n72DPI\n速度最快", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp)
                        Text("3x\n216DPI\n推荐默认", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Text("5x\n360DPI\n原图质量", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                    Text("倍率越高越清晰，加载越慢越占内存。手机屏幕一般 300-500 DPI，3x-4x 已足够清晰", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item {
                SectionCard("墨水屏") {
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("墨水屏模式", fontSize = 16.sp)
                            Text("降低分辨率解码、减少预加载、缩短 UI 自动隐藏时间以减少残影", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = config.isEinkMode, onCheckedChange = { config = config.copy(isEinkMode = it); config.save(ctx) })
                    }

                    if (config.isEinkMode) {
                        Spacer(Modifier.height(8.dp))
                        Text("翻页闪烁", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("全局生效：开启后对所有漫画强制使用此组设置（覆盖阅读内的单本设置，书内对应项将置灰）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("启用翻页闪烁（降低残影）", fontSize = 14.sp)
                            Switch(checked = config.isFlipFlashEnabled, onCheckedChange = { config = config.copy(isFlipFlashEnabled = it); config.save(ctx) })
                        }
                        if (config.isFlipFlashEnabled) {
                            Text("闪烁颜色", fontSize = 14.sp)
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("白色" to 0, "黑色" to 1, "黑白两段" to 2).forEach { (label, value) ->
                                    Surface(
                                        modifier = Modifier.weight(1f).clickable { config = config.copy(flipFlashColor = value); config.save(ctx) },
                                        shape = MaterialTheme.shapes.medium,
                                        color = if (config.flipFlashColor == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        tonalElevation = if (config.flipFlashColor == value) 4.dp else 0.dp,
                                    ) {
                                        Text(label, fontSize = 13.sp, modifier = Modifier.padding(12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = if (config.flipFlashColor == value) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                            Text("持续时间: ${config.flipFlashDuration}ms", fontSize = 14.sp)
                            Slider(value = config.flipFlashDuration.toFloat(), onValueChange = { config = config.copy(flipFlashDuration = it.toInt()) }, onValueChangeFinished = { config.save(ctx) }, valueRange = 50f..1500f, steps = 14, modifier = Modifier.fillMaxWidth())
                            Text("间隔页数: ${config.flipFlashInterval}页", fontSize = 14.sp)
                            Slider(value = config.flipFlashInterval.toFloat(), onValueChange = { config = config.copy(flipFlashInterval = it.toInt()) }, onValueChangeFinished = { config.save(ctx) }, valueRange = 1f..10f, steps = 8, modifier = Modifier.fillMaxWidth())
                        }

                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("灰阶抖动", fontSize = 14.sp)
                                Text("让墨水屏显示更多灰阶层次，纯白/纯黑不抖", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = config.isEinkDither, onCheckedChange = { config = config.copy(isEinkDither = it); config.save(ctx) })
                        }
                        if (config.isEinkDither) {
                            Text("抖动算法", fontSize = 14.sp)
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("细腻" to 0, "胶片颗粒" to 1, "柔和" to 2, "规则纹理" to 3).forEach { (label, value) ->
                                    Surface(
                                        modifier = Modifier.weight(1f).clickable { config = config.copy(einkDitherAlgorithm = value); config.save(ctx) },
                                        shape = MaterialTheme.shapes.medium,
                                        color = if (config.einkDitherAlgorithm == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        tonalElevation = if (config.einkDitherAlgorithm == value) 4.dp else 0.dp,
                                    ) {
                                        Text(label, fontSize = 13.sp, modifier = Modifier.padding(12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = if (config.einkDitherAlgorithm == value) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                            Text("白底保护", fontSize = 14.sp)
                            Text("档位越高白底越干净但细节可能损失", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(value = config.einkEndpointPassthrough.toFloat(), onValueChange = { config = config.copy(einkEndpointPassthrough = it.toInt()) }, onValueChangeFinished = { config.save(ctx) }, valueRange = 0f..8f, steps = 7, modifier = Modifier.fillMaxWidth())
                            Text("灰阶档位（数字越大过渡越平滑）", fontSize = 14.sp)
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("16" to 16, "32" to 32, "64" to 64).forEach { (label, value) ->
                                    Surface(
                                        modifier = Modifier.weight(1f).clickable { config = config.copy(einkDitherLevels = value); config.save(ctx) },
                                        shape = MaterialTheme.shapes.medium,
                                        color = if (config.einkDitherLevels == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        tonalElevation = if (config.einkDitherLevels == value) 4.dp else 0.dp,
                                    ) {
                                        Text(label, fontSize = 13.sp, modifier = Modifier.padding(12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = if (config.einkDitherLevels == value) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                        }


                        Spacer(Modifier.height(12.dp))
                        Text("解码分辨率", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("降低分辨率可加快解码速度、减少内存占用", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("1072\n(6寸)" to 1072, "1440\n(8寸)" to 1440, "1920\n(默认)" to 1920, "原图\n(全量)" to 4096).forEach { (label, value) ->
                                Surface(
                                    modifier = Modifier.weight(1f).clickable { config = config.copy(einkDecodeSize = value); config.save(ctx) },
                                    shape = MaterialTheme.shapes.medium,
                                    color = if (config.einkDecodeSize == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    tonalElevation = if (config.einkDecodeSize == value) 4.dp else 0.dp,
                                ) {
                                    Text(label, fontSize = 12.sp, modifier = Modifier.padding(12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 16.sp, color = if (config.einkDecodeSize == value) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Text("UI 自动隐藏时间", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("缩短隐藏时间可减少残影（普通模式默认 10 秒）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("3秒" to 3000L, "6秒" to 6000L, "10秒" to 10000L, "不隐藏" to 0L).forEach { (label, value) ->
                                Surface(
                                    modifier = Modifier.weight(1f).clickable { config = config.copy(einkUiHideDelay = value); config.save(ctx) },
                                    shape = MaterialTheme.shapes.medium,
                                    color = if (config.einkUiHideDelay == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    tonalElevation = if (config.einkUiHideDelay == value) 4.dp else 0.dp,
                                ) {
                                    Text(label, fontSize = 13.sp, modifier = Modifier.padding(12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = if (config.einkUiHideDelay == value) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                }
            }

            item {
                com.mangareader.smbbridge.SectionCard("点击翻页区域") {
                    Text("翻页画面时点哪一块做什么（横向翻页模式；条漫/仿真翻页不适用）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "关闭" to com.mangareader.data.ReaderConfig.ClickFlipType.NO,
                            "左右分区" to com.mangareader.data.ReaderConfig.ClickFlipType.LEFT_RIGHT,
                            "L型" to com.mangareader.data.ReaderConfig.ClickFlipType.L_SHAPE,
                        ).forEach { (label, value) ->
                            Surface(
                                modifier = Modifier.weight(1f).clickable { config = config.copy(clickFlipType = value); config.save(ctx) },
                                shape = MaterialTheme.shapes.medium,
                                color = if (config.clickFlipType == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                tonalElevation = if (config.clickFlipType == value) 4.dp else 0.dp,
                            ) {
                                Text(label, fontSize = 13.sp, modifier = Modifier.padding(10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = if (config.clickFlipType == value) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 染色预览：上一页=主色 / 菜单=灰 / 下一页=主色深，对调后水平镜像
                    ClickZonePreview(config.clickFlipType, config.clickFlipInvertH)
                    Text("深色区=上一页 · 浅色区=下一页 · 空白=唤出菜单", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    Text("深色区=上一页 · 浅色区=下一页 · 空白=唤出菜单", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("对调左右（左手模式）", fontSize = 14.sp)
                            Text(if (config.clickFlipInvertH) "开启后：点右侧=上一页、点左侧=下一页" else "关闭时：点左侧=上一页、点右侧=下一页", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.Switch(
                            checked = config.clickFlipInvertH,
                            onCheckedChange = { on -> config = config.copy(clickFlipInvertH = on); config.save(ctx) },
                            enabled = config.clickFlipType != com.mangareader.data.ReaderConfig.ClickFlipType.NO,
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                TextButton(
                    onClick = { showResetDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("恢复默认设置", fontSize = 15.sp, color = MaterialTheme.colorScheme.error)
                }
            }

        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("恢复默认设置") },
            text = { Text("将清除所有阅读设置（含单本自定义），恢复到刚安装时的状态。阅读进度不受影响。\n此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    com.mangareader.data.ReaderConfig().save(ctx)
                    prefs.edit().apply {
                        remove("default_read_mode")
                        remove("reader_volume_keys")
                        remove("reader_volume_keys_inverted")
                        remove("long_press_save")
                        remove("bookmark_note_enabled")
                        remove("home_continue_read")
                        remove("inbook_continue_read")
                        prefs.all.keys.filter { it.startsWith("book_") || it.startsWith("read_mode_") || it.startsWith("orientation_") }.forEach { remove(it) }
                        apply()
                    }
                    config = com.mangareader.data.ReaderConfig()
                    resetTrigger++
                    showResetDialog = false
                }) { Text("重置", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text("取消") } }
        )
    }
}

/**
 * 点击翻页区域染色预览（对调后水平镜像）：上一页=主色、菜单=灰、下一页=主色深。
 * 仅示意当前预设的分区形态，与 ViewerNavigation 区域表一致。
 */
@Composable
private fun ClickZonePreview(type: com.mangareader.data.ReaderConfig.ClickFlipType, invertH: Boolean) {
    val prevC = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
    val nextC = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    val menuC = MaterialTheme.colorScheme.surfaceVariant
    val bg = MaterialTheme.colorScheme.surface
    androidx.compose.foundation.Canvas(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(MaterialTheme.shapes.medium)
    ) {
        drawRect(bg)
        val w = size.width
        val h = size.height
        // invertH=true 时按水平镜像绘制（与 InvertedNavigation 的 fx→1-fx 一致）
        fun mirrorX(x: Float) = if (invertH) 1f - x else x
        when (type) {
            com.mangareader.data.ReaderConfig.ClickFlipType.NO -> {
                drawRect(menuC)
            }
            com.mangareader.data.ReaderConfig.ClickFlipType.L_SHAPE -> {
                // PREV：上 1/3 横条 + 左中 1/3 竖条；NEXT：下 1/3 横条 + 右中 1/3 竖条；中央=菜单
                fun rect(x0: Float, y0: Float, x1: Float, y1: Float, color: androidx.compose.ui.graphics.Color) {
                    val mx0 = mirrorX(x0); val mx1 = mirrorX(x1)
                    drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(minOf(mx0, mx1) * w, y0 * h),
                        size = androidx.compose.ui.geometry.Size(kotlin.math.abs(mx1 - mx0) * w, (y1 - y0) * h))
                }
                rect(0f, 0.05f, 1f, 0.33f, prevC)
                rect(0f, 0.33f, 0.33f, 0.67f, prevC)
                rect(0.67f, 0.33f, 1f, 0.67f, nextC)
                rect(0f, 0.67f, 1f, 1f, nextC)
                rect(0.33f, 0.33f, 0.67f, 0.67f, menuC)
            }
            else -> {
                // 左右分区：左 1/3 PREV / 中 MENU / 右 1/3 NEXT；顶部 5% 与 getAction 一致为 MENU
                fun rect(x0: Float, x1: Float, color: androidx.compose.ui.graphics.Color, y0: Float = 0.05f) {
                    val mx0 = mirrorX(x0); val mx1 = mirrorX(x1)
                    drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(minOf(mx0, mx1) * w, y0 * h),
                        size = androidx.compose.ui.geometry.Size(kotlin.math.abs(mx1 - mx0) * w, (1f - y0) * h))
                }
                drawRect(menuC) // 顶部 5%
                rect(0f, 0.33f, prevC)
                rect(0.33f, 0.67f, menuC)
                rect(0.67f, 1f, nextC)
            }
        }
    }
}
