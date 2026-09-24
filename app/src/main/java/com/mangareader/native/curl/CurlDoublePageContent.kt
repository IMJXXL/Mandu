package com.mangareader.native.curl

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
                Box(Modifier.fillMaxSize().background(Color(pageBgColor)))
            } else {
                val tLeftIdx = SpreadMapping.leftPage(targetSpread, totalPages, shift)
                val tRightIdx = SpreadMapping.rightPage(targetSpread, totalPages, shift)
                val tLeftBmp = cache.get(tLeftIdx)
                val tRightBmp = if (tRightIdx >= 0) cache.get(tRightIdx) else null

                if (tLeftBmp != null && !tLeftBmp.isRecycled) {
                    val merged = remember(tLeftBmp, tRightBmp) {
                        try {
                            if (tRightBmp != null && !tRightBmp.isRecycled) {
                                if (doublePageReverse) {
                                    SpreadMapping.mergeBitmaps(tRightBmp, tLeftBmp, 0, pageBgColor)
                                } else {
                                    SpreadMapping.mergeBitmaps(tLeftBmp, tRightBmp, 0, pageBgColor)
                                }
                            } else {
                                tLeftBmp
                            }
                        } catch (_: OutOfMemoryError) {
                            tLeftBmp
                        }
                    }
                    Image(
                        bitmap = merged.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(Color(pageBgColor)))
                }
            }
        },
    ) { spreadIdx ->
        @Suppress("UNUSED_EXPRESSION")
        reloadTrigger.intValue

        val leftIdx = SpreadMapping.leftPage(spreadIdx, totalPages, shift)
        val rightIdx = SpreadMapping.rightPage(spreadIdx, totalPages, shift)
        val leftBmp = cache.get(leftIdx)
        val rightBmp = if (rightIdx >= 0) cache.get(rightIdx) else null

        if (leftBmp != null && !leftBmp.isRecycled) {
            val merged = remember(leftBmp, rightBmp) {
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
            }
            Image(
                bitmap = merged.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Box(Modifier.fillMaxSize().background(Color(pageBgColor)))
        }
    }
}