package com.mangareader.ui

import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.data.ReaderConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsDialog(
    config: ReaderConfig, mode: Mode, bookId: Long, isVertical: Boolean,
    onConfigChange: (ReaderConfig) -> Unit, onModeChange: (Mode) -> Unit,
    onOrientationChange: (Int) -> Unit, onResetReading: () -> Unit, onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("阅读模式", "常规", "滤镜")
    val accent = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.surface
    val text = MaterialTheme.colorScheme.onSurface
    val chipBg = MaterialTheme.colorScheme.surfaceVariant
    val chipSelected = MaterialTheme.colorScheme.primaryContainer
    val border = MaterialTheme.colorScheme.outline

    AlertDialog(onDismissRequest = onDismiss, containerColor = bg, title = null, text = {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                tabs.forEachIndexed { index, title ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f).clickable { selectedTab = index }.padding(vertical = 8.dp)) {
                        Text(title, fontSize = 15.sp, fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == index) accent else text.copy(alpha = 0.6f))
                        if (selectedTab == index) { Spacer(Modifier.height(4.dp)); Box(Modifier.width(40.dp).height(2.dp).background(accent)) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (selectedTab) {
                    0 -> ReadingModeTab(config, mode, onConfigChange, onModeChange, onOrientationChange)
                    1 -> GeneralTab(config, onConfigChange)
                    2 -> FilterTab(config, onConfigChange)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = accent)) { Text("确定") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TsyChipRow(items: List<Pair<String, Any>>, selected: Any, onSelect: (Any) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val chipBg = MaterialTheme.colorScheme.surfaceVariant
    val chipSelected = MaterialTheme.colorScheme.primaryContainer
    val border = MaterialTheme.colorScheme.outline
    val text = MaterialTheme.colorScheme.onSurface

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (label, value) ->
            val isSelected = value == selected
            Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (isSelected) chipSelected else chipBg)
                .then(if (isSelected) Modifier.border(1.dp, accent, RoundedCornerShape(8.dp)) else Modifier.border(1.dp, border, RoundedCornerShape(8.dp)))
                .clickable { onSelect(value) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(label, fontSize = 13.sp, color = if (isSelected) accent else text)
            }
        }
    }
}

@Composable
private fun TsySection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val text = MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = text)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun TsyCheckbox(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val border = MaterialTheme.colorScheme.outline
    val text = MaterialTheme.colorScheme.onSurface

    Row(Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(modifier = Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(if (checked) accent else Color.Transparent)
            .then(if (!checked) Modifier.border(1.5.dp, border, RoundedCornerShape(4.dp)) else Modifier),
            contentAlignment = Alignment.Center) {
            if (checked) Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Text(label, fontSize = 14.sp, color = text)
    }
}

@Composable
private fun TsySlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, valueText: String, onValueChange: (Float) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val text = MaterialTheme.colorScheme.onSurface

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("$label: $valueText", fontSize = 13.sp, color = text)
        Slider(value = value, onValueChange = onValueChange, valueRange = range, steps = steps,
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent))
    }
}

