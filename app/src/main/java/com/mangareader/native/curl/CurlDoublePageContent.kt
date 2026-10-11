package com.mangareader.native.curl

import android.graphics.Bitmap
import com.mangareader.native.SpreadTable
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.mangareader.native.ScalePlan
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
    /** ★ 跨页表（2026-10-05 重做）：跨页序号→物理页映射唯一出处，取代 shift 布尔+序号算术；
     *  智能配对（宽图/长图 solo）重排后视图传新实例即全链路生效 */
    table: SpreadTable,
    isRtl: Boolean,
    doublePageReverse: Boolean,
    pageBgColor: Int,
    cache: CurlBitmapCache,
    landscapeZoom: Boolean,
    scaleMode: Int,
    zoomStart: Int,
    zoom: com.mangareader.native.curl.CurlZoomState? = null,
    curlContentAreaOnly: Boolean,
    backContentAlpha: Float,
    shadowAlpha: Float,
    /** 书脊沟阴影浓度 0~0.3（0=关闭，默认 0.09）：双页书脊贴合处的装订沟暗渐变 */
    spineShadowAlpha: Float = 0.09f,
    paperBackPresetId: Int = -1,
    /** ★ 整屏幕纸纹背景开关/浓度（textureScope=1 时由视图传入） */
    bgTextureOn: Boolean = false,
    bgTextureConcentration: Int = 100,
    reloadTrigger: MutableIntState,
    tapForwardTrigger: MutableIntState,
    tapBackwardTrigger: MutableIntState,
    /** ★ 翻页动画开始（背面层首次求值）即回调目标跨页：视图借此以非预载优先级
     *  立即拉起目标页解码——保证"第 2 页翻动时第 4 页已就位在底下" */
    onFlipTargetNeeded: (Int) -> Unit = {},
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

    // ★ P1-6 对齐单页修复（2026-10-11）：StartEndDragInteraction(PageEdge) 拖拽实机无效
    //   （单页同病根 2026-10-06 已换 GestureDragInteraction，见 CurlReaderContent），
    //   双页漏改导致卷页只能点击翻页
    val dragInteraction: PageCurlConfig.DragInteraction = PageCurlConfig.GestureDragInteraction()

    val tapInteraction = PageCurlConfig.TargetTapInteraction(
        forward = PageCurlConfig.TargetTapInteraction.Config(target = rightHalf),
        backward = PageCurlConfig.TargetTapInteraction.Config(target = leftHalf),
    )

    // ★ 背面纸底色：贴正面"纸调"（同 CurlReaderContent）——
    //   开纸纹：纸底=背景色×该纹理平均反射率（按浓度折算）；关纸纹：正常纸调；
    //   暗主题恒深灰防闪眼
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
        // ★ 背面纸底（0930 用户裁定定稿，同 CurlReaderContent）：背面=镜像内容（透出滑条）
        //   +纯色纸底，纸纹位图退役——config.backPageBitmap 恒空，无残留/花块可能；
        //   纸底色随纸纹开关/preset/浓度实时换算
        LaunchedEffect(pageBgColor, paperBackPresetId, bgTextureConcentration) {
            config.backPageColor = Color(effBackColor)
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
            val curLeft = table.firstPage(state.current)
            val curRight = table.secondPage(state.current)
            val curL = cache.get(curLeft)
            val curR = if (curRight >= 0) cache.get(curRight) else null
            if (curL != null && !curL.isRecycled) {
                val iw = (curL.width + (curR?.width ?: 0)).toFloat()
                val ih = maxOf(curL.height, curR?.height ?: curL.height).toFloat()
                val cw = constraints.maxWidth.toFloat()
                val ch = constraints.maxHeight.toFloat()
                if (iw > 0f && ih > 0f && cw > 0f && ch > 0f) {
                    // ★ 不再插手 landscapeZoom（2026-10-04）：合并跨页天然是宽图，FillHeight
                    //   会水平裁切且无法平移找回——用户要求"4 比 2 宽也不能漏 4"
                    // ★ inset 与 SpreadImage 显示矩形同源（ScalePlan 统一公式+对齐偏置）；
                    //   跨页档位收敛走 spreadMode（只留 完整显示/铺满）；RTL 左右 inset 换侧
                    //   （padding 在外层镜像之前，pre-start 落屏 end）
                    val isTablet = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp >= 600
                    val scale = ScalePlan.baseScale(
                        ScalePlan.spreadMode(scaleMode), cw, ch, iw, ih, isTablet,
                    )
                    val dispW = (iw * scale).coerceAtMost(cw)
                    val dispH = (ih * scale).coerceAtMost(ch)
                    if (dispW < cw || dispH < ch) {
                        val gaps = ScalePlan.edgeGaps(ScalePlan.alignX(zoomStart, isRtl), cw, dispW)
                        val gy = ScalePlan.edgeGaps(ScalePlan.alignY(zoomStart), ch, dispH)
                        with(LocalDensity.current) {
                            insetL = (if (isRtl) gaps.second else gaps.first).toDp()
                            insetR = (if (isRtl) gaps.first else gaps.second).toDp()
                            insetT = gy.first.toDp()
                            insetB = gy.second.toDp()
                        }
                    }
                }
            }
        }

        // 画面矩形计算用的可用尺寸（constraints 不能在嵌套可组合 lambda 里隐式取，先存局部）
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val isTablet = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp >= 600

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
                // ★ 翻页即提权（幂等，loadPageAsync 缓存/去重兜底）：背面层显示的就是目标跨页，
                //   预载若还没轮到它，此刻以非预载(绕过信号量)立即解码
                if (targetSpread in 0 until totalSpreads) onFlipTargetNeeded(targetSpread)
                val tLeft = if (targetSpread in 0 until totalSpreads) cache.get(table.firstPage(targetSpread)) else null
                val tRight = if (targetSpread in 0 until totalSpreads) {
                    val r = table.secondPage(targetSpread)
                    if (r >= 0) cache.get(r) else null
                } else null
                Box {
                    SpreadImage(tLeft, tRight, doublePageReverse, pageBgColor, isRtl, scaleMode, zoomStart, zoom = zoom, bgTexPaint = bgPaint)
                    // 沟阴影画进页面内容层：随纸翻动，不会悬在翻起的纸上方
                SpineGutterShadow(
                    spineShadowAlpha,
                    if (curlContentAreaOnly) null
                    else spineBounds(tLeft, tRight, scaleMode, zoomStart, isRtl, maxW, maxH, isTablet),
                )
                }
            },
        ) { spreadIdx ->
            @Suppress("UNUSED_EXPRESSION")
            reloadTrigger.intValue

            val leftIdx = table.firstPage(spreadIdx)
            val rightIdx = table.secondPage(spreadIdx)
            val leftBmp = cache.get(leftIdx)
            val rightBmp = if (rightIdx >= 0) cache.get(rightIdx) else null
            Box {
                SpreadImage(
                    leftBmp,
                    rightBmp,
                    doublePageReverse,
                    pageBgColor,
                    isRtl,
                    scaleMode,
                    zoomStart,
                    zoom = zoom,
                    bgTexPaint = bgPaint,
                )
                SpineGutterShadow(
                    spineShadowAlpha,
                    if (curlContentAreaOnly) null
                    else spineBounds(leftBmp, rightBmp, scaleMode, zoomStart, isRtl, maxW, maxH, isTablet),
                )
            }
        }
    }
}

