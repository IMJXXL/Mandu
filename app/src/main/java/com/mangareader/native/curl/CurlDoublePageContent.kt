package com.mangareader.native.curl

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import eu.wewox.pagecurl.ExperimentalPageCurlApi
import eu.wewox.pagecurl.config.PageCurlConfig
import eu.wewox.pagecurl.page.PageCurl
import eu.wewox.pagecurl.page.rememberPageCurlState

@OptIn(ExperimentalPageCurlApi::class)
@Composable
fun CurlDoublePageContent(
    totalSpreads: Int,
    currentSpread: Int,
    totalPages: Int,
    shift: Boolean,
    isRtl: Boolean,
    doublePageReverse: Boolean,
    pageBgColor: Int,
    cache: CurlBitmapCache,
    landscapeZoom: Boolean,
    fitScreen: Boolean,
    reloadTrigger: MutableIntState,
    tapForwardTrigger: MutableIntState,
    tapBackwardTrigger: MutableIntState,
    onSpreadChanged: (Int) -> Unit,
) {
    val state = rememberPageCurlState(
        initialCurrent = currentSpread,
        turnEndFractionX = 0.5f,
    )

    LaunchedEffect(currentSpread) {
        if (state.current != currentSpread) {
            state.snapTo(currentSpread)
        }
    }

    @Suppress("UNUSED_EXPRESSION")
    reloadTrigger.intValue

    val rightHalf = Rect(0.5f, 0f, 1f, 1f)
    val leftHalf = Rect(0f, 0f, 0.5f, 1f)

    val dragInteraction = PageCurlConfig.StartEndDragInteraction(
        pointerBehavior = PageCurlConfig.DragInteraction.PointerBehavior.PageEdge,
        forward = PageCurlConfig.StartEndDragInteraction.Config(start = rightHalf, end = leftHalf),
        backward = PageCurlConfig.StartEndDragInteraction.Config(start = leftHalf, end = rightHalf),
    )

    val tapInteraction = PageCurlConfig.TargetTapInteraction(
        forward = PageCurlConfig.TargetTapInteraction.Config(target = rightHalf),
        backward = PageCurlConfig.TargetTapInteraction.Config(target = leftHalf),
    )

    val config = remember(pageBgColor) {
        PageCurlConfig(
            backPageColor = Color(pageBgColor),
            backPageContentAlpha = 0.1f,
            shadowColor = Color.Black,
            shadowAlpha = 0.2f,
            shadowRadius = 15.dp,
            shadowOffset = DpOffset((-5).dp, 0.dp),
            dragForwardEnabled = true,
            dragBackwardEnabled = true,
            tapForwardEnabled = false,
            tapBackwardEnabled = false,
            tapCustomEnabled = false,
            dragInteraction = dragInteraction,
            tapInteraction = tapInteraction,
            onCustomTap = { _, _ -> false },
        )
    }

    LaunchedEffect(state.current) {
        if (state.current != currentSpread) {
            onSpreadChanged(state.current)
        }
    }

    LaunchedEffect(tapForwardTrigger.intValue) {
        if (tapForwardTrigger.intValue > 0) state.next()
    }

    LaunchedEffect(tapBackwardTrigger.intValue) {
        if (tapBackwardTrigger.intValue > 0) state.prev()
    }

    val curlModifier = if (isRtl) {
        Modifier.fillMaxSize().graphicsLayer { scaleX = -1f }
    } else {
        Modifier.fillMaxSize()
    }

    PageCurl(
        count = totalSpreads,
        modifier = curlModifier,
        state = state,
        config = config,
        backContent = { current, forward ->
            @Suppress("UNUSED_EXPRESSION")
            reloadTrigger.intValue

            val targetSpread = if (forward) current + 1 else current - 1
            if (targetSpread < 0 || targetSpread >= totalSpreads) {
                SpreadImage(null, null, doublePageReverse, pageBgColor, isRtl, landscapeZoom, fitScreen)
            } else {
                val tLeftIdx = SpreadMapping.leftPage(targetSpread, totalPages, shift)
                val tRightIdx = SpreadMapping.rightPage(targetSpread, totalPages, shift)
                SpreadImage(
                    cache.get(tLeftIdx),
                    if (tRightIdx >= 0) cache.get(tRightIdx) else null,
                    doublePageReverse,
                    pageBgColor,
                    isRtl,
                    landscapeZoom,
                    fitScreen,
                )
            }
        },
    ) { spreadIdx ->
        @Suppress("UNUSED_EXPRESSION")
        reloadTrigger.intValue

        val leftIdx = SpreadMapping.leftPage(spreadIdx, totalPages, shift)
        val rightIdx = SpreadMapping.rightPage(spreadIdx, totalPages, shift)
        SpreadImage(
            cache.get(leftIdx),
            if (rightIdx >= 0) cache.get(rightIdx) else null,
            doublePageReverse,
            pageBgColor,
            isRtl,
            landscapeZoom,
            fitScreen,
        )
    }
}

@Composable
private fun SpreadImage(
    leftBmp: Bitmap?,
    rightBmp: Bitmap?,
    doublePageReverse: Boolean,
    pageBgColor: Int,
    isRtl: Boolean,
    landscapeZoom: Boolean,
    fitScreen: Boolean,
) {
    val emptyBmp = remember { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap() }

    // RTL 双层镜像（对齐单页 CurlPageContent 的既定模式）：外层 curlModifier 已镜像整个
    // PageCurl（翻页几何+手势），内容在此反镜像一次恢复正立。合并序取反（XOR）后两次镜像
    // 抵消，落屏页序=bitmap 序，与 PagerReaderView 的 RTL 排布语义一致（偶数页在右先读）。
    val effReverse = doublePageReverse != isRtl

    val merged = remember(leftBmp, rightBmp, effReverse) {
        if (leftBmp != null && !leftBmp.isRecycled) {
            try {
                if (rightBmp != null && !rightBmp.isRecycled) {
                    if (effReverse) {
                        SpreadMapping.mergeBitmaps(rightBmp, leftBmp, 0, pageBgColor)
                    } else {
                        SpreadMapping.mergeBitmaps(leftBmp, rightBmp, 0, pageBgColor)
                    }
                } else {
                    leftBmp
                }
            } catch (_: OutOfMemoryError) {
                leftBmp
            }
        } else {
            null
        }
    }

    val imgBmp = remember(merged) {
        if (merged != null && !merged.isRecycled) merged.asImageBitmap() else null
    }

    // 缩放语义对齐 pager applyScale：fitScreen（漫画完整显示）=contain；否则贴满宽度；
    // landscapeZoom（自动放大横向图片，仅 fitScreen 关闭时插手）宽图再放大到贴满高度。
    // FillWidth/FillHeight 会溢出边界，必须 clipToBounds（ImageView 自带裁剪，Compose 没有）
    val wide = merged != null && !merged.isRecycled && merged.width > merged.height
    val contentScale = when {
        fitScreen -> ContentScale.Fit
        landscapeZoom && wide -> ContentScale.FillHeight
        else -> ContentScale.FillWidth
    }

    val mirror = if (isRtl) Modifier.graphicsLayer { scaleX = -1f } else Modifier

    Image(
        bitmap = imgBmp ?: emptyBmp,
        contentDescription = null,
        modifier = Modifier.fillMaxSize().clipToBounds().background(Color(pageBgColor)).then(mirror),
        contentScale = contentScale,
    )
}