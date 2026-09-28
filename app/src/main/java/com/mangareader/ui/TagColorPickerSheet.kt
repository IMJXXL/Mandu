package com.mangareader.ui

import android.graphics.Color as AColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 改色弹层（design.md §六 / mockup_color_picker.png）：
 * 预览chip + "自动"档 + 12 色板 + HSV 拾色器（SV 方块 + 色相条）+ 可选 hex 输入。
 * AUTO 传 TagColors.AUTO_SENTINEL；手动色按当前主题档写死（由调用方在 onPick 后落库）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagColorPickerSheet(
    visible: Boolean,
    tagName: String,
    currentColor: Long,
    darkTheme: Boolean,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val initColor = TagColors.resolve(tagName, currentColor, darkTheme)
    val initHsv = floatArrayOf(0f, 0f, 0f)
    AColor.colorToHSV(intOf(initColor), initHsv)
    var hue by remember(visible) { mutableFloatStateOf(initHsv[0]) }
    var sat by remember(visible) { mutableFloatStateOf(initHsv[1]) }
    var value by remember(visible) { mutableFloatStateOf(initHsv[2]) }
    var hexText by remember(visible) { mutableStateOf(String.format("#%06X", intOf(initColor) and 0xFFFFFF)) }

    val customArgb = AColor.HSVToColor(floatArrayOf(hue, sat, value)).toLong() or 0xFF000000L

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)
        ) {
            Text("标签颜色", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            // 实时预览
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(20.dp).clip(CircleShape).background(Color(customArgb)))
                        Spacer(Modifier.width(8.dp))
                        Text(tagName, fontSize = 15.sp)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text("← 实时预览所选颜色", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(14.dp))

            Text("预设色板（点选即用）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(2.dp, if (currentColor == TagColors.AUTO_SENTINEL) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                        .clickable { onPick(TagColors.AUTO_SENTINEL) },
                    contentAlignment = Alignment.Center
                ) { Text("自", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                TagColors.palette(darkTheme).forEach { c ->
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(c))
                            .border(2.dp, if (currentColor == c) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp))
                            .clickable { onPick(c) }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            Text("自定义颜色", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            // SV 方块：底色=当前色相，横向 白→色，纵向 透→黑
            BoxWithConstraints(
                Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(10.dp))
                    .pointerInput(hue) {
                        fun update(pos: Offset) {
                            sat = (pos.x / size.width).coerceIn(0f, 1f)
                            value = 1f - (pos.y / size.height).coerceIn(0f, 1f)
                        }
                        detectTapGestures { update(it) }
                    }
                    .pointerInput(hue) {
                        detectDragGestures { change, _ ->
                            sat = (change.position.x / size.width).coerceIn(0f, 1f)
                            value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                        }
                    }
            ) {
                val w = maxWidth
                val h = 170.dp
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(Color(AColor.HSVToColor(floatArrayOf(hue, 1f, 1f))))
                    drawRect(Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                }
                Box(
                    Modifier.offset(x = w * sat - 9.dp, y = h * (1 - value) - 9.dp)
                        .size(18.dp).clip(CircleShape).border(3.dp, Color.White, CircleShape)
                )
            }
            Spacer(Modifier.height(14.dp))
            // 色相条
            BoxWithConstraints(
                Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(8.dp))
                    .pointerInput(Unit) {
                        fun update(pos: Offset) { hue = (pos.x / size.width).coerceIn(0f, 1f) * 360f }
                        detectTapGestures { update(it) }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            hue = (change.position.x / size.width).coerceIn(0f, 1f) * 360f
                        }
                    }
            ) {
                val w = maxWidth
                Canvas(Modifier.fillMaxSize()) {
                    val steps = 72
                    for (i in 0 until steps) {
                        drawRect(
                            Color(AColor.HSVToColor(floatArrayOf(i.toFloat() / steps * 360f, 1f, 1f))),
                            topLeft = Offset(size.width * i / steps, 0f),
                            size = Size(size.width / steps + 1f, size.height)
                        )
                    }
                }
                Box(
                    Modifier.offset(x = w * (hue / 360f) - 8.dp)
                        .size(16.dp, 38.dp).clip(CircleShape).border(3.dp, Color.White, CircleShape)
                )
            }
            Spacer(Modifier.height(14.dp))
            // hex 输入（可选）
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = hexText,
                    onValueChange = {
                        hexText = it
                        val clean = it.removePrefix("#")
                        if (clean.length == 6) {
                            clean.toLongOrNull(16)?.let { rgb ->
                                val hsv = floatArrayOf(0f, 0f, 0f)
                                AColor.colorToHSV(rgb.toInt(), hsv)
                                hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.width(150.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp)
                )
                Spacer(Modifier.width(12.dp))
                Text("想精确就手输 hex（可选，不填就点色块）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    onPick(customArgb)
                    onDismiss()
                }) { Text("完成") }
            }
        }
    }
}

private fun intOf(argb: Long): Int = (argb and 0xFFFFFFFFL).toInt()
