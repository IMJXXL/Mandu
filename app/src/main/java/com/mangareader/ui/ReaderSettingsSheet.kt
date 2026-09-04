package com.mangareader.ui

import android.content.pm.ActivityInfo
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Color
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
    /** ★ §34.5 动图专属视图下隐藏多余功能 UI（只保留三种单页式）——Reader.kt 根据 useAnimPager 透传 */
    useAnimPager: Boolean = false,
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
                    ReaderSettingsContent(config, mode, useAnimPager, onConfigChange, onModeChange, onOrientationChange)
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
    useAnimPager: Boolean = false,
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
                    0 -> ReadingModeTab(config, mode, useAnimPager, onConfigChange, onModeChange, onOrientationChange)
                    1 -> GeneralTab(config, onConfigChange)
                    2 -> FilterTab(config, onConfigChange)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsChipRow(title: String, content: @Composable FlowRowScope.() -> Unit) {
    Column {
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

@Composable
private fun CheckboxItem(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = HORIZONTAL_PAD, vertical = VERTICAL_PAD),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SliderItem(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, valueText: String, onValueChange: (Float) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val pillColor = MaterialTheme.colorScheme.surfaceContainerHigh

    Column(
        Modifier.fillMaxWidth().padding(horizontal = HORIZONTAL_PAD, vertical = 2.dp),
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
        )
    }
}

// ===== Tab 0 =====
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadingModeTab(config: ReaderConfig, mode: Mode, useAnimPager: Boolean, onConfigChange: (ReaderConfig) -> Unit,
    onModeChange: (Mode) -> Unit, onOrientationChange: (Int) -> Unit) {

    // ★ §34.5 动图专属视图下精简：只保留三种单页式（FLIP/RTL/VERT_PAGER）
    //   隐藏：条漫/WEBTOON_GAP 模式（动图视图是 ViewPager2 翻页，不支持连续滚动）
    //         双页布局、裁剪白边、自动放大横向、拆分双页、侧边留白、中间空白类型（AnimPager 不走 SSIV/不需要这些）
    // ★ §34.5 标题提示"切换模式后如遇问题请返回书架重进"（P0-2 交接 + C4 闭环后切换已即时出图，
    //   提示降级为兜底说明；NativeReaderScreen 的 DisposableEffect 依赖 mode，切 mode 会重建视图）
    SettingsChipRow("阅读模式（切换模式后如遇问题请返回书架重进）") {
        // ★ §34.5 useAnimPager=true → 限定三种单页式（动图视图不支持条漫连续滚动）
        val modeList = if (useAnimPager) {
            listOf("单页式（从左到右）" to 0, "单页式（从右到左）" to 1, "单页式（从上到下）" to 2)
        } else {
            listOf("单页式（从左到右）" to 0, "单页式（从右到左）" to 1,
                "单页式（从上到下）" to 2, "条漫" to 3, "条漫（页间有空隙）" to 4)
        }
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
    // ★ §34.5 动图视图下：显示提示而非各项设置
    if (useAnimPager) {
        // 动图专属视图处于动图模式 —— 提示用户已在该模式中
        Text("当前为动图专属视图：仅支持单页式翻页阅读。",
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp))
    } else {
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
        CheckboxItem("拆分双页（重进漫画生效）", config.doublePageSplit) { onConfigChange(config.copy(doublePageSplit = it)) }
        if (config.doublePageSplit) {
            CheckboxItem("反转双页", config.doublePageReverse) { onConfigChange(config.copy(doublePageReverse = it)) }
        }
        // CheckboxItem("翻页动画", config.isFlipAnimation) { onConfigChange(config.copy(isFlipAnimation = it)) }
        Text("以下功能设置后，需重新进入漫画生效", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
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
    }
    // ★ §34.5 屏幕方向两项在所有 mode 下都保留（不论动图与否）
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
    CheckboxItem("始终显示章节间的过渡页面", config.chapterTransition) { onConfigChange(config.copy(chapterTransition = it)) }
    // ★ N1：无缝续读（默认关；仅同本分卷；开启时读到卷末静默进入下一卷，无过渡页无确认弹窗）
    CheckboxItem("无缝续读下一卷（读到卷末自动进入同书下一卷）", config.seamlessNextVolume) { onConfigChange(config.copy(seamlessNextVolume = it)) }
    CheckboxItem("翻页时闪烁（开启后可降低墨水屏残影）", config.isFlipFlashEnabled) { onConfigChange(config.copy(isFlipFlashEnabled = it)) }
    if (config.isFlipFlashEnabled) {
        SliderItem("持续时间", config.flipFlashDuration.toFloat(), 50f..1500f, 14, "${config.flipFlashDuration}ms") {
            onConfigChange(config.copy(flipFlashDuration = it.toInt()))
        }
        SliderItem("间隔页数", config.flipFlashInterval.toFloat(), 1f..10f, 9, "${config.flipFlashInterval}页") {
            onConfigChange(config.copy(flipFlashInterval = it.toInt()))
        }
        SettingsChipRow("闪烁颜色") {
            listOf("白色" to 0, "黑色" to 1).forEach { (label, value) ->
                FilterChip(
                    selected = value == config.flipFlashColor,
                    onClick = { onConfigChange(config.copy(flipFlashColor = value)) },
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
}
