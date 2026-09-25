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
fun CurlPageContent(
    bmp: Bitmap?,
    isRtl: Boolean,
    pageBgColor: Int,
    landscapeZoom: Boolean,
    fitScreen: Boolean,
) {
    // 缩放语义与双页 SpreadImage / pager applyScale 对齐：fitScreen（漫画完整显示）=contain；
    // 否则贴满宽度；landscapeZoom（自动放大横向图片，仅 fitScreen 关闭时插手）宽图贴满高度。
    // 全部页面统一 Image 常驻：曾对当前页用 SSIV（可双指缩放）其余页用 Image，翻页落地
    // 瞬间 AndroidView↔Image 组件类型切换产生空白帧（b12d1e4/bc54d52 双页同类问题的
    // 单页残留），统一 Image 消除切换；代价是单页仿真模式放弃双指缩放（与双页取舍一致）。
    val wide = bmp != null && !bmp.isRecycled && bmp.width > bmp.height
    val contentScale = when {
        fitScreen -> ContentScale.Fit
        landscapeZoom && wide -> ContentScale.FillHeight
        else -> ContentScale.FillWidth
    }
    val mirror = if (isRtl) Modifier.graphicsLayer { scaleX = -1f } else Modifier
    val baseModifier = Modifier.fillMaxSize().clipToBounds().then(mirror)

    if (bmp == null || bmp.isRecycled) {
        Box(modifier = Modifier.fillMaxSize().background(Color(pageBgColor)))
        return
    }

    Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = null,
        modifier = baseModifier.background(Color(pageBgColor)),
        contentScale = contentScale,
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
    tapForwardTrigger: MutableIntState,
    tapBackwardTrigger: MutableIntState,
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
            bmp = cache.get(idx),
            isRtl = isRtl,
            pageBgColor = pageBgColor,
            landscapeZoom = landscapeZoom,
            fitScreen = fitScreen,
        )
    }
}
