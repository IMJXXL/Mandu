package com.mangareader.native.curl

import android.content.Context
import android.graphics.Bitmap
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import com.mangareader.viewer.ArchiveEngine
import com.mangareader.native.ReaderViewDelegate
import com.mangareader.native.RemotePageSupply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class CurlDoublePageView(context: Context) : FrameLayout(context), ReaderViewDelegate {

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
    // 当前 spread 页索引用 Compose 状态承载：renderCompose 根作用域读它，goToPage（目录/书签
    // 跳转）改值才能触发重组把新 currentSpread 传进 CurlDoublePageContent，其
    // LaunchedEffect(currentSpread)→state.snapTo 才会执行。曾经是普通字段，参数闭包过期，
    // 跳转后画面停在旧页而进度照走（画面与进度脱节）
    private val currentPageState = mutableIntStateOf(0)
    private var currentPage: Int
        get() = currentPageState.intValue
        set(value) { currentPageState.intValue = value }
    private var isRtl = false
    private var doublePageShift = false
    private var doublePageReverse = false

    var pageBgColor: Int = android.graphics.Color.parseColor("#FFFFFF")
    var landscapeZoom: Boolean = false
    var fitScreen: Boolean = false
    // 「仅画面区域」用 Compose 状态承载：NES 里 delegate.setup()（内部 renderCompose 组合）
    // 先执行、本字段赋值在后，普通 var 赋值不触发重组 → 勾选设置后当前页不变，翻页时
    // 其他状态变化引起重组才读到新值。改 state 后赋值即重组，当前页立即应用留边。
    private val curlContentAreaOnlyState = mutableStateOf(false)
    var curlContentAreaOnly: Boolean
        get() = curlContentAreaOnlyState.value
        set(value) { curlContentAreaOnlyState.value = value }
    // 翻页背面透出程度同用 state 承载（NES 侧经 SideEffect 同步，滑块拖动即重组生效）
    private val curlBackContentAlphaState = mutableFloatStateOf(0.1f)
    var curlBackContentAlpha: Float
        get() = curlBackContentAlphaState.floatValue
        set(value) { curlBackContentAlphaState.floatValue = value }
    // 翻页阴影浓度同上（0=关闭阴影）
    private val curlShadowAlphaState = mutableFloatStateOf(0.2f)
    var curlShadowAlpha: Float
        get() = curlShadowAlphaState.floatValue
        set(value) { curlShadowAlphaState.floatValue = value }
    var remotePageSupply: RemotePageSupply? = null
    var onChapterEnd: ((Boolean) -> Unit)? = null

    override var onPageChanged: ((Int) -> Unit)? = null
    override var onPageCountChanged: ((Int) -> Unit)? = null
    override var onToggleUi: (() -> Unit)? = null

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (width == 0 || height == 0) return false
            val fx = e.x / width
            when {
                fx in 0.33f..0.67f -> onToggleUi?.invoke()
                (if (isRtl) fx < 0.33f else fx > 0.67f) -> tapForwardTrigger.intValue++
                else -> tapBackwardTrigger.intValue++
            }
            return true
        }
    })

    private val flipSound = CurlFlipSound(context)

    /** 落页纸声：翻到新 spread 时播放（设置里可关，读实时 pref 免重建视图） */
    private fun playFlipSound() {
        flipSound.play()
    }

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

    /** ★ 抖动参数即时生效：清缓存重载当前 spread（重组短暂白帧后按新参数回显，低频操作可接受） */
    fun applyEinkDitherNow(enabled: Boolean, algorithm: Int, levels: Int, endpointPassthrough: Int = 0) {
        if (loader.einkDither == enabled && loader.einkDitherAlgorithm == algorithm && loader.einkDitherLevels == levels && loader.einkEndpointPassthrough == endpointPassthrough) return
        loader.einkDither = enabled
        loader.einkDitherAlgorithm = algorithm
        loader.einkDitherLevels = levels
        loader.einkEndpointPassthrough = endpointPassthrough
        cache.clear()
        reloadTrigger.intValue++
        preloadSpread(currentPage)
    }

    /** ★ 纸张纹理即时生效：全局 PaperPageTexture 已由 NES 先行更新（含升代），清缓存重载当前 spread */
    fun applyPaperTextureNow() {
        cache.clear()
        reloadTrigger.intValue++
        preloadSpread(currentPage)
    }

    fun setPanNavigation(pan: Boolean) {
    }

    fun setDoublePageModeAndShift(doubleMode: Boolean, shiftMode: Boolean) {
        this.doublePageShift = shiftMode
    }

    fun setDoublePageReverse(reverse: Boolean) {
        this.doublePageReverse = reverse
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
        loader.bind(engine, treePath, pages, remotePageSupply)
        loader.setCurrentPage(startPage)
        renderCompose()
        preloadSpread(startPage)
    }

    private fun currentSpread(): Int =
        SpreadMapping.pageToSpread(currentPage, pageList.size, doublePageShift)

    private fun totalSpreads(): Int =
        SpreadMapping.spreadCount(pageList.size, doublePageShift)

    private fun preloadSpread(page: Int) {
        val spread = SpreadMapping.pageToSpread(page, pageList.size, doublePageShift)
        val pages = SpreadMapping.protectSpreadPages(spread, pageList.size, doublePageShift)
        // 接入缓存保护（对齐单页 preloadAround 的 protectThreeLayers）：protectedSet 恒空时
        // trimMemory→trimNonProtected 会清光全部位图包括正在显示的 spread，白屏到下次翻页
        cache.protectPages(pages)
        val currentLeft = SpreadMapping.leftPage(spread, pageList.size, doublePageShift)
        val currentRight = SpreadMapping.rightPage(spread, pageList.size, doublePageShift)
        for (p in pages) {
            loader.loadPageAsync(p, isPreload = (p != currentLeft && p != currentRight)) { reloadTrigger.intValue++ }
        }
    }

    private fun renderCompose() {
        composeView.setContent {
            CurlDoublePageContent(
                totalSpreads = totalSpreads(),
                currentSpread = currentSpread(),
                totalPages = pageList.size,
                shift = doublePageShift,
                isRtl = isRtl,
                doublePageReverse = doublePageReverse,
                pageBgColor = pageBgColor,
                cache = cache,
                landscapeZoom = landscapeZoom,
                fitScreen = fitScreen,
                curlContentAreaOnly = curlContentAreaOnly,
                backContentAlpha = curlBackContentAlpha,
                shadowAlpha = curlShadowAlpha,
                reloadTrigger = reloadTrigger,
                tapForwardTrigger = tapForwardTrigger,
                tapBackwardTrigger = tapBackwardTrigger,
                onSpreadChanged = { newSpread ->
                    val newPage = SpreadMapping.leftPage(newSpread, pageList.size, doublePageShift)
                    playFlipSound()
                    currentPage = newPage
                    loader.setCurrentPage(newPage)
                    preloadSpread(newPage)
                    if (newSpread == totalSpreads() - 1) onChapterEnd?.invoke(true)
                    if (newSpread == 0) onChapterEnd?.invoke(false)
                    onPageChanged?.invoke(newPage)
                },
            )
        }
    }

    override fun goToPage(page: Int) {
        if (page < 0 || page >= pageList.size) return
        currentPage = page
        loader.setCurrentPage(page)
        reloadTrigger.intValue++
        preloadSpread(page)
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
        val spread = currentSpread()
        val leftIdx = SpreadMapping.leftPage(spread, pageList.size, doublePageShift)
        val leftBmp = cache.get(leftIdx)
        if (leftBmp == null || leftBmp.isRecycled) return false
        // 右页存在时也需就绪：该方法是"当前页已渲染出内容"的就绪信号，右页缺位时
        // 返回 true 会让调用方提前撤遮罩露出半边白
        val rightIdx = SpreadMapping.rightPage(spread, pageList.size, doublePageShift)
        if (rightIdx < 0) return true
        val rightBmp = cache.get(rightIdx)
        return rightBmp != null && !rightBmp.isRecycled
    }

    override fun drainLiveBitmaps(): Map<Int, Bitmap> {
        return cache.drainAll()
    }

    override fun adoptBitmaps(map: Map<Int, Bitmap>) {
        cache.adopt(map)
    }
}