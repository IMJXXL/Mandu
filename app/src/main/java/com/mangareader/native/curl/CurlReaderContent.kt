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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
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
fun CurlPageContent(
    bmp: Bitmap?,
    isRtl: Boolean,
    pageBgColor: Int,
    landscapeZoom: Boolean,
    fitScreen: Boolean,
    bgTexPaint: android.graphics.Paint? = null,
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
    // ★ 整屏幕纸纹（textureScope=1）：纸面留边用外层算好的 bgPaint 铺纸纹（同配方同相位），
    //   平涂背景曾把外层纸纹背景整块盖死；关=平涂原样
    val bgMod = if (bgTexPaint != null) Modifier.drawBehind {
        drawContext.canvas.nativeCanvas.drawColor(pageBgColor)
        drawContext.canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, bgTexPaint)
    } else Modifier.background(Color(pageBgColor))

    if (bmp == null || bmp.isRecycled) {
        Box(modifier = Modifier.fillMaxSize().then(bgMod))
        return
    }

    Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = null,
        modifier = baseModifier.then(bgMod),
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
    backContentAlpha: Float,
    shadowAlpha: Float,
    paperBackPresetId: Int = -1,
    /** ★ 整屏幕纸纹背景开关/浓度（textureScope=1 时由视图传入） */
    bgTextureOn: Boolean = false,
    bgTextureConcentration: Int = 100,
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

    // ★ 背面纸底色：贴正面"纸调"——
    //   开纸纹：纸纹已烘进页面位图（背面镜像自带纹理色调），纸底=背景色×该纹理平均反射率
    //   （按浓度折算，见 PaperTexture.texturedTone）——任意透出滑条位置色调恒一致；
    //   关纸纹：背景色加深约6%的正常纸调；暗主题恒深灰防闪眼（纸纹乘在暗底上近乎不可见）
    val bgPre = Color(pageBgColor)
    val darkPre = (0.299f * bgPre.red + 0.587f * bgPre.green + 0.114f * bgPre.blue) < 0.5f
    val effBackColor = when {
        darkPre -> android.graphics.Color.rgb(0x2B, 0x28, 0x24)
        paperBackPresetId >= 0 -> com.mangareader.filter.PaperTexture.texturedTone(pageBgColor, paperBackPresetId, bgTextureConcentration)
        else -> android.graphics.Color.rgb(
            (bgPre.red * 255f * 0.94f).toInt().coerceIn(0, 255),
            (bgPre.green * 255f * 0.94f).toInt().coerceIn(0, 255),
            (bgPre.blue * 255f * 0.94f).toInt().coerceIn(0, 255),
        )
    }
    val config = remember {
        PageCurlConfig(
            backPageColor = Color(effBackColor),
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


        // ★ 整屏幕纸纹背景（textureScope=1）：背景色之上 MULTIPLY 纸纹（页面烘焙照旧，两层合成全屏纸感）
        val bgCtx = androidx.compose.ui.platform.LocalContext.current
        val bgTex = if (bgTextureOn && paperBackPresetId >= 0) {
            produceState<android.graphics.Bitmap?>(null, paperBackPresetId, bgTextureOn) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    runCatching { com.mangareader.filter.PaperTexture.loadSync(bgCtx.applicationContext, paperBackPresetId) }.getOrNull()
                }
            }.value
        } else null
        val bgPaint = remember(bgTex, bgTextureConcentration) {
            bgTex?.let { bmp ->
                android.graphics.Paint().apply {
                    shader = android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.MIRROR, android.graphics.Shader.TileMode.MIRROR)
                    isFilterBitmap = true
                    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.MULTIPLY)
                    val s = bgTextureConcentration.coerceIn(0, 100) / 100f
                    val w = 255f * (1 - s)
                    colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(floatArrayOf(
                        s, 0f, 0f, 0f, w,
                        0f, s, 0f, 0f, w,
                        0f, 0f, s, 0f, w,
                        0f, 0f, 0f, 1f, 0f
                    )))
                }
            }
        }
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color(pageBgColor)).then(
        if (bgTextureOn) Modifier.drawBehind {
            bgPaint?.let { p ->
                drawContext.canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, p)
            }
        } else Modifier
    )) {
        // ★ 背面纸底（0930 用户裁定定稿）：背面=镜像内容（透出滑条）+纯色纸底，
        //   纸纹位图退役——config.backPageBitmap 恒空，无残留/花块可能；纸底色随
        //   纸纹开关/preset/浓度实时换算（开纸纹时贴该纹理的取样色调）
        LaunchedEffect(pageBgColor, paperBackPresetId, bgTextureConcentration) {
            config.backPageColor = Color(effBackColor)
        }
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
                bmp = cache.get(idx),
                isRtl = isRtl,
                pageBgColor = pageBgColor,
                landscapeZoom = landscapeZoom,
                fitScreen = fitScreen,
                bgTexPaint = bgPaint,
            )
        }
    }
}
