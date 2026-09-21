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
     *  与 isVertical（条漫）语义分离。旧参数 vertical 保留语义兼容。 */
    fun setup(engine: ArchiveEngine, treePath: String, pages: List<ArchiveEngine.PageMeta>, startPage: Int, vertical: Boolean, clickFlip: Int, rtl: Boolean, verticalPager: Boolean = false)
    fun goToPage(page: Int)
    fun release()
    fun trimMemory(level: Int)
    var onPageChanged: ((Int) -> Unit)?
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
    /** ★ SMB/WebDAV 页面供给（design.md 2.2.2）：非 null 时阅读页面走远程会话。
     *   以参数形式在 setup() 之前注入 delegate，消除"setup 早于供给挂接"的竞态窗口。 */
    remoteSupply: com.mangareader.native.RemotePageSupply? = null,
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

    fun activeDelegate(isVerticalNow: Boolean): ReaderViewDelegate =
        if (isVerticalNow) webtoonView
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

        onDispose {
            // Compose 逆序 dispose：keyed effect 先 dispose（drain 完成）→ 本 Unit effect 最后兜底
            SrBitmapHandoffBus.recycleStale()
            ReaderTrimRegistry.clear()
        }
    }

    DisposableEffect(treePath, isVertical, isRtl, verticalPager, remoteSupply) {
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
        delegate.onToggleUi = onToggleUi
        // 先设置颜色再setup，确保adapter创建时用正确颜色
        if (delegate is PagerReaderView) delegate.pageBgColor = pageBgColor
        if (delegate is WebtoonReaderView) delegate.pageBgColor = pageBgColor
        // ★ SMB/WebDAV：供给必须在 setup 之前挂接——setup 内 preloadAround 会立刻触发 loadPageAsync
        if (delegate is PagerReaderView) delegate.remotePageSupply = remoteSupply
        if (delegate is WebtoonReaderView) delegate.remotePageSupply = remoteSupply
        if (delegate is PagerReaderView) delegate.setTileModeEnabled(tileModeEnabled)
        if (delegate is WebtoonReaderView) delegate.setTileModeEnabled(tileModeEnabled)
        // ★ §34.3：verticalPager 传给 pagerView.setup（webtoon 走默认 false 无影响）
        delegate.setup(engine, treePath, pages, savedPage, isVertical, 0, isRtl, verticalPager)
        // Apply new config parameters to the delegate
        if (delegate is PagerReaderView) {
            delegate.setCenterGapType(centerGapType)
            delegate.setLandscapeZoom(landscapeZoom)
            delegate.setFitScreen(fitScreen)
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
            fun syncVis() {
                webtoonView.visibility = if (isVertical) View.VISIBLE else View.GONE
                pagerView.visibility = if (!isVertical) View.VISIBLE else View.GONE
            }
            syncVis()
            containerRef = container
            onViewReady(webtoonView, pagerView)
            // ★ v1.13 C-2：容器就绪回调（截图用；draw(container) 只画 VISIBLE 子树，GONE 自动排除）
            onContainerReady(container)
            container
        },
        update = { container ->
            container.getChildAt(0).visibility = if (isVertical) View.VISIBLE else View.GONE
            container.getChildAt(1).visibility = if (!isVertical) View.VISIBLE else View.GONE
        },
        modifier = modifier
    )
}