/** 画面显示矩形（与 SpreadImage/curlContentAreaOnly 同源：ScalePlan 统一公式，跨页档位
 *  收敛 spreadMode，对齐偏置随 zoomStart）：书脊阴影夹在画面内用。矩形在本层（内层反镜像）
 *  坐标系——SpreadImage 的内层定位偏置与其一致（biasInner=alignX×镜像翻转）。
 *  左页未就绪（翻页背面目标页尚未解码等）返回 null=回退整宽（同旧行为）。 */
private fun spineBounds(
    left: Bitmap?,
    right: Bitmap?,
    scaleMode: Int,
    zoomStart: Int,
    isRtl: Boolean,
    boxW: Float,
    boxH: Float,
    isTablet: Boolean = false,
): Rect? {
    if (left == null || left.isRecycled || boxW <= 0f || boxH <= 0f) return null
    val iw = (left.width + (right?.width ?: 0)).toFloat()
    val ih = maxOf(left.height, right?.height ?: left.height).toFloat()
    if (iw <= 0f || ih <= 0f) return null
    val scale = ScalePlan.baseScale(ScalePlan.spreadMode(scaleMode), boxW, boxH, iw, ih, isTablet)
    val dw = (iw * scale).coerceAtMost(boxW)
    val dh = (ih * scale).coerceAtMost(boxH)
    // 屏空间偏置（同 SpreadImage）：双层镜像对位置抵消，直接用 alignX 屏语义（自查修正）
    val biasX = ScalePlan.alignX(zoomStart, isRtl)
    val gx = ScalePlan.edgeGaps(biasX, boxW, dw)
    val gy = ScalePlan.edgeGaps(ScalePlan.alignY(zoomStart), boxH, dh)
    return Rect(gx.first, gy.first, boxW - gx.second, boxH - gy.second)
}

