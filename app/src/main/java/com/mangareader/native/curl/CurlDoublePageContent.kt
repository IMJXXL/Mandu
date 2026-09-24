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
                SpreadImage(null, null, doublePageReverse, pageBgColor)
            } else {
                val tLeftIdx = SpreadMapping.leftPage(targetSpread, totalPages, shift)
                val tRightIdx = SpreadMapping.rightPage(targetSpread, totalPages, shift)
                SpreadImage(
                    cache.get(tLeftIdx),
                    if (tRightIdx >= 0) cache.get(tRightIdx) else null,
                    doublePageReverse,
                    pageBgColor,
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
        )
    }
}

@Composable
private fun SpreadImage(
    leftBmp: Bitmap?,
    rightBmp: Bitmap?,
    doublePageReverse: Boolean,
    pageBgColor: Int,
) {
    val emptyBmp = remember { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap() }

    val merged = remember(leftBmp, rightBmp) {
        if (leftBmp != null && !leftBmp.isRecycled) {
            try {
                if (rightBmp != null && !rightBmp.isRecycled) {
                    if (doublePageReverse) {
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

    Image(
        bitmap = imgBmp ?: emptyBmp,
        contentDescription = null,
        modifier = Modifier.fillMaxSize().background(Color(pageBgColor)),
        contentScale = ContentScale.Fit,
    )
}