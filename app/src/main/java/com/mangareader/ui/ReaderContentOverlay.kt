package com.mangareader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

/**
 * 灰度滤镜占位
 * 实际灰度通过 NativeReaderScreen 的 View.setLayerType HARDWARE 应用
 */
@Composable
fun GrayscaleOverlay(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    content()
}

/**
 * 翻页闪烁效果
 * 在翻页时显示短暂的亮色/暗色闪烁
 */
@Composable
fun FlipFlashOverlay(
    currentPage: Int,
    enabled: Boolean,
    durationMs: Int = 100,
    intervalPages: Int = 1,
    color: Int = 0,
    modifier: Modifier = Modifier
) {
    if (!enabled || intervalPages <= 0) return

    var flashAlpha by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(currentPage) {
        if (currentPage % intervalPages == 0 && currentPage > 0) {
            flashAlpha = 1f
            delay(durationMs.toLong())
            flashAlpha = 0f
        }
    }

    if (flashAlpha <= 0f) return

    val flashColor = when (color) {
        1 -> Color.Black
        else -> Color.White
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = flashAlpha }
    ) {
        drawRect(color = flashColor)
    }
}