// ===== Tab 0: 阅读模式（按TSY源码精确复制）=====
@Composable
private fun ReadingModeTab(config: ReaderConfig, mode: Mode, onConfigChange: (ReaderConfig) -> Unit,
    onModeChange: (Mode) -> Unit, onOrientationChange: (Int) -> Unit) {

    // 本作品
    TsySection("本作品") {
        TsySection("阅读模式") {
            TsyChipRow(
                items = listOf("默认" to -1, "单页式（从左到右）" to 0, "单页式（从右到左）" to 1,
                    "单页式（从上到下）" to 2, "条漫" to 3, "条漫（页间有空隙）" to 4),
                selected = when (mode) { Mode.FLIP -> 0; Mode.RTL -> 1; Mode.VERT -> 3; else -> 0 },
                onSelect = { value -> when (value) { 0 -> onModeChange(Mode.FLIP); 1 -> onModeChange(Mode.RTL); 3 -> onModeChange(Mode.VERT) } }
            )
        }
        TsySection("屏幕方向") {
            TsyChipRow(
                items = listOf("默认" to -1, "跟随系统" to -2, "竖屏" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    "横屏" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, "锁定竖屏" to ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
                    "锁定横屏" to ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE),
                selected = config.orientation, onSelect = { onOrientationChange(it as Int) }
            )
        }
    }

    Spacer(Modifier.height(8.dp))

    // 单页式查看器（TSY源码PagerViewerSettings）
    TsySection("单页式") {
        TsySection("页面布局") {
            TsyChipRow(
                items = listOf("单页" to ReaderConfig.PageLayout.SINGLE, "双页" to ReaderConfig.PageLayout.DOUBLE,
                    "双页跳过首页" to ReaderConfig.PageLayout.DOUBLE_SHIFTED),
                selected = config.pageLayout, onSelect = { onConfigChange(config.copy(pageLayout = it as ReaderConfig.PageLayout)) }
            )
        }
        TsyCheckbox("裁剪边缘", config.isTrimWhiteBorder) { onConfigChange(config.copy(isTrimWhiteBorder = it)) }
        TsyCheckbox("自动放大横向图片", config.landscapeZoom) { onConfigChange(config.copy(landscapeZoom = it)) }
        TsyCheckbox("图片放大时先平移再翻页", config.panNavigation) { onConfigChange(config.copy(panNavigation = it)) }
        TsyCheckbox("拆分双页（重进漫画生效）", config.doublePageSplit) { onConfigChange(config.copy(doublePageSplit = it)) }
        if (config.doublePageSplit) {
            TsyCheckbox("反转双页", config.doublePageReverse) { onConfigChange(config.copy(doublePageReverse = it)) }
        }
        TsyCheckbox("翻页动画", config.isFlipAnimation) { onConfigChange(config.copy(isFlipAnimation = it)) }
        TsySection("中间空白类型") {
            TsyChipRow(
                items = listOf("无" to 0, "添加到双页视图" to 1, "添加到宽屏视图" to 2, "两个都添加" to 3),
                selected = 0, onSelect = { }
            )
        }
    }
}

// ===== Tab 1: 常规（按TSY源码精确复制）=====
@Composable
private fun GeneralTab(config: ReaderConfig, onConfigChange: (ReaderConfig) -> Unit) {
    TsySection("背景颜色") {
        TsyChipRow(
            items = listOf("黑色" to 1, "灰色" to 2, "白色" to 0, "自动" to 3),
            selected = when (config.readBackground) {
                ReaderConfig.ReadBackground.DARK -> 1; ReaderConfig.ReadBackground.GRAY -> 2
                ReaderConfig.ReadBackground.WHITE -> 0; else -> 3
            },
            onSelect = { value -> onConfigChange(config.copy(readBackground = when (value) {
                1 -> ReaderConfig.ReadBackground.DARK; 2 -> ReaderConfig.ReadBackground.GRAY
                0 -> ReaderConfig.ReadBackground.WHITE; else -> ReaderConfig.ReadBackground.DEFAULT
            })) }
        )
    }
    TsyCheckbox("显示页码", config.isShowPageNum) { onConfigChange(config.copy(isShowPageNum = it)) }
    TsyCheckbox("沉浸式模式（重进生效）", config.isImmersiveMode) { onConfigChange(config.copy(isImmersiveMode = it)) }
    TsyCheckbox("保持屏幕常亮", config.isKeepScreenOn) { onConfigChange(config.copy(isKeepScreenOn = it)) }
    TsyCheckbox("长按显示操作菜单", true) { }
    TsyCheckbox("始终显示章节间的过渡页面", config.chapterTransition) { onConfigChange(config.copy(chapterTransition = it)) }
    TsyCheckbox("翻页时闪烁", config.isFlipFlashEnabled) { onConfigChange(config.copy(isFlipFlashEnabled = it)) }
    if (config.isFlipFlashEnabled) {
        TsySlider("持续时间", config.flipFlashDuration.toFloat(), 50f..1500f, 14, "${config.flipFlashDuration}ms") {
            onConfigChange(config.copy(flipFlashDuration = it.toInt()))
        }
        TsySlider("间隔页数", config.flipFlashInterval.toFloat(), 1f..10f, 9, "${config.flipFlashInterval}页") {
            onConfigChange(config.copy(flipFlashInterval = it.toInt()))
        }
    }
    TsyCheckbox("自动进入条漫模式", config.autoWebtoon) { onConfigChange(config.copy(autoWebtoon = it)) }
}

// ===== Tab 2: 滤镜（只留灰度）=====
@Composable
private fun FilterTab(config: ReaderConfig, onConfigChange: (ReaderConfig) -> Unit) {
    TsySection("滤镜") {
        TsyCheckbox("灰度模式", config.isGrayscale) { onConfigChange(config.copy(isGrayscale = it)) }
    }
}
