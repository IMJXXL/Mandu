package com.mangareader.native

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.animation.doOnEnd
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mangareader.eink.applyEinkDither
import com.mangareader.viewer.AnimKind
import com.mangareader.viewer.ArchiveEngine
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.abs

internal fun shouldSplitWebtoonPage(doublePageSplitEnabled: Boolean, splitSide: Int, width: Int, height: Int): Boolean {
    return doublePageSplitEnabled && splitSide == 0 && width > height
}

class WebtoonReaderView(context: Context) : FrameLayout(context), ReaderViewDelegate {

    val recycler = WebtoonRv(context)
    private val webtoonFrame = WebtoonFrame(context).apply {
        addView(recycler, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        onMarginChanged = { applySideMarginToVisible() }
    }
    // ★ 双击放大开关（config.isDoubleTapZoom）：转发给 WebtoonRv 的双击缩放判定
    var doubleTapZoom = true
        set(value) { field = value; recycler.doubleTapZoom = value }
    // ★ 点击翻页区域对齐 mihon navigationModeWebtoon（2026-10-05 R4）：
    //   条漫点击分区不再硬编码——PREV=上滚一屏 / NEXT=下滚一屏 / MENU=呼出菜单，
    //   与翻页视图共用 buildNavigation（关闭/L型/边沿/Kindlish 全部生效；竖向滚动无 RTL 概念，
    //   PREV/NEXT 不随方向翻转，与 VerticalPagerNavigation 同思路）
    private var clickFlipType: Int = 1
    private var clickFlipInvertH = false
    private var navigation: ViewerNavigation = DefaultNavigation

    private fun applyNavigation() {
        navigation = buildNavigation(clickFlipType, clickFlipInvertH, verticalPager = false)
    }

    override fun setClickFlipTypeLive(type: Int) {
        if (clickFlipType == type) return
        clickFlipType = type
        applyNavigation()
    }

    override fun setClickFlipInvert(invert: Boolean) {
        if (clickFlipInvertH == invert) return
        clickFlipInvertH = invert
        applyNavigation()
    }
    private var engine: ArchiveEngine? = null
    private var treePath = ""
    /** ★ SMB/WebDAV 页面供给抽象（RemotePageSupply）：非 null 时页面加载走远程会话，ArchiveEngine 不参与 */
    @Volatile
    var remotePageSupply: RemotePageSupply? = null
    // 超分：渐进替换事件订阅（release/setup 重建时取消）
    private var srEventJob: kotlinx.coroutines.Job? = null

    /** ★ P1-1-lite：同页在途加载去重 + 僵尸坑超时（白屏工单任务3）：
     *  idx → 入坑时间戳；同页 5s 内已在加载→复用在途任务；坑龄≥5s 视为挂死残留，覆盖放行自愈。
     *  值仍等于自己那次时间戳才 remove（防误删后来者的坑）。 */
    private val inflightLoads = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private var pageList: List<ArchiveEngine.PageMeta> = emptyList()
    private var currentPage = 0

    /** 只读暴露当前页（遮罩超时重载等外部只读场景用；写入仍走内部流程/ goToPage） */
    val currentPageForKick: Int get() = currentPage
    private val loadScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    // ★ P4（2026-10-04）：删除 ensureArchive/nativeArchive 死句柄——打开的归档句柄全程无读取方，
    //   每次开书白付一次 mmap+中央目录解析；正文解码走 engine.loadPage 独立缓存
    @Volatile private var scrolling = false
    @Volatile var isSetupScrollPending: Boolean = false
        private set
    // ★ v1.11 P0-B 防污染窗：pending 释放后的短暂静默期。
    //   背景：preloadAround 是单协程串行解码，大图总时长可超 pending 的 timeout；
    //   图片到货 notifyItemChanged → item 高度变化（WRAP_CONTENT 占位→真实高）→ RV gap-fix
    //   → onScrolled → updatePage 会把漂移页码经 onPageChanged 写进 DB（历史"恢复不准"主因之一）。
    //   窗口期内 onScrolled/onScrollStateChanged 与 pending 一样直接跳过；
    //   窗口结束时由 settleWindowRunnable 统一 updatePage() 校准为真实视觉位置并放行。
    @Volatile var isSettleWindowActive: Boolean = false
        private set
    // ★ v1.11 P0-B2（实机回归修复）：settleWindow 结束时的校准必须先验证"已真正落位"。
    //   实机教训：scrollToPositionWithOffset 只登记 pending，需等下一次 layout 才消费；
    //   若窗口期内无任何 layout（图片未到货等），视觉仍在顶部 0 附近——此时无条件 updatePage()
    //   会把 0 当真实位置回调 → cur=0; save() → DB 污染（"记忆全失效永远回第一页"帮凶）。
    //   新语义：f 偏离锚点 >2 视为未落位 → 禁止校准，重锚定 sp + requestLayout 强制消费，
    //   再给一轮窗口；有限重试防死循环，超限锁定 sp 让后续真实滚动自然接管。
    private var pendingAnchorSp = -1
    private var settleRetries = 0
    // ★ v1.11 P0-B6：本次 setup 后用户是否滚动过（state 进入 DRAGGING/SETTLING 即置位，
    //   仅用户手势能驱动这两态——webtoon 无 smoothScroll 调用，无误报源）。
    //   一旦置位，settleWindow 到期一律以视觉位置放行、绝不 re-anchor 拉回锚点
    //   （"滑动不会返回"铁律；落位纠偏仅保留给"用户从未碰屏幕"的纯恢复场景）。
    @Volatile private var sawUserScroll = false

    /** ★ 渐进尺寸缓存（2026-10-06 条漫宽图二分工单）：远程书探测被拒 → 首次进书宽图不拆。
     *  解码时顺带记录原始尺寸，节流合并进 PageSizeCacheStore——下次进书 expand 命中缓存全量生效 */
    private var setupPages: List<ArchiveEngine.PageMeta> = emptyList()
    private val probedSizes = java.util.concurrent.ConcurrentHashMap<String, IntArray>()
    private var probedFlushAt = 0L

    /**
     * ★ 远程拆分页解码（2026-10-06 远程条漫拆分接入）：supply 纯索引制，展开后的半页/段页
     * 供给不了——按元数据还原原始页字节（originalPageIdx），在视图侧完成段页矩形切分与
     * 半页裁切。几何与展开器严格同源：段矩形走 PdfTallSplitCalculator.segmentRectByCount
     * （权威段数=meta.segmentTotal），半页走 applySplitSide 同款 1.2 宽高比守卫+左右半规则。
     * 不拆页（splitSide=0 且无段）由调用方直接 supply.loadBitmap，不走这里。
     */
    private suspend fun loadRemoteExpanded(meta: ArchiveEngine.PageMeta): android.graphics.Bitmap? {
        val supply = remotePageSupply ?: return null
        val rawIdx = meta.originalPageIdx.coerceIn(0, (supply.pageCount - 1).coerceAtLeast(0))
        // ★ 复盘修 OOM（2026-10-06）：半页分支原用 decodeByteArray 全尺寸解码——4000×6000 页
        //   =96MB 位图，平板直爆堆。改走 supply.loadBitmap（适配器自带屏宽采样），采样后比例
        //   不变，1.2 宽高比守卫与裁半结果不受影响；段页分支保留 BitmapRegionDecoder
        //   （区域解码本就是省内存路径，supply.loadBitmap 拿不到区域）
        if (meta.splitSide != 0) {
            val full = withContext(Dispatchers.IO) { runCatching { supply.loadBitmap(rawIdx) }.getOrNull() }
                ?: return null
            if (full.isRecycled) return null
            // applySplitSide 同款：1.2 宽高比守卫——不是宽图就不裁（展开的页必然裁，守卫只防尺寸漂移）
            if (full.width <= full.height * 1.2f) return full
            return try {
                val halfWidth = full.width / 2
                val startX = if (meta.splitSide == 1) 0 else halfWidth
                android.graphics.Bitmap.createBitmap(full, startX, 0, halfWidth, full.height)
            } catch (_: Exception) { full }
        }
        var raw = withContext(Dispatchers.IO) { runCatching { supply.loadPageRaw(rawIdx) }.getOrNull() }
        if (raw == null || raw.isEmpty()) {
            try { Thread.sleep(120) } catch (_: InterruptedException) { return null }
            raw = withContext(Dispatchers.IO) { runCatching { supply.loadPageRaw(rawIdx) }.getOrNull() }
        }
        if (raw == null || raw.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            try {
                when {
                    meta.segmentIndex > 0 -> {
                        val dec = android.graphics.BitmapRegionDecoder.newInstance(raw, 0, raw.size, false)
                        try {
                            val imgW = dec.width; val imgH = dec.height
                            if (imgW <= 0 || imgH <= 0) return@withContext null
                            // 已不是长图（尺寸表过期/图被换）→ 整图兜底，绝不错切错位（渲染器同款守卫）
                            if (imgH.toFloat() / imgW <= com.mangareader.engine.pdf.PdfTallSplitCalculator.TALL_RATIO_THRESHOLD) {
                                return@withContext null
                            }
                            val rect = com.mangareader.engine.pdf.PdfTallSplitCalculator.segmentRectByCount(
                                imgW.toFloat(), imgH.toFloat(), meta.segmentTotal, meta.segmentIndex
                            )
                            val r0 = rect.top.toInt().coerceIn(0, imgH - 1)
                            val r1 = rect.bottom.toInt().coerceIn(r0 + 1, imgH)
                            val region = android.graphics.Rect(0, r0, imgW, r1)
                            val sw = resources.displayMetrics.widthPixels
                            val s = com.mangareader.engine.img.ImageSegmentRenderer.sampleSizeFor(imgW, sw)
                            var bmp = dec.decodeRegion(region, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })
                            if (bmp == null) {
                                System.gc()
                                bmp = dec.decodeRegion(region, android.graphics.BitmapFactory.Options().apply { inSampleSize = s * 2 })
                            }
                            if (bmp == null) return@withContext null
                            val cap = maxOf(sw, 720)
                            if (bmp.width > cap + 2) {
                                val scaledH = (bmp.height.toLong() * cap / bmp.width).toInt().coerceAtLeast(1)
                                val scaled = try { android.graphics.Bitmap.createScaledBitmap(bmp, cap, scaledH, true) } catch (_: OutOfMemoryError) { null }
                                if (scaled != null) {
                                    if (scaled !== bmp) bmp.recycle()
                                    bmp = scaled
                                }
                            }
                            bmp
                        } finally { try { dec.recycle() } catch (_: Exception) {} }
                    }
                    else -> null
                }
            } catch (_: Exception) { null }
        }
    }

    /** 远程页统一解码入口：拆分页走元数据还原，普通页保持 supply 索引直取 */
    private suspend fun loadRemotePage(meta: ArchiveEngine.PageMeta, idx: Int): android.graphics.Bitmap? {
        val supply = remotePageSupply ?: return null
        return if (meta.splitSide != 0 || meta.segmentIndex > 0) loadRemoteExpanded(meta)
        else supply.loadBitmap(meta.originalPageIdx.coerceAtMost((supply.pageCount - 1).coerceAtLeast(0)))
    }

    private fun observeProbedSize(idx: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val meta = pageList.getOrNull(idx) ?: return
        // ★ 段页/半页的位图尺寸≠整页尺寸——写进缓存会把整页误判成普通图/宽图（tallSplit/二分全乱）
        if (meta.segmentIndex != 0 || meta.splitSide != 0) return
        if (pageAnimFlags.any { it.pageIdx == idx && it.kind != AnimKind.NONE }) return // 动图页不参与拆分判定
        val key = com.mangareader.engine.pdf.VirtualPageExpander.pageSizeKey(pageList[idx])
        probedSizes[key]?.let { if (it[0] == w && it[1] == h) return }
        probedSizes[key] = intArrayOf(w, h)
        val now = android.os.SystemClock.uptimeMillis()
        if (now - probedFlushAt < 8000L) return
        probedFlushAt = now
        flushProbedSizes()
    }

    /** ★ 渐进尺寸批回调（2026-10-06 修正）：expand 在 Reader.enterReader 端跑、读缓存用的是
     *  【原始表】stamp，而视图 setup 收到的 pages 是已展开表——在此写缓存永远差一个命名空间
     *  （1.375 首版"还不行"的根因）。改为回传给 Reader，由持有原始表的一方合并落盘 */
    var onProbedSizes: ((Map<String, IntArray>) -> Unit)? = null

    private fun flushProbedSizes() {
        if (probedSizes.isEmpty()) return
        val batch = HashMap(probedSizes)
        probedSizes.clear()
        try { onProbedSizes?.invoke(batch) } catch (_: Exception) {}
    }

