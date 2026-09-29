package com.mangareader.native


import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.mangareader.viewer.ArchiveEngine

interface ReaderViewDelegate {
    /** ★ §34.3：verticalPager=true 表示 VERT_PAGER 竖向翻页模式（ViewPager2 orientation 垂直）。
     *  与 isVertical（条漫）语义分离。旧参数 vertical 保留语义兼容。 */
    fun setup(engine: ArchiveEngine, treePath: String, pages: List<ArchiveEngine.PageMeta>, startPage: Int, vertical: Boolean, clickFlip: Int, rtl: Boolean, verticalPager: Boolean = false)
    fun goToPage(page: Int)
    fun release()
    fun trimMemory(level: Int)
    var onPageChanged: ((Int) -> Unit)?
    var onPageCountChanged: ((Int) -> Unit)?
    var onToggleUi: (() -> Unit)?
    /** ★ v1.13：当前页是否已渲染出内容（无感切换遮罩的就绪信号；mihon 式"数据就绪才可见"） */
    fun isCurrentPageReady(): Boolean

    /** ★ P0-2 模式切换位图交接：把本视图缓存里还活着的位图"取出"（从缓存移除，
     *  随后 release() 只回收未移交的，不会把这些位图 recycle 掉）。动图视图不移交。 */
    fun drainLiveBitmaps(): Map<Int, android.graphics.Bitmap> = emptyMap()

    /** ★ P0-2 模式切换位图交接：把旧视图移交的位图接管进缓存，新视图首次绑定即命中，零重解码 */
    fun adoptBitmaps(map: Map<Int, android.graphics.Bitmap>) {}
}

/** ★ C4 交接所有权令牌（白屏工单 v2.4）：adopt 过的批次归新视图缓存管；
 *  从未被 adopt 的批次才允许兜底回收，且必须超时（2s）——
 *  Reader.kt 用 key() 包裹 NES，切模式=子树重建，正常移交窗口只有几十毫秒。 */
class BitmapHandoff(val bitmaps: Map<Int, android.graphics.Bitmap>, val treePath: String = "") {
    @Volatile var adopted: Boolean = false
    private val createdAt = android.os.SystemClock.uptimeMillis()
    fun isStale(): Boolean = android.os.SystemClock.uptimeMillis() - createdAt > 2000L
    fun recycleIfUnadopted() {
        if (adopted) return
        bitmaps.values.forEach { bmp -> try { bmp.recycle() } catch (_: Throwable) {} }
    }
}

/** ★ 交接总线（进程级单例）：Reader.kt 的 key() 使 NES 切模式时整树重建，
 *  remember 级交接引用随实例销毁导致 adopt 永远收不到（P02 adopt 全场零调用的根源）。
 *  总线跨实例移交；put 覆盖时回收超时未消费的旧批次。 */
object SrBitmapHandoffBus {
    private val ref = java.util.concurrent.atomic.AtomicReference<BitmapHandoff?>(null)

    fun put(h: BitmapHandoff) {
        val prev = ref.getAndSet(h)
        if (prev != null && !prev.adopted && prev.isStale()) prev.recycleIfUnadopted()
    }

    fun take(): BitmapHandoff? {
        val h = ref.getAndSet(null) ?: return null
        if (h.adopted) return null
        return h
    }

    /** 兜底回收：仅超时未消费的批次（正常模式切换的新鲜批次放回去等新实例 adopt） */
    fun recycleStale() {
        val h = ref.getAndSet(null) ?: return
        if (h.adopted || h.isStale()) {
            h.recycleIfUnadopted()
        } else {
            ref.compareAndSet(null, h) // 新鲜批次放回，等接管
        }
    }
}

/** ★ 附录 B.3 修复：三个阅读视图的 trimMemory 过去全项目无人调用（缓存无回收机制）。
 *  现在经本注册表由 MainActivity.onTrimMemory 广播。WeakHashMap 持键，不会挂住已释放视图。 */