/** 书脊沟阴影：中缝暗渐变模拟装订沟（浓度可调，0=关闭）。画在页面内容层（静态一层渐变
 *  零性能成本），翻起的纸会盖住阴影而不是被阴影盖住。bounds=画面显示矩形（渐变裁进画面，
 *  不冲进留边）；null=整宽回退（位图未就绪，或「仅画面区域」已把翻页区缩进画面）。 */
@Composable
private fun SpineGutterShadow(alpha: Float, bounds: Rect? = null) {
    if (alpha <= 0f) return
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        if (w > 0f) {
            val b = bounds
            val half = (b?.width ?: w) * 0.055f
            val cx = b?.center?.x ?: (w / 2f)
            val gradient = {
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        ((cx - half) / w).coerceIn(0f, 1f) to Color.Transparent,
                        0.5f to Color.Black.copy(alpha = alpha),
                        ((cx + half) / w).coerceIn(0f, 1f) to Color.Transparent,
                        1f to Color.Transparent,
                    )
                )
            }
            if (b != null && b.width > 0f && b.height > 0f) {
                clipRect(b.left, b.top, b.right, b.bottom) { gradient() }
            } else {
                gradient()
            }
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
    scaleMode: Int,
    zoomStart: Int,
    zoom: com.mangareader.native.curl.CurlZoomState? = null,
    bgTexPaint: android.graphics.Paint? = null,
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

    // ★ 缩放统一走 ScalePlan（2026-10-05 重做）：跨页档位收敛 spreadMode（只留完整显示/铺满），
    //   平板贴宽封顶、zoomStart 对齐全部同源——跨页天然宽图，landscapeZoom 不插手（同前），
    //   贴高/智能/原尺寸收敛为铺满（不可平移防不可找回裁切）。显示改显式尺寸 Image 一条路径。
    val effMode = ScalePlan.spreadMode(scaleMode)

    val mirror = if (isRtl) Modifier.graphicsLayer { scaleX = -1f } else Modifier

    // ★ 整屏幕纸纹（textureScope=1）：画面外留边用外层算好的 bgPaint 铺纸纹（同配方同相位），
    //   平涂背景曾把外层纸纹背景整块盖死；关=平涂原样
    val bgMod = if (bgTexPaint != null) Modifier.drawBehind {
        drawContext.canvas.nativeCanvas.drawColor(pageBgColor)
        drawContext.canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, bgTexPaint)
    } else Modifier.background(Color(pageBgColor))

    val isTablet = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp >= 600
    // 对齐：alignX 输出为屏空间语义（RTL 自动=右缘）；双层镜像对位置抵消，直接用（自查修正）
    // ★ 双击缩放层（同单页）：clip→…→mirror→zoom→内容；origin 用屏空间点按位置
    val zoomMod = if (zoom != null) Modifier.graphicsLayer {
        val zs = zoom.scale.floatValue
        scaleX = zs; scaleY = zs
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
            zoom.originX.floatValue,
            zoom.originY.floatValue,
        )
    } else Modifier
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().clipToBounds().then(bgMod).then(mirror).then(zoomMod),
        contentAlignment = biasToAlignment(ScalePlan.alignX(zoomStart, isRtl), ScalePlan.alignY(zoomStart)),
    ) {
        val img = imgBmp
        if (img != null && img.width > 0) {
            val scale = ScalePlan.baseScale(
                effMode,
                constraints.maxWidth.toFloat(),
                constraints.maxHeight.toFloat(),
                img.width.toFloat(),
                img.height.toFloat(),
                isTablet,
            )
            Image(
                bitmap = img,
                contentDescription = null,
                modifier = Modifier.size(
                    with(LocalDensity.current) { (img.width * scale).toDp() },
                    with(LocalDensity.current) { (img.height * scale).toDp() },
                ),
                contentScale = ContentScale.Fit,
            )
        } else {
            // 跨页未就绪：空位图 1:1 缩进角落不可见，仅露背景纸色（同旧 fillMaxSize 空图观感）
            Image(
                bitmap = emptyBmp,
                contentDescription = null,
                modifier = Modifier.size(with(LocalDensity.current) { 1.dp }, with(LocalDensity.current) { 1.dp }),
                contentScale = ContentScale.Fit,
            )
        }
    }
}