    // ★ 锚点落位确认（2026-09-26 实测修复）：setup 登记位可能被 notifyDataSetChanged 同帧
    //   竞争丢弃（P0-B4 已知 race）→ 视觉停在 item 0。此时任何"接受视觉位置"的逻辑都会把
    //   恢复位冲掉（15:27 日志实锤：sp=1 → user-takeover f=0 → cur=0 进度写成 0，用户侧
    //   表现=「每次打开都在第一页/必须重新打开第二次才正常」）。只有确认过 f==sp 之后，
    //   视觉位置才允许被当作"用户意图"接受。
    @Volatile private var anchorLanded = false

    /** 分割段页模式：段≈一屏，item 偏 1 = 页偏 1，落位容差必须为 0（整页模式一屏内漂移可容忍） */
    private val segmentMode: Boolean get() = pageList.any { it.segmentIndex > 0 }

    /** 锚点未落位且视觉仍在锚点附近 → 不算用户接管，应重锚回恢复位 */
    private fun anchorNotLandedNearSp(f: Int): Boolean =
        !anchorLanded && f != RecyclerView.NO_POSITION && f in pageList.indices &&
            pendingAnchorSp in pageList.indices && f != pendingAnchorSp &&
            kotlin.math.abs(f - pendingAnchorSp) <= 2

    /** 诊断取证：状态翻转进诊断日志（关于页可导出），异常静默 */
    private fun diagMark(msg: String) = try {
        com.mangareader.DiagTracer.mark(context, "w: " + when {
            msg.contains("deferred") -> "1"
            msg.contains("takeover") -> "2"
            msg.contains("anchor") -> "3"
            msg.contains("give-up") || msg.contains("giveup") -> "4"
            else -> "?"
        } + " " + msg.length)
    } catch (_: Exception) {}

