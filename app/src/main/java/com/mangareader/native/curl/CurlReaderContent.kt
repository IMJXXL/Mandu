package com.mangareader.native.curl

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.davemorrissey.labs.subscaleview.ImageSource
import com.mangareader.native.PagerSSIV
import eu.wewox.pagecurl.ExperimentalPageCurlApi
import eu.wewox.pagecurl.config.PageCurlConfig
import eu.wewox.pagecurl.config.rememberPageCurlConfig
import eu.wewox.pagecurl.page.PageCurl
import eu.wewox.pagecurl.page.rememberPageCurlState

@OptIn(ExperimentalPageCurlApi::class)
@Composable
fun CurlPageContent(
    idx: Int,
    bmp: Bitmap?,
    pageBgColor: Int,
    landscapeZoom: Boolean,
    fitScreen: Boolean,
) {
    if (bmp == null || bmp.isRecycled) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(pageBgColor))
        )
        return
    }
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
        modifier = Modifier.fillMaxSize()
    )
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

    val dragInteraction = if (isRtl) {
        PageCurlConfig.StartEndDragInteraction(
            forward = PageCurlConfig.StartEndDragInteraction.Config(start = leftHalf, end = rightHalf),
            backward = PageCurlConfig.StartEndDragInteraction.Config(start = rightHalf, end = leftHalf),
        )
    } else {
        PageCurlConfig.StartEndDragInteraction(
            forward = PageCurlConfig.StartEndDragInteraction.Config(start = rightHalf, end = leftHalf),
            backward = PageCurlConfig.StartEndDragInteraction.Config(start = leftHalf, end = rightHalf),
        )
    }

    val tapInteraction = if (isRtl) {
        PageCurlConfig.TargetTapInteraction(
            forward = PageCurlConfig.TargetTapInteraction.Config(target = leftHalf),
            backward = PageCurlConfig.TargetTapInteraction.Config(target = rightHalf),
        )
    } else {
        PageCurlConfig.TargetTapInteraction(
            forward = PageCurlConfig.TargetTapInteraction.Config(target = rightHalf),
            backward = PageCurlConfig.TargetTapInteraction.Config(target = leftHalf),
        )
    }

    val config = rememberPageCurlConfig(
        backPageColor = Color(pageBgColor),
        dragInteraction = dragInteraction,
        tapInteraction = tapInteraction,
    )

    LaunchedEffect(state.current) {
        if (state.current != currentPage) {
            onPageChanged(state.current)
        }
    }

    PageCurl(
        count = totalPages,
        state = state,
        config = config,
    ) { idx ->
        CurlPageContent(
            idx = idx,
            bmp = cache.get(idx),
            pageBgColor = pageBgColor,
            landscapeZoom = landscapeZoom,
            fitScreen = fitScreen,
        )
    }
}
