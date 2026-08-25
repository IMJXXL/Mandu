package com.mangareader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas

private val COLOR_PREV = Color(0xCCFF7733.toInt())
private val COLOR_NEXT = Color(0xCC84E296.toInt())
private val COLOR_MENU = Color(0xCC95818D.toInt())

data class NavZone(val rect: Rect, val color: Color, val label: String)

private val horizontalZones = listOf(
    NavZone(Rect(0f, 0.05f, 0.33f, 1f), COLOR_PREV, "上一页"),
    NavZone(Rect(0.33f, 0.05f, 0.67f, 1f), COLOR_MENU, "菜单"),
    NavZone(Rect(0.67f, 0.05f, 1f, 1f), COLOR_NEXT, "下一页"),
)

// 竖向模式：上=上一页，中=菜单，下=下一页
private val verticalZones = listOf(
    NavZone(Rect(0f, 0.05f, 1f, 0.45f), COLOR_PREV, "上一页"),
    NavZone(Rect(0f, 0.45f, 1f, 0.55f), COLOR_MENU, "菜单"),
    NavZone(Rect(0f, 0.55f, 1f, 1f), COLOR_NEXT, "下一页"),
)

@Composable
fun ReaderNavigationOverlay(
    visible: Boolean,
    isVertical: Boolean = false,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val zones = if (isVertical) verticalZones else horizontalZones
    val textPaint = remember {
        android.graphics.Paint().apply {
            textAlign = android.graphics.Paint.Align.CENTER
            color = android.graphics.Color.WHITE
            textSize = 64f
            isAntiAlias = true
        }
    }
    val textBorderPaint = remember {
        android.graphics.Paint().apply {
            textAlign = android.graphics.Paint.Align.CENTER
            color = android.graphics.Color.BLACK
            textSize = 64f
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 8f
            isAntiAlias = true
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        // 顶部5%安全区
        drawRect(
            color = COLOR_MENU.copy(alpha = 0.3f),
            topLeft = Offset(0f, 0f),
            size = Size(size.width, size.height * 0.05f)
        )
        zones.forEach { zone ->
            val left = zone.rect.left * size.width
            val top = zone.rect.top * size.height
            val w = zone.rect.width * size.width
            val h = zone.rect.height * size.height
            drawRect(color = zone.color, topLeft = Offset(left, top), size = Size(w, h))
            val cx = left + w / 2
            val cy = top + h / 2
            drawContext.canvas.nativeCanvas.drawText(zone.label, cx, cy, textBorderPaint)
            drawContext.canvas.nativeCanvas.drawText(zone.label, cx, cy, textPaint)
        }
    }
}