    private val settleWindowRunnable = Runnable {
        // ★ v1.11 P0-B5（实机回归修复）：窗口到期时用户仍在触摸/惯性滚动中 → 顺延校验。
        //   实机日志实锤（17:04 会话）：进卷守卫期内用户长距 fling（0→950），1.5s timeout 抢跑
        //   释放时 state 仍非 IDLE（USER-TAKEOVER 无机会执行），1200ms 后窗口校验拿旧锚点 sp=0
        //   对比 f=950 → 误判 NOT-LANDED 强拉回顶部（"画面到了又弹回去"）。
        //   判据可靠性：程序性 gap-fix 滚动不改变 RV scrollState（恒 IDLE），只有用户手势
        //   （DRAGGING/SETTLING）非 IDLE——不会误顺延。用户停手后的 IDLE 由主 listener 收窗分支接管。
        if (recycler.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
            android.util.Log.d("WebtoonRV", "settle deferred: user still scrolling (state=${recycler.scrollState})")
            // ★ 用户主动滑动中（2026-09-26 实测反馈）：sawUserScroll=用户已接管 → 守卫使命结束，
            //   立即收窗放行（同 IDLE 收窗语义）——页码实时跟随+预载持续。原实现无条件顺延窗口，
            //   用户连续滑动期间页码冻结数秒、预载停摆（滑动只见未加载占位=糊）。
            if (sawUserScroll) {
                val lm = recycler.layoutManager as? LinearLayoutManager
                val f = lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
                // ★ 锚点未落位保护：视觉仍在锚点附近且从未确认落位 → 不接受、也不中断正在
                //   进行中的手势，顺延窗口；手势自然结束由 IDLE 收窗分支重锚回恢复位。
                if (anchorNotLandedNearSp(f) && settleRetries < 3) {
                    settleRetries++
                    diagMark("deferred guard f=$f sp=$pendingAnchorSp not-landed -> wait idle")
                    startSettleWindow()
                    return@Runnable
                }
                anchorLanded = true
                if (f != RecyclerView.NO_POSITION) pendingAnchorSp = f
                isSettleWindowActive = false
                updatePage()
                loadScope.coroutineContext.cancelChildren()
                preloadAround(currentPage, jump = false)
                diagMark("deferred user-takeover f=$f")
                return@Runnable
            }
            startSettleWindow()
            return@Runnable
        }
        isSettleWindowActive = false
        val sp = pendingAnchorSp
        val f = (recycler.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition()
            ?: RecyclerView.NO_POSITION
        // ★ 段页模式落位容差 0：段≈一屏，f 偏 1 = 显示的整页偏 1（整页模式沿用 ±2 屏容忍）
        val landedTol = if (segmentMode) 0 else 2
        if (sp in pageList.indices && (f == RecyclerView.NO_POSITION || kotlin.math.abs(f - sp) > landedTol)) {
            if (sawUserScroll) {
                // ★ P0-B6：用户滚动过 → 视觉即真相。漂移可能是用户滑动/误触输入所致，
                //   强拉回锚点=夺走用户位置（实机三轮回跳事故元凶）。所见即所得放行。
                //   ★ 但锚点从未落位且视觉仍在锚点附近时例外：此刻的"视觉"是登记位被
                //   丢弃的产物而非用户意图（15:27 日志 sp=1 → f=0 → cur=0 实锤），
                //   重锚回恢复位再给一次窗口；重试耗尽才接受，防极端场景死循环。
                if (anchorNotLandedNearSp(f) && settleRetries < 3) {
                    settleRetries++
                    android.util.Log.d("WebtoonRV", "drift guard f=$f sp=$sp not-landed -> re-anchor")
                    diagMark("drift guard f=$f sp=$sp not-landed -> re-anchor")
                    currentPage = sp
                    (recycler.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(sp, 0)
                    recycler.post { recycler.requestLayout() }
                    startSettleWindow()
                    return@Runnable
                }
                anchorLanded = true
                android.util.Log.d("WebtoonRV", "settle drift f=$f sp=$sp but USER-SCROLLED -> accept visual pos")
                updatePage()
                return@Runnable
            }
            if (settleRetries < 3) {
                settleRetries++
                android.util.Log.d("WebtoonRV", "settle NOT-LANDED f=$f sp=$sp retry=$settleRetries -> re-anchor")
                diagMark("not-landed f=$f sp=$sp retry=$settleRetries re-anchor")
                currentPage = sp
                (recycler.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(sp, 0)
                recycler.post { recycler.requestLayout() }
                startSettleWindow()
                return@Runnable
            }
            android.util.Log.d("WebtoonRV", "settle give-up: lock sp=$sp, wait real scroll")
                diagMark("give-up lock sp=$sp")
            anchorLanded = true
            currentPage = sp
        } else {
            android.util.Log.d("WebtoonRV", "settleWindow END landed f=$f -> calibrate updatePage")
            diagMark("window-end landed f=$f")
            anchorLanded = true
            updatePage()
        }
    }

    private fun startSettleWindow() {
        recycler.removeCallbacks(settleWindowRunnable)
        isSettleWindowActive = true
        recycler.postDelayed(settleWindowRunnable, SETTLE_WINDOW_MS)
    }

    // ★ v1.11 P0-A2（自查补丁）：pending 释放机制的两件套（一次性 IDLE listener + timeout runnable）
    //   必须成员化跟踪。原因：WebtoonReaderView 是 remember 单例跨卷复用，若进卷后 1.5s 内就
    //   切卷/退出/切动图视图（旧 doScrollAndFinalize 的 timeout 尚未 fire），旧 listener 会永久
    //   残留在 recycler 上——用户在新卷首次翻页松手时，幽灵 listener 的 IDLE 回调会把新卷的
    //   isSetupScrollPending 提前关掉并启动 settleWindow，守卫失效重现污染窗口。
    private var pendingReleaseListener: RecyclerView.OnScrollListener? = null
    private var pendingTimeoutRunnable: Runnable? = null

    /** 统一清理 pending 释放机制的所有挂起件（setup 重入 / release 时调用） */
    private fun cancelPendingRelease() {
        pendingReleaseListener?.let { recycler.removeOnScrollListener(it) }
        pendingReleaseListener = null
        pendingTimeoutRunnable?.let { recycler.removeCallbacks(it) }
        pendingTimeoutRunnable = null
        recycler.removeCallbacks(settleWindowRunnable)
        isSettleWindowActive = false
    }
    private var adapter: PageAdapter? = null
    // ★ F3.1 修正版（0829 双页闪退实锤）：按张数限容（8 张），sizeOf 恒返回 1。
    //   byteCount 计重在位图 recycle 后归 0 → LruCache 计重失配 → evictAll 抛异常闪退。
    //   单页内存上限由 F3.2 解码闸兜底。原有 synchronized 包裹保留（嵌套无害）。
    // ★ 内存预算制（2026-09-25 PDF 条漫分割实查）：原 LruCache(8) 按条数计重，分割段位图
    //   1080×4800≈20MB/段 × 8 = 166MB 常驻逼近 largeHeap 上限。改为字节预算 128MB：
    //   小页（~7MB）可缓 18 条提升命中率，大段自动减条数，总量有界。
    private val bitmapCache = object : android.util.LruCache<Int, Bitmap>(128 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }
    // ★ ⑧ 最小修复辅助：清空同步块。
    //   ★ v102 关键修复：不再 recycle——Bitmap 可能仍被显示中的 ImageView/SSIV 异步持有，
    //   recycle → native 像素 UAF → RenderThread/GC SIGSEGV。只 evict 让 GC 兜底回收。
    private fun clearBitmapCacheRecycling() {
        synchronized(bitmapCache) {
            // ★ 白屏工单任务2附属：指认"谁在清缓存"（C4 定位用：size + 视图实例 hash）
            Log.w("Recycle", "webtoon clearBitmapCacheRecycling n=${bitmapCache.size()} view=${System.identityHashCode(this)}")
            bitmapCache.evictAll()
        }
        clearTileFileCache()
    }

    private fun clearTileFileCache() {
        tileFileCache.clear()
        synchronized(tileTempFiles) {
            tileTempFiles.forEach { f -> try { f.delete() } catch (_: Exception) {} }
            tileTempFiles.clear()
        }
    }
    var imageMarginH: Int = 0
        set(value) {
            field = value
            webtoonFrame.marginPct = value
            applySideMarginToVisible()
        }
    private fun applySideMarginToVisible() {
        val rv = recycler
        val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first < 0 || last < first) return
        val rvW = rv.width.takeIf { it > 0 } ?: rv.context.resources.displayMetrics.widthPixels
        val m = imageMarginH.coerceAtLeast(0) * rvW / 100
        for (i in first..last) {
            val vh = rv.findViewHolderForAdapterPosition(i) ?: continue
            val lp = vh.itemView.layoutParams as? androidx.recyclerview.widget.RecyclerView.LayoutParams ?: continue
            if (lp.marginStart != m || lp.marginEnd != m) {
                lp.marginStart = m; lp.marginEnd = m
                vh.itemView.layoutParams = lp
            }
        }
    }
    var trimWhiteBorder: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            clearTileFileCache()
            synchronized(bitmapCache) { bitmapCache.evictAll() }
            adapter?.notifyDataSetChanged()
        }
    var trimThreshold: Int = 220
    var trimWhiteRatio: Float = 0.90f
    // ★ 固定裁剪（仅本书功能 2026-10-04）：条漫长图"页高"概念不同，上下按比例裁会裁掉大量
    //   内容 → 条漫只应用左右。变更走 setFixedCropLR 清缓存重绑。
    var cropLeftPct: Float = 0f
    var cropRightPct: Float = 0f

    fun setFixedCropLR(left: Float, right: Float) {
        if (cropLeftPct == left && cropRightPct == right) return
        cropLeftPct = left; cropRightPct = right
        clearTileFileCache()
        synchronized(bitmapCache) { bitmapCache.evictAll() }
        adapter?.notifyDataSetChanged()
    }
    var rotationAdapt: Boolean = false
    var rotationReverse: Boolean = false
    var pageBgColor: Int = android.graphics.Color.parseColor("#E8E8E8")
    private var tileModeEnabled = false
    private val tileFileCache = java.util.concurrent.ConcurrentHashMap<Int, java.io.File>()
    private val tileTempFiles = java.util.Collections.synchronizedSet(HashSet<java.io.File>())

    fun setTileModeEnabled(enabled: Boolean) {
        if (this.tileModeEnabled != enabled) {
            this.tileModeEnabled = enabled
            if (!enabled) clearTileFileCache()
            adapter?.notifyDataSetChanged()
        }
    }

    // ★ E4（对齐上游可见性启停机制）：条漫现在直接播放动图——apng4android 引擎与
    //   翻页模式同源；可见=start、不可见=显式 stop（onViewAttached/Detached），邻居页
    //   不再空转解码。注册表上限 6，超限停帧移除最旧（防爆内存）。
    private data class AnimFlag(val pageIdx: Int, val kind: AnimKind)
    @Volatile private var pageAnimFlags: List<AnimFlag> = emptyList()
    @Volatile private var animatedCount: Int = 0
    private val webtoonAnimDrawables = java.util.concurrent.ConcurrentHashMap<Int, android.graphics.drawable.Drawable>()
    private val animInflight = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Int, Boolean>())
    /** 动图 drawable 构造失败页：翻回静图路径后不再反复重试动图（防"失败→重探→再失败"死循环） */
    private val animFailed = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Int, Boolean>())
    /** §24 二代 hook：检测到 ≥3 张动图卷时回调 Reader.kt 弹"条漫下只能静态看，是否切翻页"对话框 */
    var onAnimDetected: ((animatedCount: Int) -> Unit)? = null

    private var doublePageSplitEnabled = false
    private var doublePageReverseEnabled = false

    fun setDoublePageSplit(split: Boolean) {
        if (doublePageSplitEnabled == split) return

        doublePageSplitEnabled = split
        synchronized(bitmapCache) { bitmapCache.evictAll() }
        inflightLoads.clear()
        adapter?.notifyDataSetChanged()
    }

    fun setDoublePageReverse(reverse: Boolean) {
        if (doublePageReverseEnabled == reverse) return

        doublePageReverseEnabled = reverse
        synchronized(bitmapCache) { bitmapCache.evictAll() }
        inflightLoads.clear()
        adapter?.notifyDataSetChanged()
    }

    private fun splitAndMerge(bmp: Bitmap): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val halfW = w / 2
        val rightStart = w - halfW
        return try {
            val result = Bitmap.createBitmap(halfW, h * 2, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            val upperSrc: Rect
            val lowerSrc: Rect
            if (doublePageReverseEnabled) {
                upperSrc = Rect(rightStart, 0, w, h)
                lowerSrc = Rect(0, 0, halfW, h)
            } else {
                upperSrc = Rect(0, 0, halfW, h)
                lowerSrc = Rect(rightStart, 0, w, h)
            }
            val upperDst = Rect(0, 0, halfW, h)
            val lowerDst = Rect(0, h, halfW, h * 2)
            canvas.drawBitmap(bmp, upperSrc, upperDst, null)
            canvas.drawBitmap(bmp, lowerSrc, lowerDst, null)
            result
        } catch (_: OutOfMemoryError) {
            bmp
        } catch (_: Exception) {
            bmp
        }
    }

    /** 应用裁剪白边和旋转适应处理 */
    private fun processBitmap(bmp: Bitmap?, idx: Int = -1): Bitmap? {
        if (bmp == null || bmp.isRecycled || bmp.width <= 10 || bmp.height <= 10) return bmp
        var result: Bitmap? = bmp
        val meta = if (idx >= 0) pageList.getOrNull(idx) else null
        if (meta != null && shouldSplitWebtoonPage(doublePageSplitEnabled, meta.splitSide, result!!.width, result.height)) {
            result = splitAndMerge(result!!)
        }
        if (cropLeftPct > 0.001f || cropRightPct > 0.001f) {
            try {
                result = com.mangareader.viewer.ImageTrimmer.applyFixedCrop(result!!, cropLeftPct, 0f, cropRightPct, 0f)
            } catch (_: Exception) {}
        }
        if (trimWhiteBorder) {
            try {
                result = com.mangareader.viewer.ImageTrimmer.ensurePixelReadable(result!!)
                val trim = com.mangareader.viewer.ImageTrimmer.trimWhiteBorders(result!!, threshold = trimThreshold, whiteRatio = trimWhiteRatio, factor = 0.15f)
                result = com.mangareader.viewer.ImageTrimmer.applyTrim(result!!, trim)
            } catch (_: Exception) {}
        }
        if (result != null && !result.isRecycled && rotationAdapt) {
            if (result!!.width > result.height) {
                val matrix = android.graphics.Matrix()
                if (rotationReverse) matrix.postRotate(-90f) else matrix.postRotate(90f)
                result = Bitmap.createBitmap(result!!, 0, 0, result!!.width, result!!.height, matrix, true)
            }
        }
        // ★ 墨水屏灰阶抖动（256级模拟）：所有现有处理之后、入缓存之前，最终位图一次性处理
        if (result != null && !result.isRecycled && einkDither) {
            try { result = result!!.applyEinkDither(einkDitherAlgorithm, einkDitherLevels, einkEndpointPassthrough) } catch (_: Exception) {}
        }
        return result
    }

    /** ★ 墨水屏灰阶抖动参数（解码管线参数，非 UI 状态；变更经 applyEinkDitherNow 即时生效） */
    var einkDither = false
    var einkDitherAlgorithm = 0
    var einkDitherLevels = 16
    var einkEndpointPassthrough = 0
    fun setEinkDither(enabled: Boolean, algorithm: Int, levels: Int, endpointPassthrough: Int = 0) {
        einkDither = enabled; einkDitherAlgorithm = algorithm; einkDitherLevels = levels; einkEndpointPassthrough = endpointPassthrough
    }

    /** ★ 抖动参数即时生效：清缓存 + notify 触发可见项重绑按需重载（条漫按需加载，miss 即新参数解码） */
    fun applyEinkDitherNow(enabled: Boolean, algorithm: Int, levels: Int, endpointPassthrough: Int = 0) {
        if (einkDither == enabled && einkDitherAlgorithm == algorithm && einkDitherLevels == levels && einkEndpointPassthrough == endpointPassthrough) return
        setEinkDither(enabled, algorithm, levels, endpointPassthrough)
        synchronized(bitmapCache) { bitmapCache.evictAll() }
        adapter?.notifyDataSetChanged()
    }

    /** ★ 纸张纹理即时生效：全局 PaperPageTexture 已由 NES 先行更新（含升代），清缓存+notify 触发可见项重绑按需重载 */
    fun applyPaperTextureNow() {
        synchronized(bitmapCache) { bitmapCache.evictAll() }
        adapter?.notifyDataSetChanged()
    }
    var gapEnabled: Boolean = false
        set(value) {
            field = value
            for (i in 0 until (recycler.adapter?.itemCount ?: 0)) {
                val vh = recycler.findViewHolderForAdapterPosition(i) ?: continue
                val lp = vh.itemView.layoutParams as? RecyclerView.LayoutParams ?: continue
                lp.bottomMargin = gapPxFor(i)
                vh.itemView.layoutParams = lp
            }
        }

    // ★ C1 段间无隙（2026-09-25）：WEBTOON_GAP 的 15dp 页隙只应插在「页与页」之间——
    //   同一原图拆出的相邻段（segmentIndex>0 且 originalPageIdx 相同）之间不插隙，
    //   避免长页内部出现假页界
    private val gapPxBase: Int
        get() = if (gapEnabled) (15 * context.resources.displayMetrics.density).toInt() else 0

    private fun gapPxFor(pos: Int): Int {
        if (!gapEnabled) return 0
        if (pos < 0) return gapPxBase
        val m = pageList.getOrNull(pos) ?: return gapPxBase
        val next = pageList.getOrNull(pos + 1)
        if (m.segmentIndex > 0 && next != null && next.segmentIndex > 0 && next.originalPageIdx == m.originalPageIdx) return 0
        return gapPxBase
    }

    override var onPageChanged: ((Int) -> Unit)? = null
    override var onPageCountChanged: ((Int) -> Unit)? = null
    override var onToggleUi: (() -> Unit)? = null
    /** ★ N1 章末回调：条漫只有"向后读"，滚到最后一页即触发 true（语义对齐 PagerReaderView.onChapterEnd） */
    var onChapterEnd: ((Boolean) -> Unit)? = null

    init {
        addView(webtoonFrame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        adapter = PageAdapter()
        recycler.layoutManager = LinearLayoutManager(context).apply { isItemPrefetchEnabled = false }
        recycler.itemAnimator = null
        recycler.adapter = adapter
        recycler.setItemViewCacheSize(if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) 4 else 2)
        recycler.tapListener = { onToggleUi?.invoke() }
        recycler.tapZoneScroll = { ratio -> scrollViewport(ratio) }
        // ★ R4：点击区按导航模式分发（PREV=上滚/NEXT=下滚/MENU=菜单），随设置即时生效
        recycler.tapActionRouter = { ev ->
            val fx = ev.x / recycler.width.coerceAtLeast(1)
            val fy = ev.y / recycler.height.coerceAtLeast(1)
            val ratio = context.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE).getFloat("webtoon_tap_scroll_ratio", 0.8f)
            when (navigation.getAction(fx, fy)) {
                NavigationAction.PREV -> recycler.tapZoneScroll?.invoke(-ratio)
                NavigationAction.NEXT -> recycler.tapZoneScroll?.invoke(ratio)
                NavigationAction.MENU -> recycler.tapListener?.invoke(ev)
            }
        }
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                scrolling = state != RecyclerView.SCROLL_STATE_IDLE
                if (scrolling) sawUserScroll = true  // ★ P0-B6：DRAGGING/SETTLING 仅由用户手势驱动
                if (!scrolling) {
                    // ★ v1.11 P0-B5：防污染窗内用户完成一轮真实滚动 → 用户已接管位置。
                    //   锚点同步为视觉位置并提前收窗校准放行——不再等窗口超时后拿旧锚点
                    //   强判 NOT-LANDED 拽回恢复页（P0-A"尊重视觉"语义在窗口期的延伸）。
                    if (isSettleWindowActive && !isSetupScrollPending) {
                        val lm = rv.layoutManager as? LinearLayoutManager
                        val f = lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
                        // ★ 锚点未落位保护（2026-09-26 实测）：恢复位登记被同帧竞争丢弃 → 视觉停在
                        //   锚点附近，此刻的"用户位置"其实是锚点失败的产物——重锚回恢复位而非接受
                        //   （15:27 日志实锤：sp=1 → takeover f=0 → cur=0，恢复进度被冲成第 1 页）。
                        if (anchorNotLandedNearSp(f) && settleRetries < 3) {
                            settleRetries++
                            diagMark("takeover guard f=$f sp=$pendingAnchorSp not-landed -> re-anchor")
                            lm?.scrollToPositionWithOffset(pendingAnchorSp, 0)
                            rv.requestLayout()
                            return
                        }
                        anchorLanded = true
                        if (f != RecyclerView.NO_POSITION) pendingAnchorSp = f
                        android.util.Log.d("WebtoonRV", "settleWindow USER-TAKEOVER f=$f -> calibrate updatePage")
                        diagMark("user-takeover f=$f")
                        recycler.removeCallbacks(settleWindowRunnable)
                        isSettleWindowActive = false
                        updatePage()
                        loadScope.coroutineContext.cancelChildren()
                        preloadAround(currentPage, jump = false)
                        return
                    }
                    // ★ ★ §23/E：这里不再无条件释放 pending——doScrollAndFinalize 用 OnScrollListener 接管释放时机，
                    //   否则进入 RV 后首个 fling settled 会立刻把 pending 改 false，导致待滚 target 落空。
                    // ★ Bug#13 修复：pending 期间 SKIP updatePage（防 setup 未落位时 firstVisibleItem=2 ≠ sp=54 重算 currentPage）
                    // ★ v1.11 P0-B：settleWindow 防污染窗内同样 SKIP（窗口结束由 settleWindowRunnable 统一校准放行）
                    if (isSetupScrollPending || isSettleWindowActive) {
                        return
                    }
                    updatePage()
                    loadScope.coroutineContext.cancelChildren()
                    preloadAround(currentPage, jump = false)
                }
            }
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (isSetupScrollPending || isSettleWindowActive) return
                val prevPage = currentPage
                updatePage()
                if (currentPage != prevPage) {
                    preloadAround(currentPage, jump = false)
                }
            }
        })
        // ★ v1.11 P0-B3（mihon 方案 B 实装，WebtoonViewer.kt:83 同款）：冻结 RecyclerView——
        //   GONE 子树不参与测量布局，doScrollAndFinalize 登记的 pending position 在解冻前安全保存；
        //   解冻（VISIBLE 自带 requestLayout）后的首次 onLayoutChildren 直接以 sp 为锚点填充，
        //   第一帧即正确页码，消灭旧补偿式方案"先显示第一页、过一会才跳对"的中间态。
        //   注：NativeReaderScreen 的三视图路由操作的是本类外层 visibility，与此内部字段互不干扰。
        recycler.visibility = View.GONE
    }

    private fun updatePage() {
        if (pageList.isEmpty()) return
        val lm = recycler.layoutManager as? LinearLayoutManager ?: return
        val f = lm.findFirstVisibleItemPosition()
        val l = lm.findLastVisibleItemPosition()
        if (f == RecyclerView.NO_POSITION) return
        // ★ 主规则=视口中心线命中的 item（2026-09-26）：旧规则（自底向上找 bottom<=height 或
        //   top<0 的项）在段高≈屏高的分割模式下边界态报上一页——当前段底部未过屏底时
        //   bottom<=height 不成立，落回 top<0 的上一段（15:13 日志实锤：cur=1 后 7 秒无翻页、
        //   又自行跳回 0）。中心线语义=「正在看的那页」，段/整页模式通用。
        var best = RecyclerView.NO_POSITION
        for (i in f..l) {
            val v = lm.findViewByPosition(i) ?: continue
            if (v.top <= height / 2 && v.bottom > height / 2) { best = i; break }
        }
        if (best != RecyclerView.NO_POSITION && l == pageList.size - 1) {
            val lastV = lm.findViewByPosition(l)
            if (lastV != null && lastV.bottom <= height) best = l
        }
        if (best == RecyclerView.NO_POSITION) {
            // 中心线落在页隙/缺口：回退旧规则
            for (i in l downTo f) {
                val v = lm.findViewByPosition(i) ?: continue
                if (v.bottom <= height || v.top < 0) { best = i; break }
            }
        }
        if (best == RecyclerView.NO_POSITION) best = l
        if (best in pageList.indices && best != currentPage) {
            currentPage = best; onPageChanged?.invoke(currentPage)
            // ★ N1 条漫到底哨兵：滚到最后一页即章末（竖读无方向歧义）
            if (best >= pageList.size - 1) onChapterEnd?.invoke(true)
        }
    }

    override fun setup(engine: ArchiveEngine, treePath: String, pages: List<ArchiveEngine.PageMeta>, startPage: Int, vertical: Boolean, clickFlip: Int, rtl: Boolean, verticalPager: Boolean) {
        val changed = this.pageList.size != pages.size || this.treePath != treePath
        this.engine = engine; this.treePath = treePath
        // ★ R4：进书时同步点击翻页区域（后续变化走 setClickFlipTypeLive/setClickFlipInvert）
        if (clickFlipType != clickFlip) { clickFlipType = clickFlip; applyNavigation() }
        val screenMax = maxOf(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
        val screenWidth = context.resources.displayMetrics.widthPixels
        val cfg = com.mangareader.data.ReaderConfig.load(context)
        tabletWebtoonWidthPct = context.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE).getInt("webtoon_tablet_cap_pct", 100).coerceIn(50, 100)
        recycler.tapScrollRatio = context.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE).getFloat("webtoon_tap_scroll_ratio", 0.8f)
        setupPages = pages
        this.pageList = com.mangareader.engine.pdf.VirtualPageExpander.expand(pages, cfg.tallSplitEnabled, true, screenMax, screenWidth, engine, treePath,
            tallRatioThreshold = com.mangareader.engine.pdf.PdfTallSplitCalculator.ratioThreshold(cfg.tallSplitSensitivity))
        this.currentPage = startPage
        onPageCountChanged?.invoke(this.pageList.size)
        remotePageSupply?.onPageCountGrow?.add { newCount ->
            if (newCount > pageList.size) {
                val oldSize = pageList.size
                val grown = pageList + (pageList.size until newCount).map {
                    ArchiveEngine.PageMeta(it, "", "", name = "")
                }
                pageList = grown
                onPageCountChanged?.invoke(pageList.size)
                post { adapter?.notifyItemRangeInserted(oldSize, newCount - oldSize) }
            }
        }
        // 超分渐进替换：订阅引擎完成事件，命中当前章节即原位替换
        srEventJob?.cancel()
        srEventJob = try {
            com.mangareader.sr.SrCore.scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                com.mangareader.sr.SrCore.readyEvents.collect { ev ->
                    if (ev.chapterId == com.mangareader.sr.SrPipeline.sessionChapterId && ev.pageIndex in pageList.indices) {
                        injectUpscaledBitmap(ev.pageIndex, ev.bitmap)
                    }
                }
            }
        } catch (_: Exception) { null }
        if (changed) {
            loadScope.coroutineContext.cancelChildren()
            clearBitmapCacheRecycling()  // ★ ⑧ 最小修复：切章先 recycle 再 clear
            // ★ §24 二代 Bug#6：切卷时必须清空动图检测状态——否则上一卷的 pageAnimFlags 残留污染新卷 idx 判定、
            //   animatedCount 不归零导致提前误触发"切翻页"对话框
            pageAnimFlags = emptyList()
            animatedCount = 0
            animFailed.clear()
            adapter?.notifyDataSetChanged()
        }
        // ★ v1.11 P0-B3：mihon 方案 B——recycler 已在 init 冻结为 GONE，此处直接登记目标位并解冻，
        //   首次 layout 即锚定 startPage（不再 PreDraw 等待/300ms 兜底，无"先显示第一页"中间态）。
        // ★ v1.11 P0-A2：切卷/重入前清掉上一卷遗留的 settleWindow 与 pending 释放机制（防跨卷串扰/幽灵 listener）
        cancelPendingRelease()
        isSetupScrollPending = true
        diagMark("setup pages=${pages.size} sp=$startPage pending=true")
        doScrollAndFinalize(startPage)
    }

    /** 真正滚动：scrollToPositionWithOffset 把 item 顶端对齐到 RV 顶端。
     *  ★ v1.11 P0-A 重写释放语义：
     *  - 平台事实：scrollToPositionWithOffset 经 LLM.onLayoutChildren 消费 pending，不经过
     *    DRAGGING/SETTLING 状态 → 纯恢复场景 onScrollStateChanged(IDLE) 永不触发，
     *    原"等 IDLE 校准"形同虚设，pending 实际恒由 timeout 兜底释放（守卫窗=timeout 全长）。
     *  - 原校准分支 abs(f-sp)>2 时强拉回 sp：用户在守卫窗内翻页松手即被强制拽回记忆页
     *    （"3 秒橡皮筋"，反复对抗直到 timeout）——已废除。新语义：IDLE 一旦到来即代表用户
     *    已接管位置 → 尊重视觉，currentPage 同步为 firstVisible，不再写回 sp。
     *  - timeout 3000→1500ms：防污染职责移交 settleWindow（startSettleWindow），
     *    缩短用户被守卫覆盖的暴露期。
     *  - 两个释放出口（IDLE 用户接管 / timeout 纯恢复）都启动 settleWindow：
     *    吞掉串行 preload 图片陆续到货引发的 gap-fix onScrolled，窗口结束统一校准放行。 */
    private fun doScrollAndFinalize(startPage: Int) {
        if (pageList.isEmpty()) {
            isSetupScrollPending = false
            recycler.visibility = View.VISIBLE  // ★ 空卷防御性解冻（正常空卷走 missing dialog 不至此）
            return
        }
        val sp = startPage.coerceIn(0, pageList.size - 1)
        android.util.Log.d("WebtoonRV", "doScrollAndFinalize sp=$sp total=${pageList.size}")
        pendingAnchorSp = sp
        settleRetries = 0
        anchorLanded = false  // ★ 锚点落位确认：新一轮恢复重置（15:27 日志 sp=1→takeover f=0 实锤）
        sawUserScroll = false  // ★ P0-B6：新一轮恢复重置用户滚动标志
        currentPage = sp  // ★ 显式设上（mihon 模式），万一 onScrolled 还未跑也能立刻读到正确 currentPage
        (recycler.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(sp, 0)
        // ★ v1.11 P0-B3 mihon 式解冻：GONE→VISIBLE 自带 requestLayout，下一帧 layout 消费
        //   pending position 并以 sp 为锚点填充——首帧即在正确位置，无"先第一页后跳转"中间态。
        //   （首次入场：解冻即首次布局；切卷：RV 已 VISIBLE，由 notifyDataSetChanged 的 layout 消费。）
        recycler.visibility = View.VISIBLE
        // ★ v1.11 P0-B4（实机回归修复）：首帧校验补登记。
        //   实机日志实锤：解冻后首次布局偶发未消费登记位（f=0，notifyDataSetChanged 结构变更与
        //   登记位同帧竞争被框架丢弃，非确定性复现；mihon 靠异步 submitList 天然错开此竞争），
        //   画面停在顶部直至 1.5s timeout+settleWindow 兜底才跳转——用户可见明显"快速滑动"。
        //   此处解冻帧 traversal 结束后立即校验：偏离 sp>2 当场补登记一次，下一帧 layout 消费，
        //   纠偏窗口从 ~2.6s 缩至 1-2 帧。settleWindow 兜底继续保留为第二层保险。
        recycler.post {
            if (!isSetupScrollPending) return@post
            val lm0 = recycler.layoutManager as? LinearLayoutManager ?: return@post
            val f0 = lm0.findFirstVisibleItemPosition()
            // ★ 段页模式容差 0（f 偏 1 = 整页偏 1）；整页模式保留 ±2 屏容忍
            val firstTol = if (segmentMode) 0 else 2
            if (f0 == RecyclerView.NO_POSITION || kotlin.math.abs(f0 - sp) > firstTol) {
                android.util.Log.d("WebtoonRV", "first-frame re-anchor f=$f0 sp=$sp")
                diagMark("first-frame f=$f0 sp=$sp re-anchor")
                lm0.scrollToPositionWithOffset(sp, 0)
                recycler.requestLayout()
            } else {
                anchorLanded = true
            }
        }
        preloadAround(sp, jump = sp != 0)
        var released = false
        // 防幽灵：新一轮注册前清掉上一卷可能残留的 listener/timeout（P0-A2）
        cancelPendingRelease()
        val listener = object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                if (state == RecyclerView.SCROLL_STATE_IDLE && !released) {
                    released = true
                    recycler.removeOnScrollListener(this)
                    pendingReleaseListener = null
                    pendingTimeoutRunnable?.let { recycler.removeCallbacks(it) }
                    pendingTimeoutRunnable = null
                    isSetupScrollPending = false
                    val f = (recycler.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition()
                        ?: RecyclerView.NO_POSITION
                    if (f != RecyclerView.NO_POSITION && kotlin.math.abs(f - sp) > 2) {
                        // 用户已接管：以视觉真实位置为准（P0-A：不再拽回 sp）
                        android.util.Log.d("WebtoonRV", "IDLE release USER-TAKEOVER f=$f (sp=$sp)")
                        currentPage = f
                        pendingAnchorSp = f  // ★ 锚点同步为用户位置，settleWindow 落位判定以此为基准
                    } else {
                        // 未明显移动：维持恢复权威值
                        android.util.Log.d("WebtoonRV", "IDLE release KEEP sp=$sp")
                        currentPage = sp
                    }
                    anchorLanded = true  // ★ 两个出口都视为锚点阶段结束（settleWindow 接管后续校准）
                    startSettleWindow()
                }
            }
        }
        pendingReleaseListener = listener
        recycler.addOnScrollListener(listener)
        // 兜底：纯恢复无交互场景 state 无变化、IDLE listener 不触发，靠 timeout 释放。
        //   ★ timeout 不跑 updatePage（防 RV 未落位时 updatePage 把 currentPage 改成漂移值）；校准交给 settleWindow
        //   ★ v1.11 P0-B5：用户仍在滚动中（守卫期内就开滑的长距 fling）→ 推迟抢跑，
        //     等本轮手势自然结束由 IDLE 出口 USER-TAKEOVER（锚点同步为用户位置）。
        //     否则 timeout 释放后窗口校验拿旧锚点强拉回恢复页。上限 6×500ms 防极端长按卡死守卫。
        var deferCount = 0
        val timeout = object : Runnable {
            override fun run() {
                if (released) return
                if (recycler.scrollState != RecyclerView.SCROLL_STATE_IDLE && deferCount < 6) {
                    deferCount++
                    android.util.Log.d("WebtoonRV", "timeout deferred x$deferCount: user scrolling -> wait IDLE takeover")
                    recycler.postDelayed(this, 500)
                    return
                }
                released = true
                recycler.removeOnScrollListener(listener)
                pendingReleaseListener = null
                pendingTimeoutRunnable = null
                isSetupScrollPending = false
                currentPage = sp  // 纯恢复：currentPage 维持恢复权威值
                android.util.Log.d("WebtoonRV", "timeout release sp=$sp -> settleWindow")
                startSettleWindow()
            }
        }
        pendingTimeoutRunnable = timeout
        recycler.postDelayed(timeout, 1500)
    }

    override fun goToPage(p: Int) {
        if (p < 0 || p >= pageList.size) return
        // ★ P0-B5 验证日志（根因修复确认后删除）
        android.util.Log.d("WebtoonRV", "goToPage p=$p cur=$currentPage pend=$isSetupScrollPending win=$isSettleWindowActive")
        val distance = kotlin.math.abs(p - currentPage)
        currentPage = p
        // ★ v1.11 P0-B7：指令式跳页（进度条/Toc/外部 jump）同步窗口校验锚点。
        //   实机实锤：主动拖进度条 → goToPage 经 scrollToPosition 静默登记（不经 scrollState，
        //   sawUserScroll 不置位）→ settleWindow 到期拿旧恢复锚点 sp 对比 f=p 判 NOT-LANDED
        //   → 强拉回第 1 页。锚点跟随指令目标后，窗口核对的是"是否到达你要求的位置"：
        //   landed f≈p 正常放行；真异常偏离则 re-anchor 到 p=重试完成用户指令，语义完全正向。
        //   （非窗口期赋值无读者，无害；下次 setup 由 doScrollAndFinalize 重置）
        pendingAnchorSp = p
        if (distance > 5) {
            loadScope.coroutineContext.cancelChildren()
        }
        recycler.scrollToPosition(p)
        preloadAround(p, jump = distance > 5)
    }

    /**
     * 硬件按键/遥控条漫滚动（2026-10-03）：按视口高度比例平滑滚动。
     * ratio 正=顺向（向下），负=逆向（向上）；首按 0.8 屏，长按连发 0.12 屏/次。
     * 不动 currentPage/锚点——与手势滚动同一条自然路径，落点由既有的校准循环兜住。
     */
    fun scrollViewport(ratio: Float) {
        val dy = (height * ratio).toInt()
        if (dy == 0) return
        try {
            if (kotlin.math.abs(ratio) >= 0.5f) recycler.smoothScrollBy(0, dy)
            else recycler.scrollBy(0, dy)
        } catch (_: Exception) {
        }
    }
    fun nextPage() { if (currentPage < pageList.size - 1) goToPage(currentPage + 1) }
    fun prevPage() { if (currentPage > 0) goToPage(currentPage - 1) }

    fun injectUpscaledBitmap(pageIndex: Int, bitmap: Bitmap) {
        // ★ 墨水屏灰阶抖动：SR 注入的高清图不走 processBitmap，此处补抖动保持同一标准
        var bmp = bitmap
        if (einkDither && !bmp.isRecycled) {
            try { bmp = bmp.applyEinkDither(einkDitherAlgorithm, einkDitherLevels, einkEndpointPassthrough) } catch (_: Exception) {}
        }
        // ★ 纸张纹理烘焙：SR 源图未贴纹理，注入缓存前补贴
        bmp = com.mangareader.filter.PaperPageTexture.apply(bmp)
        synchronized(bitmapCache) { bitmapCache.put(pageIndex, bmp) }
        android.util.Log.i("WebtoonRV", "srInject p=$pageIndex bmp=${bmp.width}x${bmp.height}")
        recycler.post {
            adapter?.notifyItemChanged(pageIndex, "ld")
        }
    }

    /** ★ P0-2 模式切换位图交接：把活着的位图从缓存"取走"（release 不再回收），
     *  交给新视图 adoptBitmaps，切换零重解码（治"切换瞬间当前±1 页白屏"）。 */
    override fun drainLiveBitmaps(): Map<Int, Bitmap> = synchronized(bitmapCache) {
        val out = LinkedHashMap<Int, Bitmap>()
        for (k in bitmapCache.snapshot().keys) {
            val v = bitmapCache.remove(k) ?: continue
            if (!v.isRecycled) out[k] = v
        }
        Log.d("P02", "drain size=${out.size} keys=${out.keys}")
        out
    }

    /** ★ P0-2：接管旧视图移交的位图（首次绑定即命中，不再走解码回传链） */
    override fun adoptBitmaps(map: Map<Int, Bitmap>) {

        synchronized(bitmapCache) {
            for ((k, v) in map) {
                if (v.isRecycled) continue
                var bmp = v
                val meta = pageList.getOrNull(k)
                if (meta != null && shouldSplitWebtoonPage(doublePageSplitEnabled, meta.splitSide, bmp.width, bmp.height)) {

                    bmp = splitAndMerge(bmp)
                }
                bitmapCache.put(k, bmp)
            }
        }
    }

    private suspend fun obtainTileFile(idx: Int): java.io.File? {
        val meta = pageList[idx]
        val supply = remotePageSupply
        if (supply == null) {
            val realFile = withContext(Dispatchers.IO) { engine?.getRealFileForPage(treePath, meta) }
            if (realFile != null && realFile.exists() && realFile.length() > 0) return realFile
            val raw = withContext(Dispatchers.IO) { runCatching { engine?.loadPageRaw(meta, treePath) }.getOrNull() }
            if (raw != null) return writeTileTempFile(idx, raw)
        } else {
            val raw = withContext(Dispatchers.IO) { runCatching { supply.loadPageRaw(idx) }.getOrNull() }
            if (raw != null) return writeTileTempFile(idx, raw)
        }
        return null
    }

    private fun writeTileTempFile(idx: Int, raw: ByteArray): java.io.File? {
        return try {
            val file = java.io.File(context.cacheDir, "wtile_${System.identityHashCode(this)}_${idx}.tmp")
            java.io.FileOutputStream(file).use { it.write(raw) }
            synchronized(tileTempFiles) { tileTempFiles.add(file) }
            file
        } catch (_: Exception) { null }
    }

    private fun loadPageAsync(idx: Int, onResult: ((Bitmap?) -> Unit)? = null) {
        if (idx < 0 || idx >= pageList.size) { onResult?.invoke(null); return }
        val cached = synchronized(bitmapCache) { bitmapCache[idx] }
        if (cached != null && !cached.isRecycled) {
            // 缓存自愈：注入事件可能丢失导致一直存的是原图；若超分结果已就绪，趁本次绑定直接换超分图
            try {
                // ★ F4.6：UI 绑定走 Fast 入口——磁盘命中不解码，交给 pollForResult 异步回填
                com.mangareader.sr.SrPipeline.noteViewport(recycler?.width ?: 0, 0, true)
                val srHit = com.mangareader.sr.SrPipeline.attachOrScheduleFast(idx, cached)
                if (srHit != null && !srHit.isRecycled && srHit !== cached) {
                    synchronized(bitmapCache) { bitmapCache.put(idx, com.mangareader.filter.PaperPageTexture.apply(srHit)) }
                    recycler.post { adapter?.notifyItemChanged(idx, "ld") }
                }
            } catch (_: Exception) {}
            onResult?.invoke(cached)
            return
        }
        val meta = pageList[idx]
        // ★ P1-1-lite：同页在途去重（无回调调用方）；坑龄≥5s 视为挂死残留，覆盖放行（白屏工单任务3）
        val trackInflight = onResult == null
        var myPitTs = -1L
        if (trackInflight) {
            val pitTs = inflightLoads[idx]
            val nowTs = android.os.SystemClock.uptimeMillis()
            if (pitTs != null && nowTs - pitTs < 5000L) {
                ArchiveEngine.d("Dedup", "drop idx=$idx age=${nowTs - pitTs}ms")
                return
            }
            inflightLoads[idx] = nowTs
            myPitTs = nowTs
        }
        if (meta.source == "pdf" && treePath.isNotEmpty() && meta.segmentIndex <= 0) {
            try {
                val sharedKey = engine?.getPdfSharedKey(treePath, meta.index)
                if (sharedKey != null) {
                    engine?.getCachedBitmap(sharedKey)?.let { bmp ->
                        // 共享位图是引擎级原图（多视图共用），不可原地处理——复制后走同一处理管线，
                        // 否则墨水屏抖动在此早退被跳过（同一本 PDF 部分页无抖动、观感斑驳）
                        var out = bmp
                        if (einkDither && !out.isRecycled) {
                            try {
                                out = out.copy(Bitmap.Config.ARGB_8888, false)
                                    ?.applyEinkDither(einkDitherAlgorithm, einkDitherLevels, einkEndpointPassthrough) ?: bmp
                            } catch (_: Exception) { out = bmp }
                        }
                        synchronized(bitmapCache) { bitmapCache.put(idx, com.mangareader.filter.PaperPageTexture.apply(out)) }
                        onResult?.invoke(out)
                        return
                    }
                }
            } catch (_: Exception) {}
        }
        val sm = maxOf(width, height).coerceAtLeast(1)
        loadScope.launch {
            try {
            // ★ 段页不走 tile：tile 按「整图文件+区域」渲染，不认识 segmentIndex，会显示错误区域；
            //   宽图二分半页（splitSide）同理——tile 整图渲染会漏掉裁半（2026-10-02）
            if (tileModeEnabled && !trimWhiteBorder && !(cropLeftPct > 0.001f || cropRightPct > 0.001f) && meta.segmentIndex <= 0 && meta.splitSide == 0) {
                val tileFile = obtainTileFile(idx)
                if (tileFile != null) {
                    ArchiveEngine.d("WebtoonRV", "tile OK idx=$idx file=${tileFile.length()}B")
                    tileFileCache[idx] = tileFile
                    recycler.post { adapter?.notifyItemChanged(idx, "ld") }
                    onResult?.invoke(null)
                    return@launch
                }
                ArchiveEngine.d("WebtoonRV", "tile MISS idx=$idx -> fallback decode")
            }
            val bmp = withContext(Dispatchers.IO) {
                var result: Bitmap? = null
                if (meta.source in listOf("mobi", "azw", "azw3", "pdb", "prc")) {
                    result = loadMobiPageAsync(meta)
                }
                // 所有格式统一走 Java BitmapFactory（正确处理 CMYK/ICC 色彩空间）
                if (result == null) {
                    // ★ SMB/WebDAV 页面供给抽象：远程会话优先，null 时不回退 engine（路径已被守卫拦截）
                    //   ★ 远程拆分接入：半页/段页按元数据还原原始页后视图侧切分，普通页索引直取
                    val supply = remotePageSupply
                    result = if (supply != null) loadRemotePage(pageList[idx], idx)
                    else {
                        val m = pageList[idx]
                        if (m.segmentIndex > 0) {
                            if (m.source == "pdf") engine?.loadPdfPageSegment(m, treePath, m.segmentIndex)
                            else engine?.loadImagePageSegment(m, treePath, m.segmentIndex)
                        } else engine?.loadPage(m, treePath)
                    }
                }
                result
            }
            // ★ 渐进尺寸收集：用原始 bmp（processBitmap 会裁白边，尺寸会变）
            if (bmp != null && !bmp.isRecycled) observeProbedSize(idx, bmp.width, bmp.height)
            var processed = processBitmap(bmp, idx)
            if (processed == null) {
                ArchiveEngine.w("WebtoonRV", "loadPage FAIL idx=$idx bmpNull=${bmp == null} tile=$tileModeEnabled src=${meta.source}")
            }
            if (processed != null) {
                // 超分：命中缓存直接换成超分图；未命中先显示原图、后台异步超分
                // （完成走订阅→injectUpscaledBitmap；另有 pollForResult 轮询兜底防事件丢失）
                try {
                    com.mangareader.sr.SrPipeline.noteViewport(recycler?.width ?: 0, 0, true)
                    val srHit = com.mangareader.sr.SrPipeline.attachOrSchedule(idx, processed)
                    if (srHit != null && !srHit.isRecycled) {
                        processed = srHit
                    } else {
                        com.mangareader.sr.SrPipeline.pollForResult(idx) { b -> injectUpscaledBitmap(idx, b) }
                    }
                } catch (_: Exception) {}
                // ★ 纸张纹理烘焙：管线末端、入缓存前（纸随滚动/缩放，替代旧屏幕覆盖层）
                processed = com.mangareader.filter.PaperPageTexture.apply(processed)
                synchronized(bitmapCache) { bitmapCache.put(idx, processed) }
                // ★ 白屏工单任务1：补 webtoon 成功路径观测（与 pager 对齐，消除盲区）
                ArchiveEngine.d("WebtoonRV", "loaded idx=$idx bmp=${processed.width}x${processed.height}")
                recycler.post { adapter?.notifyItemChanged(idx, "ld") }
            }
            onResult?.invoke(processed)
            } finally {
                if (myPitTs != -1L && inflightLoads[idx] == myPitTs) inflightLoads.remove(idx)
            }
        }
    }

    private fun loadMobiPageAsync(meta: ArchiveEngine.PageMeta): Bitmap? {
        val e = engine ?: return null
        try {
            val p = e.parsePathForMobi(treePath) ?: return null
            val tree = android.net.Uri.parse("content://${p.first}/tree/${android.net.Uri.encode(p.second)}")
            val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, p.third)
            val cacheKey = uri.toString()
            // ★ 2026-10-05 快路径前置：免 libmobi 整档装载（同 ArchiveEngine 链路，页表同源缓存）
            e.mobiFastPage(cacheKey, uri, meta.localIdx)?.let { rawData ->
                val sm = maxOf(width, height).coerceAtLeast(1)
                return com.mangareader.native.NativeEngine.decodeRaw(rawData, sm, sm)
                    ?: android.graphics.BitmapFactory.decodeByteArray(rawData, 0, rawData.size)
            }
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val fd = pfd.detachFd()
            try {
                com.mangareader.native.NativeEngine.openMobiWithFd(cacheKey, fd)
            } catch (_: Exception) {}
            val rawData = com.mangareader.native.NativeEngine.readMobiPage(cacheKey, meta.localIdx)
            pfd.close()
            if (rawData != null) {
                val sm = maxOf(width, height).coerceAtLeast(1)
                return com.mangareader.native.NativeEngine.decodeRaw(rawData, sm, sm)
                    ?: android.graphics.BitmapFactory.decodeByteArray(rawData, 0, rawData.size)
            }
        } catch (_: Exception) {}
        return null
    }

    private fun preloadAround(center: Int, jump: Boolean = false) {
        // 超分联动：报当前页；只有"整本"作用域下才前后预载
        try { com.mangareader.sr.SrPipeline.onPageChanged(center, pageList.size) } catch (_: Exception) {}
        val lm = recycler.layoutManager as? LinearLayoutManager ?: return
        val firstVisible = lm.findFirstVisibleItemPosition()
        val lastVisible = lm.findLastVisibleItemPosition()
        // ★ 预载距离 ±3 项：段高 ×2（一段≈一页≈2 屏）时即 ±6 屏，与最初完工版覆盖一致；
        //   jump（进卷恢复/跳页）±2 项。配合分批并行+4 渲染槽，窗口填满速度远超原串行 2 槽。
        val forwardPages = if (jump) 2 else 3
        val backwardPages = if (jump) 2 else 3
        val loadList = mutableListOf<Int>()
        val currentCached = synchronized(bitmapCache) { bitmapCache[currentPage] }
        if (currentCached == null) loadList.add(currentPage)
        for (i in firstVisible..lastVisible) {
            if (i != currentPage && i in pageList.indices) loadList.add(i)
        }
        for (i in 1..forwardPages) {
            val fwd = currentPage + i
            if (fwd in pageList.indices && fwd !in loadList) loadList.add(fwd)
        }
        for (i in 1..backwardPages) {
            val bwd = currentPage - i
            if (bwd in pageList.indices && bwd !in loadList) loadList.add(bwd)
        }
        loadScope.launch {
            // ★ 分批并发（2026-09-26）：串行解码 ±6 项窗口需 3s+，滑动永远追不上。
            //   按渲染槽数分批并行（ArchiveEngine renderSlot=4），段窗口 <1s 填满；
            //   引擎层 inflightSegments 去重防重复渲染。
            kotlinx.coroutines.coroutineScope {
                for (batch in loadList.chunked(4)) {
                    batch.map { idx -> async { preloadOne(idx) } }.awaitAll()
                }
            }
        }
    }

    /** 预载单项：缓存 miss 才解码，成功回填位图缓存并通知刷新（慢载 >500ms 进诊断日志取证） */
    private suspend fun preloadOne(idx: Int) {
        val meta0 = pageList.getOrNull(idx) ?: return
        val cached = synchronized(bitmapCache) { bitmapCache[idx] }
        if (cached != null) return
        val startMs = android.os.SystemClock.uptimeMillis()
        val bmp = withContext(Dispatchers.IO) {
            var result: Bitmap? = null
            val meta = meta0
            try {
                if (meta.source in listOf("mobi", "azw", "azw3", "pdb", "prc")) {
                    result = loadMobiPageAsync(meta)
                }
                // 所有格式统一走 Java BitmapFactory（正确处理 CMYK/ICC 色彩空间）
                if (result == null) {
                    // ★ SMB/WebDAV 页面供给抽象：远程会话优先，null 时不回退 engine
                    //   ★ 远程拆分接入：与 loadPageAsync 主路径同款元数据驱动
                    val supply = remotePageSupply
                    result = if (supply != null) loadRemotePage(pageList[idx], idx)
                    else {
                        val m = pageList[idx]
                        if (m.segmentIndex > 0) {
                            if (m.source == "pdf") engine?.loadPdfPageSegment(m, treePath, m.segmentIndex)
                            else engine?.loadImagePageSegment(m, treePath, m.segmentIndex)
                        } else engine?.loadPage(m, treePath)
                    }
                }
            } catch (_: Exception) {}
            result
        }
        if (bmp != null) {
            val processed = processBitmap(bmp, idx)
            synchronized(bitmapCache) { bitmapCache.put(idx, processed ?: bmp) }
            recycler.post { adapter?.notifyItemChanged(idx, "ld") }
            val cost = android.os.SystemClock.uptimeMillis() - startMs
            // ★ 慢载取证：>500ms 说明预载跟不上滑动/渲染槽打满（用户侧观感=往后全是没加载的）
            if (cost > 500) diagMark("slow-load idx=$idx ${cost}ms ${bmp.width}x${bmp.height} src=${meta0.source}")
        } else {
            ArchiveEngine.w("WebtoonRV", "preload FAIL idx=$idx src=${pageList.getOrNull(idx)?.source}")
        }
    }

    /** ★ v1.13：当前页是否已出图 */
    override fun isCurrentPageReady(): Boolean {
        val lm = recycler.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return false
        val pos = lm.findFirstVisibleItemPosition()
        if (pos < 0) return false
        val vh = recycler.findViewHolderForAdapterPosition(pos) as? PageVH ?: return false
        return vh.iv.drawable != null
    }

    override fun release() {
        runCatching { flushProbedSizes() } // ★ 退出前把渐进收集的尺寸落盘，下次进书拆分全量生效
        srEventJob?.cancel(); srEventJob = null
        // ★ v1.11 P0：清掉挂起的 settleWindow 与 pending 释放机制（view 销毁/切视图后不再执行）
        cancelPendingRelease()
        // ★ §24 二代：释放信号状态（E4 起条漫播放动图，Drawable 一并停帧清空）
        pageAnimFlags = emptyList()
        animatedCount = 0
        animFailed.clear()
        onAnimDetected = null
        webtoonAnimDrawables.values.forEach { (it as? android.graphics.drawable.Animatable)?.stop() }
        webtoonAnimDrawables.clear()
        animInflight.clear()

        loadScope.coroutineContext.cancelChildren()
        inflightLoads.clear() // ★ 白屏工单任务3
        clearBitmapCacheRecycling()  // ★ ⑧ 修复硬伤：WebtoonReaderView.release() 原本完全不 recycle 任何 bitmap
                                        //   （PagerReaderView 至少 release 里有 recycle）→ 独立严重缺陷
        try {
            // ★ v1.13 C-1：消除提前 return（engine==null 会跳过尾部清身份字段）——改 if 包裹
            val e = engine
            if (e != null) {
                val p = e.getRealFileForPath(treePath)
                if (p.isNotEmpty() && treePath.startsWith("a:")) {
                    val tree = android.net.Uri.parse("content://${treePath.substringAfter("a:").substringBefore("|")}/tree/${android.net.Uri.encode(treePath.substringAfter("|").substringBefore("|"))}")
                    val docId = treePath.substringAfterLast("|").substringBeforeLast(".")
                    val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                    com.mangareader.native.NativeEngine.pdfClose(uri.toString())
                }
            }
        } catch (_: Exception) {}
        // ★ v1.13 C-1：身份字段随缓存一并作废——下次 setup 必命中 changed=true 走全量重建
        //   （notifyDataSetChanged + doScrollAndFinalize 重锚定 + preloadAround 补载），
        //   根治"模式切换自 release 后重 setup 落入轻量分支、可见 VH 持已 recycle bitmap"的白屏
        pageList = emptyList()
        treePath = ""
    }

    override fun trimMemory(level: Int) {
        // ★ 分级治理（白屏工单任务2）：UI_HIDDEN(20)/COMPLETE(40)/RUNNING_MODERATE(5) 一律忽略——
        //   旧写法 level>=LOW 的大口子把"退后台/销毁"也当全清处理，切后台即清光缓存（白屏主凶）。
        //   LOW/CRITICAL：保留 currentPage±2 阅读窗口，只回收窗外；绝不取消在途加载。
        when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> return
        }
        if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        synchronized(bitmapCache) {
            val keep = currentPage
            for ((k, _) in bitmapCache.snapshot()) {
                if (kotlin.math.abs(k - keep) > 2) {
                    bitmapCache.remove(k)
                }
            }
        }
        val keepIdx = currentPage
        for ((k, f) in tileFileCache.toMap()) {
            if (kotlin.math.abs(k - keepIdx) > 2) {
                tileFileCache.remove(k)
                if (synchronized(tileTempFiles) { tileTempFiles.remove(f) }) {
                    try { f.delete() } catch (_: Exception) {}
                }
            }
        }
    }

    // ★ 平板条漫栏宽百分比（50~100，100=铺满原状）：阅读设置滑条，setup 时读取
    var tabletWebtoonWidthPct = 100

    inner class PageAdapter : RecyclerView.Adapter<PageVH>() {
        override fun getItemCount() = pageList.size
        // ★ 平板条漫栏宽（用户滑条 50%~100%，100/手机 = MATCH_PARENT 铺满原状）：
        //   比例越小放大越轻越清晰、越贴近条漫按手机屏绘制的原生阅读宽度
        private fun contentLp(parent: android.view.ViewGroup): android.widget.FrameLayout.LayoutParams =
            if (tabletWebtoonWidthPct < 100 && parent.context.resources.configuration.smallestScreenWidthDp >= 600) {
                val dm = parent.context.resources.displayMetrics
                val cap = dm.widthPixels * tabletWebtoonWidthPct / 100
                android.widget.FrameLayout.LayoutParams(cap, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                }
            } else android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT)

        // 平板限宽随旋转/开关刷新：configChanges 自处理不重建，回收复用的旧 holder 可能带着过期宽度；
        // 开关关闭时负责还原 MATCH_PARENT（setup 重读偏好，换章/切模式即生效）
        private fun refreshTabletWidth(h: PageVH) {
            val ctx = h.itemView.context
            val tablet = ctx.resources.configuration.smallestScreenWidthDp >= 600
            val dm = ctx.resources.displayMetrics
            val desired = if (tabletWebtoonWidthPct < 100 && tablet)
                dm.widthPixels * tabletWebtoonWidthPct / 100
            else android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            val frame = h.itemView as? android.widget.FrameLayout ?: return
            for (i in 0 until frame.childCount) {
                val ch = frame.getChildAt(i)
                val lp = ch.layoutParams as? android.widget.FrameLayout.LayoutParams ?: continue
                if (lp.width != desired) {
                    lp.width = desired
                    lp.gravity = if (desired != android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
                        android.view.Gravity.CENTER_HORIZONTAL else android.view.Gravity.NO_GRAVITY
                    ch.layoutParams = lp
                }
            }
        }
        override fun onCreateViewHolder(parent: android.view.ViewGroup, vt: Int): PageVH {
            val frame = android.widget.FrameLayout(parent.context).apply {
                // 初值给基础页隙；per-position 的段关系间隙在 onBindViewHolder 修正
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = gapPxBase
                }
            }
            if (tileModeEnabled) {
                val ssiv = WebtoonSSIV(parent.context).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT)
                    isZoomEnabled = true
                    isPanEnabled = true
                    isQuickScaleEnabled = true
                    setMaxScale(5f)
                    setMaxTileSize(512)
                    setMinimumTileDpi(160)
                    setMinimumScaleType(1)
                    setDoubleTapZoomDuration(300)
                    com.mangareader.filter.PaperBgDrawable.apply(this, parent.context.applicationContext, pageBgColor, com.mangareader.filter.PaperPageTexture.overlayMode, com.mangareader.filter.PaperPageTexture.presetId, com.mangareader.filter.PaperPageTexture.concentration)
                    visibility = android.view.View.GONE
                }
                val iv = android.widget.ImageView(parent.context).apply {
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    com.mangareader.filter.PaperBgDrawable.apply(this, parent.context.applicationContext, pageBgColor, com.mangareader.filter.PaperPageTexture.overlayMode, com.mangareader.filter.PaperPageTexture.presetId, com.mangareader.filter.PaperPageTexture.concentration)
                    adjustViewBounds = true
                    visibility = android.view.View.VISIBLE
                }
                frame.addView(iv, contentLp(parent))
                frame.addView(ssiv, contentLp(parent))
                return PageVH(frame, iv, ssiv)
            }
            val iv = android.widget.ImageView(parent.context).apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                com.mangareader.filter.PaperBgDrawable.apply(this, parent.context.applicationContext, pageBgColor, com.mangareader.filter.PaperPageTexture.overlayMode, com.mangareader.filter.PaperPageTexture.presetId, com.mangareader.filter.PaperPageTexture.concentration)
                adjustViewBounds = true
            }
            frame.addView(iv, contentLp(parent))
            return PageVH(frame, iv)
        }
        override fun onBindViewHolder(h: PageVH, pos: Int, payloads: MutableList<Any>) {
            refreshTabletWidth(h)
            if (payloads.contains("ld")) {
                val tileFile = tileFileCache[pos]
                if (tileFile != null && tileFile.exists()) {
                    if (h.isBound) h.setTileFile(tileFile)
                    return
                }
                val animD = synchronized(webtoonAnimDrawables) { webtoonAnimDrawables[pos] }
                if (animD != null) {
                    // ★ E4 修正：动图页不得被静图位图覆盖（位图晚到会顶掉 drawable）
                    if (h.isBound) h.setAnim(animD, pageBgColor)
                    return
                }
                val cached = synchronized(bitmapCache) { bitmapCache[pos] }
                if (cached != null && !cached.isRecycled) {
                    if (h.isBound) h.set(cached)
                } else {
                    // ★ P1-2 自愈：通知到了但缓存没图（回传链曾断裂/缓存被清）——不再静默放过，立即重载。
                    //   缓存命中分支不再重发通知，不会无限循环；解码失败本身就不发通知。
                    loadPageAsync(pos)
                }
                return
            }
            onBindViewHolder(h, pos)
        }
        override fun onBindViewHolder(h: PageVH, pos: Int) {
            h.hasImage = false
            h.isBound = true
            h.boundPosition = pos
            // ★ C1：VH 回收复用跨位置时按段关系修正页隙（创建时的初值不一定适配新位置）
            val gap = gapPxFor(pos)
            (h.itemView.layoutParams as? RecyclerView.LayoutParams)?.let { lp ->
                var changed = false
                if (lp.bottomMargin != gap) { lp.bottomMargin = gap; changed = true }
                val rvW = recycler.width.takeIf { it > 0 } ?: recycler.context.resources.displayMetrics.widthPixels
                val sm = imageMarginH.coerceAtLeast(0) * rvW / 100
                if (lp.marginStart != sm || lp.marginEnd != sm) { lp.marginStart = sm; lp.marginEnd = sm; changed = true }
                if (changed) h.itemView.layoutParams = lp
            }

            val tileFile = tileFileCache[pos]
            if (tileFile != null && tileFile.exists()) {
                h.setTileFile(tileFile)
                ensureAnimKindAsync(pos)
                return
            }
            // 静图页：原路径（§24 撤回，条漫不再处理动图渲染——所有页在条漫下都按静图渲染）
            val cached = synchronized(bitmapCache) { bitmapCache[pos] }
            val knownAnimKind = pageAnimFlags.firstOrNull { it.pageIdx == pos }?.kind
            if (cached != null && !cached.isRecycled && cached.width > 1) {
                h.set(cached)
            } else if (knownAnimKind != null && knownAnimKind != AnimKind.NONE) {
                // ★ 已知动图页跳过静解码（2026-10-03"打开 GIF 几十秒"专项）：长条 GIF 首帧=
                //   整屏逻辑尺寸，全尺寸解一帧可达百 MB 级——纯浪费，动图 drawable 马上接管；
                //   构造失败时 loadAnimAsync 会翻回静图路径（animFailed），不会白屏。
                //   仍给一屏占位（setAnim 会复位），防等待期 item 高度≈0 扰动条漫锚定
                h.iv.minimumHeight = if (recycler.height > 0) recycler.height
                                     else h.itemView.resources.displayMetrics.heightPixels
            } else {
                // ★ v1.11 一屏占位（mihon WebtoonPageHolder.kt:54-58/:265 同构机制，官方注释
                //   "keep a minimum height size of the holder"防 adapter 填屏）：无图 item 高度≈0
                //   会让锚定 fill 的位置计算地基失真——图片到货逐页膨胀引发漂移/NOT-LANDED。
                //   一屏占位让地基从一开始就接近真实；真图到位由 PageVH.set 清零恢复内容高。
                h.iv.minimumHeight = if (recycler.height > 0) recycler.height
                                     else h.itemView.resources.displayMetrics.heightPixels
                loadPageAsync(pos)
            }
            // ★ §24 二代：每页异步 peek 一次；确认动图则拉起播放（E4）
            ensureAnimKindAsync(pos)
            val readyAnim = synchronized(webtoonAnimDrawables) { webtoonAnimDrawables[pos] }
            if (readyAnim != null) {
                // 重绑时 drawable 已就绪：必须重新上屏（loadAnimAsync 会因已存在而早退）
                h.setAnim(readyAnim, pageBgColor)
            } else if (pageAnimFlags.firstOrNull { it.pageIdx == pos }?.kind?.let { it != AnimKind.NONE } == true) {
                loadAnimAsync(pos)
            }
        }

        /** 可见性启停：attach=可见 → start */
        override fun onViewAttachedToWindow(h: PageVH) {
            super.onViewAttachedToWindow(h)
            (h.iv.drawable as? android.graphics.drawable.Animatable)?.start()
        }

        /** 可见性启停：detach=不可见 → 显式 stop（对齐上游 0919 修 ~200% CPU 空转的机制） */
        override fun onViewDetachedFromWindow(h: PageVH) {
            (h.iv.drawable as? android.graphics.drawable.Animatable)?.stop()
            super.onViewDetachedFromWindow(h)
        }

        /** §24 二代：单页 peek 仅用于 let Reader.kt 知道本卷含动图，不在条漫内播，无 Drawable */
        private fun ensureAnimKindAsync(pos: Int) {
            val e = engine ?: return
            val meta = pageList.getOrNull(pos) ?: return
            if (animFailed.contains(pos)) return  // 构造失败过：静图路径接管，不再重试
            if (pageAnimFlags.any { it.pageIdx == pos }) return  // 已 peek 过
            loadScope.launch {
                val kind = withContext(Dispatchers.IO) {
                    try {
                        val supply = remotePageSupply
                        if (supply != null) {
                            // 远端页：ArchiveEngine 守卫拦 smb:/webdav:，改用供给字节嗅探
                            // （loadPageRaw 落 SmbCache 页缓存，位图加载本来就取过同一份）
                            // ★ 拆分接入：展开后流位≠原始页，动图页恒不拆但索引仍按元数据还原
                            val rawIdx = meta.originalPageIdx.coerceIn(0, (supply.pageCount - 1).coerceAtLeast(0))
                            val raw = supply.loadPageRaw(rawIdx) ?: return@withContext AnimKind.NONE
                            e.peekAnimKindFromRaw(raw)
                        } else {
                            e.peekAnimKindFast(treePath, meta)
                        }
                    } catch (_: Exception) { AnimKind.NONE }
                }
                if (pageAnimFlags.any { it.pageIdx == pos }) return@launch  // 防 race
                pageAnimFlags = pageAnimFlags + AnimFlag(pos, kind)
                if (kind != AnimKind.NONE) {
                    animatedCount++
                    loadAnimAsync(pos)  // ★ 立即拉起播放（原"≥3 张弹切翻页"提示随播放能力取消）
                }
            }
        }

        /**
         * E4：动图页 drawable 加载（单飞）。mmap 真实文件优先，否则原始字节；
         * 完成后若该页仍被绑定则 setAnim + start，未绑定则停帧等 attach 再启。
         */
        private fun loadAnimAsync(pos: Int) {
            if (webtoonAnimDrawables.containsKey(pos)) {
                // 已加载过：把现有 drawable 补上当前 holder（主线程）
                val existing = synchronized(webtoonAnimDrawables) { webtoonAnimDrawables[pos] }
                recycler.post {
                    val h = recycler.findViewHolderForAdapterPosition(pos) as? PageVH
                    if (existing != null && h != null && h.isBound && h.boundPosition == pos) h.setAnim(existing, pageBgColor)
                }
                return
            }
            if (animInflight.contains(pos)) return
            val kind = pageAnimFlags.firstOrNull { it.pageIdx == pos }?.kind ?: return
            if (kind == AnimKind.NONE) return
            if (!animInflight.add(pos)) return
            loadScope.launch {
                val t0 = android.os.SystemClock.uptimeMillis()
                val meta = pageList.getOrNull(pos) ?: return@launch
                val drawable = withContext(Dispatchers.IO) {
                    try {
                        val supply = remotePageSupply
                        val realFile = if (supply == null) engine?.getRealFileForPage(treePath, meta) else null
                        if (realFile != null && realFile.exists() && realFile.length() > 0) {
                            makeAnimDrawable(ByteArray(0), kind, realFile)
                        } else {
                            val raw = if (supply != null) {
                                runCatching {
                                    supply.loadPageRaw(meta.originalPageIdx.coerceIn(0, (supply.pageCount - 1).coerceAtLeast(0)))
                                }.getOrNull()
                            } else {
                                runCatching { engine?.loadPageRaw(meta, treePath) }.getOrNull()
                            }
                            raw?.let { makeAnimDrawable(it, kind) }
                        }
                    } catch (_: Exception) { null }
                }
                val ms = android.os.SystemClock.uptimeMillis() - t0
                if (ms > 2500) runCatching {
                    // 慢动图取证（"打开 GIF 几十秒"专项）：超 2.5s 落持久短码
                    com.mangareader.DiagTracer.markStatic("reader: animslow ms=$ms kind=$kind idx=$pos")
                }
                if (drawable == null) {
                    // ★ 构造失败翻回静图路径（bind 见动图标记会跳过静解码，不翻标就白屏）；
                    //   animFailed 挡住 ensureAnimKindAsync 重探，防"失败→重探→再失败"循环
                    animFailed.add(pos)
                    animInflight.remove(pos)
                    pageAnimFlags = pageAnimFlags.filter { it.pageIdx != pos }
                    recycler.post { adapter?.notifyItemChanged(pos) }
                    return@launch
                }
                try {
                    synchronized(webtoonAnimDrawables) {
                        webtoonAnimDrawables[pos] = drawable
                        while (webtoonAnimDrawables.size > 6) {
                            val eldest = webtoonAnimDrawables.entries.iterator()
                            if (!eldest.hasNext()) break
                            val e = eldest.next()
                            (e.value as? android.graphics.drawable.Animatable)?.stop()
                            eldest.remove()
                        }
                    }
                    recycler.post {
                        val h = recycler.findViewHolderForAdapterPosition(pos) as? PageVH
                        if (h != null && h.isBound && h.boundPosition == pos) {
                            h.setAnim(drawable, pageBgColor)
                        } else {
                            (drawable as? android.graphics.drawable.Animatable)?.stop()
                        }
                    }
                } finally {
                    animInflight.remove(pos)
                }
            }
        }

        /** 与 PagerReaderView.makeAnimDrawable 同源：apng4android 直造，绕开 Coil 包装；mmap 优先。 */
        private fun makeAnimDrawable(raw: ByteArray, kind: AnimKind, file: java.io.File? = null): android.graphics.drawable.Drawable? {
            return try {
                val buffer = if (file != null && file.exists() && file.length() > 0) {
                    try {
                        java.io.RandomAccessFile(file, "r").use { raf ->
                            raf.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, file.length())
                        }
                    } catch (_: Exception) { java.nio.ByteBuffer.wrap(raw) }
                } else {
                    java.nio.ByteBuffer.wrap(raw)
                }
                val loader = object : com.github.penfeizhou.animation.loader.ByteBufferLoader() {
                    override fun getByteBuffer(): java.nio.ByteBuffer = buffer
                }
                val drawable = when (kind) {
                    AnimKind.WEBP -> com.github.penfeizhou.animation.webp.WebPDrawable(loader)
                    AnimKind.APNG -> com.github.penfeizhou.animation.apng.APNGDrawable(loader)
                    AnimKind.GIF -> com.github.penfeizhou.animation.gif.GifDrawable(loader)
                    AnimKind.NONE -> return null
                }
                // 主线程同步触发 header 解析拿 intrinsic 尺寸（adjustViewBounds 依赖）
                try { drawable.intrinsicWidth } catch (_: Throwable) {}
                try { drawable.intrinsicHeight } catch (_: Throwable) {}
                drawable
            } catch (_: Throwable) { null }
        }
        override fun onViewRecycled(h: PageVH) { h.clear() }
    }

    /** PageVH：§24 撤回，回到非 inner class 纯静图版本 */
    class PageVH(frame: android.widget.FrameLayout, val iv: android.widget.ImageView, val ssiv: WebtoonSSIV? = null) : RecyclerView.ViewHolder(frame) {
        private var heldBitmap: Bitmap? = null
        var hasImage = false
        var isBound = true
        var boundPosition = -1
        fun set(b: Bitmap) {
            iv.minimumHeight = 0
            heldBitmap = b
            ssiv?.visibility = android.view.View.GONE
            iv.visibility = android.view.View.VISIBLE
            iv.setImageBitmap(b)
            hasImage = true
        }
        fun setTileFile(file: java.io.File) {
            heldBitmap = null
            iv.visibility = android.view.View.GONE
            val ssv = ssiv ?: return
            ssv.visibility = android.view.View.VISIBLE
            ssv.setImage(com.davemorrissey.labs.subscaleview.ImageSource.uri(itemView.context, android.net.Uri.fromFile(file)))
            hasImage = true
        }
        fun setAnim(d: android.graphics.drawable.Drawable, bg: Int) {
            iv.minimumHeight = 0
            // ★ 透明帧的动图背景跟随阅读器背景色，不透出黑底
            iv.setBackgroundColor(bg)
            heldBitmap = null
            ssiv?.visibility = android.view.View.GONE
            iv.visibility = android.view.View.VISIBLE
            iv.setImageDrawable(d)
            hasImage = true
            (d as? android.graphics.drawable.Animatable)?.start()
        }

        fun clear() {
            heldBitmap = null; hasImage = false; isBound = false; boundPosition = -1
            (iv.drawable as? android.graphics.drawable.Animatable)?.stop()
            iv.setImageDrawable(null)
            try { ssiv?.resetScaleAndCenter() } catch (_: Exception) {}
            ssiv?.visibility = android.view.View.GONE
            iv.visibility = android.view.View.VISIBLE
        }
    }
}

