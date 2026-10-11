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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 逐格阅读设置面板（2026-10-11，用户拍板"点按钮进设置面板控制功能与开启"）。
 * 骨架与 [ReaderSettingsSheet] 同款：Box 遮罩 + AnimatedVisibility——竖屏底部面板 / 横屏右侧全高抽屉。
 * 即改即生效：显示类（压暗/动画）纯绘制热更新；识别类（灵敏度/方向）清缓存重检当前页
 * （链路：Reader → NES LaunchedEffect → PanelGuideOverlay.onRenderSettingsChanged / onDetectSettingsChanged）。
 */
@Composable
fun PanelGuideSheet(
    sensitivity: Int,
    dim: Int,
    anim: Boolean,
    rtlOverride: Int,
    focusMode: Int,
    active: Boolean,
    onSensitivity: (Int) -> Unit,
    onDim: (Int) -> Unit,
    onAnim: (Boolean) -> Unit,
    onRtl: (Int) -> Unit,
    onFocus: (Int) -> Unit,
    onToggleActive: () -> Unit,
    onDismiss: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

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
                        .width(400.dp)
                        .fillMaxHeight()
                        .clickable(interactionSource = null, indication = null, onClick = {})
                } else {
                    Modifier
                        .fillMaxWidth()
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
                        "自动识别漫画分格，逐格放大浏览。其余区域压暗，滑切/点按切格；点中间区退出。",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))

                    PanelChipRow(
                        title = "检测灵敏度",
                        options = listOf("宽松" to 0, "标准" to 1, "严格" to 2),
                        selected = sensitivity,
                        onSelect = onSensitivity,
                    )
                    Text(
                        when (sensitivity) {
                            0 -> "弱分格/彩漫页更容易识别出分格，可能偶尔误分"
                            2 -> "更保守：拿不准就整页显示"
                            else -> "白底黑线分格漫画的推荐档位"
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
                        title = "选中的图",
                        options = listOf("放大铺满" to 0, "原位高亮" to 1, "放大压暗" to 2),
                        selected = focusMode,
                        onSelect = onFocus,
                    )
                    Text(
                        when (focusMode) {
                            1 -> "整页保持原位不放大，选中格原位亮显、其余压暗"
                            2 -> "放大铺满且选中格同步按压暗程度变暗（暗房/夜间护眼）"
                            else -> "选中格放大铺满全屏（切格时其余区域压暗可见）"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp),
                    )

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

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAnim(!anim) }
                            .padding(horizontal = 24.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Checkbox(checked = anim, onCheckedChange = null)
                        Column {
                            Text("翻格动画", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "切格时的缩放过渡（墨水屏自动关闭）",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            )
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))

                    Button(
                        onClick = onToggleActive,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 4.dp),
                    ) {
                        Text(if (active) "退出逐格阅读" else "开始逐格阅读")
                    }
                    Text(
                        "识别不到分格的页面按整页显示并在右下角提示，逐格模式保持到你手动关。" +
                            "经典白底黑线分格识别最好，彩漫/满版跨页可能整页降级。",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

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
        Row(
            Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
