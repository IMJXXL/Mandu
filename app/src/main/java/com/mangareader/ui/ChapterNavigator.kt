package com.mangareader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ChapterNavigator(
    isVertical: Boolean,
    currentPage: Int,
    totalPages: Int,
    onPageChange: (Int) -> Unit,
    onSliderFinished: () -> Unit,
    onPreviousChapter: (() -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
    isShowPageNum: Boolean = true,
    isRtl: Boolean = false,
    bgColor: Color = Color.Black,
    onPreviewPage: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    HorizontalNavigator(currentPage, totalPages, onPageChange, onSliderFinished, onPreviousChapter, onNextChapter, isShowPageNum, isRtl, bgColor, onPreviewPage, modifier)
}

@Composable
private fun HorizontalNavigator(
    currentPage: Int, totalPages: Int, onPageChange: (Int) -> Unit, onSliderFinished: () -> Unit,
    onPreviousChapter: (() -> Unit)?, onNextChapter: (() -> Unit)?, isShowPageNum: Boolean, isRtl: Boolean, bgColor: Color,
    onPreviewPage: ((Int) -> Unit)?, modifier: Modifier
) {
    // 根据背景亮度决定文字颜色
    val luminance = bgColor.red * 0.299f + bgColor.green * 0.587f + bgColor.blue * 0.114f
    val textColor = if (luminance > 0.5f) Color.Black else Color.White
    val surfaceColor = bgColor.copy(alpha = 0.85f)

    // ★ v1.11 P0-B6 节流提交：Slider 拖动实时预览保留，但 onPageChange 提交限频 ≥300ms
    //   （实机堆栈实锤：误触/快拖时 dispatchRawDelta 以 20ms/次 连发 → goToPage 风暴拉扯画面）。
    //   松手 onValueChangeFinished 无条件补发 pendingPage 最终值，保证落点精确不丢步。
    var lastEmit by remember { mutableLongStateOf(0L) }
    var lastChange by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    var throttleMs by remember { mutableIntStateOf(150) }
    // ★ 丝滑二期：拖动每帧只写普通数组（非 state → 零重组）；气泡用隔离小组件（重组范围最小）
    val pendingRef = remember { intArrayOf(currentPage) }
    val previewPageRef = remember { intArrayOf(-1) }
    val previewAtRef = remember { longArrayOf(0L) }
    var bubblePage by remember { mutableIntStateOf(-1) }
    var bubbleFrac by remember { mutableFloatStateOf(0f) }
    var trackWidth by remember { mutableIntStateOf(0) }

    Surface(color = surfaceColor, shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledIconButton(
                onClick = { if (isRtl) onNextChapter?.invoke() else onPreviousChapter?.invoke() },
                enabled = if (isRtl) onNextChapter != null else onPreviousChapter != null,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = if (isRtl) "下一本" else "上一本", modifier = Modifier.size(20.dp))
            }
            if (isShowPageNum) Text(
                if (isRtl) "$totalPages" else "${currentPage + 1}",
                color = textColor, fontSize = 13.sp, modifier = Modifier.width(36.dp)
            )
            val sliderValue = if (isRtl) ((totalPages - 1 - currentPage).coerceAtLeast(0)).toFloat() else currentPage.toFloat()
            Box(Modifier.weight(1f).onSizeChanged { trackWidth = it.width }) {
                Slider(
                    value = sliderValue,
                    onValueChange = { v ->
                        val newPage = if (isRtl) (totalPages - 1 - v.toInt()).coerceIn(0, (totalPages - 1).coerceAtLeast(0)) else v.toInt().coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                        val now = android.os.SystemClock.elapsedRealtime()
                        val frameInterval = now - lastChange
                        lastChange = now
                        if (frameInterval in 20..100) {
                            throttleMs = (throttleMs * 1.4f).toInt().coerceAtMost(300)
                        } else if (frameInterval < 20 && now - lastEmit > throttleMs) {
                            // ★ 丝滑对齐：下限 40ms（mhark 同款）
                            throttleMs = (throttleMs * 0.85f).toInt().coerceAtLeast(40)
                        }
                        pendingRef[0] = newPage
                        // 页码气泡：只重算局部状态（气泡小组件单独重组）
                        bubblePage = newPage
                        bubbleFrac = if (totalPages <= 1) 0f else (v / (totalPages - 1).toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                        // 预解码预览：页变化 + ≥80ms 双守卫（此前每帧全量 preloadAround 是拖动卡顿主因）
                        if (onPreviewPage != null && newPage != previewPageRef[0] && now - previewAtRef[0] >= 80) {
                            previewPageRef[0] = newPage
                            previewAtRef[0] = now
                            onPreviewPage(newPage)
                        }
                        if (now - lastEmit >= throttleMs) {
                            lastEmit = now
                            onPageChange(newPage)
                        }
                    },
                    onValueChangeFinished = {
                        // 松手无条件提交最终值（节流可能吞掉最后一步），同值重复回调无害
                        onPageChange(pendingRef[0])
                        bubblePage = -1
                        onSliderFinished()
                    },
                    valueRange = 0f..(totalPages - 1).toFloat().coerceAtLeast(1f),
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF7C8CF8), activeTrackColor = Color(0xFF7C8CF8))
                )

            }
            if (isShowPageNum) Text(
                if (isRtl) "${currentPage + 1}" else "$totalPages",
                color = textColor.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.width(36.dp)
            )
            FilledIconButton(
                onClick = { if (isRtl) onPreviousChapter?.invoke() else onNextChapter?.invoke() },
                enabled = if (isRtl) onPreviousChapter != null else onNextChapter != null,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = if (isRtl) "上一本" else "下一本", modifier = Modifier.size(20.dp))
            }
        }
    }
}


/** 跟随拇指的页码气泡（对齐 mhark/Material Slider 的 label；独立组件=拖动时只有它重组） */
@Composable
private fun SliderBubble(page: Int, frac: Float, trackWidth: Int, isRtl: Boolean) {
    if (page < 0 || trackWidth <= 0) return
    val density = androidx.compose.ui.platform.LocalDensity.current
    val xPx = with(density) {
        val fx = if (isRtl) 1f - frac else frac
        (fx * trackWidth).toInt().coerceIn(0, trackWidth - 1)
    }
    val yPx = with(density) { (-30).dp.roundToPx() }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color(0xFF7C8CF8),
        modifier = Modifier.offset { IntOffset(xPx, yPx) }
    ) {
        Text(
            text = "${page + 1}",
            color = Color.White,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
