package com.mangareader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 逐格阅读设置面板（2026-10-11，用户拍板"点按钮进设置面板控制功能与开启"）。
 * 骨架与 [ReaderSettingsSheet] 同款：Box 遮罩 + AnimatedVisibility——竖屏底部面板 / 横屏右侧全高抽屉。
 * 即改即生效：显示类（压暗/动画）纯绘制热更新；识别类（灵敏度/方向）清缓存重检当前页
 * （链路：Reader → NES LaunchedEffect → PanelGuideOverlay.onRenderSettingsChanged / onDetectSettingsChanged）。
 * 2026-10-14 重构：五功能域分组折叠 + Switch→Checkbox 统一风格 + 总开关置顶 + 翻格动画墨水屏联动。
 */
@Composable
fun PanelGuideSheet(
    sensitivity: Int,
    dim: Int,

    rtlOverride: Int,
    dimMode: Int,
    splitMode: Int,
    active: Boolean,
    transitionMode: Int,
    displayMode: Int,
    cropMode: Int,
    slideMs: Long,
    precision: Int,
    previewEnabled: Boolean,
    progressEnabled: Boolean,
    swipeInvert: Boolean,
    tapInvert: Boolean,
    foldJoinEnabled: Boolean,
    autoRotateEnabled: Boolean,
    autoRotateDir: Int,
    crossPageEnabled: Boolean,
    bubbleMergeEnabled: Boolean,
    engineMode: Int,
    borderWidth: Int,

    onSensitivity: (Int) -> Unit,
    onDim: (Int) -> Unit,

    onRtl: (Int) -> Unit,
    onDimMode: (Int) -> Unit,
    onSplitMode: (Int) -> Unit,
    onTransition: (Int) -> Unit,
    onDisplay: (Int) -> Unit,
    onCrop: (Int) -> Unit,
    onSlideMs: (Long) -> Unit,
    onPrecision: (Int) -> Unit,
    onPreview: (Boolean) -> Unit,
    onProgress: (Boolean) -> Unit,
    onSwipeInvert: (Boolean) -> Unit,
    onTapInvert: (Boolean) -> Unit,
    onFoldJoin: (Boolean) -> Unit,
    onAutoRotate: (Boolean) -> Unit,
    onAutoRotateDir: (Int) -> Unit,
    onCrossPage: (Boolean) -> Unit,
    onBubbleMerge: (Boolean) -> Unit,
    onEngineMode: (Int) -> Unit,
    onBorderWidth: (Int) -> Unit,
    onToggleActive: () -> Unit,
    onDismiss: () -> Unit,
    presets: List<com.mangareader.panel.PanelGuidePreset.Preset> = emptyList(),
    currentPresetId: String? = null,
    onPresetSelect: (com.mangareader.panel.PanelGuidePreset.Preset) -> Unit = {},
    onPresetSave: (String) -> Unit = {},
    onPresetDelete: (String) -> Unit = {},
    onPresetRename: (String, String) -> Unit = { _, _ -> },
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }


    var groupRecognize by rememberSaveable { mutableStateOf(true) }
    var groupDisplay by rememberSaveable { mutableStateOf(true) }
    var groupAux by rememberSaveable { mutableStateOf(false) }
    var groupAdvanced by rememberSaveable { mutableStateOf(false) }
    var groupPreset by rememberSaveable { mutableStateOf(false) }

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
            enter = if (landscape) slideInHorizontally(tween(300)) { it } else slideInVertically(tween(300)) { it },
            exit = if (landscape) slideOutHorizontally(tween(300)) { it } else slideOutVertically(tween(300)) { it },
        ) {
            Surface(
                modifier = if (landscape) {
                    Modifier
                        .width(440.dp)
                        .fillMaxHeight()
                        .clickable(interactionSource = null, indication = null, onClick = {})
                } else {
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = 520.dp)
                        .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.60f).dp)
                        .clickable(interactionSource = null, indication = null, onClick = {})
                },
                shape = if (landscape) RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)
                else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 10.dp)
                ) {
                    Text(
                        "逐格阅读",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                    Text(
                        "该功能还在开发中，尚不完善，仅供测试体验",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleActive() }
                            .padding(horizontal = 24.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Switch(checked = active, onCheckedChange = null)
                        Text("开启逐格阅读", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "识别不到分格的页面按整页显示。白底黑线分格识别最好，彩漫/满版跨页可能整页降级。",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))

                    PanelChipRow(
                        title = "引擎模式",
                        options = listOf("框选法" to 0, "坐标法" to 1),
                        selected = engineMode,
                        onSelect = onEngineMode,
                    )
                    Text(
                        when (engineMode) {
                            0 -> "以框为基准的算法"
                            else -> "以坐标为基准的算法"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 6.dp),
                    )
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))

                    if (engineMode == 0) {
                        PanelChipRow(
                            title = "检测灵敏度",
                            options = listOf("宽松" to 0, "标准" to 1, "严格" to 2),
                            selected = sensitivity,
                            onSelect = onSensitivity,
                        )
                        PanelChipRow(
                            title = "横向顺序",
                            options = listOf("跟随模式" to 0, "先右·日漫" to 1, "先左·国漫" to 2),
                            selected = rtlOverride,
                            onSelect = onRtl,
                        )
                        PanelChipRow(
                            title = "显示模式",
                            options = listOf("压暗其余" to 0, "隐藏其余" to 1),
                            selected = displayMode,
                            onSelect = onDisplay,
                        )
                        if (displayMode == 0) {
                            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("压暗程度", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Text("$dim%", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                }
                                Slider(
                                    value = dim.toFloat(),
                                    onValueChange = { onDim((it + 0.5f).toInt()) },
                                    valueRange = 0f..100f,
                                )
                            }
                        }
                        PanelChipRow(
                            title = "切换方式",
                            options = listOf("直接切换" to 0, "追焦" to 1),
                            selected = transitionMode,
                            onSelect = onTransition,
                        )
                        Text(
                            when (transitionMode) {
                                0 -> "无过渡，直接跳到下一格"
                                else -> "镜头追焦当前格，平滑滑动"
                            },
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 6.dp),
                        )
                        if (transitionMode == 1) {
                            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("追焦时长", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Text("${slideMs}ms", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                }
                                Slider(
                                    value = slideMs.toFloat().coerceIn(1000f, 8000f),
                                    onValueChange = { onSlideMs((it + 0.5f).toLong()) },
                                    valueRange = 1000f..8000f,
                                    steps = 13,
                                )
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        CheckboxRow(
                            checked = previewEnabled,
                            onCheckedChange = onPreview,
                            title = "分格预览",
                            info = "进入逐格前预览本页分格",
                        )
                        CheckboxRow(
                            checked = progressEnabled,
                            onCheckedChange = onProgress,
                            title = "格进度",
                            info = "底部显示当前第几格",
                        )
                        CheckboxRow(
                            checked = swipeInvert,
                            onCheckedChange = onSwipeInvert,
                            title = "滑动反转",
                            info = "左滑切上一格",
                        )
                        CheckboxRow(
                            checked = tapInvert,
                            onCheckedChange = onTapInvert,
                            title = "点按反转",
                            info = "左右点按互换",
                        )
                        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("框线粗细", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text(if (borderWidth == 0) "隐藏" else "${borderWidth}dp", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            }
                            Slider(
                                value = borderWidth.toFloat(),
                                onValueChange = { onBorderWidth((it + 0.5f).toInt()) },
                                valueRange = 0f..6f,
                                steps = 5,
                            )
                        }
                    } else {
                        GroupSection(
                        title = "识别设置",
                        expanded = groupRecognize,
                        onToggle = { groupRecognize = !groupRecognize },
                    ) {
                        PanelChipRow(
                            title = "检测灵敏度",
                            options = listOf("宽松" to 0, "标准" to 1, "严格" to 2),
                            selected = sensitivity,
                            onSelect = onSensitivity,
                        )
                        Text(
                            when (sensitivity) {
                                0 -> "弱分格和彩漫更易通过，可能偶尔误分"
                                2 -> "拿不准就整页显示"
                                else -> "白底黑线分格推荐档位"
                            },
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 6.dp),
                        )

                        PanelChipRow(
                            title = "识别精度",
                            options = listOf("普通" to 0, "精细" to 1),
                            selected = precision,
                            onSelect = onPrecision,
                        )
                        Text(
                            when (precision) {
                                0 -> "快，适合清晰分格"
                                else -> "准，适合模糊或低对比度边框"
                            },
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 6.dp),
                        )

                        PanelChipRow(
                            title = "横向顺序",
                            options = listOf("跟随模式" to 0, "先右·日漫" to 1, "先左·国漫" to 2),
                            selected = rtlOverride,
                            onSelect = onRtl,
                        )

                        PanelChipRow(
                            title = "逐格条件",
                            options = listOf("整页都能分才逐格" to 0, "能分几格分几格" to 1),
                            selected = splitMode,
                            onSelect = onSplitMode,
                        )
                    }

                    GroupSection(
                        title = "显示设置",
                        expanded = groupDisplay,
                        onToggle = { groupDisplay = !groupDisplay },
                    ) {
                        PanelChipRow(
                            title = "画面显示",
                            options = listOf("压暗其余" to 0, "隐藏其余" to 1),
                            selected = displayMode,
                            onSelect = onDisplay,
                        )

                        if (displayMode == 0) {
                            PanelChipRow(
                                title = "其余画面",
                                options = listOf("全部一样暗" to 0, "当前格原位稍亮" to 1),
                                selected = dimMode,
                                onSelect = onDimMode,
                            )
                        }

                        PanelChipRow(
                            title = "画面裁切",
                            options = listOf("紧贴分格" to 0, "放宽边距" to 1, "留固定边距" to 2, "不裁切只高亮" to 3),
                            selected = cropMode,
                            onSelect = onCrop,
                        )

                        if (displayMode == 0) {
                            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("压暗程度", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Text("$dim%", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                }
                                Slider(
                                    value = dim.toFloat(),
                                    onValueChange = { onDim((it + 0.5f).toInt()) },
                                    valueRange = 0f..100f,
                                )
                                if (dim == 0) {
                                    Text(
                                        "0% = 不压暗，仅放大当前格",
                                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                    )
                                }
                            }
                        }

                        PanelChipRow(
                            title = "切换方式",
                            options = listOf("直接切换" to 0, "平滑平移" to 1, "渐隐渐显" to 2, "追焦" to 3),
                            selected = transitionMode,
                            onSelect = onTransition,
                        )
                        Text(
                            when (transitionMode) {
                                0 -> "无过渡，直接跳到下一格"
                                1 -> "整页放大，画面平移到下一格"
                                2 -> "渐隐渐显切换，墨水屏友好"
                                else -> "镜头追焦当前格，平滑滑动"
                            },
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 6.dp),
                        )

                        if (transitionMode == 1) {
                            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("过渡时长", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Text("${slideMs}ms", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                }
                                Slider(
                                    value = slideMs.toFloat().coerceIn(100f, 500f),
                                    onValueChange = { onSlideMs((it + 0.5f).toLong()) },
                                    valueRange = 100f..500f,
                                )
                            }
                        }
                        if (transitionMode == 3) {
                            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("追焦时长", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Text("${slideMs}ms", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                }
                                Slider(
                                    value = slideMs.toFloat().coerceIn(1000f, 8000f),
                                    onValueChange = { onSlideMs((it + 0.5f).toLong()) },
                                    valueRange = 1000f..8000f,
                                    steps = 13,
                                )
                            }
                        }


                    }

                    GroupSection(
                        title = "辅助功能",
                        expanded = groupAux,
                        onToggle = { groupAux = !groupAux },
                    ) {
                        CheckboxRow(
                            checked = previewEnabled,
                            onCheckedChange = onPreview,
                            title = "分格预览",
                            info = "进入逐格前预览本页分格",
                        )
                        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("框线粗细", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text(if (borderWidth == 0) "隐藏" else "${borderWidth}dp", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            }
                            Slider(
                                value = borderWidth.toFloat(),
                                onValueChange = { onBorderWidth((it + 0.5f).toInt()) },
                                valueRange = 0f..6f,
                                steps = 5,
                            )
                        }
                        CheckboxRow(
                            checked = progressEnabled,
                            onCheckedChange = onProgress,
                            title = "格进度",
                            info = "底部显示当前第几格",
                        )
                        CheckboxRow(
                            checked = swipeInvert,
                            onCheckedChange = onSwipeInvert,
                            title = "滑动反转",
                            info = "左滑切上一格",
                        )
                        CheckboxRow(
                            checked = tapInvert,
                            onCheckedChange = onTapInvert,
                            title = "点按反转",
                            info = "左右点按互换",
                        )
                    }

                    GroupSection(
                        title = "高级功能",
                        expanded = groupAdvanced,
                        onToggle = { groupAdvanced = !groupAdvanced },
                    ) {
                        CheckboxRow(
                            checked = foldJoinEnabled,
                            onCheckedChange = onFoldJoin,
                            title = "折痕移除",
                            info = "跨页扫描件去折痕拼接",
                        )
                        CheckboxRow(
                            checked = autoRotateEnabled,
                            onCheckedChange = onAutoRotate,
                            title = "跨页大图旋转",
                            info = "竖屏旋转铺开看",
                        )
                        if (autoRotateEnabled) {
                            PanelChipRow(
                                title = "旋转方向",
                                options = listOf("顺时针" to 0, "逆时针" to 1, "自动" to 2),
                                selected = autoRotateDir,
                                onSelect = onAutoRotateDir,
                            )
                        }
                        CheckboxRow(
                            checked = crossPageEnabled,
                            onCheckedChange = onCrossPage,
                            title = "跨页连续过渡",
                            info = "末格前进连续滑动",
                        )
                        CheckboxRow(
                            checked = bubbleMergeEnabled,
                            onCheckedChange = onBubbleMerge,
                            title = "跨格气泡合并",
                            info = "气泡跨越分格时同组显示",
                        )
                    }

                    GroupSection(
                        title = "预设管理",
                        expanded = groupPreset,
                        onToggle = { groupPreset = !groupPreset },
                    ) {
                        PresetRegion(
                            presets = presets,
                            currentPresetId = currentPresetId,
                            onPresetSelect = onPresetSelect,
                            onPresetSave = onPresetSave,
                            onPresetDelete = onPresetDelete,
                            onPresetRename = onPresetRename,
                        )
                    }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelChipRow(
    title: String,
    options: List<Pair<String, Int>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        )
        FlowRow(
            Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEach { (label, value) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
private fun GroupSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 24.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column { content() }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
    }
}