// ==================== TSY 条漫缩放架构 ====================

/** 自定义 GestureDetector，处理 long tap 确认（避免与 quick-scale 冲突） */
internal open class GestureDetectorWithLongTap(
    context: Context,
    private val listener: Listener,
) : GestureDetector(context, listener) {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longTapTime = ViewConfiguration.getLongPressTimeout().toLong()
    private val doubleTapTime = ViewConfiguration.getDoubleTapTimeout().toLong()
    private var downX = 0f; private var downY = 0f; private var lastUp = 0L
    private var lastDownEvent: MotionEvent? = null
    private val longTapFn = Runnable { listener.onLongTapConfirmed(lastDownEvent!!) }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastDownEvent?.recycle(); lastDownEvent = MotionEvent.obtain(ev)
                if (ev.downTime - lastUp > doubleTapTime) {
                    downX = ev.x; downY = ev.y
                    handler.postDelayed(longTapFn, longTapTime)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) handler.removeCallbacks(longTapFn)
            }
            MotionEvent.ACTION_UP -> { lastUp = ev.eventTime; handler.removeCallbacks(longTapFn) }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> handler.removeCallbacks(longTapFn)
        }
        return super.onTouchEvent(ev)
    }

    internal open class Listener : SimpleOnGestureListener() {
        open fun onLongTapConfirmed(ev: MotionEvent) {}
    }
}

