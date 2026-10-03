package com.mangareader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.sr.SrCore
import com.mangareader.sr.SrPipeline
import com.mangareader.sr.SrUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * 前后对比叠层：左半原图 / 右半超分图，拖动竖线看差异。
 * 两侧强制同一绘制矩形（都用原图尺寸做 Fit 计算），画面位置/高度绝对一致。
 * 退出：点右上角"退出对比"按钮，或再点一次底栏"对比"。
 */
@Composable
fun SrCompareOverlay(pageIndex: Int, tick: Int, onClose: () -> Unit, bgColor: Int = android.graphics.Color.BLACK) {
    if (!SrCore.initialized) return
    if (!SrUiState.compareActive.value) return

    // tick 变化驱动重取（超分完成事件到来时刷新）
    // ★ 2026-10-03 审计修复：peekOriginal/peekSr 对产物/批量 webp 走 decodeFile——
    //   原先在 remember{} 组合期主线程解码全尺寸大图（tick 每变一次重解一次，ANR 风险）。
    //   改 LaunchedEffect(IO) 装载进状态，装载完成前保持空（浮层短暂空屏可接受）。
    var original by remember(pageIndex) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var enhanced by remember(pageIndex) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(pageIndex, tick) {
        val o = withContext(Dispatchers.IO) { SrPipeline.peekOriginal(pageIndex) }
        val e = withContext(Dispatchers.IO) { SrPipeline.peekSr(pageIndex) }
        original = o
        enhanced = e
    }


    // ★ 底色跟随阅读器背景色（非固定黑）：长页适配后两侧必然露出底色，与阅读观感保持一致
    Box(Modifier.fillMaxSize().background(Color(bgColor))) {
        // 局部快照（委托属性不能 smart cast，且装载完成前保持空）
        val originalBmp = original
        val enhancedBmp = enhanced
        if (originalBmp != null && enhancedBmp != null && !originalBmp.isRecycled && !enhancedBmp.isRecycled) {
            var fraction by remember(pageIndex) { mutableFloatStateOf(0.5f) }
            var panY by remember(pageIndex) { mutableFloatStateOf(0f) }
            // ★ 手势轴向自动判定（无按钮）：一次拖动手势开始后，首个有效位移决定轴——
            //   横向=只移分割线（纵向临时失效），纵向=只平移长页；松手即释放
            var axisDecided by remember(pageIndex) { mutableStateOf(false) }
            var axisVertical by remember(pageIndex) { mutableStateOf(false) }
            val oImg = originalBmp.asImageBitmap()
            val eImg = enhancedBmp.asImageBitmap()

            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { axisDecided = false },
                            onDrag = { change, dragAmount ->
                                if (!axisDecided &&
                                    (kotlin.math.abs(dragAmount.x) > 0.6f || kotlin.math.abs(dragAmount.y) > 0.6f)
                                ) {
                                    axisVertical = kotlin.math.abs(dragAmount.y) > kotlin.math.abs(dragAmount.x)
                                    axisDecided = true
                                }
                                if (axisDecided) {
                                    if (axisVertical) {
                                        panY += dragAmount.y
                                    } else {
                                        fraction = (fraction + dragAmount.x / size.width).coerceIn(0.02f, 0.98f)
                                    }
                                }
                                change.consume()
                            },
                            onDragEnd = { axisDecided = false },
                            onDragCancel = { axisDecided = false },
                        )
                    }
                    .pointerInput(Unit) {
                        // 单击也能定位分割线
                        detectTapGestures { pt ->
                            fraction = (pt.x / size.width).coerceIn(0.02f, 0.98f)
                        }
                    },
            ) {
                // ★ 条漫长页：按宽适配（=条漫阅读的正常大小），纵向拖动浏览；
                //   短页/横页：整页适配屏幕居中（原行为）
                val fitWScale = size.width / oImg.width
                val fitH = oImg.height * fitWScale
                val tall = fitH > size.height
                val scale = if (tall) fitWScale else min(size.width / oImg.width, size.height / oImg.height)
                val dw = oImg.width * scale
                val dh = oImg.height * scale
                val left = (size.width - dw) / 2
                val top = if (tall) panY.coerceIn(size.height - dh, 0f) else (size.height - dh) / 2
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
                val tag = if (SrCore.settings.upscaleEnabled) "增强" else "优化后"
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
                    if (SrCore.settings.upscaleEnabled) "本页还没有可对比的增强图（翻一页让它先算，或等几秒）"
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
