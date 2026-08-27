package com.mangareader.native

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.mangareader.viewer.ArchiveEngine

interface ReaderViewDelegate {
    /** ★ §34.3：verticalPager=true 表示 VERT_PAGER 竖向翻页模式（ViewPager2 orientation 垂直）。
     *  与 isVertical（条漫连续滚动）语义分离。旧参数 vertical 保留语义兼容。 */
    fun setup(engine: ArchiveEngine, treePath: String, pages: List<ArchiveEngine.PageMeta>, startPage: Int, vertical: Boolean, clickFlip: Int, rtl: Boolean, verticalPager: Boolean = false)
    fun goToPage(page: Int)
    fun release()
    fun trimMemory(level: Int)
    var onPageChanged: ((Int) -> Unit)?
    var onToggleUi: (() -> Unit)?
    /** ★ v1.13：当前页是否已渲染出内容（无感切换遮罩的就绪信号；mihon 式"数据就绪才可见"） */
    fun isCurrentPageReady(): Boolean
}

private fun getCombinedPaint(grayscale: Boolean, invertedColors: Boolean): Paint? {
    if (!grayscale && !invertedColors) return null
    return Paint().apply {
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix().apply {
                if (grayscale) setSaturation(0f)
                if (invertedColors) {
                    postConcat(ColorMatrix(floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f,
                    )))
                }
            },
        )
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
    trimWhiteBorder: Boolean = false,
    trimThreshold: Int = 220,
    trimWhiteRatio: Float = 0.90f,
    panNavigation: Boolean = false,
    pageLayout: Int = 0,
    pageBgColor: Int = android.graphics.Color.parseColor("#111111"),
    doublePageReverse: Boolean = false,
    doublePageSplit: Boolean = false,
    /** ★ §34.3：VERT_PAGER 模式——竖向翻页走 PagerReaderView（不是 webtoon 连续滚动） */
    verticalPager: Boolean = false,
    /** ★ §34.5：动图卷专用视图——true 时用 AnimPagerReaderView 代替 PagerReaderView。
     *  Reader.kt 进卷批量 peek 后判定为动图卷，弹对话框让用户选；选"进入动图视图"后置 true 走本视图。
     *  与 vertical 不冲突：条漫卷下 peek 到动图也会弹窗，弹窗确认后也用 animPager（animPager 自身支持 verticalPager） */
    useAnimPager: Boolean = false,
    /** ★ 翻页特效一期：仿真卷页（GL）。true 且横向/非动图/非竖翻/单页布局时路由 CurlPagerView；
     *  其余场景一律不激活（等价旧行为）。动图/条漫/VERT_PAGER/双页排他见方案 §6.3 */
    flipCurl: Boolean = false,
    modifier: Modifier = Modifier,
    onPageChanged: (Int) -> Unit = {},
    onToggleUi: () -> Unit = {},
    onViewReady: (webtoonView: View, pagerView: View) -> Unit = { _, _ -> },
    /** ★ v1.13 C-2：把内容容器暴露给上层做切换前同步截图（无感模式切换遮罩的数据源） */
    onContainerReady: (FrameLayout) -> Unit = {}
) {
    val ctx = LocalContext.current
    var savedPage by remember { mutableIntStateOf(startPage) }
    var firstComposition by remember { mutableStateOf(true) }
    var containerRef by remember { mutableStateOf<FrameLayout?>(null) }

    val webtoonView = remember { WebtoonReaderView(ctx).apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) } }
    val pagerView = remember { PagerReaderView(ctx).apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) } }
    // ★ §34.5：动图卷专用视图——与 pagerView 并存，但渲染时二选一（useAnimPager 决定哪个加进 container）
    val animPagerView = remember { AnimPagerReaderView(ctx).apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) } }
    // ★ 翻页特效一期：仿真卷页视图（GLSurfaceView，纯增量）。不满足激活条件时永远 GONE，零行为差异
    val curlView = remember { com.mangareader.native.curl.CurlPagerView(ctx).apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) } }

    // ★ 特效激活条件收敛一处：竖向条漫/动图/竖向翻页/双页布局全部排他（方案 §6.3 硬性规则）
    val curlActive = flipCurl && !isVertical && !useAnimPager && !verticalPager && pageLayout == 0

    // ★ §34.5：isVertical（条漫）走 webtoonView；useAnimPager（动图卷）走 animPagerView；其余走 pagerView。
    //   useAnimPager 优先 —— 动图卷不论是条漫还是翻页模式，确认进动图视图后统一走 animPagerView
    //   （animPagerView 内部 verticalPager 选项自己做竖向/横向翻页切换）。
    fun activeDelegate(isVerticalNow: Boolean, useAnimPagerNow: Boolean): ReaderViewDelegate =
        if (isVerticalNow && !useAnimPagerNow) webtoonView
        else if (useAnimPagerNow) animPagerView
        else if (curlActive) curlView
        else pagerView

    // Track last released delegate to avoid double release
    var lastReleasedDelegate by remember { mutableStateOf<ReaderViewDelegate?>(null) }

    DisposableEffect(treePath, isVertical, isRtl, verticalPager, useAnimPager, curlActive) {
        if (firstComposition) {
            savedPage = startPage
            firstComposition = false
        }
        val delegate = activeDelegate(isVertical, useAnimPager)
        // Release previous delegate only once
        val prevDelegate = lastReleasedDelegate
        if (prevDelegate != null && prevDelegate !== delegate) {
            prevDelegate.release()
        }
        lastReleasedDelegate = delegate
        delegate.onPageChanged = { page -> savedPage = page; onPageChanged(page) }
        delegate.onToggleUi = onToggleUi
        // 先设置颜色再setup，确保adapter创建时用正确颜色
        if (delegate is PagerReaderView) delegate.pageBgColor = pageBgColor
        if (delegate is AnimPagerReaderView) delegate.pageBgColor = pageBgColor
        if (delegate is WebtoonReaderView) delegate.pageBgColor = pageBgColor
        if (delegate is com.mangareader.native.curl.CurlPagerView) delegate.pageBgColor = pageBgColor
        // ★ §34.3：verticalPager 传给 pagerView.setup（webtoon 走默认 false 无影响）
        delegate.setup(engine, treePath, pages, savedPage, isVertical, 0, isRtl, verticalPager)
        // Apply new config parameters to the delegate
        if (delegate is PagerReaderView) {
            delegate.setCenterGapType(centerGapType)
            delegate.setLandscapeZoom(landscapeZoom)
            delegate.setTrimWhiteBorder(trimWhiteBorder, trimThreshold, trimWhiteRatio)
            delegate.setPanNavigation(panNavigation)
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
        }
        onDispose { delegate.release(); lastReleasedDelegate = null }
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
            activeDelegate(isVertical, useAnimPager).goToPage(startPage)
        }
    }

    // Apply grayscale/invert via hardware layer paint
    LaunchedEffect(grayscale, invertColors) {
        containerRef?.let { container ->
            val paint = getCombinedPaint(grayscale, invertColors)
            if (paint != null) {
                container.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
            } else {
                container.setLayerType(View.LAYER_TYPE_NONE, null)
            }
        }
    }

    AndroidView(
        factory = {
            val container = FrameLayout(ctx).apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
            container.addView(webtoonView)
            container.addView(pagerView)
            container.addView(animPagerView)  // ★ §34.5 第三条路由
            container.addView(curlView)       // ★ 翻页特效一期 第四条路由（child3）
            fun syncVis() {
                webtoonView.visibility = if (isVertical && !useAnimPager) View.VISIBLE else View.GONE
                pagerView.visibility = if (!isVertical && !useAnimPager && !curlActive) View.VISIBLE else View.GONE
                animPagerView.visibility = if (useAnimPager) View.VISIBLE else View.GONE
                curlView.visibility = if (curlActive) View.VISIBLE else View.GONE
            }
            syncVis()
            containerRef = container
            // ★ §34.5 onViewReady 兼容旧签名 (webtoonView, pagerView) →
            //   不论 useAnimPager 都传 pagerView 作第二参：Reader.kt 上的 hook（flipAnimation/margin/onChapterEnd/onLongPress）
            //   都是 PagerReaderView 专属，动图卷下不显示时挂上无副作用；useAnimPager 切回 false 时立即生效。
            //   AnimPagerReaderView 不需要这些 hook（其内部状态自洽）。
            onViewReady(webtoonView, pagerView)
            // ★ v1.13 C-2：容器就绪回调（截图用；draw(container) 只画 VISIBLE 子树，GONE 自动排除）
            onContainerReady(container)
            container
        },
        update = { container ->
            // ★ §34.5 三视图按状态切显隐；★ 特效一期第四视图 curlView（child3）
            //   child0=webtoonView, child1=pagerView, child2=animPagerView, child3=curlView
            container.getChildAt(0).visibility = if (isVertical && !useAnimPager) View.VISIBLE else View.GONE
            container.getChildAt(1).visibility = if (!isVertical && !useAnimPager && !curlActive) View.VISIBLE else View.GONE
            container.getChildAt(2).visibility = if (useAnimPager) View.VISIBLE else View.GONE
            container.getChildAt(3).visibility = if (curlActive) View.VISIBLE else View.GONE
        },
        modifier = modifier
    )
}
