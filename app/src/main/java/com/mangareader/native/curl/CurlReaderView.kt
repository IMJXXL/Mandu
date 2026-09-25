package com.mangareader.native.curl

import android.content.Context
import android.graphics.Bitmap
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
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
    var remotePageSupply: RemotePageSupply? = null
        set(value) {
            field = value
        }

    override var onPageChanged: ((Int) -> Unit)? = null
    override var onPageCountChanged: ((Int) -> Unit)? = null
    override var onToggleUi: (() -> Unit)? = null
    var onChapterEnd: ((Boolean) -> Unit)? = null

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
                reloadTrigger = reloadTrigger,
                tapForwardTrigger = tapForwardTrigger,
                tapBackwardTrigger = tapBackwardTrigger,
                onPageChanged = { newPage ->
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