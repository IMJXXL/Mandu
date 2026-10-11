package com.mangareader.native.curl

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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
import com.mangareader.native.ScalePlan
import androidx.compose.ui.unit.dp
import eu.wewox.pagecurl.ExperimentalPageCurlApi
import eu.wewox.pagecurl.config.PageCurlConfig
import eu.wewox.pagecurl.page.PageCurl
import eu.wewox.pagecurl.page.rememberPageCurlState

/** 仿真双击缩放状态（视图层 GestureDetector 驱动，内容层 graphicsLayer 消费）：
 *  scale 1↔2，origin=点按点在内容区的归一化坐标（屏空间；内层按镜像翻转后用） */
class CurlZoomState {
    val scale = androidx.compose.runtime.mutableFloatStateOf(1f)
    val originX = androidx.compose.runtime.mutableFloatStateOf(0.5f)
    val originY = androidx.compose.runtime.mutableFloatStateOf(0.5f)
    fun reset() { scale.floatValue = 1f }
}

@OptIn(ExperimentalPageCurlApi::class)
@Composable
fun CurlPageContent(
    bmp: Bitmap?,
    isRtl: Boolean,
    pageBgColor: Int,
    landscapeZoom: Boolean,
    scaleMode: Int,
    zoomStart: Int,
    bgTexPaint: android.graphics.Paint? = null,
    zoom: CurlZoomState? = null,
) {
    // ★ 缩放统一走 ScalePlan（2026-10-05 重做，唯一公式出处；仿真不可平移 → staticMode 收敛：
    //   贴高/智能对宽图会横向裁掉且拖不回来）。比例、平板防糊封顶、zoomStart 对齐全在此派生；
    //   显示改用显式尺寸 Image（位图尺寸×比例），五种档位一条路径，消灭 ContentScale 分支。
    // landscapeZoom（自动放大横向图片）：仅 contain/铺满档插手，宽图静态贴高、对齐起始缘。
    // 全部页面统一 Image 常驻：曾对当前页用 SSIV（可双指缩放）其余页用 Image，翻页落地
    // 瞬间 AndroidView↔Image 组件类型切换产生空白帧（b12d1e4/bc54d52 双页同类问题的
    // 单页残留），统一 Image 消除切换；细节缩放由双击缩放补（isDoubleTapZoom 门控）。
    // ★ 内层反镜像同摘（外层镜像已摘，保留会变成真镜像显示）；isRtl 参数仍供 alignX 缩放对齐用
    val baseModifier = Modifier.fillMaxSize().clipToBounds()
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

    val isTablet = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp >= 600
    val iw = bmp.width.toFloat()
    val ih = bmp.height.toFloat()
    val wide = iw > ih
    val effMode = ScalePlan.staticMode(scaleMode)
    val landscapeFill = landscapeZoom && ScalePlan.landscapeZoomable(effMode) && wide
    // 对齐：alignX 输出即屏空间语义（RTL 自动=对齐右缘）。★ 内层偏置直接用屏语义——
    // 外层 PageCurl 镜像与本层反镜像作用在同一全宽区域上，对"布局位置"完全抵消
    // （净效果=恒等，只有 artwork 朝向被修正），所以定位不需要再翻一次；
    // 定位→镜像→还原 的往返推导见 2026-10-05 自查修正（此前误加 XOR 翻转，
    // RTL 下对齐边/缩放中心落到镜像错的一侧；LTR 无镜像不受影响）
    val biasX = ScalePlan.alignX(zoomStart, isRtl)
    val align = biasToAlignment(biasX, ScalePlan.alignY(zoomStart))

    // ★ 双击缩放层：套在镜像之后（clip→mirror→zoom→内容，溢出仍被外层 clip 裁住）。
    //   镜像+外层镜像=恒等（位置抵消），transformOrigin 直接用屏空间点按位置即可
    //   （已知近似：「仅画面区域」有 inset 时按整视口归一化，原点略有偏移，仅视觉影响）
    val zoomMod = if (zoom != null) Modifier.graphicsLayer {
        val zs = zoom.scale.floatValue
        scaleX = zs; scaleY = zs
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
            zoom.originX.floatValue,
            zoom.originY.floatValue,
        )
    } else Modifier
    BoxWithConstraints(modifier = baseModifier.then(zoomMod).then(bgMod), contentAlignment = align) {
        val cw = constraints.maxWidth.toFloat()
        val ch = constraints.maxHeight.toFloat()
        val scale = if (landscapeFill) ch / ih
        else ScalePlan.baseScale(effMode, cw, ch, iw, ih, isTablet)
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(
                with(LocalDensity.current) { (iw * scale).toDp() },
                with(LocalDensity.current) { (ih * scale).toDp() },
            ),
            contentScale = ContentScale.Fit,
        )
    }
}

