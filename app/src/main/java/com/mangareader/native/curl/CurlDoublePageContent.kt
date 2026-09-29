package com.mangareader.native.curl

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
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
    curlContentAreaOnly: Boolean,
    backContentAlpha: Float,
    shadowAlpha: Float,
    paperBackPresetId: Int = -1,
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

    val config = remember {
        PageCurlConfig(
            backPageColor = Color(pageBgColor),
            backPageContentAlpha = backContentAlpha,
            shadowColor = Color.Black,
            shadowAlpha = shadowAlpha,
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
    // 背面透出程度/阴影浓度运行时可调：PageCurlConfig 的字段本身是 mutableStateOf，
    // 绘制层直接读 state，改值即重绘且不打断进行中的翻页动画，无需重建 config 对象
    LaunchedEffect(backContentAlpha) {
        config.backPageContentAlpha = backContentAlpha
    }
    LaunchedEffect(shadowAlpha) {
        config.shadowAlpha = shadowAlpha
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

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color(pageBgColor))) {
        // ★ 纸背纹理（同 CurlReaderContent）：按当前纸张 preset 烘米色纸纹图
        val ctx = androidx.compose.ui.platform.LocalContext.current
        // ★ 纸背底色随背景亮度：暗色主题深灰纸，避免翻页背面亮块闪眼
        val bg = Color(pageBgColor)
        val dark = (0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue) < 0.5f
        // ★ 纸背：主线程仅缓存快查；未命中后台烘焙回填（首帧短暂无纸背，随后浮现）
        val backBitmap = remember(paperBackPresetId, dark, constraints.maxWidth, constraints.maxHeight) {
            if (paperBackPresetId < 0) null
            else runCatching {
                com.mangareader.filter.PaperBackTexture.peek(paperBackPresetId, constraints.maxWidth, constraints.maxHeight, dark)
            }.getOrNull()
        }
        LaunchedEffect(pageBgColor) {
            config.backPageColor = Color(pageBgColor)
        }
        LaunchedEffect(paperBackPresetId, dark, constraints.maxWidth, constraints.maxHeight) {
            if (paperBackPresetId >= 0 && backBitmap == null) {
                val w = constraints.maxWidth; val h = constraints.maxHeight
                val baked = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    runCatching { com.mangareader.filter.PaperBackTexture.get(ctx, paperBackPresetId, w, h, dark) }.getOrNull()
                }
                config.backPageBitmap = baked?.asImageBitmap()
            }
        }
        LaunchedEffect(backBitmap) {
            if (backBitmap != null) config.backPageBitmap = backBitmap.asImageBitmap()
        }
        // 「仅画面区域」模式：翻页动画范围缩到当前 spread 的显示矩形（与 SpreadImage 同一
        // 缩放语义），留边由外层背景铺满、不参与翻页，PageEdge 拖拽也随之变成"画面边缘"。
        // 矩形取当前 spread 已解码位图尺寸，未就绪时回退整屏；state.current 只在 turn 落地
        // 后整型提交，约束变化都发生在静止态，不会打断翻页中的折叠几何。
        var insetL = 0.dp
        var insetT = 0.dp
        var insetR = 0.dp
        var insetB = 0.dp
        if (curlContentAreaOnly) {
            val curLeft = SpreadMapping.leftPage(state.current, totalPages, shift)
            val curRight = SpreadMapping.rightPage(state.current, totalPages, shift)
            val curL = cache.get(curLeft)
            val curR = if (curRight >= 0) cache.get(curRight) else null
            if (curL != null && !curL.isRecycled) {
                val iw = (curL.width + (curR?.width ?: 0)).toFloat()
                val ih = maxOf(curL.height, curR?.height ?: curL.height).toFloat()
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
            count = totalSpreads,
            modifier = curlModifier,
            state = state,
            config = config,
            backContent = { current, forward ->
                @Suppress("UNUSED_EXPRESSION")
                reloadTrigger.intValue

                val targetSpread = if (forward) current + 1 else current - 1
                Box {
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
                    // 沟阴影画进页面内容层：随纸翻动，不会悬在翻起的纸上方
                    SpineGutterShadow()
                }
            },
        ) { spreadIdx ->
            @Suppress("UNUSED_EXPRESSION")
            reloadTrigger.intValue

            val leftIdx = SpreadMapping.leftPage(spreadIdx, totalPages, shift)
            val rightIdx = SpreadMapping.rightPage(spreadIdx, totalPages, shift)
            Box {
                SpreadImage(
                    cache.get(leftIdx),
                    if (rightIdx >= 0) cache.get(rightIdx) else null,
                    doublePageReverse,
                    pageBgColor,
                    isRtl,
                    landscapeZoom,
                    fitScreen,
                )
                SpineGutterShadow()
            }
        }
    }
}

/** 书脊沟阴影：中缝恒定低强度暗渐变，模拟装订沟。画在页面内容层（静态一层渐变零性能成本），
 *  翻起的纸会盖住阴影而不是被阴影盖住。 */
@Composable
private fun SpineGutterShadow() {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        if (w > 0f) {
            val cx = w / 2f
            val half = w * 0.055f
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    ((cx - half) / w).coerceIn(0f, 1f) to Color.Transparent,
                    0.5f to Color.Black.copy(alpha = 0.09f),
                    ((cx + half) / w).coerceIn(0f, 1f) to Color.Transparent,
                    1f to Color.Transparent,
                )
            )
        }
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