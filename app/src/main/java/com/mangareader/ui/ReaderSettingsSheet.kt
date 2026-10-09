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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.ui.platform.LocalContext

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
    /** ★ 书架长按设置面板用：非 null 时底部显示「应用」「清除单本覆盖」操作栏 */
    onApply: (() -> Unit)? = null,
    onClearOverrides: (() -> Unit)? = null,
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
                    ReaderSettingsContent(config, mode, bookId, onConfigChange, onModeChange, onOrientationChange, fixedCrop, onCropChange, onApply, onClearOverrides)
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
    onApply: (() -> Unit)? = null,
    onClearOverrides: (() -> Unit)? = null,
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
        if (onApply != null || onClearOverrides != null) {
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = HORIZONTAL_PAD, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onClearOverrides != null) {
                    TextButton(
                        onClick = onClearOverrides,
                        modifier = Modifier.weight(1f),
                    ) { Text("清除单本覆盖") }
                }
                if (onApply != null) {
                    Button(
                        onClick = onApply,
                        modifier = Modifier.weight(1f),
                    ) { Text("应用") }
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
private fun CheckboxItem(label: String, checked: Boolean, enabled: Boolean = true, info: String? = null, blockedHint: String? = null, onCheckedChange: (Boolean) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.38f)
            // ★ blockedHint（2026-10-07 三态互斥）：灰显但可点——点击弹提示说明当前为什么不能开
            .clickable(enabled = enabled || blockedHint != null) {
                if (enabled) onCheckedChange(!checked)
                else blockedHint?.let {
                    android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
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
    // ★ 三态互斥（2026-10-07 用户定则）：拆分双页 与 双页合并（手动/横屏触发）互斥。
    //   拆分开 → 双页档位/横屏自动双页灰显，点击提示为什么不能开；合并态 → 拆分灰显。
    //   横屏自动双页触发时布局选择器显示"双页"勾选（档位=本书 autoDoubleLayout 记忆），
    //   用户改档位按书记住、竖屏不受影响——横屏影响双页，双页不影响横屏
    val isLandscapeNow = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val autoDoubleActive = config.pageLayout == ReaderConfig.PageLayout.SINGLE &&
        config.autoDoubleLandscape && isLandscapeNow && !config.doublePageSplit
    val effectiveLayout = if (autoDoubleActive)
        (if (config.autoDoubleLayout == 1) ReaderConfig.PageLayout.DOUBLE_SHIFTED else ReaderConfig.PageLayout.DOUBLE)
    else config.pageLayout
    val sheetCtx = androidx.compose.ui.platform.LocalContext.current
    fun blockedToast(msg: String) =
        android.widget.Toast.makeText(sheetCtx, msg, android.widget.Toast.LENGTH_SHORT).show()
    SettingsChipRow("页面布局") {
            listOf("单页" to ReaderConfig.PageLayout.SINGLE, "双页" to ReaderConfig.PageLayout.DOUBLE,
                "双页+起始页" to ReaderConfig.PageLayout.DOUBLE_SHIFTED).forEach { (label, value) ->
                // 拆分开启时双页档位灰显（单页恒可用）；横屏自动双页生效中选双页档位=改本书记忆
                val blockedBySplit = config.doublePageSplit && value != ReaderConfig.PageLayout.SINGLE
                FilterChip(
                    selected = value == effectiveLayout,
                    modifier = Modifier.alpha(if (blockedBySplit) 0.38f else 1f),
                    onClick = {
                        when {
                            blockedBySplit ->
                                blockedToast("拆分双页开启时不能使用双页合并，请先关闭拆分双页")
                            value == ReaderConfig.PageLayout.SINGLE && autoDoubleActive -> {
                                // 横屏自动双页生效中点"单页"=此刻就要单页：不同时关掉自动开关，
                                // 点击等于没点（布局派生立刻又变回双页）
                                onConfigChange(config.copy(pageLayout = ReaderConfig.PageLayout.SINGLE, autoDoubleLandscape = false))
                            }
                            autoDoubleActive -> {
                                // ★ 触发中改档位：写入本书"自动双页档位"记忆（下次横屏触发用该档位），
                                //   竖屏仍单页；同时落实互斥——合并状态下不能拆分
                                onConfigChange(config.copy(
                                    autoDoubleLayout = if (value == ReaderConfig.PageLayout.DOUBLE_SHIFTED) 1 else 0,
                                    doublePageSplit = false))
                            }
                            else -> {
                                // 单独开双页：不动横屏自动双页（双页不影响横屏）；互斥关拆分
                                onConfigChange(config.copy(pageLayout = value, doublePageSplit = false))
                            }
                        }
                    },
                    label = { Text(label, fontSize = 13.sp) },
                )
            }
        }
        if (config.pageLayout == ReaderConfig.PageLayout.SINGLE && config.autoDoubleLandscape && !config.doublePageSplit) {
            Text(
                "横屏时这本会自动以双页显示（回竖屏恢复单页）；开关对没单独调过的书全局生效" +
                    if (config.autoDoubleLayout == 1) "；当前档位：双页+起始页" else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
        }
        Text("切换双页模式时可能会短暂卡顿，滑动一下即可恢复", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        // ★ 2026-10-06 工单：起始页 2..10 可配（"跳过首页"泛化）；反转双页挪到页面布局区
        if (config.pageLayout == ReaderConfig.PageLayout.DOUBLE_SHIFTED ||
            (autoDoubleActive && config.autoDoubleLayout == 1)
        ) {
            SliderItem(
                "合并起始页", config.doublePageStart.toFloat(), 2f..10f, 8,
                "第 ${config.doublePageStart} 页起合并（前 ${config.doublePageStart - 1} 页单独显示）",
            ) { onConfigChange(config.copy(doublePageStart = it.toInt())) }
        }
        if (config.pageLayout != ReaderConfig.PageLayout.SINGLE || autoDoubleActive || config.doublePageSplit) {
            // ★ 2026-10-06 工单：反转双页归位页面布局区（原在画面区深处，与拆分无关）
            CheckboxItem(
                "反转双页（每对页面左右顺序镜像）", config.doublePageReverse,
                info = "日漫右开想从左页开始、或起始页档下想先看右半，用它互换每对页面的左右顺序",
            ) { onConfigChange(config.copy(doublePageReverse = it)) }
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
        // ★ stableBookId 哈希可负：负 id 也是入库/可定位书（仅 0=无身份），恒可配
        if (bookId != 0L) {
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

        CheckboxItem("自动放大横向图片（宽图自动贴满高度；仅适应屏幕档生效）", config.landscapeZoom) { onConfigChange(config.copy(landscapeZoom = it)) }
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
            listOf("适应屏幕" to 0, "拉伸" to 5, "适应宽度" to 1, "适应高度" to 2, "原始尺寸" to 3, "智能适应" to 4).forEach { (label, value) ->
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
        // ★ 三态互斥（2026-10-07 用户定则）：拆分优先——开启拆分=退出合并态（回单页布局、
        //   关横屏自动双页），合并状态下不能拆分；关闭拆分无副作用。拆分开关自身恒可点
        //   （若灰显会与横屏自动双页的灰显互相锁死，旧数据两态并存时谁也关不掉）
        CheckboxItem("拆分双页（跨页图拆成左右两页，翻页/条漫均生效）", config.doublePageSplit) {
            onConfigChange(
                if (it) config.copy(
                    doublePageSplit = true,
                    pageLayout = ReaderConfig.PageLayout.SINGLE,
                    autoDoubleLandscape = false,
                ) else config.copy(doublePageSplit = false)
            )
        }
        if (config.pageLayout != ReaderConfig.PageLayout.SINGLE) {
        }
        // ★ 上限 49：50% 每侧=两侧合计 100% 画面归零（用户 10-07 实测"50就没了"）；
        //   无负值——负留白是旧 dp 版语义，百分比版 0=贴边
        SliderItem("侧边留白（屏宽百分比，上限49%）", config.imageMarginH.coerceIn(0, 49).toFloat(), 0f..49f, 49, "${config.imageMarginH.coerceIn(0, 49)}%") {
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

    TapZoneSection(config = config, onConfigChange = onConfigChange)
}

// ===== Tab 1 =====
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GeneralTab(config: ReaderConfig, onConfigChange: (ReaderConfig) -> Unit) {
    CheckboxItem("显示页码", config.isShowPageNum) { onConfigChange(config.copy(isShowPageNum = it)) }
    CheckboxItem("沉浸式模式", config.isImmersiveMode) { onConfigChange(config.copy(isImmersiveMode = it)) }
    CheckboxItem("非全面屏阅读时隐藏底部导航栏", config.hideNavBarInReader) { onConfigChange(config.copy(hideNavBarInReader = it)) }
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
    SliderItem("自动翻页间隔", config.autoFlipInterval.toFloat(), 0f..30000f, 29,
        if (config.autoFlipInterval == 0) "关闭" else "${config.autoFlipInterval / 1000.0}秒") {
        onConfigChange(config.copy(autoFlipInterval = Math.round(it).toInt()))
    }
    val tapCtx = LocalContext.current
    val tapPrefs = remember { tapCtx.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE) }
    var tapScrollRatio by remember { mutableStateOf(tapPrefs.getFloat("webtoon_tap_scroll_ratio", 0.8f)) }
    SettingsChipRow("条漫点击翻页距离") {
        listOf("半屏" to 0.5f, "3/4屏" to 0.75f, "默认" to 0.8f, "整屏" to 1.0f).forEach { (label, value) ->
            FilterChip(
                selected = tapScrollRatio == value,
                onClick = {
                    tapScrollRatio = value
                    tapPrefs.edit().putFloat("webtoon_tap_scroll_ratio", value).apply()
                },
                label = { Text(label, fontSize = 13.sp) },
            )
        }
    }
    CheckboxItem("自动进入条漫模式", config.autoWebtoon) { onConfigChange(config.copy(autoWebtoon = it)) }
    // ★ 横屏自动双页：始终显示（隐藏条件曾让双页布局用户找不到开关）；全局语义。
    //   ★ 互斥（2026-10-07）：拆分双页开启时灰显，点击提示
    CheckboxItem("横屏时自动使用双页（仅页面布局为单页时生效）", config.autoDoubleLandscape,
        enabled = !config.doublePageSplit,
        blockedHint = "拆分双页开启时不能启用横屏自动双页，请先关闭拆分双页",
    ) { onConfigChange(config.copy(autoDoubleLandscape = it)) }
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
/**
 * 点击翻页区域染色预览（对调后水平镜像）：上一页=主色、菜单=灰、下一页=主色深。
 * 仅示意当前预设的分区形态，与 ViewerNavigation 区域表一致。
 */
@Composable
private fun ClickZonePreview(type: ReaderConfig.ClickFlipType, invertH: Boolean) {
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
        fun mirrorX(x: Float) = if (invertH) 1f - x else x
        when (type) {
            ReaderConfig.ClickFlipType.NO -> {
                drawRect(menuC)
            }
            ReaderConfig.ClickFlipType.L_SHAPE -> {
                fun rect(x0: Float, y0: Float, x1: Float, y1: Float, color: androidx.compose.ui.graphics.Color) {
                    val mx0 = mirrorX(x0); val mx1 = mirrorX(x1)
                    drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(minOf(mx0, mx1) * w, y0 * h),
                        size = androidx.compose.ui.geometry.Size(kotlin.math.abs(mx1 - mx0) * w, (y1 - y0) * h))
                }
                drawRect(menuC)
                rect(0f, 0f, 1f, 0.33f, prevC)
                rect(0f, 0.33f, 0.33f, 0.67f, prevC)
                rect(0.67f, 0.33f, 1f, 0.67f, nextC)
                rect(0f, 0.67f, 1f, 1f, nextC)
                rect(0.33f, 0.33f, 0.67f, 0.67f, menuC)
            }
            ReaderConfig.ClickFlipType.EDGE -> {
                fun rect(x0: Float, y0: Float, x1: Float, y1: Float, color: androidx.compose.ui.graphics.Color) {
                    val mx0 = mirrorX(x0); val mx1 = mirrorX(x1)
                    drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(minOf(mx0, mx1) * w, y0 * h),
                        size = androidx.compose.ui.geometry.Size(kotlin.math.abs(mx1 - mx0) * w, (y1 - y0) * h))
                }
                drawRect(menuC)
                rect(0f, 0f, 0.33f, 1f, nextC)
                rect(0.67f, 0f, 1f, 1f, nextC)
                rect(0.33f, 0.66f, 0.67f, 1f, prevC)
            }
            ReaderConfig.ClickFlipType.KINDLISH -> {
                fun rect(x0: Float, x1: Float, color: androidx.compose.ui.graphics.Color) {
                    val mx0 = mirrorX(x0); val mx1 = mirrorX(x1)
                    drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(minOf(mx0, mx1) * w, 0.33f * h),
                        size = androidx.compose.ui.geometry.Size(kotlin.math.abs(mx1 - mx0) * w, 0.67f * h))
                }
                drawRect(menuC)
                rect(0f, 0.33f, prevC)
                rect(0.33f, 1f, nextC)
            }
            else -> {
                fun rect(x0: Float, x1: Float, color: androidx.compose.ui.graphics.Color, y0: Float = 0f) {
                    val mx0 = mirrorX(x0); val mx1 = mirrorX(x1)
                    drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(minOf(mx0, mx1) * w, y0 * h),
                        size = androidx.compose.ui.geometry.Size(kotlin.math.abs(mx1 - mx0) * w, (1f - y0) * h))
                }
                drawRect(menuC)
                rect(0f, 0.33f, prevC)
                rect(0.33f, 0.67f, menuC)
                rect(0.67f, 1f, nextC)
            }
        }
    }
}
/**
 * 可视化区域预览：调 buildNavigation().regions 作为单一数据源，用 BoxWithConstraints
 * 叠加绘制每个 region 的边框 + 动作图标 + 动作文字。纯展示组件，不响应点击。
 * 关闭态（type==NO）regions 为空，整体标注「点击翻页已关闭」。
 */
@Composable
private fun TapZoneVisualPreview(type: ReaderConfig.ClickFlipType, invertH: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val regions = remember(type, invertH) {
        com.mangareader.native.buildNavigation(type.ordinal, invertH, false).regions
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(surface)
    ) {
        if (regions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("点击翻页已关闭", color = primary, fontSize = 13.sp)
            }
        } else {
            val W = maxWidth.value
            val H = maxHeight.value
            regions.forEach { region ->
                val r = region.rect
                val x = r.left * W
                val y = r.top * H
                val w = (r.right - r.left) * W
                val h = (r.bottom - r.top) * H
                Box(
                    modifier = Modifier
                        .offset(x = x.dp, y = y.dp)
                        .size(width = w.dp, height = h.dp)
                        .border(1.dp, primary)
                        .background(primary.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (region.action != com.mangareader.native.NavigationAction.MENU) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = when (region.action) {
                                    com.mangareader.native.NavigationAction.PREV -> Icons.AutoMirrored.Filled.ArrowBack
                                    com.mangareader.native.NavigationAction.NEXT -> Icons.AutoMirrored.Filled.ArrowForward
                                    com.mangareader.native.NavigationAction.MENU -> Icons.Filled.Menu
                                },
                                contentDescription = null,
                                tint = primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = when (region.action) {
                                    com.mangareader.native.NavigationAction.PREV -> "上一页"
                                    com.mangareader.native.NavigationAction.NEXT -> "下一页"
                                    com.mangareader.native.NavigationAction.MENU -> "菜单"
                                },
                                color = primary,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun TapZoneSection(config: ReaderConfig, onConfigChange: (ReaderConfig) -> Unit) {
    GroupTitle("点击翻页区域")
    Text(
        "翻页画面时点哪一块做什么（翻页/条漫/仿真翻页均适用；条漫中上一页=上滚一屏、下一页=下滚一屏）",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = HORIZONTAL_PAD),
    )
    Spacer(Modifier.height(10.dp))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = HORIZONTAL_PAD),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            "关闭" to ReaderConfig.ClickFlipType.NO,
            "左右" to ReaderConfig.ClickFlipType.LEFT_RIGHT,
            "L型" to ReaderConfig.ClickFlipType.L_SHAPE,
            "边沿" to ReaderConfig.ClickFlipType.EDGE,
            "Kindle" to ReaderConfig.ClickFlipType.KINDLISH,
        ).forEach { (label, value) ->
            Surface(
                modifier = Modifier.weight(1f).clickable { onConfigChange(config.copy(clickFlipType = value)) },
                shape = MaterialTheme.shapes.medium,
                color = if (config.clickFlipType == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = if (config.clickFlipType == value) 4.dp else 0.dp,
            ) {
                Text(
                    label, fontSize = 13.sp, modifier = Modifier.padding(10.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = if (config.clickFlipType == value) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Box(Modifier.padding(horizontal = HORIZONTAL_PAD)) {
        ClickZonePreview(config.clickFlipType, config.clickFlipInvertH)
    }
    Text(
        "深色区=上一页 · 浅色区=下一页 · 空白=唤出菜单",
        fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = HORIZONTAL_PAD, end = HORIZONTAL_PAD, top = 4.dp),
    )
    Spacer(Modifier.height(8.dp))
    Box(Modifier.padding(horizontal = HORIZONTAL_PAD)) {
        TapZoneVisualPreview(config.clickFlipType, config.clickFlipInvertH)
    }
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = HORIZONTAL_PAD),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("对调左右（左手模式）", fontSize = 14.sp)
            Text(
                if (config.clickFlipInvertH) "开启后：点右侧=上一页、点左侧=下一页" else "关闭时：点左侧=上一页、点右侧=下一页",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = config.clickFlipInvertH,
            onCheckedChange = { on -> onConfigChange(config.copy(clickFlipInvertH = on)) },
            enabled = config.clickFlipType != ReaderConfig.ClickFlipType.NO,
        )
    }
}
