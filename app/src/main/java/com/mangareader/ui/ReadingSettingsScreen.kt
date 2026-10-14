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

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ReadingSettingsScreen(onBack: () -> Unit, onOpenKeyMap: (() -> Unit)? = null, onOpenPrefs: (() -> Unit)? = null, onOpenGroupSort: (() -> Unit)? = null) {
    BackHandler { onBack() }
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("manga_reader", Context.MODE_PRIVATE) }
    // ★ load 零写盘（评审硬不变量）：readBackground 补种已移入 ReaderPrefMigration
    var config by remember { mutableStateOf(com.mangareader.data.ReaderConfig.load(ctx)) }
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
                .wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = CONTENT_MAX_WIDTH_DP)
                .padding(16.dp)
        ) {

            if (onOpenPrefs != null) item {
                SectionCard("作用域") {
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenPrefs() }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("阅读偏好", fontSize = 16.sp)
                            Text(
                                "每项都能直接改全局默认值，也能切成只对一本书生效",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("›", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (onOpenGroupSort != null) item {
                SectionCard("排序") {
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenGroupSort() }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("分组排序", fontSize = 16.sp)
                            Text(
                                "按关键词分组排序章节，番外篇不再跑到正篇上面",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("›", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item {
                SectionCard("显示") {
                    val bgOptions = listOf(
                        "默认" to com.mangareader.data.ReaderConfig.ReadBackground.WHITE,
                        "纯白" to com.mangareader.data.ReaderConfig.ReadBackground.PURE_WHITE,
                        "纯黑" to com.mangareader.data.ReaderConfig.ReadBackground.DARK,
                    )
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        bgOptions.forEach { (label, bgEnum) ->
                            val bgColor = com.mangareader.data.mapReadBackgroundToColor(bgEnum)
                            val isSelected = config.readBackground == bgEnum
                            Surface(
                                modifier = Modifier.weight(1f).clickable {
                                    config = config.copy(readBackground = bgEnum)
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

                    // ★ 默认阅读模式已并入 设置 › 阅读设置 › 阅读偏好 ›「阅读模式」（2026-10-04，
                    //   避免同一设置两个入口；偏好页里还能为它选全局/单本作用域）

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

                    // ★ 按键自定义入口（2026-10-03）：蓝牙遥控器/翻页器/键盘映射管理页
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 10.dp).clickable { onOpenKeyMap?.invoke() },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("按键自定义（遥控器/键盘）", fontSize = 16.sp)
                            Text("蓝牙遥控器、翻页器、蓝牙键盘的按键功能映射，支持按键码自动侦测", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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


                    var hapticFeedback by remember(resetTrigger) { mutableStateOf(prefs.getBoolean("progress_haptic_feedback", false)) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("进度条触感", fontSize = 16.sp)
                            Text("拖动进度条时每经过一页振动反馈，需系统设置中开启振动", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = hapticFeedback, onCheckedChange = { hapticFeedback = it; prefs.edit().putBoolean("progress_haptic_feedback", it).apply() })
                    }

                    var hapticIntensity by remember(resetTrigger) { mutableStateOf(prefs.getInt("haptic_intensity", 0).coerceIn(0, 10)) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("震动强度", fontSize = 16.sp, color = if (hapticFeedback) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f))
                            Text("0=系统默认，1-10=增益振动（需设备支持）", fontSize = 12.sp, color = if (hapticFeedback) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f))
                        }
                        Text(if (hapticIntensity == 0) "默认" else "$hapticIntensity", fontSize = 14.sp, color = if (hapticFeedback) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f))
                    }
                    Slider(
                        value = hapticIntensity.toFloat(),
                        onValueChange = { hapticIntensity = Math.round(it).toInt().coerceIn(0, 10) },
                        onValueChangeFinished = { prefs.edit().putInt("haptic_intensity", hapticIntensity).apply() },
                        valueRange = 0f..10f,
                        steps = 9,
                        enabled = hapticFeedback,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                    )
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

                    // ★ 判定档位（2026-10-05，用户"判定太容易触发"）：按高/宽比例分三档——
                    //   宽松>3（原行为）、标准>4.5、严格>6.5，逐档要求更长的图才切，普通长页不再误切
                    val splitDisabled = !config.tallSplitEnabled
                    Text("分割判定", fontSize = 16.sp)
                    Text("页高超过页宽多少倍才切割：宽松最易触发，严格只拆真正的超长条漫", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth().alpha(if (splitDisabled) 0.4f else 1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("宽松（高>宽3倍）" to 0, "标准（高>宽4.5倍）" to 1, "严格（高>宽6.5倍）" to 2).forEach { (label, v) ->
                            Surface(
                                modifier = Modifier.weight(1f).clickable { if (!splitDisabled) { config = config.copy(tallSplitSensitivity = v); config.save(ctx) } },
                                shape = MaterialTheme.shapes.medium,
                                color = if (config.tallSplitSensitivity == v) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                tonalElevation = if (config.tallSplitSensitivity == v) 4.dp else 0.dp,
                            ) {
                                Text(label, fontSize = 12.sp, modifier = Modifier.padding(10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = if (config.tallSplitSensitivity == v) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }

                    // ★ 平板条漫页宽滑条：50%~100% 档位（5% 一档），默认 100=铺满原状；仅平板生效
                    var webtoonCapPct by remember { mutableStateOf(ctx.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE).getInt("webtoon_tablet_cap_pct", 100)) }
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("条漫页宽（平板）", fontSize = 16.sp)
                            Text(if (webtoonCapPct >= 100) "铺满" else "$webtoonCapPct%", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        Text("限制平板上条漫画面占屏幕宽度的最大比例：100% 即铺满全屏（原样）；调窄后画面居中、两侧留背景，放大更轻、更清晰。手机不受影响，重新进入阅读后生效", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Slider(
                            value = webtoonCapPct.toFloat(),
                            onValueChange = { v -> webtoonCapPct = (v / 5f).toInt() * 5 },
                            onValueChangeFinished = {
                                ctx.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE).edit().putInt("webtoon_tablet_cap_pct", webtoonCapPct).apply()
                            },
                            valueRange = 50f..100f,
                            steps = 9,
                        )
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
                    // ★ 恢复默认前自动快照（可回滚）+ 清作用域注册表与总开关（三层模型纳管）
                    com.mangareader.data.ReaderPrefMigration.snapshotNow(ctx)
                    prefs.edit().apply {
                        remove("pref_center_scope_reg")
                        remove("pref_center_book_override_enabled")
                        remove("default_read_mode")
                        remove("webtoon_tablet_cap_pct")
                        remove("page_curl_sound")
                        remove("reader_volume_keys")
                        remove("reader_volume_keys_inverted")
                        remove("long_press_save")
                        remove("bookmark_note_enabled")
                        remove("home_continue_read")
                        remove("inbook_continue_read")
                        remove("webtoon_tap_scroll_ratio")
                        remove("progress_haptic_feedback")
                        remove("haptic_intensity")
                        // 画质增强（sr 模块）全套：全局键 + sr_book_<id>_* 单作品覆盖 + 引擎/降噪/黑白等
                        // （超分设置此前一直游离在恢复默认之外，2026-10 NPU 引入时一并纳管）
                        prefs.all.keys.filter { it.startsWith("sr_") }.forEach { remove(it) }
                        prefs.all.keys.filter { it.startsWith("panelGuide") }.forEach { remove(it) }
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