/** 手势检测在未缩放父容器上（TSY WebtoonFrame） */
private class WebtoonFrame(context: Context) : android.widget.FrameLayout(context) {
    var marginPct = 0
        set(value) { field = value; applyMargin() }
    var onMarginChanged: (() -> Unit)? = null
    fun applyMargin() {
        val w = width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val m = marginPct.coerceAtLeast(0) * w / 100
        runCatching {
            val rv = getChildAt(0)
            com.mangareader.DiagTracer.mark(context,
                "wtMargin pct=$marginPct px=$m frameW=$w rvW=${rv?.width ?: -1}" +
                    " scale=${(rv as? WebtoonRv)?.diagScale() ?: -1f}")
        }
        onMarginChanged?.invoke()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyMargin()
    }
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { (getChildAt(0) as? WebtoonRv)?.onScaleBegin(); return true }
        override fun onScale(d: ScaleGestureDetector): Boolean { (getChildAt(0) as? WebtoonRv)?.onScale(d.scaleFactor); return true }
        override fun onScaleEnd(d: ScaleGestureDetector) { (getChildAt(0) as? WebtoonRv)?.onScaleEnd() }
    })
    private val flingDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            return (getChildAt(0) as? WebtoonRv)?.zoomFling(vx.toInt(), vy.toInt()) ?: false
        }
    })

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        flingDetector.onTouchEvent(ev)
        // Clamp to RecyclerView bounds
        val rv = getChildAt(0) as? WebtoonRv ?: return super.dispatchTouchEvent(ev)
        val rect = android.graphics.Rect()
        rv.getHitRect(rect); rect.inset(1, 1)
        if (rect.right >= rect.left && rect.bottom >= rect.top) {
            ev.setLocation(ev.x.coerceIn(rect.left.toFloat(), rect.right.toFloat()), ev.y.coerceIn(rect.top.toFloat(), rect.bottom.toFloat()))
        }
        return super.dispatchTouchEvent(ev)
    }
}

