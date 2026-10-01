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
import androidx.compose.ui.unit.sp

private val COLOR_PREV = Color(0xCCFF7733.toInt())
private val COLOR_NEXT = Color(0xCC84E296.toInt())
private val COLOR_MENU = Color(0xCC95818D.toInt())

data class NavZone(val rect: Rect, val color: Color, val label: String)

private val horizontalZones = listOf(
    NavZone(Rect(0f, 0.05f, 0.33f, 1f), COLOR_PREV, "上一页"),
    NavZone(Rect(0.33f, 0.05f, 0.67f, 1f), COLOR_MENU, "菜单"),
    NavZone(Rect(0.67f, 0.05f, 1f, 1f), COLOR_NEXT, "下一页"),
)

// 竖向模式：上=上一页，中=菜单，下=下一页（与 VerticalPagerNavigation 真实分区严格一致）
private val verticalZones = listOf(
    NavZone(Rect(0f, 0.05f, 1f, 0.4f), COLOR_PREV, "上一页"),
    NavZone(Rect(0f, 0.4f, 1f, 0.6f), COLOR_MENU, "菜单"),
    NavZone(Rect(0f, 0.6f, 1f, 1f), COLOR_NEXT, "下一页"),
)

@Composable
fun ReaderNavigationOverlay(
    visible: Boolean,
    isVertical: Boolean = false,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val zones = if (isVertical) verticalZones else horizontalZones
    // ★ 字号 px→sp：原 64f 像素在高密度/平板上相对变小，改 sp 随密度与字体缩放
    val labelPx = with(androidx.compose.ui.platform.LocalDensity.current) { 21.sp.toPx() }
    val textPaint = remember(labelPx) {
        android.graphics.Paint().apply {
            textAlign = android.graphics.Paint.Align.CENTER
            color = android.graphics.Color.WHITE
            textSize = labelPx
            isAntiAlias = true
        }
    }
    val textBorderPaint = remember(labelPx) {
        android.graphics.Paint().apply {
            textAlign = android.graphics.Paint.Align.CENTER
            color = android.graphics.Color.BLACK
            textSize = labelPx
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = labelPx / 8f
            isAntiAlias = true
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        // 顶部5%安全区（横向/竖向真实行为一致：ViewerNavigation.getAction 对 y<5% 恒判 MENU）
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
