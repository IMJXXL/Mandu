package com.mangareader.native

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
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
import com.mangareader.viewer.AnimKind
import com.mangareader.viewer.ArchiveEngine
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.abs

class WebtoonReaderView(context: Context) : FrameLayout(context), ReaderViewDelegate {

    val recycler = WebtoonRv(context)
    private val webtoonFrame = WebtoonFrame(context).apply {
        addView(recycler, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
    private val archiveMutex = Mutex()
    private var archive: com.mangareader.native.NativeEngine.Archive? = null
    private var archivePath = ""
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
    private val settleWindowRunnable = Runnable {
        // ★ v1.11 P0-B5（实机回归修复）：窗口到期时用户仍在触摸/惯性滚动中 → 顺延校验。
        //   实机日志实锤（17:04 会话）：进卷守卫期内用户长距 fling（0→950），1.5s timeout 抢跑
        //   释放时 state 仍非 IDLE（USER-TAKEOVER 无机会执行），1200ms 后窗口校验拿旧锚点 sp=0
        //   对比 f=950 → 误判 NOT-LANDED 强拉回顶部（"画面到了又弹回去"）。
        //   判据可靠性：程序性 gap-fix 滚动不改变 RV scrollState（恒 IDLE），只有用户手势
        //   （DRAGGING/SETTLING）非 IDLE——不会误顺延。用户停手后的 IDLE 由主 listener 收窗分支接管。
        if (recycler.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
            android.util.Log.d("WebtoonRV", "settle deferred: user still scrolling (state=${recycler.scrollState})")
            startSettleWindow()
            return@Runnable
        }
        isSettleWindowActive = false
        val sp = pendingAnchorSp
        val f = (recycler.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition()
            ?: RecyclerView.NO_POSITION
        if (sp in pageList.indices && (f == RecyclerView.NO_POSITION || kotlin.math.abs(f - sp) > 2)) {
            if (sawUserScroll) {
                // ★ P0-B6：用户滚动过 → 视觉即真相。漂移可能是用户滑动/误触输入所致，
                //   强拉回锚点=夺走用户位置（实机三轮回跳事故元凶）。所见即所得放行。
                android.util.Log.d("WebtoonRV", "settle drift f=$f sp=$sp but USER-SCROLLED -> accept visual pos")
                updatePage()
                return@Runnable
            }
            if (settleRetries < 3) {
                settleRetries++
                android.util.Log.d("WebtoonRV", "settle NOT-LANDED f=$f sp=$sp retry=$settleRetries -> re-anchor")
                currentPage = sp
                (recycler.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(sp, 0)
                recycler.post { recycler.requestLayout() }
                startSettleWindow()
                return@Runnable
            }
            android.util.Log.d("WebtoonRV", "settle give-up: lock sp=$sp, wait real scroll")
            currentPage = sp
        } else {
            android.util.Log.d("WebtoonRV", "settleWindow END landed f=$f -> calibrate updatePage")
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
    private val bitmapCache = object : android.util.LruCache<Int, Bitmap>(8) {
        override fun sizeOf(key: Int, value: Bitmap): Int = 1
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
    }
    var imageMarginH: Int = 0
        set(value) {
            field = value
            val marginPx = (value * context.resources.displayMetrics.density).toInt()
            webtoonFrame.setPadding(marginPx, 0, marginPx, 0)
        }
    var trimWhiteBorder: Boolean = false
    var trimThreshold: Int = 220
    var trimWhiteRatio: Float = 0.90f
    var rotationAdapt: Boolean = false
    var rotationReverse: Boolean = false
    var pageBgColor: Int = android.graphics.Color.parseColor("#E8E8E8")

    // ★ §24 二代：条漫下"不播动图，但需检测一下数量让 Reader.kt 弹切翻页提示"
    //   保留最小信号：每个被 bind 的页异步 peek 一次；累计 ≥3 动图时回调 onAnimDetected
    //   不持有 Drawable、不 start，纯 peek 计数（内存轻量）
    private data class AnimFlag(val pageIdx: Int, val kind: AnimKind)
    @Volatile private var pageAnimFlags: List<AnimFlag> = emptyList()
    @Volatile private var animatedCount: Int = 0
    /** §24 二代 hook：检测到 ≥3 张动图卷时回调 Reader.kt 弹"条漫下只能静态看，是否切翻页"对话框 */
    var onAnimDetected: ((animatedCount: Int) -> Unit)? = null

    /** 应用裁剪白边和旋转适应处理 */
    private fun processBitmap(bmp: Bitmap?): Bitmap? {
        if (bmp == null || bmp.isRecycled || bmp.width <= 10 || bmp.height <= 10) return bmp
        var result = bmp
        if (trimWhiteBorder) {
            try {
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
        return result
    }
    var gapEnabled: Boolean = false
        set(value) {
            field = value
            val gapPx = if (value) (15 * context.resources.displayMetrics.density).toInt() else 0
            for (i in 0 until (recycler.adapter?.itemCount ?: 0)) {
                val vh = recycler.findViewHolderForAdapterPosition(i) ?: continue
                val lp = vh.itemView.layoutParams as? RecyclerView.LayoutParams ?: continue
                lp.bottomMargin = gapPx
                vh.itemView.layoutParams = lp
            }
        }

    override var onPageChanged: ((Int) -> Unit)? = null
    override var onToggleUi: (() -> Unit)? = null
    /** ★ N1 章末回调：条漫只有"向后读"，滚到最后一页即触发 true（语义对齐 PagerReaderView.onChapterEnd） */
    var onChapterEnd: ((Boolean) -> Unit)? = null

    init {
        addView(webtoonFrame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        adapter = PageAdapter()
        recycler.layoutManager = LinearLayoutManager(context).apply { isItemPrefetchEnabled = false }
        recycler.itemAnimator = null
        recycler.adapter = adapter
        recycler.tapListener = { onToggleUi?.invoke() }
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
                        if (f != RecyclerView.NO_POSITION) pendingAnchorSp = f
                        android.util.Log.d("WebtoonRV", "settleWindow USER-TAKEOVER f=$f -> calibrate updatePage")
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
        val cy = height / 2; var best = f; var bd = Int.MAX_VALUE
        for (i in f..l) {
            val v = lm.findViewByPosition(i) ?: continue
            val d = abs((v.top + v.bottom) / 2 - cy)
            if (d < bd) { bd = d; best = i }
        }
        if (best in pageList.indices && best != currentPage) {
            currentPage = best; onPageChanged?.invoke(currentPage)
            // ★ N1 条漫到底哨兵：滚到最后一页即章末（竖读无方向歧义）
            if (best >= pageList.size - 1) onChapterEnd?.invoke(true)
        }
    }

    override fun setup(engine: ArchiveEngine, treePath: String, pages: List<ArchiveEngine.PageMeta>, startPage: Int, vertical: Boolean, clickFlip: Int, rtl: Boolean, verticalPager: Boolean) {
        val changed = this.pageList.size != pages.size || this.treePath != treePath
        this.engine = engine; this.treePath = treePath; this.pageList = pages; this.currentPage = startPage
        remotePageSupply?.onPageCountGrow?.add { newCount ->
            if (newCount > pageList.size) {
                val oldSize = pageList.size
                val grown = pageList + (pageList.size until newCount).map {
                    ArchiveEngine.PageMeta(it, "", "", name = "")
                }
                pageList = grown
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
            adapter?.notifyDataSetChanged()
        }
        // ★ v1.11 P0-B3：mihon 方案 B——recycler 已在 init 冻结为 GONE，此处直接登记目标位并解冻，
        //   首次 layout 即锚定 startPage（不再 PreDraw 等待/300ms 兜底，无"先显示第一页"中间态）。
        // ★ v1.11 P0-A2：切卷/重入前清掉上一卷遗留的 settleWindow 与 pending 释放机制（防跨卷串扰/幽灵 listener）
        cancelPendingRelease()
        isSetupScrollPending = true
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
            if (f0 == RecyclerView.NO_POSITION || kotlin.math.abs(f0 - sp) > 2) {
                android.util.Log.d("WebtoonRV", "first-frame re-anchor f=$f0 sp=$sp")
                lm0.scrollToPositionWithOffset(sp, 0)
                recycler.requestLayout()
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
        recycler.scrollToPosition(p)
        preloadAround(p, jump = distance > 5)
    }
    fun nextPage() { if (currentPage < pageList.size - 1) goToPage(currentPage + 1) }
    fun prevPage() { if (currentPage > 0) goToPage(currentPage - 1) }

    fun injectUpscaledBitmap(pageIndex: Int, bitmap: Bitmap) {
        synchronized(bitmapCache) { bitmapCache.put(pageIndex, bitmap) }
        android.util.Log.i("WebtoonRV", "srInject p=$pageIndex bmp=${bitmap.width}x${bitmap.height}")
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
        Log.d("P02", "adopt size=${map.size} keys=${map.keys}")
        synchronized(bitmapCache) {
            for ((k, v) in map) if (!v.isRecycled) bitmapCache.put(k, v)
        }
    }

    private fun loadPageAsync(idx: Int, onResult: ((Bitmap?) -> Unit)? = null) {
        if (idx < 0 || idx >= pageList.size) { onResult?.invoke(null); return }
        val cached = synchronized(bitmapCache) { bitmapCache[idx] }
        if (cached != null && !cached.isRecycled) {
            // 缓存自愈：注入事件可能丢失导致一直存的是原图；若超分结果已就绪，趁本次绑定直接换超分图
            try {
                // ★ F4.6：UI 绑定走 Fast 入口——磁盘命中不解码，交给 pollForResult 异步回填
                val srHit = com.mangareader.sr.SrPipeline.attachOrScheduleFast(idx, cached)
                if (srHit != null && !srHit.isRecycled && srHit !== cached) {
                    synchronized(bitmapCache) { bitmapCache.put(idx, srHit) }
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
        if (meta.source == "pdf" && treePath.isNotEmpty()) {
            try {
                val sharedKey = engine?.getPdfSharedKey(treePath, meta.index)
                if (sharedKey != null) {
                    engine?.getCachedBitmap(sharedKey)?.let { bmp ->
                        synchronized(bitmapCache) { bitmapCache.put(idx, bmp) }
                        onResult?.invoke(bmp)
                        return
                    }
                }
            } catch (_: Exception) {}
        }
        val sm = maxOf(width, height).coerceAtLeast(1)
        loadScope.launch {
            try {
            val bmp = withContext(Dispatchers.IO) {
                var result: Bitmap? = null
                if (meta.source in listOf("mobi", "azw", "azw3", "pdb", "prc")) {
                    result = loadMobiPageAsync(meta)
                }
                // 所有格式统一走 Java BitmapFactory（正确处理 CMYK/ICC 色彩空间）
                if (result == null) {
                    // ★ SMB/WebDAV 页面供给抽象：远程会话优先，null 时不回退 engine（路径已被守卫拦截）
                    val supply = remotePageSupply
                    result = if (supply != null) supply.loadBitmap(idx)
                    else engine?.loadPage(pageList[idx], treePath)
                }
                result
            }
            var processed = processBitmap(bmp)
            if (processed != null) {
                // 超分：命中缓存直接换成超分图；未命中先显示原图、后台异步超分
                // （完成走订阅→injectUpscaledBitmap；另有 pollForResult 轮询兜底防事件丢失）
                try {
                    val srHit = com.mangareader.sr.SrPipeline.attachOrSchedule(idx, processed)
                    if (srHit != null && !srHit.isRecycled) {
                        processed = srHit
                    } else {
                        com.mangareader.sr.SrPipeline.pollForResult(idx) { b -> injectUpscaledBitmap(idx, b) }
                    }
                } catch (_: Exception) {}
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
        val forwardPages = if (jump) 1 else 3
        val backwardPages = if (jump) 1 else 3
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
            for (idx in loadList) {
                val cached = synchronized(bitmapCache) { bitmapCache[idx] }
                if (cached != null) continue
                val bmp = withContext(Dispatchers.IO) {
                    var result: Bitmap? = null
                    val meta = pageList[idx]
                    try {
                        if (meta.source in listOf("mobi", "azw", "azw3", "pdb", "prc")) {
                            result = loadMobiPageAsync(meta)
                        }
                        // 所有格式统一走 Java BitmapFactory（正确处理 CMYK/ICC 色彩空间）
                        if (result == null) {
                            // ★ SMB/WebDAV 页面供给抽象：远程会话优先，null 时不回退 engine
                            val supply = remotePageSupply
                            result = if (supply != null) supply.loadBitmap(idx)
                            else engine?.loadPage(pageList[idx], treePath)
                        }
                    } catch (_: Exception) {}
                    result
                }
                if (bmp != null) {
                    val processed = processBitmap(bmp)
                    synchronized(bitmapCache) { bitmapCache.put(idx, processed ?: bmp) }
                    recycler.post { adapter?.notifyItemChanged(idx, "ld") }
                }
            }
        }
    }

    private suspend fun ensureArchive() {
        val e = engine ?: return
        val p = withContext(Dispatchers.IO) { try { e.getRealFileForPath(treePath) } catch (_: Exception) { "" } }
        if (p.isEmpty()) return
        if (p == archivePath && archive != null) return
        archiveMutex.withLock {
            if (p == archivePath && archive != null) return
            archive?.close()
            archive = withContext(Dispatchers.IO) { try { com.mangareader.native.NativeEngine.openArchive(p) } catch (_: Exception) { null } }
            archivePath = p
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
        srEventJob?.cancel(); srEventJob = null
        // ★ v1.11 P0：清掉挂起的 settleWindow 与 pending 释放机制（view 销毁/切视图后不再执行）
        cancelPendingRelease()
        // ★ §24 二代：释放信号状态（条漫下只检测不播，无 Drawable 需 stop；清计数防跨卷泄漏）
        pageAnimFlags = emptyList()
        animatedCount = 0
        onAnimDetected = null

        loadScope.coroutineContext.cancelChildren()
        inflightLoads.clear() // ★ 白屏工单任务3
        clearBitmapCacheRecycling()  // ★ ⑧ 修复硬伤：WebtoonReaderView.release() 原本完全不 recycle 任何 bitmap
                                        //   （PagerReaderView 至少 release 里有 recycle）→ 独立严重缺陷
        archive?.close(); archive = null; archivePath = ""
        try {
            // ★ v1.13 C-1：消除提前 return（engine==null 会跳过尾部清身份字段）——改 if 包裹
            val e = engine
            if (e != null) {
                val p = e.getRealFileForPath(treePath)
                if (p.isNotEmpty()) {
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
            for ((k, v) in bitmapCache.snapshot()) {
                if (kotlin.math.abs(k - keep) > 2) {
                    try { if (!v.isRecycled) v.recycle() } catch (_: Exception) {}
                    bitmapCache.remove(k)
                }
            }
        }
    }

    inner class PageAdapter : RecyclerView.Adapter<PageVH>() {
        override fun getItemCount() = pageList.size
        override fun onCreateViewHolder(parent: android.view.ViewGroup, vt: Int): PageVH {
            val gapPx = if (gapEnabled) (15 * parent.context.resources.displayMetrics.density).toInt() else 0
            val frame = android.widget.FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = gapPx
                }
            }
            val iv = android.widget.ImageView(parent.context).apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(pageBgColor)
                adjustViewBounds = true
            }
            frame.addView(iv, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
            return PageVH(frame, iv)
        }
        override fun onBindViewHolder(h: PageVH, pos: Int, payloads: MutableList<Any>) {
            if (payloads.contains("ld")) {
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

            // 静图页：原路径（§24 撤回，条漫不再处理动图渲染——所有页在条漫下都按静图渲染）
            val cached = synchronized(bitmapCache) { bitmapCache[pos] }
            if (cached != null && !cached.isRecycled && cached.width > 1) {
                h.set(cached)
            } else {
                // ★ v1.11 一屏占位（mihon WebtoonPageHolder.kt:54-58/:265 同构机制，官方注释
                //   "keep a minimum height size of the holder"防 adapter 填屏）：无图 item 高度≈0
                //   会让锚定 fill 的位置计算地基失真——图片到货逐页膨胀引发漂移/NOT-LANDED。
                //   一屏占位让地基从一开始就接近真实；真图到位由 PageVH.set 清零恢复内容高。
                h.iv.minimumHeight = if (recycler.height > 0) recycler.height
                                     else h.itemView.resources.displayMetrics.heightPixels
                loadPageAsync(pos)
            }
            // ★ §24 二代：每页异步 peek 一次（仅检测，不播）；≥3 张动图时触发 onAnimDetected 弹"切翻页"提示
            ensureAnimKindAsync(pos)
        }

        /** §24 二代：单页 peek 仅用于 let Reader.kt 知道本卷含动图，不在条漫内播，无 Drawable */
        private fun ensureAnimKindAsync(pos: Int) {
            val e = engine ?: return
            val meta = pageList.getOrNull(pos) ?: return
            if (pageAnimFlags.any { it.pageIdx == pos }) return  // 已 peek 过
            loadScope.launch {
                val kind = withContext(Dispatchers.IO) {
                    try { e.peekAnimKind(treePath, meta) } catch (_: Exception) { AnimKind.NONE }
                }
                if (pageAnimFlags.any { it.pageIdx == pos }) return@launch  // 防 race
                pageAnimFlags = pageAnimFlags + AnimFlag(pos, kind)
                if (kind != AnimKind.NONE) {
                    animatedCount++
                    if (animatedCount >= com.mangareader.ui.ReaderParams.ANIM_PROMPT_THRESHOLD) {
                        onAnimDetected?.invoke(animatedCount)
                    }
                }
            }
        }
        override fun onViewRecycled(h: PageVH) { h.clear() }
    }

    /** PageVH：§24 撤回，回到非 inner class 纯静图版本 */
    class PageVH(frame: android.widget.FrameLayout, val iv: android.widget.ImageView) : RecyclerView.ViewHolder(frame) {
        private var heldBitmap: Bitmap? = null
        var hasImage = false
        var isBound = true
        var boundPosition = -1
        /** 静态图 set */
        fun set(b: Bitmap) {
            iv.minimumHeight = 0  // ★ v1.11 一屏占位撤除：真图到位，恢复 adjustViewBounds 真实内容高
            heldBitmap = b
            iv.setImageBitmap(b)
            hasImage = true
        }
        fun clear() {
            heldBitmap = null; hasImage = false; isBound = false; boundPosition = -1; iv.setImageBitmap(null)
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
    var doubleTapZoom = true

    private var currentScale = DEFAULT_RATE
    private var isZooming = false
    private var halfWidth = 0; private var halfHeight = 0
    var originalHeight = 0; private set
    private var heightSet = false
    private var atFirstPosition = false; private var atLastPosition = false
    private val minRate get() = DEFAULT_RATE

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
        if (currentScale < 1) return 0f
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
        if (currentScale <= DEFAULT_RATE) {
            x = 0f; y = 0f
        } else {
            x = getPositionX(x); y = getPositionY(y)
        }
    }

    fun onScaleBegin() { if (detector.isDoubleTapping) detector.isQuickScaling = true }
    fun onScaleEnd() {
        if (currentScale <= DEFAULT_RATE) {
            currentScale = DEFAULT_RATE
            x = 0f; y = 0f
            setScaleRate(DEFAULT_RATE)
        }
    }

    internal inner class GestureListener : GestureDetectorWithLongTap.Listener() {
        override fun onSingleTapConfirmed(ev: MotionEvent): Boolean { tapListener?.invoke(ev); return false }
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

private const val DEFAULT_RATE = 1f
private const val MAX_SCALE_RATE = 3f

/** ★ v1.11 P0-B：防污染窗时长。覆盖 pending 释放后串行 preload 剩余图片的到货期（gap-fix 漂移期）。 */
private const val SETTLE_WINDOW_MS = 1200L