/** TSY WebtoonRecyclerView：缩放状态 + zoom drag + double-tap */
class WebtoonRv(ctx: Context) : RecyclerView(ctx) {
    var tapListener: ((MotionEvent) -> Unit)? = null
    var longTapListener: ((MotionEvent) -> Boolean)? = null
    // ★ 条漫左右点击翻屏回调：由 WebtoonReaderView 接到 scrollViewport（WebtoonRv 是
    //   独立顶级类拿不到外层阅读器，直接调 scrollViewport 恒 Unresolved——上一轮未编译漏网）
    var tapZoneScroll: ((Float) -> Unit)? = null
    var tapScrollRatio: Float = 0.8f
    /** ★ R4：点击分区路由（外层 WebtoonReaderView 按 ViewerNavigation 分发）；null=旧硬编码三分 */
    var tapActionRouter: ((MotionEvent) -> Unit)? = null
    var doubleTapZoom = true

    private var currentScale = DEFAULT_RATE
    /** ★ 诊断：当前整体缩放态（留白"幅度不对"取证——放大态下留白被同步放大） */
    fun diagScale(): Float = currentScale
    private var isZooming = false
    private var halfWidth = 0; private var halfHeight = 0
    var originalHeight = 0; private set
    private var heightSet = false
    private var atFirstPosition = false; private var atLastPosition = false
    private val minRate get() = MIN_RATE

