package com.mangareader.ui

import android.content.pm.ActivityInfo
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.data.BookCrop
import com.mangareader.data.ReaderConfig
import kotlinx.coroutines.launch

private const val ANIM_DURATION = 300
private val HORIZONTAL_PAD = 24.dp
private val VERTICAL_PAD = 10.dp
private val SPACING_SMALL = 8.dp
private val SHEET_SHAPE = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
private val SIDE_PANEL_SHAPE = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    config: ReaderConfig,
    mode: Mode,
    bookId: Long,

    onConfigChange: (ReaderConfig) -> Unit,
    onModeChange: (Mode) -> Unit,
    onOrientationChange: (Int) -> Unit,
    /** ★ 固定四边裁剪（仅本书功能）：值由 Reader.kt 持有并持久化到 BookCropStore */
    fixedCrop: BookCrop = BookCrop.OFF,
    onCropChange: (BookCrop) -> Unit = {},
    onDismiss: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val sheetCtx = androidx.compose.ui.platform.LocalContext.current

    // Box overlay — no Dialog, no scrim, reader stays visible underneath
    // ★ 1001 UI 审查 Phase3：横屏高度稀缺 → 面板改右侧滑出全高抽屉；竖屏保持底部面板原样
    val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = if (visible) 0.3f else 0f))
            .clickable(
                interactionSource = null,
                indication = null,
                onClick = { visible = false; onDismiss() }
            ),
        contentAlignment = if (landscape) Alignment.CenterEnd else Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = if (landscape) slideInHorizontally(tween(ANIM_DURATION)) { it } else slideInVertically(tween(ANIM_DURATION)) { it },
            exit = if (landscape) slideOutHorizontally(tween(ANIM_DURATION)) { it } else slideOutVertically(tween(ANIM_DURATION)) { it }
        ) {
            Surface(
                modifier = if (landscape) {
                    Modifier
                        .width(440.dp)
                        .fillMaxHeight()
                        .clickable(interactionSource = null, indication = null, onClick = {})
                } else {
                    Modifier
                        .widthIn(max = 520.dp)
                        .fillMaxWidth()
                        .heightIn(max = with(LocalDensity.current) {
                            (LocalConfiguration.current.screenHeightDp * 0.60f).dp
                        })
                        .clickable(interactionSource = null, indication = null, onClick = {})
                },
                shape = if (landscape) SIDE_PANEL_SHAPE else SHEET_SHAPE,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                content = {
                    ReaderSettingsContent(config, mode, bookId, onConfigChange, onModeChange, onOrientationChange, fixedCrop, onCropChange)
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
    bookId: Long,

    onConfigChange: (ReaderConfig) -> Unit,
    onModeChange: (Mode) -> Unit,
    onOrientationChange: (Int) -> Unit,
    fixedCrop: BookCrop,
    onCropChange: (BookCrop) -> Unit,
) {
    val pagerState = rememberPagerState { 3 }
    val tabs = listOf("阅读模式", "常规", "滤镜")
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth().animateContentSize()) {
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
                    0 -> ReadingModeTab(config, mode, bookId, onConfigChange, onModeChange, onOrientationChange, fixedCrop, onCropChange)
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
private fun CheckboxItem(label: String, checked: Boolean, enabled: Boolean = true, info: String? = null, onCheckedChange: (Boolean) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
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
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (info != null) {
            Icon(
                Icons.Filled.Info,
                contentDescription = "说明",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp).clickable {
                    android.widget.Toast.makeText(ctx, info, android.widget.Toast.LENGTH_SHORT).show()
                }
            )
        }
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
private fun ReadingModeTab(config: ReaderConfig, mode: Mode, bookId: Long, onConfigChange: (ReaderConfig) -> Unit,
    onModeChange: (Mode) -> Unit, onOrientationChange: (Int) -> Unit,
    fixedCrop: BookCrop, onCropChange: (BookCrop) -> Unit) {

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
        // ★ 2026-10-05 工单：横屏自动双页生效时面板要显示实际所见——存储值仍是"单页"
        //   （回竖屏恢复），选中态按有效布局派生；点击仍写存储值
        val isLandscapeNow = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val autoDoubleActive = config.pageLayout == ReaderConfig.PageLayout.SINGLE &&
            config.autoDoubleLandscape && isLandscapeNow
        val effectiveLayout = if (autoDoubleActive) ReaderConfig.PageLayout.DOUBLE else config.pageLayout
            listOf("单页" to ReaderConfig.PageLayout.SINGLE, "双页" to ReaderConfig.PageLayout.DOUBLE,
                "双页跳过首页" to ReaderConfig.PageLayout.DOUBLE_SHIFTED).forEach { (label, value) ->
                FilterChip(
                    selected = value == effectiveLayout,
                    onClick = {
                        if (value == ReaderConfig.PageLayout.SINGLE && autoDoubleActive) {
                            // 横屏自动双页生效中点"单页"=此刻就要单页：不同时关掉自动开关，
                            // 点击等于没点（布局派生立刻又变回双页）
                            onConfigChange(config.copy(pageLayout = ReaderConfig.PageLayout.SINGLE, autoDoubleLandscape = false))
                        } else {
                            onConfigChange(config.copy(pageLayout = value))
                        }
                    },
                    label = { Text(label, fontSize = 13.sp) },
                )
            }
        }
        if (config.pageLayout == ReaderConfig.PageLayout.SINGLE && config.autoDoubleLandscape) {
            Text(
                "横屏时这本会自动以双页显示（回竖屏恢复单页）；开关对没单独调过的书全局生效",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
        }
        Text("切换双页模式时可能会短暂卡顿，滑动一下即可恢复", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        // ★ 2026-10-05 工单：想要"左/右为起始页"——日漫右开（首页独占右侧）＝"双页跳过首页"；
        //   每对页面的左右先后顺序＝"反转双页"
        if (config.pageLayout != ReaderConfig.PageLayout.SINGLE) {
            Text(
                "起始页怎么选：首页独占一侧（日漫右开）＝上方选「双页跳过首页」；每对页面左右互换＝「反转双页」",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
        }
        if (config.pageLayout != ReaderConfig.PageLayout.SINGLE) {
            // ★ 智能配对提示（2026-10-05）：双页布局下宽图/长图不再硬拼，开关在常规页
            Text(
                "已启用智能配对：横宽跨页图与超长条图单独显示不参与合并（常规页可关）",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
        }
        CheckboxItem("裁剪白边", config.isTrimWhiteBorder) { onConfigChange(config.copy(isTrimWhiteBorder = it)) }
        if (config.isTrimWhiteBorder) {
            // 检测阈值：像素平均亮度超过此值视为"白色"，越小越激进
            SliderItem("检测阈值（默认 220）", config.trimThreshold.toFloat(), 180f..250f, 70, "${config.trimThreshold}") {
                onConfigChange(config.copy(trimThreshold = it.toInt()))
            }
            // 白色比例：一行/列中白色像素占比达到此值才算"白边"，越小越容易判定
            SliderItem("白色比例（默认90%）", config.trimWhiteRatio, 0.50f..0.95f, 45, "${(config.trimWhiteRatio * 100).toInt()}%") {
                onConfigChange(config.copy(trimWhiteRatio = it))
            }
        }

        // ★ 固定裁剪（仅本书功能 2026-10-04）：给这本书记四边裁剪比例，解码时裁掉再铺满——
        //   裁后画面无"放大态"，翻页手势照常。bookId<=0（未入库临时打开）不显示也不持久化。
        if (bookId > 0) {
            CheckboxItem("固定裁剪（本书）", fixedCrop.active) { on ->
                onCropChange(if (on) BookCrop(0.05f, 0.05f, 0.05f, 0.05f) else BookCrop.OFF)
            }
            if (fixedCrop.active) {
                SliderItem("裁上边", fixedCrop.top, 0f..BookCrop.MAX, 39, "${(fixedCrop.top * 100).toInt()}%") {
                    onCropChange(fixedCrop.copy(top = it))
                }
                SliderItem("裁下边", fixedCrop.bottom, 0f..BookCrop.MAX, 39, "${(fixedCrop.bottom * 100).toInt()}%") {
                    onCropChange(fixedCrop.copy(bottom = it))
                }
                SliderItem("裁左边", fixedCrop.left, 0f..BookCrop.MAX, 39, "${(fixedCrop.left * 100).toInt()}%") {
                    onCropChange(fixedCrop.copy(left = it))
                }
                SliderItem("裁右边", fixedCrop.right, 0f..BookCrop.MAX, 39, "${(fixedCrop.right * 100).toInt()}%") {
                    onCropChange(fixedCrop.copy(right = it))
                }
                Text(
                    "拖滑条当前页实时看效果，整本书记住，下次进书仍生效（与阅读偏好开关无关）。\n" +
                        "与裁白边可叠加（先固定裁、再自动裁白边）；条漫模式只裁左右；动图页不裁。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 11.sp,
                    modifier = Modifier.padding(start = HORIZONTAL_PAD, end = HORIZONTAL_PAD, bottom = VERTICAL_PAD),
                )
                TextButton(onClick = { onCropChange(BookCrop.OFF) }, modifier = Modifier.padding(start = 12.dp)) {
                    Text("重置裁剪", fontSize = 13.sp)
                }
            }
        }

        CheckboxItem("自动放大横向图片（宽图自动贴满高度；仅完整显示/铺满档生效）", config.landscapeZoom) { onConfigChange(config.copy(landscapeZoom = it)) }
        CheckboxItem("双击放大（关闭后快速连点=两次点击翻页）", config.isDoubleTapZoom) { onConfigChange(config.copy(isDoubleTapZoom = it)) }
        GroupTitle("翻页")
        CheckboxItem("翻页动画（点击翻页过渡）", config.isFlipAnimation) { onConfigChange(config.copy(isFlipAnimation = it)) }
        if (config.isFlipAnimation) {
            CheckboxItem("仿真翻页Beta（仅静图左右翻页生效，拆分双页/动图等模式下失效）", config.isPageCurlAnimation) { onConfigChange(config.copy(isPageCurlAnimation = it)) }
            if (config.isPageCurlAnimation) {
                val sheetCtx = androidx.compose.ui.platform.LocalContext.current
                var curlSoundOn by remember {
                    mutableStateOf(sheetCtx.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE).getBoolean("page_curl_sound", true))
                }
                CheckboxItem("翻页纸声（落页沙沙声，可关）", curlSoundOn) {
                    curlSoundOn = it
                    sheetCtx.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE).edit().putBoolean("page_curl_sound", it).apply()
                }
            }
            if (config.isPageCurlAnimation) {
                CheckboxItem("翻页仅限画面区域（画面外的留边不参与翻页动画）", config.isCurlContentAreaOnly) { onConfigChange(config.copy(isCurlContentAreaOnly = it)) }
                SliderItem("背面透出程度（翻页背面内容的可见强度，默认10%；双页透出下一页，单页为背面镜像明暗）", config.curlBackContentAlpha, 0f..1f, 19, "${(config.curlBackContentAlpha * 100).toInt()}%") {
                    onConfigChange(config.copy(curlBackContentAlpha = it))
                }
                SliderItem("翻页阴影浓度（0=关闭，默认20%）", config.curlShadowAlpha, 0f..0.5f, 9, "${(config.curlShadowAlpha * 100).toInt()}%") {
                    onConfigChange(config.copy(curlShadowAlpha = it))
                }
                SliderItem("书脊阴影浓度（双页书脊装订沟，0=关闭，默认9%）", config.spineShadowAlpha, 0f..0.3f, 29, "${(config.spineShadowAlpha * 100).toInt()}%") {
                    onConfigChange(config.copy(spineShadowAlpha = it))
                }
            }
        }
        GroupTitle("画面")
        // ★ 画面缩放五档（2026-10-05 重做，替代二选一）：ScalePlan 唯一公式出处；
        //   条漫恒贴宽；仿真/无平移视图运行时收敛（贴高/智能→铺满防不可找回裁切、双页→铺满）
        SettingsChipRow("画面缩放") {
            listOf("完整显示" to 0, "铺满屏幕" to 1, "贴满高度" to 2, "原始尺寸" to 3, "智能适应" to 4).forEach { (label, value) ->
                FilterChip(
                    selected = value == config.imageScaleMode,
                    onClick = { onConfigChange(config.copy(imageScaleMode = value)) },
                    label = { Text(label, fontSize = 13.sp) },
                )
            }
        }
        Text(
            when (config.imageScaleMode) {
                0 -> "整页缩在屏幕里，四边不裁切，可能有黑边"
                2 -> "高度贴满，宽图左右超出（翻页模式可拖动看；仿真/条漫不适用）"
                3 -> "1:1 像素显示，大图超出部分靠拖动（仿真不可拖）"
                4 -> "竖版页贴宽、横版页贴高，两个方向都尽量占满"
                else -> "放大到没有黑边，上下没看全的地方划着看（条漫模式恒为贴宽）"
            },
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        SettingsChipRow("缩放起始位置") {
            listOf("自动" to 0, "左" to 1, "中" to 2, "右" to 3).forEach { (label, value) ->
                FilterChip(
                    selected = value == config.zoomStart,
                    onClick = { onConfigChange(config.copy(zoomStart = value)) },
                    label = { Text(label, fontSize = 13.sp) },
                )
            }
        }
        Text(
            "横向有留白/宽图放大后对齐哪一侧；“自动”跟随阅读方向（日漫右开→右缘）",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        CheckboxItem("拆分双页（跨页图拆成左右两页，翻页/条漫均生效）", config.doublePageSplit) { onConfigChange(config.copy(doublePageSplit = it)) }
        if (config.pageLayout != ReaderConfig.PageLayout.SINGLE) {
            // ★ 2026-10-05：反转双页不再藏在"拆分双页"下——合并双页也需要左右顺序镜像（工单：起始页左右）
            CheckboxItem("反转双页（每对页面左右顺序镜像）", config.doublePageReverse) { onConfigChange(config.copy(doublePageReverse = it)) }
        }
        SliderItem("侧边留白", config.imageMarginH.toFloat(), -20f..50f, 70, "${config.imageMarginH}dp") {
            onConfigChange(config.copy(imageMarginH = it.toInt()))
        }
        SettingsChipRow("双页中缝") {
            listOf("贴合" to 0, "窄缝" to 1, "中缝" to 2, "宽缝" to 3).forEach { (label, value) ->
                FilterChip(
                    selected = value == config.centerGapType,
                    // 仿真翻页双页的中缝写死贴合（拟真书脊靠书脊阴影过渡，无物理白缝），置灰防误配
                    enabled = !config.isPageCurlAnimation,
                    onClick = { onConfigChange(config.copy(centerGapType = value)) },
                    label = { Text(label, fontSize = 13.sp) },
                )
            }
        }
        if (config.isPageCurlAnimation) {
            Text(
                "仿真翻页下中缝不生效：书页在书脊处贴合，由书脊阴影过渡",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 11.sp,
                modifier = Modifier.padding(start = 4.dp)
            )
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
    CheckboxItem("无缝续读下一卷", config.seamlessNextVolume, info = "读到卷末自动进入同书下一卷，无过渡页、无确认弹窗") { onConfigChange(config.copy(seamlessNextVolume = it)) }
    // ★ 翻页闪烁固定全局（2026-10-04）：书内开关直接写全局键，对所有书生效
    CheckboxItem(
        "翻页时闪烁",
        config.isFlipFlashEnabled,
        info = "全局生效：对所有漫画生效",
    ) { onConfigChange(config.copy(isFlipFlashEnabled = it)) }
    if (config.isFlipFlashEnabled) {
        SliderItem("持续时间", config.flipFlashDuration.toFloat(), 50f..1500f, 14, "${config.flipFlashDuration}ms") {
            onConfigChange(config.copy(flipFlashDuration = it.toInt()))
        }
        SliderItem("间隔页数", config.flipFlashInterval.toFloat(), 1f..10f, 9, "${config.flipFlashInterval}页") {
            onConfigChange(config.copy(flipFlashInterval = it.toInt()))
        }
        SettingsChipRow("闪烁颜色") {
            listOf("白色" to 0, "黑色" to 1, "黑白两段" to 2).forEach { (label, value) ->
                FilterChip(
                    selected = value == config.flipFlashColor,
                    onClick = { onConfigChange(config.copy(flipFlashColor = value)) },
                    label = { Text(label, fontSize = 13.sp) },
                )
            }
        }
    }
    CheckboxItem("自动进入条漫模式", config.autoWebtoon) { onConfigChange(config.copy(autoWebtoon = it)) }
    // ★ 横屏自动双页：始终显示（隐藏条件曾让双页布局用户找不到开关）；全局语义
    CheckboxItem("横屏时自动使用双页（仅页面布局为单页时生效）", config.autoDoubleLandscape) { onConfigChange(config.copy(autoDoubleLandscape = it)) }
    // ★ 2026-10-05 横屏自动条漫：与横屏自动双页互斥的另一半（工单：横屏铺满该有整本连续滚动）
    if (!config.autoDoubleLandscape) {
        CheckboxItem("横屏时自动使用条漫（翻页模式连续滚动）", config.landscapeAutoWebtoon) { onConfigChange(config.copy(landscapeAutoWebtoon = it)) }
    }
    // ★ 双页智能配对（2026-10-05 重做）：双页布局或横屏自动双页时展示；全局语义
    CheckboxItem(
        "双页智能配对（宽图/长图单独显示）",
        config.doubleMergeSmart,
        info = "横宽跨页图（宽>高）与超长条图不参与两两合并、单独占一屏；两页宽高比差过大也不配。页面尺寸自动扫描，远程书（尺寸未知）回退为全部合并",
    ) { onConfigChange(config.copy(doubleMergeSmart = it)) }
    AmbienceSection(config, onConfigChange)
}

/** 氛围音：预设雨声 + 用户自选音频（SAF 导入拷入 filesDir/ambience/）。
 *  导入项右上角移除角标只删应用内副本，用户本地原文件不动。
 *  选择模型：""=关 "rain"=预设 其余=导入文件名 */
@Composable
private fun AmbienceSection(config: ReaderConfig, onConfigChange: (ReaderConfig) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var imported by remember { mutableStateOf(listImportedAmbience(ctx)) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            val name = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                importAmbienceFile(ctx, uri)
            }
            if (name != null) {
                imported = listImportedAmbience(ctx)
                onConfigChange(config.copy(ambienceSound = name))
            }
        }
    }

    SettingsChipRow("氛围音") {
        // ★ 预置与 AmbiencePlayer.PRESETS 同源。注意配对序＝(值, 中文标签)——此前按
        //   (label, value) 解构把英文值显示出来、还把中文标签当值写入（工单实锤：
        //   芯片显示 rain/waves、选什么声音都一样）。显示标签、写值，两者分开。
        (listOf("" to "关闭") + com.mangareader.sound.AmbiencePlayer.PRESETS).forEach { (value, label) ->
            FilterChip(
                selected = config.ambienceSound == value,
                onClick = { onConfigChange(config.copy(ambienceSound = value)) },
                label = { Text(label, fontSize = 13.sp) },
            )
        }
        imported.forEach { name ->
            // 导入项右上角移除角标：深色圆盘 + 细环 + 矢量叉，与芯片底色拉开层次
            Box {
                FilterChip(
                    selected = config.ambienceSound == name,
                    onClick = { onConfigChange(config.copy(ambienceSound = name)) },
                    label = { Text(name.substringBeforeLast('.').take(10), fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp)) },
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = (-2).dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant)
                        .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
                        .clickable { pendingDelete = name },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "移除",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.surface,
                    )
                }
            }
        }
        FilterChip(
            selected = false,
            onClick = { importLauncher.launch(arrayOf("audio/*")) },
            label = { Text("+ 导入", fontSize = 13.sp) },
        )
    }
    if (config.ambienceSound.isNotEmpty()) {
        SliderItem("氛围音量（默认60%）", config.ambienceVolume.toFloat(), 0f..100f, 99, "${config.ambienceVolume}%") {
            onConfigChange(config.copy(ambienceVolume = it.toInt()))
        }
    }
    pendingDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("移除氛围音") },
            text = { Text("将「${name.substringBeforeLast('.')}」从氛围音列表移除？\n只移除应用内的副本，你本地原来的音频文件不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    val del = name
                    pendingDelete = null
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            java.io.File(java.io.File(ctx.filesDir, "ambience"), del).delete()
                        }
                        imported = listImportedAmbience(ctx)
                        if (config.ambienceSound == del) {
                            onConfigChange(config.copy(ambienceSound = ""))
                        }
                    }
                }) { Text("移除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

internal fun listImportedAmbience(ctx: android.content.Context): List<String> =
    java.io.File(ctx.filesDir, "ambience").listFiles()?.map { it.name }?.sorted() ?: emptyList()

internal fun importAmbienceFile(ctx: android.content.Context, uri: android.net.Uri): String? = runCatching {
    val displayName = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "import"
    var name = displayName.replace(Regex("[\\\\/:*?\"<>|\r\n]"), "_").take(80).ifEmpty { "import" }
    val dir = java.io.File(ctx.filesDir, "ambience").apply { mkdirs() }
    var dest = java.io.File(dir, name)
    var i = 1
    while (dest.exists()) {
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        dest = java.io.File(dir, if (ext.isEmpty()) "$base($i)" else "$base($i).$ext")
        i++
    }
    ctx.contentResolver.openInputStream(uri)?.use { ins ->
        dest.outputStream().use { ins.copyTo(it) }
    } ?: return null
    dest.name
}.getOrNull()

// ===== Tab 2 =====
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterTab(config: ReaderConfig, onConfigChange: (ReaderConfig) -> Unit) {
    CheckboxItem("灰度模式", config.isGrayscale) { onConfigChange(config.copy(isGrayscale = it)) }
    CheckboxItem("反色", config.isInvertColors) { onConfigChange(config.copy(isInvertColors = it)) }
    CheckboxItem("图片亮度", config.brightnessEnabled) {
        onConfigChange(config.copy(brightnessEnabled = it, brightness = if (it) config.brightness else 0))
    }
    if (config.brightnessEnabled) {
        SliderItem("图片亮度（默认 0）", config.brightness.toFloat(), -100f..100f, 199, "${config.brightness}") {
            onConfigChange(config.copy(brightness = it.toInt()))
        }
    }
    CheckboxItem("屏幕亮度", config.screenBrightnessEnabled) {
        onConfigChange(config.copy(screenBrightnessEnabled = it, screenBrightness = if (it) config.screenBrightness else 0))
    }
    if (config.screenBrightnessEnabled) {
        SliderItem("屏幕亮度（默认 0）", config.screenBrightness.toFloat(), -100f..100f, 199, "${config.screenBrightness}") {
            onConfigChange(config.copy(screenBrightness = it.toInt()))
        }
    }
    CheckboxItem("对比度", config.contrastEnabled) {
        onConfigChange(config.copy(contrastEnabled = it, contrast = if (it) config.contrast else 100))
    }
    if (config.contrastEnabled) {
        SliderItem("对比度（默认 100）", config.contrast.toFloat(), 0f..200f, 199, "${config.contrast}") {
            onConfigChange(config.copy(contrast = it.toInt()))
        }
    }
    CheckboxItem("伽马", config.gammaEnabled) {
        onConfigChange(config.copy(gammaEnabled = it, gamma = if (it) config.gamma else 100))
    }
    if (config.gammaEnabled) {
        SliderItem("伽马（默认 100）", config.gamma.toFloat(), 50f..150f, 99, "${config.gamma}") {
            onConfigChange(config.copy(gamma = it.toInt()))
        }
    }
    if (android.os.Build.VERSION.SDK_INT < 33) {
        Text(
            "安卓 13 以下为线性近似，细节略有丢失",
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 11.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )
    }
    CheckboxItem("纸张纹理", config.textureEnabled) { onConfigChange(config.copy(textureEnabled = it)) }
    if (config.textureEnabled) {
        SettingsChipRow("纸张类型") {
            com.mangareader.filter.PaperTexture.PRESETS.forEachIndexed { index, (_, label) ->
                FilterChip(
                    selected = index == config.texturePresetId,
                    onClick = { onConfigChange(config.copy(texturePresetId = index)) },
                    label = { Text(label, fontSize = 13.sp) },
                )
            }
        }
        // 纹理烘焙进页面位图（纸随页翻动/缩放、只作用于实际画面，墨色纯黑），浓度=纸纹深浅
        SliderItem("纸感浓度（默认100%=真实纸感）", config.textureConcentration.toFloat(), 0f..100f, 99, "${config.textureConcentration}%") {
            onConfigChange(config.copy(textureConcentration = it.toInt()))
        }
        SettingsChipRow("生效范围") {
            listOf("仅图片" to 0, "全屏幕" to 1).forEach { (label, value) ->
                FilterChip(
                    selected = value == config.textureScope,
                    onClick = { onConfigChange(config.copy(textureScope = value)) },
                    label = { Text(label, fontSize = 13.sp) },
                )
            }
        }
        Text(
            if (config.textureScope == 1)
                "全屏幕：画面与页面外的留边背景都铺纸纹；背景纸纹固定不随页动"
            else
                "仅图片：纸纹随页面翻动缩放，只作用于画面本身；动图页与瓦片模式不生效",
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 11.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )
    }
    if (config.isEinkMode) {
        CheckboxItem("灰阶抖动（让墨水屏显示更多灰阶层次）", config.isEinkDither) { onConfigChange(config.copy(isEinkDither = it)) }
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
            SliderItem("白底保护", config.einkEndpointPassthrough.toFloat(), 0f..8f, 7, "档位 ${config.einkEndpointPassthrough}") {
                onConfigChange(config.copy(einkEndpointPassthrough = it.toInt()))
            }
            SettingsChipRow("灰阶档位（数字越大过渡越平滑）") {
                listOf("16（默认）" to 16, "32" to 32, "64" to 64).forEach { (label, value) ->
                    FilterChip(
                        selected = value == config.einkDitherLevels,
                        onClick = { onConfigChange(config.copy(einkDitherLevels = value)) },
                        label = { Text(label, fontSize = 13.sp) },
                    )
                }
            }
            Text(
                "把画面抖动出更多灰阶层次，纯白/纯黑不参与抖动。建议在系统里把本应用的刷新模式设为标准或质量，快速模式下效果不明显。",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 11.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
            )
        }
    }
}