@Composable
private fun CheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    info: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 24.dp, vertical = 6.dp)
            .alpha(if (enabled) 1f else 0.38f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (info != null) {
                Text(
                    info,
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetRegion(
    presets: List<com.mangareader.panel.PanelGuidePreset.Preset>,
    currentPresetId: String?,
    onPresetSelect: (com.mangareader.panel.PanelGuidePreset.Preset) -> Unit,
    onPresetSave: (String) -> Unit,
    onPresetDelete: (String) -> Unit,
    onPresetRename: (String, String) -> Unit,
) {
    var dropdownExpanded by remember { mutableStateOf(false) }
    var saveDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<com.mangareader.panel.PanelGuidePreset.Preset?>(null) }
    var saveName by remember { mutableStateOf("") }
    var renameName by remember { mutableStateOf("") }

    Text(
        "预设",
        style = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        ),
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            Row(
                modifier = Modifier
                    .clickable { dropdownExpanded = true }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val current = presets.firstOrNull { it.id == currentPresetId }
                Text(
                    current?.name ?: "选择预设",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = "选择")
            }
            DropdownMenu(
                expanded = dropdownExpanded,
                onDismissRequest = { dropdownExpanded = false },
            ) {
                presets.forEach { p ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(p.name)
                                if (p.id == currentPresetId) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "当前",
                                        modifier = Modifier.size(16.dp).padding(start = 4.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        },
                        onClick = {
                            dropdownExpanded = false
                            onPresetSelect(p)
                        },
                    )
                }
            }
        }
        TextButton(onClick = { saveName = ""; saveDialog = true }) {
            Text("存为预设")
        }
    }
    presets.forEach { p ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (p.id == currentPresetId) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "当前",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                p.name,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = if (p.id == currentPresetId) FontWeight.SemiBold else FontWeight.Normal,
                ),
                modifier = Modifier.weight(1f),
            )
            if (!p.isBuiltin) {
                IconButton(onClick = { renameName = p.name; renameTarget = p }) {
                    Icon(Icons.Default.Edit, contentDescription = "重命名")
                }
                IconButton(onClick = { onPresetDelete(p.id) }) {
                    Icon(Icons.Default.Delete, contentDescription = "删除")
                }
            }
        }
    }
    if (saveDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { saveDialog = false },
            title = { Text("存为预设") },
            text = {
                OutlinedTextField(
                    value = saveName,
                    onValueChange = { saveName = it },
                    label = { Text("预设名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (saveName.isNotBlank()) {
                            onPresetSave(saveName.trim())
                            saveDialog = false
                        }
                    },
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("取消") } },
        )
    }
    renameTarget?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = renameName,
                    onValueChange = { renameName = it },
                    label = { Text("新名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameName.isNotBlank()) {
                            onPresetRename(target.id, renameName.trim())
                            renameTarget = null
                        }
                    },
                ) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }
}