    private val listener = GestureListener()
    private val detector = Detector()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        halfWidth = MeasureSpec.getSize(widthSpec) / 2
        halfHeight = MeasureSpec.getSize(heightSpec) / 2
        if (!heightSet) { originalHeight = MeasureSpec.getSize(heightSpec); heightSet = true }
        super.onMeasure(widthSpec, heightSpec)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        detector.onTouchEvent(e)
        return super.onTouchEvent(e)
    }

    override fun onScrolled(dx: Int, dy: Int) {
        super.onScrolled(dx, dy)
        val lm = layoutManager as? LinearLayoutManager ?: return
        atFirstPosition = lm.findFirstVisibleItemPosition() == 0
        atLastPosition = lm.findLastVisibleItemPosition() == lm.itemCount - 1
    }

    private fun getPositionX(px: Float): Float {
        if (currentScale < 1) return 0f
        return px.coerceIn(-halfWidth * (currentScale - 1), halfWidth * (currentScale - 1))
    }
    private fun getPositionY(py: Float): Float {
        if (currentScale < 1) return (originalHeight / 2 - halfHeight).toFloat()
        return py.coerceIn(-halfHeight * (currentScale - 1), halfHeight * (currentScale - 1))
    }
    private fun setScaleRate(rate: Float) { scaleX = rate; scaleY = rate }

    private fun zoom(fromRate: Float, toRate: Float, fromX: Float, toX: Float, fromY: Float, toY: Float) {
        isZooming = true
        val anim = AnimatorSet()
        anim.playTogether(
            ValueAnimator.ofFloat(fromX, toX).apply { addUpdateListener { x = it.animatedValue as Float } },
            ValueAnimator.ofFloat(fromY, toY).apply { addUpdateListener { y = it.animatedValue as Float } },
            ValueAnimator.ofFloat(fromRate, toRate).apply { addUpdateListener { currentScale = it.animatedValue as Float; setScaleRate(currentScale) } }
        )
        anim.duration = 200; anim.interpolator = DecelerateInterpolator(); anim.start()
        anim.doOnEnd {
            isZooming = false; currentScale = toRate
            if (toRate <= DEFAULT_RATE) {
                x = 0f; y = 0f
                setScaleRate(DEFAULT_RATE)
                if (originalHeight > 0 && layoutParams.height != originalHeight) {
                    layoutParams.height = originalHeight
                    halfHeight = layoutParams.height / 2
                }
                requestLayout()
            }
        }
    }

    fun zoomFling(vx: Int, vy: Int): Boolean {
        if (currentScale <= 1f) return false
        val f = 0.4f; val anim = AnimatorSet()
        if (vx != 0) anim.play(ValueAnimator.ofFloat(x, getPositionX(x + f * vx / 2)).apply { addUpdateListener { x = getPositionX(it.animatedValue as Float) } })
        if (vy != 0 && (atFirstPosition || atLastPosition))
            anim.play(ValueAnimator.ofFloat(y, getPositionY(y + f * vy / 2)).apply { addUpdateListener { y = getPositionY(it.animatedValue as Float) } })
        anim.duration = 400; anim.interpolator = DecelerateInterpolator(); anim.start()
        return true
    }

    private fun zoomScrollBy(dx: Int, dy: Int) {
        if (dx != 0) x = getPositionX(x + dx)
        if (dy != 0) y = getPositionY(y + dy)
    }

    fun onScale(scaleFactor: Float) {
        currentScale = (currentScale * scaleFactor).coerceIn(minRate, MAX_SCALE_RATE)
        setScaleRate(currentScale)
        if (currentScale < DEFAULT_RATE) {
            layoutParams.height = (originalHeight / currentScale).toInt()
            halfHeight = layoutParams.height / 2
            requestLayout()
        } else if (layoutParams.height != originalHeight && originalHeight > 0) {
            layoutParams.height = originalHeight
            halfHeight = layoutParams.height / 2
            requestLayout()
        }
        if (currentScale <= DEFAULT_RATE) {
            x = 0f; y = getPositionY(y)
        } else {
            x = getPositionX(x); y = getPositionY(y)
        }
    }

    fun onScaleBegin() { if (detector.isDoubleTapping) detector.isQuickScaling = true }
    fun onScaleEnd() {

    }

    internal inner class GestureListener : GestureDetectorWithLongTap.Listener() {
        override fun onSingleTapConfirmed(ev: MotionEvent): Boolean {
            // ★ 条漫点击翻屏（2026-10-05，用户"触控笔点击不能翻页"）：条漫此前点击=呼出菜单、
            //   没有点击翻页语义（笔/手指一致）。对齐主流阅读器：左右 30% 点击=逆向/顺向滚动
            //   一屏（笔与手指同路），中带保持呼出菜单；双击缩放不受影响（Confirmed 已避开双击）。
            //   菜单开着时点按落在菜单层、到不了本视图，无需另行判断。
            // ★ R4（2026-10-05 对齐 mihon navigationModeWebtoon）：分区路由交给外层
            //   WebtoonReaderView 的 ViewerNavigation（点击翻页区域设置全模式生效）；
            //   未接线时保持原硬编码 30% 三分兜底。
            val fx = ev.x / width.coerceAtLeast(1)
            try { com.mangareader.DiagTracer.mark(context, "tap src=${ev.source} tool=${ev.getToolType(0)} fx=${(fx * 100).toInt()} kind=webtoon") } catch (_: Throwable) {}
            val router = tapActionRouter
            if (router != null) router(ev)
            else when {
                fx < 0.30f -> tapZoneScroll?.invoke(-tapScrollRatio)
                fx > 0.70f -> tapZoneScroll?.invoke(tapScrollRatio)
                else -> tapListener?.invoke(ev)
            }
            return false
        }
        override fun onDoubleTap(ev: MotionEvent): Boolean { detector.isDoubleTapping = true; return false }
        fun onDoubleTapConfirmed(ev: MotionEvent) {
            if (!isZooming && doubleTapZoom) {
                if (scaleX != DEFAULT_RATE) {
                    zoom(currentScale, DEFAULT_RATE, x, 0f, y, 0f)
                } else {
                    val s = 2f
                    zoom(DEFAULT_RATE, s, 0f, (halfWidth - ev.x) * (s - 1), 0f, (halfHeight - ev.y) * (s - 1))
                }
            }
        }
        override fun onLongTapConfirmed(ev: MotionEvent) { longTapListener?.invoke(ev) }
    }

    internal inner class Detector : GestureDetectorWithLongTap(context, listener) {
        private var scrollPointerId = 0; private var downX = 0; private var downY = 0
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var isZoomDragging = false
        var isDoubleTapping = false; var isQuickScaling = false

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { scrollPointerId = ev.getPointerId(0); downX = (ev.x + 0.5f).toInt(); downY = (ev.y + 0.5f).toInt() }
                MotionEvent.ACTION_POINTER_DOWN -> { val i = ev.actionIndex; scrollPointerId = ev.getPointerId(i); downX = (ev.getX(i) + 0.5f).toInt(); downY = (ev.getY(i) + 0.5f).toInt() }
                MotionEvent.ACTION_MOVE -> {
                    if (isDoubleTapping && isQuickScaling) return true
                    val idx = ev.findPointerIndex(scrollPointerId); if (idx < 0) return false
                    val cx = (ev.getX(idx) + 0.5f).toInt(); val cy = (ev.getY(idx) + 0.5f).toInt()
                    var dx = cx - downX; var dy = if (atFirstPosition || atLastPosition) cy - downY else 0
                    if (!isZoomDragging && currentScale > 1f) {
                        var start = false
                        if (abs(dx) > touchSlop) { dx += if (dx < 0) touchSlop else -touchSlop; start = true }
                        if (abs(dy) > touchSlop) { dy += if (dy < 0) touchSlop else -touchSlop; start = true }
                        if (start) isZoomDragging = true
                    }
                    if (isZoomDragging) zoomScrollBy(dx, dy)
                }
                MotionEvent.ACTION_UP -> {
                    if (isDoubleTapping && !isQuickScaling) listener.onDoubleTapConfirmed(ev)
                    isZoomDragging = false; isDoubleTapping = false; isQuickScaling = false
                }
                MotionEvent.ACTION_CANCEL -> { isZoomDragging = false; isDoubleTapping = false; isQuickScaling = false }
            }
            return super.onTouchEvent(ev)
        }
    }
}

private const val MIN_RATE = 0.5f
private const val DEFAULT_RATE = 1f
private const val MAX_SCALE_RATE = 3f

/** ★ v1.11 P0-B：防污染窗时长。覆盖 pending 释放后串行 preload 剩余图片的到货期（gap-fix 漂移期）。 */
private const val SETTLE_WINDOW_MS = 1200L
