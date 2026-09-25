package com.mangareader.ui

import android.content.pm.ActivityInfo
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.data.ReaderConfig
import kotlinx.coroutines.launch

private const val ANIM_DURATION = 300
private val HORIZONTAL_PAD = 24.dp
private val VERTICAL_PAD = 10.dp
private val SPACING_SMALL = 8.dp
private val SHEET_SHAPE = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    config: ReaderConfig,
    mode: Mode,
    bookId: Long,

    onConfigChange: (ReaderConfig) -> Unit,
    onModeChange: (Mode) -> Unit,
    onOrientationChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    // Box overlay — no Dialog, no scrim, reader stays visible underneath
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = if (visible) 0.3f else 0f))
            .clickable(
                interactionSource = null,
                indication = null,
                onClick = { visible = false; onDismiss() }
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(tween(ANIM_DURATION)) { it },
            exit = slideOutVertically(tween(ANIM_DURATION)) { it }
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .heightIn(max = with(LocalDensity.current) {
                        (LocalConfiguration.current.screenHeightDp * 0.60f).dp
                    })
                    .clickable(interactionSource = null, indication = null, onClick = {}),
                shape = SHEET_SHAPE,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                content = {
                    ReaderSettingsContent(config, mode, onConfigChange, onModeChange, onOrientationChange)
                }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsContent(
    config: ReaderConfig,
    mode: Mode,

    onConfigChange: (ReaderConfig) -> Unit,
    onModeChange: (Mode) -> Unit,
    onOrientationChange: (Int) -> Unit,
) {
    val pagerState = rememberPagerState { 3 }
    val tabs = listOf("阅读模式", "常规", "滤镜")
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth()) {
        PrimaryTabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.primary,
            divider = {},
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(title, fontSize = 14.sp) },
                    unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        HorizontalDivider()

        HorizontalPager(
            state = pagerState,
            verticalAlignment = Alignment.Top,
            modifier = Modifier.weight(1f)
        ) { page ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = VERTICAL_PAD)
                    .verticalScroll(rememberScrollState())
            ) {
                when (page) {
                    0 -> ReadingModeTab(config, mode, onConfigChange, onModeChange, onOrientationChange)
                    1 -> GeneralTab(config, onConfigChange)
                    2 -> FilterTab(config, onConfigChange)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsChipRow(title: String, content: @Composable FlowRowScope.() -> Unit) {    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = HORIZONTAL_PAD, vertical = VERTICAL_PAD)
        )
        FlowRow(
            modifier = Modifier.padding(start = HORIZONTAL_PAD, top = 0.dp, end = HORIZONTAL_PAD, bottom = VERTICAL_PAD),
            horizontalArrangement = Arrangement.spacedBy(SPACING_SMALL),
            verticalArrangement = Arrangement.spacedBy(SPACING_SMALL),
            content = content
        )
    }
}

// 阅读模式 tab 内的小节标题：用于把翻页/画面相关设置分成可视的组（无 chip 内容）
@Composable
private fun GroupTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = HORIZONTAL_PAD, vertical = VERTICAL_PAD)
    )
}