object ReaderTrimRegistry {
    private val delegates = java.util.WeakHashMap<ReaderViewDelegate, Boolean>()
    fun register(d: ReaderViewDelegate) = synchronized(delegates) { delegates[d] = true }
    fun clear() = synchronized(delegates) { delegates.clear() }
    fun trimAll(level: Int) {
        val snapshot = synchronized(delegates) { delegates.keys.toList() }
        // ★ 白屏工单任务1：观测 trim 触发频率/级别（C2 定论的最后一块直接证据）
        android.util.Log.w("TrimRegistry", "level=$level n=${snapshot.size}")
        snapshot.forEach { try { it.trimMemory(level) } catch (_: Throwable) {} }
    }
}


@Composable
fun NativeReaderScreen(
    engine: ArchiveEngine,
    treePath: String,
    pages: List<ArchiveEngine.PageMeta>,
    startPage: Int,
    isVertical: Boolean,
    isRtl: Boolean = false,
    grayscale: Boolean = false,
    invertColors: Boolean = false,
    centerGapType: Int = 0,
    landscapeZoom: Boolean = true,
    fitScreen: Boolean = false,
    trimWhiteBorder: Boolean = false,
    trimThreshold: Int = 220,
    trimWhiteRatio: Float = 0.90f,
    panNavigation: Boolean = false,
    pageLayout: Int = 0,
    pageBgColor: Int = android.graphics.Color.parseColor("#FFFFFF"),
    doublePageReverse: Boolean = false,
    doublePageSplit: Boolean = false,
    tileModeEnabled: Boolean = false,
    /** ★ §34.3：VERT_PAGER 模式——竖向翻页走 PagerReaderView（不是 webtoon 连续滚动） */
    verticalPager: Boolean = false,
    flipAnimationEnabled: Boolean = true,
    pageCurlAnimationEnabled: Boolean = false,
    /** 双页 curl「仅画面区域」模式：翻页动画范围缩到画面矩形，留边不参与 */
    curlContentAreaOnly: Boolean = false,
    /** 双页 curl 翻页背面透出程度 0~1：背面显示下一页内容的强度（0=纯背景色，1=完全清晰） */
    curlBackContentAlpha: Float = 0.1f,
    /** 仿真翻页投影阴影浓度 0~0.5（0=关闭阴影，默认 0.2），单页/双页 curl 共用 */
    curlShadowAlpha: Float = 0.2f,
    /** ★ 墨水屏灰阶抖动（256级模拟）：解码管线参数，变更经 appliedDither SideEffect 即时生效 */
    einkDither: Boolean = false,
    einkDitherAlgorithm: Int = 0,
    einkDitherLevels: Int = 16,
    einkEndpointPassthrough: Int = 0,
    brightness: Int = 0,
    screenBrightness: Int = 0,
    contrast: Int = 100,
    gamma: Int = 100,
    textureEnabled: Boolean = false,
    texturePresetId: Int = 0,
    textureConcentration: Int = 100,
    /** ★ SMB/WebDAV 页面供给（design.md 2.2.2）：非 null 时阅读页面走远程会话。
     *   以参数形式在 setup() 之前注入 delegate，消除"setup 早于供给挂接"的竞态窗口。 */
    remoteSupply: com.mangareader.native.RemotePageSupply? = null,
    modifier: Modifier = Modifier,
    onPageChanged: (Int) -> Unit = {},
    onPageCountChanged: (Int) -> Unit = {},
    onToggleUi: () -> Unit = {},
    onViewReady: (webtoonView: View, pagerView: View) -> Unit = { _, _ -> },
    onChapterEnd: (Boolean) -> Unit = {},
    /** ★ v1.13 C-2：把内容容器暴露给上层做切换前同步截图（无感模式切换遮罩的数据源） */
    onContainerReady: (FrameLayout) -> Unit = {}
) {
    val ctx = LocalContext.current
    var savedPage by remember { mutableIntStateOf(startPage) }
    var firstComposition by remember { mutableStateOf(true) }
    var containerRef by remember { mutableStateOf<FrameLayout?>(null) }
    var brightnessOverlayAlpha by remember { mutableStateOf(0f) }
    var brightnessOverlayRef by remember { mutableStateOf<View?>(null) }

    val webtoonView = remember { WebtoonReaderView(ctx).apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) } }
    val pagerView = remember { PagerReaderView(ctx).apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) } }
    val curlReaderView = remember { com.mangareader.native.curl.CurlReaderView(ctx).apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) } }
    val curlDoublePageView = remember { com.mangareader.native.curl.CurlDoublePageView(ctx).apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) } }

    val useCurl = com.mangareader.native.curl.isCurlEnabled(
        flipAnimationEnabled = flipAnimationEnabled,
        pageCurlAnimationEnabled = pageCurlAnimationEnabled,
        isVertical = isVertical,
        verticalPager = verticalPager,

        doublePageSplit = doublePageSplit,
        tileModeEnabled = tileModeEnabled,
    )

    var curlDisabledByAnim by remember { mutableStateOf(false) }

    LaunchedEffect(treePath, useCurl) {
        if (useCurl && remoteSupply == null) {
            val checkRange = (maxOf(0, startPage - 2)..minOf(pages.size - 1, startPage + 2))
            var found = false
            for (i in checkRange) {
                try {
                    val kind = engine.peekAnimKindFast(treePath, pages[i])
                    if (kind != com.mangareader.viewer.AnimKind.NONE) {
                        found = true
                        break
                    }
                } catch (_: Exception) {}
            }
            curlDisabledByAnim = found
        } else {
            curlDisabledByAnim = false
        }
    }

    val effectiveUseCurl = useCurl && !curlDisabledByAnim

    fun activeDelegate(isVerticalNow: Boolean): ReaderViewDelegate =
        if (isVerticalNow) webtoonView
        else if (effectiveUseCurl && pageLayout != 0) curlDoublePageView
        else if (effectiveUseCurl) curlReaderView
        else pagerView

    // Track last released delegate to avoid double release
    var lastReleasedDelegate by remember { mutableStateOf<ReaderViewDelegate?>(null) }

    // ★ P0-2 切换交接容器：onDispose 时 old.drain → 新 effect body 里 new.adopt。
    //   没有任何人接管的兜底（退出阅读）：下面这个 Unit effect 反方向最晚 dispose，发现残留会统一回收。
    // ★ C4 交接：Reader.kt 的 key() 使切模式时 NES 整树重建，交接走进程级总线跨实例移交。
    //   Unit 兜底只回收"超时未消费"批次，正常切换的新鲜批次等新实例接管。
    DisposableEffect(Unit) {
        // 三视图的 trimMemory 一并接线（修复"trimMemory 全项目无调用方"）
        ReaderTrimRegistry.register(webtoonView)
        ReaderTrimRegistry.register(pagerView)
        ReaderTrimRegistry.register(curlReaderView)
        ReaderTrimRegistry.register(curlDoublePageView)

        onDispose {
            // Compose 逆序 dispose：keyed effect 先 dispose（drain 完成）→ 本 Unit effect 最后兜底
            SrBitmapHandoffBus.recycleStale()
            ReaderTrimRegistry.clear()
        }
    }

    DisposableEffect(treePath, isVertical, isRtl, verticalPager, remoteSupply, flipAnimationEnabled, pageCurlAnimationEnabled, curlContentAreaOnly, pageLayout, tileModeEnabled, effectiveUseCurl, doublePageReverse, doublePageSplit) {
        if (firstComposition) {
            savedPage = startPage
            firstComposition = false
        }
        val delegate = activeDelegate(isVertical)
        // Release previous delegate only once
        val prevDelegate = lastReleasedDelegate
        if (prevDelegate != null && prevDelegate !== delegate) {
            prevDelegate.release()
        }
        lastReleasedDelegate = delegate
        delegate.onPageChanged = { page -> savedPage = page; onPageChanged(page) }
        delegate.onPageCountChanged = { count -> onPageCountChanged(count) }
        delegate.onToggleUi = onToggleUi
        // 先设置颜色再setup，确保adapter创建时用正确颜色
        if (delegate is PagerReaderView) delegate.pageBgColor = pageBgColor
        if (delegate is WebtoonReaderView) delegate.pageBgColor = pageBgColor
        // ★ SMB/WebDAV：供给必须在 setup 之前挂接——setup 内 preloadAround 会立刻触发 loadPageAsync
        if (delegate is PagerReaderView) delegate.remotePageSupply = remoteSupply
        if (delegate is WebtoonReaderView) delegate.remotePageSupply = remoteSupply
        if (delegate is PagerReaderView) delegate.setTileModeEnabled(tileModeEnabled)
        if (delegate is WebtoonReaderView) delegate.setTileModeEnabled(tileModeEnabled)
        if (delegate is com.mangareader.native.curl.CurlReaderView) delegate.remotePageSupply = remoteSupply
        if (delegate is com.mangareader.native.curl.CurlDoublePageView) delegate.remotePageSupply = remoteSupply
        // ★ 纸张纹理：全局烘焙设置必须在 setup 前同步——setup 内 preloadAround 立刻开始解码，
        //   晚了首页就会以无纹理位图入缓存（appliedTexture SideEffect 只管运行时变化）
        com.mangareader.filter.PaperPageTexture.sync(ctx, textureEnabled, texturePresetId, textureConcentration)
        // ★ §34.3：verticalPager 传给 pagerView.setup（webtoon 走默认 false 无影响）
        delegate.setup(engine, treePath, pages, savedPage, isVertical, 0, isRtl, verticalPager)
        // Apply new config parameters to the delegate
        if (delegate is PagerReaderView) {
            delegate.setCenterGapType(centerGapType)
            delegate.setLandscapeZoom(landscapeZoom)
            delegate.setFitScreen(fitScreen)
            delegate.setTrimWhiteBorder(trimWhiteBorder, trimThreshold, trimWhiteRatio)
            delegate.setPanNavigation(panNavigation)
            delegate.setEinkDither(einkDither, einkDitherAlgorithm, einkDitherLevels, einkEndpointPassthrough)
        }
        if (delegate is PagerReaderView) {
            delegate.setDoublePageReverse(doublePageReverse)
            delegate.setDoublePageSplit(doublePageSplit)
        }
        if (delegate is WebtoonReaderView) {
            delegate.pageBgColor = pageBgColor
            delegate.trimWhiteBorder = trimWhiteBorder
            delegate.trimThreshold = trimThreshold
            delegate.trimWhiteRatio = trimWhiteRatio
            delegate.setEinkDither(einkDither, einkDitherAlgorithm, einkDitherLevels, einkEndpointPassthrough)
        }
        if (delegate is com.mangareader.native.curl.CurlReaderView) {
            delegate.pageBgColor = pageBgColor
            delegate.landscapeZoom = landscapeZoom
            delegate.fitScreen = fitScreen
            delegate.curlContentAreaOnly = curlContentAreaOnly
            delegate.setTrimWhiteBorder(trimWhiteBorder, trimThreshold, trimWhiteRatio)
            delegate.setPanNavigation(panNavigation)
            delegate.setEinkDither(einkDither, einkDitherAlgorithm, einkDitherLevels, einkEndpointPassthrough)
            delegate.onChapterEnd = onChapterEnd
        }
        if (delegate is com.mangareader.native.curl.CurlDoublePageView) {
            delegate.pageBgColor = pageBgColor
            delegate.landscapeZoom = landscapeZoom
            delegate.fitScreen = fitScreen
            delegate.curlContentAreaOnly = curlContentAreaOnly
            delegate.setTrimWhiteBorder(trimWhiteBorder, trimThreshold, trimWhiteRatio)
            delegate.setPanNavigation(panNavigation)
            delegate.setEinkDither(einkDither, einkDitherAlgorithm, einkDitherLevels, einkEndpointPassthrough)

            delegate.setDoublePageModeAndShift(pageLayout == 1 || pageLayout == 2, pageLayout == 2)
            delegate.setDoublePageReverse(doublePageReverse)
            delegate.onChapterEnd = onChapterEnd
        }
        // ★ P0-2：切换交接——本次 setup 后立即从总线接管旧实例移交的位图（跨实例），首帧绑定直接命中缓存
        android.util.Log.w("NES", "setup delegate=${System.identityHashCode(delegate)} v=$isVertical vp=$verticalPager tree=$treePath")
        SrBitmapHandoffBus.take()?.let { h ->
            // ★ F2.3 身份标签：批次归属与当前章不符（切章/换书时 key() 重建的竞态窗口）
            //   不接管并立刻回收旧图防错配，新视图按未命中流程正常解码
            if (h.treePath == treePath) {
                h.adopted = true
                delegate.adoptBitmaps(h.bitmaps)
            } else {
                engine.removeBitmapsByValue(h.bitmaps.values)
                h.recycleIfUnadopted()
            }
        }
        onDispose {
            // 顺序关键：先 drain（位图从缓存挪走，归属权移交），再 release（残留位图统一回收）
            android.util.Log.w("NES", "dispose delegate=${System.identityHashCode(delegate)}")
            SrBitmapHandoffBus.put(BitmapHandoff(delegate.drainLiveBitmaps(), treePath))
            delegate.release()
            lastReleasedDelegate = null
        }
    }

    // ★ 背面透出程度/阴影浓度走轻量同步：故意不进上面 DisposableEffect 的 key——滑块拖动
    //   会高频触发，重启该 effect 会把 delegate.setup 整个重跑（重载页面）。重组提交后直接
    //   写 delegate 侧的 Compose state 字段，由其内部重组即时生效（写相同值无副作用）。
    SideEffect {
        val delegate = activeDelegate(isVertical)
        if (delegate is com.mangareader.native.curl.CurlDoublePageView) {
            delegate.curlBackContentAlpha = curlBackContentAlpha
            delegate.curlShadowAlpha = curlShadowAlpha
        } else if (delegate is com.mangareader.native.curl.CurlReaderView) {
            delegate.curlBackContentAlpha = curlBackContentAlpha
            delegate.curlShadowAlpha = curlShadowAlpha
        }
    }

    // ★ 灰阶抖动参数即时生效：变化即清缓存重载当前页（约 40~90ms/页）。remember 初值取
    //   当前参数，避免首次组合与 DisposableEffect setup 的 setEinkDither 重复触发重载；
    //   抖动不进上面 DisposableEffect 的 key（重启会整跑 setup），走此轻量通道
    var appliedDither by remember { mutableStateOf(listOf(einkDither, einkDitherAlgorithm, einkDitherLevels, einkEndpointPassthrough)) }
    SideEffect {
        val cur = listOf(einkDither, einkDitherAlgorithm, einkDitherLevels, einkEndpointPassthrough)
        if (appliedDither != cur) {
            appliedDither = cur
            when (val d = activeDelegate(isVertical)) {
                is WebtoonReaderView -> d.applyEinkDitherNow(einkDither, einkDitherAlgorithm, einkDitherLevels, einkEndpointPassthrough)
                is PagerReaderView -> d.applyEinkDitherNow(einkDither, einkDitherAlgorithm, einkDitherLevels, einkEndpointPassthrough)
                is com.mangareader.native.curl.CurlReaderView -> d.applyEinkDitherNow(einkDither, einkDitherAlgorithm, einkDitherLevels, einkEndpointPassthrough)
                is com.mangareader.native.curl.CurlDoublePageView -> d.applyEinkDitherNow(einkDither, einkDitherAlgorithm, einkDitherLevels, einkEndpointPassthrough)
                else -> {}
            }
        }
    }

    // Handle pageLayout: SINGLE=0, DOUBLE=1, DOUBLE_SHIFTED=2
    LaunchedEffect(isVertical, isRtl, pageLayout) {
        if (!isVertical && pagerView is PagerReaderView) {
            val doubleMode = pageLayout == 1 || pageLayout == 2
            val shiftMode = pageLayout == 2
            // Single update: set both flags then refresh once
            pagerView.setDoublePageModeAndShift(doubleMode, shiftMode)
        }
    }


    LaunchedEffect(startPage) {
        android.util.Log.d("WebtoonRV", "NES LaunchedEffect startPage=$startPage savedPage=$savedPage")
        if (startPage != savedPage) {
            savedPage = startPage
            // ★ P0-B5 验证日志（根因修复确认后删除）
            android.util.Log.d("WebtoonRV", "NES -> goToPage($startPage)")
            activeDelegate(isVertical).goToPage(startPage)
        }
    }

    // Apply grayscale/invert/brightness/contrast/gamma via hardware layer paint
    LaunchedEffect(grayscale, invertColors, brightness, contrast, gamma) {
        containerRef?.let { container ->
            val paint = com.mangareader.filter.GammaShaderProvider.getEffectivePaint(
                grayscale, invertColors, brightness, contrast, gamma
            )
            if (paint != null) {
                container.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
            } else {
                container.setLayerType(View.LAYER_TYPE_NONE, null)
            }
            val renderEffect = com.mangareader.filter.GammaShaderProvider.createGammaRenderEffect(gamma)
            if (renderEffect != null && android.os.Build.VERSION.SDK_INT >= 31) {
                container.setRenderEffect(renderEffect)
            } else if (android.os.Build.VERSION.SDK_INT >= 31) {
                container.setRenderEffect(null)
            }
        }
    }

    LaunchedEffect(screenBrightness) {
        com.mangareader.filter.BrightnessController.applyBrightness(
            screenBrightness, (ctx as? android.app.Activity)?.window, ctx
        ) { alpha -> brightnessOverlayAlpha = alpha }
    }

    SideEffect {
        brightnessOverlayRef?.let { overlay ->
            overlay.alpha = brightnessOverlayAlpha
            overlay.visibility = if (brightnessOverlayAlpha > 0f) View.VISIBLE else View.GONE
        }
    }

    // ★ 纸张纹理烘焙通道：全局 PaperPageTexture 设置运行时变化（阅读内面板拖动）→ 同步设置
    //  （内部升代，旧位图视为未贴）+ 当前 delegate 清缓存按需重载。首组合同步在上方
    //   DisposableEffect setup 里做；remember 初值取当前参数避免与 setup 重复触发（eink 同款）
    var appliedTexture by remember { mutableStateOf(listOf(textureEnabled, texturePresetId, textureConcentration)) }
    SideEffect {
        val cur = listOf(textureEnabled, texturePresetId, textureConcentration)
        if (appliedTexture != cur) {
            appliedTexture = cur
            com.mangareader.filter.PaperPageTexture.sync(ctx, textureEnabled, texturePresetId, textureConcentration)
            when (val d = activeDelegate(isVertical)) {
                is WebtoonReaderView -> d.applyPaperTextureNow()
                is PagerReaderView -> d.applyPaperTextureNow()
                is com.mangareader.native.curl.CurlReaderView -> d.applyPaperTextureNow()
                is com.mangareader.native.curl.CurlDoublePageView -> d.applyPaperTextureNow()
                else -> {}
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            com.mangareader.filter.BrightnessController.reset()
            val window = (ctx as? android.app.Activity)?.window
            if (window != null) {
                window.attributes = window.attributes.apply {
                    this.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }

    AndroidView(
        factory = {
            // 两层结构：container 是滤镜层（灰度/反色/对比度/图片亮度经硬件层 paint 作用于此），
            // brightnessOverlay 放在 container 之外的 root 上——屏幕调暗是面板行为，若在滤镜层内，
            // 图片亮度的加法提升会把黑遮罩抬灰（调暗效果被部分抵消的物理矛盾）
            val root = FrameLayout(ctx).apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
            val container = FrameLayout(ctx).apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
            container.addView(webtoonView)
            container.addView(pagerView)
            container.addView(curlReaderView)
            container.addView(curlDoublePageView)
            val brightnessOverlay = View(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(android.graphics.Color.BLACK)
                alpha = 0f
                visibility = View.GONE
            }
            root.addView(container)
            root.addView(brightnessOverlay)
            brightnessOverlayRef = brightnessOverlay
            fun syncVis() {
                webtoonView.visibility = if (isVertical) View.VISIBLE else View.GONE
                pagerView.visibility = if (!isVertical && !effectiveUseCurl) View.VISIBLE else View.GONE
                curlReaderView.visibility = if (!isVertical && effectiveUseCurl && pageLayout == 0) View.VISIBLE else View.GONE
                curlDoublePageView.visibility = if (!isVertical && effectiveUseCurl && pageLayout != 0) View.VISIBLE else View.GONE
            }
            syncVis()
            containerRef = container
            onViewReady(webtoonView, pagerView)
            onContainerReady(container)
            root
        },
        update = { container ->
            webtoonView.visibility = if (isVertical) View.VISIBLE else View.GONE
            pagerView.visibility = if (!isVertical && !effectiveUseCurl) View.VISIBLE else View.GONE
            curlReaderView.visibility = if (!isVertical && effectiveUseCurl && pageLayout == 0) View.VISIBLE else View.GONE
            curlDoublePageView.visibility = if (!isVertical && effectiveUseCurl && pageLayout != 0) View.VISIBLE else View.GONE
        },
        modifier = modifier
    )
}
