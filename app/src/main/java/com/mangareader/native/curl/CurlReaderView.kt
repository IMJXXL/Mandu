package com.mangareader.native.curl

import android.content.Context
import android.graphics.Bitmap
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import com.mangareader.viewer.ArchiveEngine
import com.mangareader.native.ReaderViewDelegate
import com.mangareader.native.RemotePageSupply
import com.mangareader.native.NavigationAction
import com.mangareader.native.ViewerNavigation
import com.mangareader.native.DefaultNavigation
import com.mangareader.native.AllMenuNavigation
import com.mangareader.native.LNavigation
import com.mangareader.native.InvertedNavigation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class CurlReaderView(context: Context) : FrameLayout(context), ReaderViewDelegate {

    private val reloadTrigger = mutableIntStateOf(0)
    private val tapForwardTrigger = mutableIntStateOf(0)
    private val tapBackwardTrigger = mutableIntStateOf(0)
    private val composeView = ComposeView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }
    private val cache = CurlBitmapCache(12)
    private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var loader = CurlPageLoader(context, cache, scope)

    private var engine: ArchiveEngine? = null
    private var treePath: String = ""
    private var pageList: List<ArchiveEngine.PageMeta> = emptyList()
    // 同 CurlDoublePageView：当前页用 Compose 状态承载，goToPage（目录/书签跳转）才能
    // 经重组把新 currentPage 传进 CurlReaderContent 触发 state.snapTo，否则画面与进度脱节
    private val currentPageState = mutableIntStateOf(0)
    private var currentPage: Int
        get() = currentPageState.intValue
        set(value) { currentPageState.intValue = value }
    private var isRtl = false

    var pageBgColor: Int = android.graphics.Color.parseColor("#FFFFFF")
    var landscapeZoom: Boolean = false
    var fitScreen: Boolean = false
    private val curlContentAreaOnlyState = mutableStateOf(false)
    var curlContentAreaOnly: Boolean
        get() = curlContentAreaOnlyState.value
        set(value) { curlContentAreaOnlyState.value = value }
    // 翻页阴影浓度用 Compose 状态承载（NES 侧经 SideEffect 同步，拖滑块即重组生效）
    private val curlShadowAlphaState = mutableFloatStateOf(0.2f)
    var curlShadowAlpha: Float
        get() = curlShadowAlphaState.floatValue
        set(value) { curlShadowAlphaState.floatValue = value }
    // ★ 纸背纹理 preset（-1=不启用；NES SideEffect 写入）
    private val paperBackPresetIdState = mutableIntStateOf(-1)
    var paperBackPresetId: Int
        get() = paperBackPresetIdState.intValue
        set(value) { paperBackPresetIdState.intValue = value }
    // ★ 整屏幕纸纹（textureScope=1）：背景铺纹理开关与浓度（NES 按 config 传入）
    private val paperBgOnState = mutableStateOf(false)
    var paperBgOn: Boolean
        get() = paperBgOnState.value
        set(value) { paperBgOnState.value = value }
    private val paperBgConcState = mutableIntStateOf(100)
    var paperBgConcentration: Int
        get() = paperBgConcState.intValue
        set(value) { paperBgConcState.intValue = value }
    // 背面透出程度同上：单页翻页背面画的是当前页镜像，该值控制背面纸的明暗可见度
    private val curlBackContentAlphaState = mutableFloatStateOf(0.1f)
    var curlBackContentAlpha: Float
        get() = curlBackContentAlphaState.floatValue
        set(value) { curlBackContentAlphaState.floatValue = value }
    var remotePageSupply: RemotePageSupply? = null
        set(value) {
            field = value
        }

    override var onPageChanged: ((Int) -> Unit)? = null
    override var onPageCountChanged: ((Int) -> Unit)? = null
    override var onToggleUi: (() -> Unit)? = null
    var onChapterEnd: ((Boolean) -> Unit)? = null
    private val flipSound = CurlFlipSound(context)

    private var clickFlipType: Int = 1
    private var clickFlipInvertH = false
    private var navigation: ViewerNavigation = DefaultNavigation

    private fun applyNavigation() {
        val base = when (clickFlipType) {
            0 -> AllMenuNavigation
            4 -> LNavigation
            else -> DefaultNavigation
        }
        navigation = if (clickFlipInvertH) InvertedNavigation(base) else base
    }

    override fun setClickFlipInvert(invert: Boolean) {
        if (clickFlipInvertH == invert) return
        clickFlipInvertH = invert
        applyNavigation()
    }

    override fun setClickFlipTypeLive(type: Int) {
        if (clickFlipType == type) return
        clickFlipType = type
        applyNavigation()
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (width == 0 || height == 0) return false
            val fx = e.x / width
            val fy = e.y / height
            // RTL 与 pager 同款：区域固定（左 PREV/右 NEXT），RTL 在动作层翻转
            when (navigation.getAction(fx, fy)) {
                NavigationAction.PREV -> if (isRtl) tapForwardTrigger.intValue++ else tapBackwardTrigger.intValue++
                NavigationAction.NEXT -> if (isRtl) tapBackwardTrigger.intValue++ else tapForwardTrigger.intValue++
                NavigationAction.MENU -> onToggleUi?.invoke()
            }
            return true
        }
    })

    init {
        addView(composeView)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        super.dispatchTouchEvent(ev)
        gestureDetector.onTouchEvent(ev)
        return true
    }


    fun setTrimWhiteBorder(trim: Boolean, threshold: Int = 220, whiteRatio: Float = 0.90f) {
        loader.trimWhiteBorder = trim
        loader.trimThreshold = threshold
        loader.trimWhiteRatio = whiteRatio
    }

    /** ★ 墨水屏灰阶抖动透传 loader（单/双页 curl 共用解码管线） */
    fun setEinkDither(enabled: Boolean, algorithm: Int, levels: Int, endpointPassthrough: Int = 0) {
        loader.einkDither = enabled
        loader.einkDitherAlgorithm = algorithm
        loader.einkDitherLevels = levels
        loader.einkEndpointPassthrough = endpointPassthrough
    }

    /** ★ 抖动参数即时生效：清缓存重载当前页周边（重组短暂白帧后按新参数回显，低频操作可接受） */
    fun applyEinkDitherNow(enabled: Boolean, algorithm: Int, levels: Int, endpointPassthrough: Int = 0) {
        if (loader.einkDither == enabled && loader.einkDitherAlgorithm == algorithm && loader.einkDitherLevels == levels && loader.einkEndpointPassthrough == endpointPassthrough) return
        loader.einkDither = enabled
        loader.einkDitherAlgorithm = algorithm
        loader.einkDitherLevels = levels
        loader.einkEndpointPassthrough = endpointPassthrough
        cache.clear()
        reloadTrigger.intValue++
        loader.preloadAround(currentPage, pageList.size) { reloadTrigger.intValue++ }
    }

    /** ★ 纸张纹理即时生效：全局 PaperPageTexture 已由 NES 先行更新（含升代），清缓存重载 */
    fun applyPaperTextureNow() {
        cache.clear()
        loader.clearBakeTracking()   // 换纹理后旧"已烘焙"标记作废，否则新浓度不会重烘
        reloadTrigger.intValue++
        loader.preloadAround(currentPage, pageList.size) { reloadTrigger.intValue++ }
    }

    fun setPanNavigation(pan: Boolean) {
    }

    override fun setup(
        engine: ArchiveEngine,
        treePath: String,
        pages: List<ArchiveEngine.PageMeta>,
        startPage: Int,
        vertical: Boolean,
        clickFlip: Int,
        rtl: Boolean,
        verticalPager: Boolean,
    ) {
        this.engine = engine
        this.treePath = treePath
        this.pageList = pages
        this.currentPage = startPage
        this.isRtl = rtl
        // ★ 点击翻页区域接线（此前参数收下零使用、硬编码三分——设置静默失效）
        if (clickFlipType != clickFlip) {
            clickFlipType = clickFlip
            applyNavigation()
        }
        // ★ 上报真实页数，防上一章条漫的展开页数残留（见 PagerReaderView.setup 同注）
        onPageCountChanged?.invoke(pageList.size)
        loader.bind(engine, treePath, pages, remotePageSupply)
        loader.setCurrentPage(startPage)
        renderCompose()
        loader.preloadAround(startPage, pages.size) { idx ->
            reloadTrigger.intValue++
        }
    }

    private fun renderCompose() {
        composeView.setContent {
            CurlReaderContent(
                totalPages = pageList.size,
                currentPage = currentPage,
                isRtl = isRtl,
                pageBgColor = pageBgColor,
                cache = cache,
                landscapeZoom = landscapeZoom,
                fitScreen = fitScreen,
                backContentAlpha = curlBackContentAlpha,
                shadowAlpha = curlShadowAlpha,
                paperBackPresetId = paperBackPresetId,
                bgTextureOn = paperBgOn,
                bgTextureConcentration = paperBgConcentration,
                reloadTrigger = reloadTrigger,
                tapForwardTrigger = tapForwardTrigger,
                tapBackwardTrigger = tapBackwardTrigger,
                onPageChanged = { newPage ->
                    // 翻页落地（state.current 提交）才响，目录跳页 snapTo 不响，与双页语义一致
                    flipSound.play()
                    currentPage = newPage
                    loader.setCurrentPage(newPage)
                    cache.protectThreeLayers(newPage)
                    loader.preloadAround(newPage, pageList.size) { idx ->
                        reloadTrigger.intValue++
                    }
                    if (newPage == pageList.size - 1) onChapterEnd?.invoke(true)
                    if (newPage == 0) onChapterEnd?.invoke(false)
                    onPageChanged?.invoke(newPage)
                },
                curlContentAreaOnly = curlContentAreaOnly,
            )
        }
    }

    override fun goToPage(page: Int) {
        if (page < 0 || page >= pageList.size) return
        currentPage = page
        loader.setCurrentPage(page)
        reloadTrigger.intValue++
        loader.preloadAround(page, pageList.size) { idx ->
            reloadTrigger.intValue++
        }
    }

    override fun release() {
        flipSound.release()
        loader.cancelAll()
        scope.cancel()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        loader = CurlPageLoader(context, cache, scope)
        composeView.disposeComposition()
    }

    override fun trimMemory(level: Int) {
        cache.trimNonProtected(level)
    }

    override fun isCurrentPageReady(): Boolean {
        val bmp = cache.get(currentPage)
        return bmp != null && !bmp.isRecycled
    }

    override fun drainLiveBitmaps(): Map<Int, Bitmap> {
        return cache.drainAll()
    }

    override fun adoptBitmaps(map: Map<Int, Bitmap>) {
        cache.adopt(map)
    }
}