/** ScalePlan 对齐偏置 → Compose Alignment（x: -1起始缘/0居中/+1结束缘；y: -1顶/0居中/+1底） */
internal fun biasToAlignment(biasX: Int, biasY: Int): Alignment = when {
    biasX < 0 && biasY < 0 -> Alignment.TopStart
    biasX > 0 && biasY < 0 -> Alignment.TopEnd
    biasX < 0 && biasY > 0 -> Alignment.BottomStart
    biasX > 0 && biasY > 0 -> Alignment.BottomEnd
    biasX < 0 -> Alignment.CenterStart
    biasX > 0 -> Alignment.CenterEnd
    biasY < 0 -> Alignment.TopCenter
    biasY > 0 -> Alignment.BottomCenter
    else -> Alignment.Center
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
    scaleMode: Int,
    zoomStart: Int,
    zoom: CurlZoomState? = null,
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

    // ★ 2026-10-06 "卷页模式只能点击翻页"修复：StartEndDragInteraction(PageEdge) 的拖拽
    //   交互在实机表现为滑动无效（172100 日志 17:03-17:05 卷页书全程 0 次 swipe 标记，
    //   只有点击翻页）。换 GestureDragInteraction：全屏任意位置按拖拽方向判定翻页，
    //   翻页线锚定手指（Default），松手 settle——与主流漫画阅读器的卷页交互一致。
    val dragInteraction: PageCurlConfig.DragInteraction = PageCurlConfig.GestureDragInteraction()

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
                    // ★ inset 与 CurlPageContent 显示矩形同源（ScalePlan 统一公式+对齐偏置）——
                    //   「仅画面区域」的翻页范围必须跟实际显示矩形一致，否则画面贴边而翻页区
                    //   还按居中算，留边两侧点按/拖拽落空。RTL 注意：padding 在外层镜像之前，
                    //   左右 inset 要按镜像换侧（pre-start 落屏 end）
                    val isTablet = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp >= 600
                    val effMode = ScalePlan.staticMode(scaleMode)
                    val scale = if (landscapeZoom && ScalePlan.landscapeZoomable(effMode) && iw > ih) ch / ih
                    else ScalePlan.baseScale(effMode, cw, ch, iw, ih, isTablet)
                    val dispW = (iw * scale).coerceAtMost(cw)
                    val dispH = (ih * scale).coerceAtMost(ch)
                    if (dispW < cw || dispH < ch) {
                        val gaps = ScalePlan.edgeGaps(ScalePlan.alignX(zoomStart, isRtl), cw, dispW)
                        val gy = ScalePlan.edgeGaps(ScalePlan.alignY(zoomStart), ch, dispH)
                        with(LocalDensity.current) {
                            // ★ 审查修复（2026-10-11）：镜像已摘，这对交换失去了它补偿的对象——
                            //   保留会把 RTL 宽图自动对齐从右缘（阅读起始缘，正确）翻到左缘。
                            //   双页（镜像未摘）的同款交换保留。
                            insetL = gaps.first.toDp()
                            insetR = gaps.second.toDp()
                            insetT = gy.first.toDp()
                            insetB = gy.second.toDp()
                        }
                    }
                }
            }
        }

        val areaModifier = Modifier.fillMaxSize().padding(
            start = insetL, top = insetT, end = insetR, bottom = insetB,
        )
        // ★ RTL 方向统一（报告 §二.2，2026-10-11）：摞镜像——此前外层镜像把卷页手势/几何
        //   一起翻转，RTL 书变成"右滑=下一页"，与 pager 的"左滑永远=下一页"相反（测试工单实锤：
        //   从 pager 切过来按左滑习惯翻回上一页）。摞镜后单页视觉零变化（与内层
        //   反镜像本就互抵，两层同摘），仅手势方向与 pager 对齐。双页的双层镜像与
        //   页序 XOR/spine 四处联动，需真机回归后再摘（本批不动）。
        val curlModifier = areaModifier

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
                scaleMode = scaleMode,
                zoomStart = zoomStart,
                zoom = zoom,
                bgTexPaint = bgPaint,
            )
        }
    }
}
