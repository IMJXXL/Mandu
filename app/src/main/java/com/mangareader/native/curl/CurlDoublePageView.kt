package com.mangareader.native.curl

import android.content.Context
import android.graphics.Bitmap
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.mutableIntStateOf
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
    var curlContentAreaOnly: Boolean = false
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
                reloadTrigger = reloadTrigger,
                tapForwardTrigger = tapForwardTrigger,
                tapBackwardTrigger = tapBackwardTrigger,
                onSpreadChanged = { newSpread ->
                    val newPage = SpreadMapping.leftPage(newSpread, pageList.size, doublePageShift)
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