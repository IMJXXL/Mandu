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
                backContentAlpha = curlBackContentAlpha,
                shadowAlpha = curlShadowAlpha,
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