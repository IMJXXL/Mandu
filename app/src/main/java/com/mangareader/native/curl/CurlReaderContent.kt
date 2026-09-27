package com.mangareader.native.curl

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.davemorrissey.labs.subscaleview.ImageSource
import com.mangareader.native.PagerSSIV
import eu.wewox.pagecurl.ExperimentalPageCurlApi
import eu.wewox.pagecurl.config.PageCurlConfig
import eu.wewox.pagecurl.page.PageCurl
import eu.wewox.pagecurl.page.rememberPageCurlState

@OptIn(ExperimentalPageCurlApi::class)
@Composable
fun CurlPageContent(
    idx: Int,
    bmp: Bitmap?,
    isCurrentPage: Boolean,
    isRtl: Boolean,
    pageBgColor: Int,
    landscapeZoom: Boolean,
    fitScreen: Boolean,
) {
    val mirror = if (isRtl) Modifier.graphicsLayer { scaleX = -1f } else Modifier
    val baseModifier = Modifier.fillMaxSize().then(mirror)

    if (bmp == null || bmp.isRecycled) {
        Box(modifier = baseModifier.background(Color(pageBgColor)))
        return
    }

    if (isCurrentPage) {
        AndroidView(
            factory = { ctx ->
                PagerSSIV(ctx).apply {
                    isZoomEnabled = true
                    isPanEnabled = true
                    isQuickScaleEnabled = true
                    setMaxScale(5f)
                    setMaxTileSize(512)
                    setMinimumTileDpi(160)
                    setMinimumScaleType(1)
                    setDoubleTapZoomDuration(300)
                    visibility = android.view.View.VISIBLE
                    setImage(ImageSource.cachedBitmap(bmp))
                }
            },
            update = { ssiv ->
                if (ssiv.visibility != android.view.View.VISIBLE) {
                    ssiv.visibility = android.view.View.VISIBLE
                }
            },
            modifier = baseModifier,
        )
    } else {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = baseModifier,
            contentScale = ContentScale.Fit,
        )
    }
}

@OptIn(ExperimentalPageCurlApi::class)
@Composable
fun CurlReaderContent(
    totalPages: Int,
    currentPage: Int,
    isRtl: Boolean,
    pageBgColor: Int,
    cache: CurlBitmapCache,
    landscapeZoom: Boolean,
    fitScreen: Boolean,
    backContentAlpha: Float,
    shadowAlpha: Float,
    reloadTrigger: MutableIntState,
    tapForwardTrigger: MutableIntState,
    tapBackwardTrigger: MutableIntState,
    onPageChanged: (Int) -> Unit,
    curlContentAreaOnly: Boolean = false,
) {
    val state = rememberPageCurlState(initialCurrent = currentPage)


    LaunchedEffect(currentPage) {
        if (state.current != currentPage) {
            state.snapTo(currentPage)
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
        if (state.current != currentPage) {
            onPageChanged(state.current)
        }
    }

    LaunchedEffect(tapForwardTrigger.intValue) {
        if (tapForwardTrigger.intValue > 0) state.next()
    }

    LaunchedEffect(tapBackwardTrigger.intValue) {
        if (tapBackwardTrigger.intValue > 0) state.prev()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color(pageBgColor))) {
        var insetL = 0.dp
        var insetT = 0.dp
        var insetR = 0.dp
        var insetB = 0.dp
        if (curlContentAreaOnly) {
            val bmp = cache.get(state.current)
            if (bmp != null && !bmp.isRecycled) {
                val iw = bmp.width.toFloat()
                val ih = bmp.height.toFloat()
                val cw = constraints.maxWidth.toFloat()
                val ch = constraints.maxHeight.toFloat()
                if (iw > 0f && ih > 0f && cw > 0f && ch > 0f) {
                    val scale = when {
                        fitScreen -> minOf(cw / iw, ch / ih)
                        landscapeZoom && iw > ih -> ch / ih
                        else -> cw / iw
                    }
                    val dispW = (iw * scale).coerceAtMost(cw)
                    val dispH = (ih * scale).coerceAtMost(ch)
                    if (dispW < cw || dispH < ch) {
                        with(LocalDensity.current) {
                            insetL = ((cw - dispW) / 2f).toDp()
                            insetR = insetL
                            insetT = ((ch - dispH) / 2f).toDp()
                            insetB = insetT
                        }
                    }
                }
            }
        }

        val areaModifier = Modifier.fillMaxSize().padding(
            start = insetL, top = insetT, end = insetR, bottom = insetB,
        )
        val curlModifier = if (isRtl) {
            areaModifier.graphicsLayer { scaleX = -1f }
        } else {
            areaModifier
        }

        PageCurl(
            count = totalPages,
            modifier = curlModifier,
            state = state,
            config = config,
        ) { idx ->
            CurlPageContent(
                idx = idx,
                bmp = cache.get(idx),
                isCurrentPage = idx == state.current,
                isRtl = isRtl,
                pageBgColor = pageBgColor,
                landscapeZoom = landscapeZoom,
                fitScreen = fitScreen,
            )
        }
    }
}

