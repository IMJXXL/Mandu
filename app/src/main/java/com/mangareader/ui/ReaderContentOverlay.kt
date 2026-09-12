package com.mangareader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * color: 0=白色, 1=黑色, 2=黑白两段(前半白后半黑，墨水屏全刷新)
 * 硬切换无 alpha 渐变，避免墨水屏中间灰度刷新
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

    var flashColor by remember { mutableStateOf<Color?>(null) }

    LaunchedEffect(currentPage) {
        if (currentPage % intervalPages == 0 && currentPage > 0) {
            if (color == 2) {
                flashColor = Color.White
                delay(durationMs.toLong() / 2)
                flashColor = Color.Black
                delay(durationMs.toLong() / 2)
            } else {
                flashColor = if (color == 1) Color.Black else Color.White
                delay(durationMs.toLong())
            }
            flashColor = null
        }
    }

    val fc = flashColor ?: return

    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(color = fc)
    }
}