@Composable
private fun CheckboxItem(label: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = HORIZONTAL_PAD, vertical = VERTICAL_PAD),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SliderItem(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, valueText: String, enabled: Boolean = true, onValueChange: (Float) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val pillColor = MaterialTheme.colorScheme.surfaceContainerHigh

    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = HORIZONTAL_PAD, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SPACING_SMALL)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Surface(
                modifier = Modifier.padding(start = 4.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = pillColor,
            ) {
                Box(Modifier.padding(6.dp, 1.dp), contentAlignment = Alignment.Center) {
                    Text(valueText, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Slider(
            value = value,
            onValueChange = {
                onValueChange(it)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            },
            valueRange = range,
            steps = steps,
            enabled = enabled,
        )
    }
}

// ===== Tab 0 =====
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadingModeTab(config: ReaderConfig, mode: Mode, onConfigChange: (ReaderConfig) -> Unit,
    onModeChange: (Mode) -> Unit, onOrientationChange: (Int) -> Unit) {

    SettingsChipRow("阅读模式（切换模式后如遇问题请返回书架重进）") {
        val modeList = listOf("单页式（从左到右）" to 0, "单页式（从右到左）" to 1,
            "单页式（从上到下）" to 2, "条漫" to 3, "条漫（页间有空隙）" to 4)
        modeList.forEach { (label, value) ->
            FilterChip(
                selected = value == when (mode) {
                    Mode.FLIP -> 0; Mode.RTL -> 1; Mode.VERT_PAGER -> 2
                    Mode.VERT -> 3; Mode.WEBTOON_GAP -> 4; else -> 0
                },
                onClick = { when (value) {
                    0 -> onModeChange(Mode.FLIP); 1 -> onModeChange(Mode.RTL)
                    2 -> onModeChange(Mode.VERT_PAGER); 3 -> onModeChange(Mode.VERT)
                    4 -> onModeChange(Mode.WEBTOON_GAP)
                } },
                label = { Text(label, fontSize = 13.sp) },
            )
        }
    }
    SettingsChipRow("页面布局") {
            listOf("单页" to ReaderConfig.PageLayout.SINGLE, "双页" to ReaderConfig.PageLayout.DOUBLE,
                "双页跳过首页" to ReaderConfig.PageLayout.DOUBLE_SHIFTED).forEach { (label, value) ->
                FilterChip(
                    selected = value == config.pageLayout,
                    onClick = { onConfigChange(config.copy(pageLayout = value)) },
                    label = { Text(label, fontSize = 13.sp) },
                )
            }
        }
        Text("切换双页模式时可能会短暂卡顿，滑动一下即可恢复", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        CheckboxItem("裁剪白边", config.isTrimWhiteBorder) { onConfigChange(config.copy(isTrimWhiteBorder = it)) }
        if (config.isTrimWhiteBorder) {
            // 检测阈值：像素平均亮度超过此值视为"白色"，越小越激进
            SliderItem("检测阈值（默认220）", config.trimThreshold.toFloat(), 180f..250f, 70, "${config.trimThreshold}") {
                onConfigChange(config.copy(trimThreshold = it.toInt()))
            }
            // 白色比例：一行/列中白色像素占比达到此值才算"白边"，越小越容易判定
            SliderItem("白色比例（默认90%）", config.trimWhiteRatio, 0.50f..0.95f, 45, "${(config.trimWhiteRatio * 100).toInt()}%") {
                onConfigChange(config.copy(trimWhiteRatio = it))
            }
        }

        CheckboxItem("自动放大横向图片（平板上建议关闭，关闭时宽图完整显示）", config.landscapeZoom) { onConfigChange(config.copy(landscapeZoom = it)) }
        GroupTitle("翻页")
        CheckboxItem("翻页动画（点击翻页过渡）", config.isFlipAnimation) { onConfigChange(config.copy(isFlipAnimation = it)) }
        if (config.isFlipAnimation) {
            CheckboxItem("仿真翻页Beta（仅静图左右翻页生效，拆分双页/动图等模式下失效）", config.isPageCurlAnimation) { onConfigChange(config.copy(isPageCurlAnimation = it)) }
            if (config.isPageCurlAnimation) {
                CheckboxItem("翻页仅限画面区域（双页模式：画面外的留边不参与翻页动画）", config.isCurlContentAreaOnly) { onConfigChange(config.copy(isCurlContentAreaOnly = it)) }
                SliderItem("背面透出程度（翻页背面内容的可见强度，默认10%；双页透出下一页，单页为背面镜像明暗）", config.curlBackContentAlpha, 0f..1f, 19, "${(config.curlBackContentAlpha * 100).toInt()}%") {
                    onConfigChange(config.copy(curlBackContentAlpha = it))
                }
                SliderItem("翻页阴影浓度（0=关闭，默认20%）", config.curlShadowAlpha, 0f..0.5f, 9, "${(config.curlShadowAlpha * 100).toInt()}%") {
                    onConfigChange(config.copy(curlShadowAlpha = it))
                }
            }
        }
        GroupTitle("画面")
        Text("以下功能设置后，需重新进入漫画生效", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        CheckboxItem("漫画完整显示（开启后单页模式下，画面不会越界屏幕）", config.fitScreen) { onConfigChange(config.copy(fitScreen = it)) }
        CheckboxItem("拆分双页", config.doublePageSplit) { onConfigChange(config.copy(doublePageSplit = it)) }
        if (config.doublePageSplit) {
            CheckboxItem("反转双页", config.doublePageReverse) { onConfigChange(config.copy(doublePageReverse = it)) }
        }
        SliderItem("侧边留白", config.imageMarginH.toFloat(), -20f..50f, 70, "${config.imageMarginH}dp") {
            onConfigChange(config.copy(imageMarginH = it.toInt()))
        }
        SettingsChipRow("中间空白类型") {
            listOf("无" to 0, "添加到双页视图" to 1, "添加到宽屏视图" to 2, "两个都添加" to 3).forEach { (label, value) ->
                FilterChip(
                    selected = value == config.centerGapType,
                    onClick = { onConfigChange(config.copy(centerGapType = value)) },
                    label = { Text(label, fontSize = 13.sp) },
                )
            }
        }


    SettingsChipRow("屏幕方向") {
        listOf("默认" to -1, "跟随系统" to -2, "竖屏" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            "横屏" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, "锁定竖屏" to ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            "锁定横屏" to ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE).forEach { (label, value) ->
            FilterChip(
                selected = value == config.orientation,
                onClick = { onOrientationChange(value) },
                label = { Text(label, fontSize = 13.sp) },
            )
        }
    }
}

// ===== Tab 1 =====
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GeneralTab(config: ReaderConfig, onConfigChange: (ReaderConfig) -> Unit) {
    CheckboxItem("显示页码", config.isShowPageNum) { onConfigChange(config.copy(isShowPageNum = it)) }
    CheckboxItem("沉浸式模式（重进生效）", config.isImmersiveMode) { onConfigChange(config.copy(isImmersiveMode = it)) }
    CheckboxItem("保持屏幕常亮", config.isKeepScreenOn) { onConfigChange(config.copy(isKeepScreenOn = it)) }

    // ★ N1：无缝续读（默认关；仅同本分卷；开启时读到卷末静默进入下一卷，无过渡页无确认弹窗）
    CheckboxItem("无缝续读下一卷（读到卷末自动进入同书下一卷）", config.seamlessNextVolume) { onConfigChange(config.copy(seamlessNextVolume = it)) }
    // ★ 翻页闪烁「全局 > 单本」：全局接管时常规只保留一行灰置开关（子项不可改故不显示），
    //   全局未开启时为常规的单本完整设置组
    val flashGlobal = config.flipFlashGlobalActive
    CheckboxItem(
        if (flashGlobal) "翻页时闪烁（全局接管中：设置→阅读设置→墨水屏）" else "翻页时闪烁（开启后可降低墨水屏残影）",
        config.isFlipFlashEnabled,
        enabled = !flashGlobal,
    ) { onConfigChange(config.copy(isFlipFlashEnabled = it)) }
    if (config.isFlipFlashEnabled && !flashGlobal) {
        SliderItem("持续时间", config.flipFlashDuration.toFloat(), 50f..1500f, 14, "${config.flipFlashDuration}ms", enabled = !flashGlobal) {
            onConfigChange(config.copy(flipFlashDuration = it.toInt()))
        }
        SliderItem("间隔页数", config.flipFlashInterval.toFloat(), 1f..10f, 9, "${config.flipFlashInterval}页", enabled = !flashGlobal) {
            onConfigChange(config.copy(flipFlashInterval = it.toInt()))
        }
        SettingsChipRow("闪烁颜色") {
            listOf("白色" to 0, "黑色" to 1, "黑白两段" to 2).forEach { (label, value) ->
                FilterChip(
                    selected = value == config.flipFlashColor,
                    onClick = { onConfigChange(config.copy(flipFlashColor = value)) },
                    enabled = !flashGlobal,
                    label = { Text(label, fontSize = 13.sp) },
                )
            }
        }
    }
    CheckboxItem("自动进入条漫模式", config.autoWebtoon) { onConfigChange(config.copy(autoWebtoon = it)) }
}

// ===== Tab 2 =====
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterTab(config: ReaderConfig, onConfigChange: (ReaderConfig) -> Unit) {
    CheckboxItem("灰度模式", config.isGrayscale) { onConfigChange(config.copy(isGrayscale = it)) }
    CheckboxItem("反色", config.isInvertColors) { onConfigChange(config.copy(isInvertColors = it)) }
    // ★ 墨水屏灰阶抖动（256级模拟）：画面观感类设置归入滤镜 tab；总开关在阅读设置→墨水屏开启后
    //   常规 tab 不再重复出现；算法名以中文特性描述呈现，存值仍为 0/1/2/3
    CheckboxItem("灰阶抖动·256级模拟（墨水屏设备，重进生效）", config.isEinkDither) { onConfigChange(config.copy(isEinkDither = it)) }
    if (config.isEinkDither) {
        SettingsChipRow("抖动算法") {
            listOf("细腻（默认）" to 0, "胶片颗粒" to 1, "柔和" to 2, "规则纹理" to 3).forEach { (label, value) ->
                FilterChip(
                    selected = value == config.einkDitherAlgorithm,
                    onClick = { onConfigChange(config.copy(einkDitherAlgorithm = value)) },
                    label = { Text(label, fontSize = 13.sp) },
                )
            }
        }
        SettingsChipRow("灰阶档位（面板实际灰度数）") {
            listOf("16（默认）" to 16, "32" to 32, "64" to 64).forEach { (label, value) ->
                FilterChip(
                    selected = value == config.einkDitherLevels,
                    onClick = { onConfigChange(config.copy(einkDitherLevels = value)) },
                    label = { Text(label, fontSize = 13.sp) },
                )
            }
        }
        Text(
            "把画面抖动模拟出256级灰阶，纯白/纯黑不参与抖动。需在系统将本应用的刷新模式设为标准/质量（GC16）；快速/A2模式只渲染黑白，抖动会呈现为噪点。",
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 11.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )
    }
}
