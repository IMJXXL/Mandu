package com.mangareader.native.curl

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
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
    animData: CurlAnimData?,
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

    if (animData != null) {
        AndroidView(
            factory = { ctx ->
                android.widget.ImageView(ctx).apply {
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    visibility = android.view.View.VISIBLE
                    val drawable = makeCurlAnimDrawable(animData.raw, animData.kind)
                    if (drawable != null) {
                        setImageDrawable(drawable)
                        (drawable as? android.graphics.drawable.Animatable)?.start()
                    }
                }
            },
            update = { iv ->
                if (iv.visibility != android.view.View.VISIBLE) {
                    iv.visibility = android.view.View.VISIBLE
                }
            },
            modifier = baseModifier,
        )
    } else if (isCurrentPage) {
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
    reloadTrigger: MutableIntState,
    onPageChanged: (Int) -> Unit,
    onToggleUi: () -> Unit,
) {
    val state = rememberPageCurlState(initialCurrent = currentPage)
    val currentOnToggleUi by rememberUpdatedState(onToggleUi)

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
            tapForwardEnabled = true,
            tapBackwardEnabled = true,
            tapCustomEnabled = true,
            dragInteraction = dragInteraction,
            tapInteraction = tapInteraction,
            onCustomTap = { size, offset ->
                val fx = offset.x / size.width
                if (fx in 0.33f..0.67f) {
                    currentOnToggleUi()
                    true
                } else false
            },
        )
    }

    LaunchedEffect(state.current) {
        if (state.current != currentPage) {
            onPageChanged(state.current)
        }
    }

    val curlModifier = if (isRtl) {
        Modifier.fillMaxSize().graphicsLayer { scaleX = -1f }
    } else {
        Modifier.fillMaxSize()
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
            animData = cache.getAnim(idx),
            isCurrentPage = idx == state.current,
            isRtl = isRtl,
            pageBgColor = pageBgColor,
            landscapeZoom = landscapeZoom,
            fitScreen = fitScreen,
        )
    }
}
