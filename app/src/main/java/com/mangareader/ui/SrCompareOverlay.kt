package com.mangareader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.sr.SrCore
import com.mangareader.sr.SrPipeline
import com.mangareader.sr.SrUiState
import kotlin.math.min

/**
 * 前后对比叠层：左半原图 / 右半超分图，拖动竖线看差异。
 * 两侧强制同一绘制矩形（都用原图尺寸做 Fit 计算），画面位置/高度绝对一致。
 * 退出：点右上角"退出对比"按钮，或再点一次底栏"对比"。
 */
@Composable
fun SrCompareOverlay(pageIndex: Int, tick: Int, onClose: () -> Unit) {
    if (!SrCore.initialized) return
    if (!SrUiState.compareActive.value) return

    // tick 变化驱动重取（超分完成事件到来时刷新）
    val original = remember(pageIndex, tick) { SrPipeline.peekOriginal(pageIndex) }
    val enhanced = remember(pageIndex, tick) { SrPipeline.peekSr(pageIndex) }

    // ★ 纯黑底：长页按高度适配后两侧会露出底下阅读器（邻页/不同缩放），看起来像三段错位内容；
    //   对比视图必须是独立黑场舞台
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (original != null && enhanced != null && !original.isRecycled && !enhanced.isRecycled) {
            var fraction by remember(pageIndex) { mutableFloatStateOf(0.5f) }
            val oImg = original.asImageBitmap()
            val eImg = enhanced.asImageBitmap()

            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            fraction = (change.position.x / size.width).coerceIn(0.02f, 0.98f)
                            change.consume()
                        }
                    }
                    .pointerInput(Unit) {
                        // 单击也能拖动定位分割线
                        detectTapGestures { pt ->
                            fraction = (pt.x / size.width).coerceIn(0.02f, 0.98f)
                        }
                    },
            ) {
                // 用原图尺寸算 Fit 矩形；两半都用这个矩形画 → 位置/高度完全一致
                val scale = min(size.width / oImg.width, size.height / oImg.height)
                val dw = oImg.width * scale
                val dh = oImg.height * scale
                val left = (size.width - dw) / 2
                val top = (size.height - dh) / 2
                val dstOffset = IntOffset(left.toInt(), top.toInt())
                val dstSize = IntSize(dw.toInt(), dh.toInt())
                val srcSize = IntSize(oImg.width, oImg.height)
                val splitX = size.width * fraction

                clipRect(0f, 0f, splitX, size.height) {
                    drawImage(oImg, srcSize = srcSize, dstOffset = dstOffset, dstSize = dstSize)
                }
                clipRect(splitX, 0f, size.width, size.height) {
                    drawImage(eImg, srcSize = IntSize(eImg.width, eImg.height), dstOffset = dstOffset, dstSize = dstSize)
                }
                drawLine(Color.White, Offset(splitX, 0f), Offset(splitX, size.height), strokeWidth = 4f)
                drawCircle(Color.White, radius = 18f, center = Offset(splitX, size.height / 2))
                drawCircle(Color(0x66000000), radius = 14f, center = Offset(splitX, size.height / 2))
            }

            // 角标加半透明黑底，白图上也看得清
            Surface(
                color = Color(0x99000000),
                shape = MaterialTheme.shapes.extraSmall,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
            ) {
                Text("原图", color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
            }
            Surface(
                color = Color(0x992E7D32),
                shape = MaterialTheme.shapes.extraSmall,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            ) {
                val tag = if (SrCore.settings.upscaleEnabled) "超分" else "优化后"
                Text(tag, color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
            }
        } else {
            // 提示文案上卡一黑，免得白底图上看不见
            Surface(
                color = Color(0xCC000000),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            ) {
                Text(
                    if (SrCore.settings.upscaleEnabled) "本页还没有可对比的超分图（翻一页让它先算，或等几秒）"
                    else "本页还没有可对比的黑白优化图（翻一页让它先算，或等几秒）",
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        // 退出按钮
        Surface(
            onClick = onClose,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
        ) {
            Text(
                "✕ 退出对比",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}
