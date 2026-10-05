package com.mangareader.native

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

import com.mangareader.viewer.AnimKind
import com.mangareader.viewer.ArchiveEngine
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ★ §34 动图卷专用阅读视图（用户 2026-08-15 决策：动图卷单独引入全新视图，不复用 PagerReaderView 复杂时序）。
 *
 * 设计要点：
 * 1. ViewPager2 + PagerAdapter 做"一页一整页"翻页（与 PagerReaderView 一致，VERT_PAGER 时切竖向）。
 * 2. Adapter 不复用 holder：[RecyclerView.RecycledViewPool.setMaxRecycledViews] 设 0 → 每次 onBind 都 onCreate 新 holder
 *    → 每个 page 独立 ImageView + 独立 Drawable，离开屏幕后 holder 被回收不复用至别的 page。
 * 3. WebView 行为委托 Android framework：drawable 构造后 iv.setImageDrawable(drawable)，
 *    holder attach → ImageView 触发 drawable.setVisible(true) → FrameAnimationDrawable.autoPlay=true → innerStart() 自动启动；
 *    holder detach → setVisible(false) → innerStop() 自动停止。**完全不写 stop/start 状态机**。
 * 4. 不维护 pageAnimDrawables map / inflight set / animRawCache——holder 自带的 drawable 就是缓存。
 *    唯一 IO 优化：onBind 时若 inflightLoads 已含此 idx → 复用正在飞的 load 结果（避免快速翻动重复 IO）。
 *
 * 数据源支持：folder (d:)、zip/apk/epub (a:)、压缩内嵌套 (z:)——Api 全部经 ArchiveEngine.loadPageRaw 抽象。
 *
 * 注意：本视图不做静图降级——若卷里出现静图页，它会显示空白（静图卷会被 Reader.kt 路由至 PagerReaderView）。
 * Reader.kt 进卷 peek 首页判定为动图后才用本视图。
 */
class AnimPagerReaderView(context: Context) : FrameLayout(context), ReaderViewDelegate {

    private val viewPager: ViewPager2
    private var pagerAdapter: AnimPagerAdapter? = null

    private var engine: ArchiveEngine? = null
    private var treePath: String = ""
    private var pageList: List<ArchiveEngine.PageMeta> = emptyList()
    private var currentPage = 0
    private var isRtl = false
    private var verticalPager: Boolean = false

    var pageBgColor: Int = android.graphics.Color.parseColor("#FFFFFF")
    var imageMarginH: Int = 0

    private var loadScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    /** IO inflight 守卫：避免 onBind 同 idx 触发多次 load + 同 holder 二次重设 */
    private val inflightLoads = java.util.Collections.synchronizedSet(HashSet<Int>())
    // ★ v1.13 动图提速：raw 单次读取复用缓存（LRU≤3，peek/构造 drawable 共用，消除重复全文件读）
    private val rawCache = object : LinkedHashMap<Int, ByteArray>(3, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?): Boolean = size > 3
    }
    private val rawCacheLock = Any()
    // ★ 限制并发解码：动画 WebP 解码耗内存大，两页同时解码易 OOM
    private val decodeSem = java.util.concurrent.Semaphore(1)

    /** ★ v1.13 动图提速：后台预读 raw 入缓存（setup 时对当前页预热，首帧 IO 提前开始） */
    fun warmRaw(idx: Int) {
        val e = engine ?: return
        val meta = pageList.getOrNull(idx) ?: return
        synchronized(rawCacheLock) { if (rawCache.containsKey(idx)) return }
        loadScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    try { e.loadPageRaw(meta, treePath) } catch (_: Exception) { null }
                } ?: return@launch
                synchronized(rawCacheLock) { rawCache[idx] = raw }
            } catch (_: Exception) {}
        }
    }

    override var onPageChanged: ((Int) -> Unit)? = null
    override var onPageCountChanged: ((Int) -> Unit)? = null
    override var onToggleUi: (() -> Unit)? = null
    /** ★ N1 章末回调：true=末页 / false=首页，语义与 PagerReaderView.onChapterEnd 一致（动图卷无缝续读用） */
    var onChapterEnd: ((Boolean) -> Unit)? = null

    /** ★ §34.1 hook：同 PagerReaderView 语义——Reader.kt 用来挂钩动图提示（本视图本身就在播，不需弹窗）*/
    var onAnimDetected: ((animatedCount: Int) -> Unit)? = null
    /** ★ §34.1 进卷必须调一次该批量检查以判定本卷是否动图卷——Reader.kt 触发外部提示决策 */
    var onChapterAllAnimated: ((isAllAnimated: Boolean) -> Unit)? = null

    private var navigation: ViewerNavigation = DefaultNavigation
    private var centerGapType: Int = 0
    private var clickFlipType: Int = 1
    private var clickFlipInvertH = false

    private fun applyNavigation() {
        // ★ R6（2026-10-05 对齐）：原硬编码 Default 无视点击翻页区域设置——动图卷与其他
        //   视图点击区不一致（实锤不一致类缺陷）。收敛到 buildNavigation 唯一入口。
        navigation = buildNavigation(clickFlipType, clickFlipInvertH, verticalPager)
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

    init {
        viewPager = ViewPager2(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            offscreenPageLimit = 1
            isUserInputEnabled = true
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    val realPos = if (isRtl) pageList.size - 1 - position else position
                    if (realPos in pageList.indices && realPos != currentPage) {
                        currentPage = realPos
                        onPageChanged?.invoke(currentPage)
                        // ★ N1 动图卷：与 Pager 一致的章末回调（首/末页端）
                        if (currentPage == pageList.size - 1) onChapterEnd?.invoke(true)
                        if (currentPage == 0) onChapterEnd?.invoke(false)
                    }
                }
            })
        }
        addView(viewPager)
        isFocusable = true; isFocusableInTouchMode = true
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (width == 0 || height == 0) return false
            val fx = e.x / width
            val fy = e.y / height
            val action = navigation.getAction(fx, fy)
            // ★ 点击链路诊断（同 PagerReaderView 2026-10-05）：本视图是翻页动画开启时的活动视图
            try { com.mangareader.DiagTracer.mark(context, "tap src=${e.source} tool=${e.getToolType(0)} fx=${(fx * 100).toInt()} fy=${(fy * 100).toInt()} act=$action anim=1") } catch (_: Throwable) {}
            when (action) {
                NavigationAction.PREV -> { if (!verticalPager && isRtl) nextPage() else prevPage() }
                NavigationAction.NEXT -> { if (!verticalPager && isRtl) prevPage() else nextPage() }
                NavigationAction.MENU -> { onToggleUi?.invoke() }
            }
            return true
        }
    })

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        super.dispatchTouchEvent(ev)
        gestureDetector.onTouchEvent(ev)
        return true
    }

    override fun setup(
        engine: ArchiveEngine, treePath: String, pages: List<ArchiveEngine.PageMeta>,
        startPage: Int, vertical: Boolean, clickFlip: Int, rtl: Boolean, verticalPager: Boolean
    ) {
        // ★ v1.13 C-1：release 会置 pagerAdapter=null，而本视图被 remember 单例复用——
        //   动图视图内切模式（useAnimPager 下任意 mode 切换）会触发"自 release 再 setup"，
        //   若仅按 size/treePath 判定则同卷恒 false → adapter 空、整视图永久空白。加 null 判定强制重建
        val pagesChanged = pagerAdapter == null ||
                this.pageList.size != pages.size || this.treePath != treePath
        val rtlChanged = this.isRtl != rtl
        val orientationChanged = this.verticalPager != verticalPager
        this.engine = engine; this.treePath = treePath; this.pageList = pages; this.isRtl = rtl; this.verticalPager = verticalPager
        // ★ R6：进书同步点击翻页区域（原硬编码 Default，设置页改了不生效）
        if (clickFlipType != clickFlip) { clickFlipType = clickFlip }
        applyNavigation()
        viewPager.orientation = if (verticalPager) ViewPager2.ORIENTATION_VERTICAL else ViewPager2.ORIENTATION_HORIZONTAL
        if (pagesChanged) {
            this.currentPage = startPage
            loadScope.cancel()
            loadScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            inflightLoads.clear()
            pagerAdapter = AnimPagerAdapter()
            viewPager.adapter = pagerAdapter
            // ★ 关键：禁用 RecyclerView 的 holder 复用——每页独立 holder，避免 drawable 被错种到别页
            val rv = viewPager.getChildAt(0) as? RecyclerView
            rv?.setRecycledViewPool(object : RecyclerView.RecycledViewPool() {
                override fun setMaxRecycledViews(viewType: Int, max: Int) {}  // 永远不增长
                override fun getRecycledView(viewType: Int): RecyclerView.ViewHolder? = null  // 永不返回复用实例
            })
            // 强制 RV 每次都 onCreateViewHolder——还给一个超大的缓存上限，避免 drop
            rv?.setItemViewCacheSize(0)
            viewPager.setCurrentItem((if (isRtl) pages.size - 1 - startPage else startPage).coerceIn(0, pages.size - 1), false)
        } else if (rtlChanged || orientationChanged) {
            this.currentPage = startPage
            val pos = if (isRtl) pages.size - 1 - startPage else startPage
            viewPager.setCurrentItem(pos.coerceIn(0, pages.size - 1), false)
        } else {
            this.currentPage = startPage
        }
        // ★ v1.13 动图提速：setup 即后台预热当前页 raw（首帧三段 IO 提前开始，用户感知等待大幅缩短）
        if (pagesChanged && pageList.isNotEmpty()) {
            android.util.Log.i("ANIMT", "setup warmRaw startPage=$startPage")
            warmRaw(startPage.coerceIn(0, pageList.size - 1))
        }
    }

    override fun isCurrentPageReady(): Boolean = pagerAdapter != null

    override fun goToPage(page: Int) {
        if (page < 0 || page >= pageList.size) return
        val pos = if (isRtl) pageList.size - 1 - page else page
        viewPager.setCurrentItem(pos.coerceIn(0, pagerAdapter?.itemCount?.minus(1) ?: 0), false)
    }

    override fun release() {
        loadScope.cancel()
        pagerAdapter = null
        viewPager.adapter = null
    }

    override fun trimMemory(level: Int) {
        // ★ 简版：不开销——RV 自己会回收离屏 holder，drawable 在 detach 时被 innerStop
    }

    fun nextPage() {
        if (currentPage < pageList.size - 1) goToPage(currentPage + 1)
    }
    fun prevPage() {
        if (currentPage > 0) goToPage(currentPage - 1)
    }

    /** ★ §34.1 单页动图 Drawable 构造——同 PagerReaderView.makeAnimDrawable，复制以解耦
     *  （2026-09-05 恢复 v1.2 的 APNG4Android 引擎：全 API 兼容、不被 Coil 包装成 ScaleDrawable） */
    private fun makeAnimDrawable(raw: ByteArray, kind: AnimKind, file: java.io.File? = null): Drawable? {
        if (kind == AnimKind.NONE) return null
        return try {
            // ★ mmap 优先：12MB 落 native memory 不占 Java 堆
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
            // ★ §34.1 初始化：构造后同步触发 header 解析（getIntrinsicWidth 阻塞读 header），防首帧 0×0 全白
            try { drawable.intrinsicWidth } catch (_: Throwable) {}
            try { drawable.intrinsicHeight } catch (_: Throwable) {}
            drawable
        } catch (t: Throwable) {
            null
        }
    }

    /** ★ §34.1 单页 raw kind peek（suspend）——在 IO 线程做，不能在主线程同步调
     *   这是 ArchiveEngine.peekAnimKind 是 suspend 函数的强制要求 */
    private suspend fun peekAnimKindSuspend(idx: Int): AnimKind {
        val e = engine ?: return AnimKind.NONE
        val meta = pageList.getOrNull(idx) ?: return AnimKind.NONE
        return try { e.peekAnimKindFast(treePath, meta) } catch (_: Exception) {
            AnimKind.NONE
        }
    }

    /** ★ §34.1 异步加载单页动图——内部走 peekAnimKind 判 kind → loadPageRaw → makeAnimDrawable → set 到 ImageView。
     *  使用 inflight 守卫避免同 idx 重复触发。userIdx 是调用者期望的 page idx（用于 holder fresh 校验） */
    private fun loadAndSetDrawableAsync(idx: Int, holder: AnimViewHolder) {
        val e = engine ?: return
        val meta = pageList.getOrNull(idx) ?: return
        if (!inflightLoads.add(idx)) {
            return
        }
        loadScope.launch {
            // ★ ANIMT 探针：单页三段（IO 读 raw → 构造 drawable → 主线程上屏）总耗时
            val t0 = android.os.SystemClock.elapsedRealtime()
            try {
                // ★ v1.13 动图提速：raw 单次读取（缓存命中免 IO），kind 从同一份 raw 判定——
                //   消除原"peek 全读 + load 再全读"的双倍 IO
                // ★ mmap 优先：File 可用时跳过 readBytes，12MB 落 native memory 不占 Java 堆
                val realFile = withContext(Dispatchers.IO) { e.getRealFileForPage(treePath, meta) }
                val cached = if (realFile == null) synchronized(rawCacheLock) { rawCache[idx] } else null
                val raw = cached ?: withContext(Dispatchers.IO) {
                    try { e.loadPageRaw(meta, treePath) } catch (_: Exception) { null }
                }
                if (raw == null) { withContext(Dispatchers.Main) { holder.hideLoading() }; return@launch }
                if (realFile == null && cached == null) synchronized(rawCacheLock) { rawCache[idx] = raw }
                val kind = e.peekAnimKindFromRaw(raw)
                if (kind == AnimKind.NONE) {
                    // ★ 静图 fallback：decode 到 Bitmap 设到 ImageView（原直接 return 导致不显示）
                    decodeSem.acquire()
                    val bmp = try {
                        withContext(Dispatchers.IO) {
                            try { android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size) } catch (oom: OutOfMemoryError) { android.util.Log.e("ANIMT", "decode OOM idx=$idx size=${raw.size}"); synchronized(rawCacheLock) { rawCache.clear() }; null } catch (_: Exception) { null }
                        }
                    } finally { decodeSem.release() }
                    withContext(Dispatchers.Main) {
                        val pos = holder.bindingAdapterPosition
                        val fresh = pos != RecyclerView.NO_POSITION && pos in pageList.indices
                                && (if (isRtl) pageList.size - 1 - pos else pos) == idx
                        if (fresh) { holder.setStaticBitmap(idx, bmp) }
                        else { holder.hideLoading() }
                    }
                    return@launch
                }
                // 构造 drawable（信号量限制并发，避免两页同时解码 OOM）
                decodeSem.acquire()
                val drawable = try {
                    withContext(Dispatchers.IO) {
                        try { makeAnimDrawable(raw, kind, realFile) } catch (oom: OutOfMemoryError) { android.util.Log.e("ANIMT", "anim OOM idx=$idx kind=$kind size=${raw.size}"); synchronized(rawCacheLock) { rawCache.clear() }; null } catch (_: Exception) { null }
                    }
                } finally { decodeSem.release() }
                if (drawable == null) { android.util.Log.e("ANIMT", "drawable null idx=$idx"); withContext(Dispatchers.Main) { holder.hideLoading() }; return@launch }
                // 4. 主线程 set 到 holder
                withContext(Dispatchers.Main) {
                    val pos = holder.bindingAdapterPosition
                    val fresh = pos != RecyclerView.NO_POSITION && pos in pageList.indices
                            && (if (isRtl) pageList.size - 1 - pos else pos) == idx
                    if (fresh) {
                        holder.setDrawable(idx, drawable)
                        android.util.Log.i("ANIMT", "page ready idx=$idx total=${android.os.SystemClock.elapsedRealtime() - t0}ms rawBytes=${raw.size}")
                    }
                    else { holder.hideLoading() }
                }
            } finally {
                inflightLoads.remove(idx)
            }
        }
    }

    inner class AnimPagerAdapter : RecyclerView.Adapter<AnimViewHolder>() {
        override fun getItemCount() = pageList.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AnimViewHolder {
            val container = FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(pageBgColor)
                val marginPx = (imageMarginH * parent.context.resources.displayMetrics.density).toInt()
                setPadding(marginPx, 0, marginPx, 0)
            }
            val iv = ImageView(parent.context).apply {
                id = android.R.id.content
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.VISIBLE
            }
            container.addView(iv)
            val loadingBar = android.widget.ProgressBar(parent.context, null, android.R.attr.progressBarStyleLarge).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams((48 * parent.context.resources.displayMetrics.density).toInt(), (48 * parent.context.resources.displayMetrics.density).toInt(), android.view.Gravity.CENTER)
                indeterminateDrawable?.setColorFilter(0xFFBB86FC.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
                visibility = View.GONE
            }
            container.addView(loadingBar)
            return AnimViewHolder(container)
        }
        override fun onBindViewHolder(holder: AnimViewHolder, position: Int) {
            val realIdx = if (isRtl) pageList.size - 1 - position else position
            val container = holder.itemView as FrameLayout
            val marginPx = (imageMarginH * container.context.resources.displayMetrics.density).toInt()
            container.setPadding(marginPx, 0, marginPx, 0)
            container.setBackgroundColor(pageBgColor)

            holder.reset()  // ★ 清前一张残留 drawable（若是新 holder 则空操作）
            holder.showLoading()  // ★ 占位动画
            // ★ §34.5：peek 改到 loadAndSetDrawableAsync 内部做（ArchiveEngine.peekAnimKind 是 suspend，
            //   不能在主线程同步调）。该函数里 IO peek + load + makeDrawable + 主线程 set。
            loadAndSetDrawableAsync(realIdx, holder)
        }
        override fun onViewRecycled(holder: AnimViewHolder) {
            holder.reset()
        }
    }

    inner class AnimViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iv: ImageView? = itemView.findViewById(android.R.id.content)
        private val loadingBar: android.widget.ProgressBar? = (itemView as? android.widget.FrameLayout)?.let { container ->
            (0 until container.childCount).map { container.getChildAt(it) }
                .filterIsInstance<android.widget.ProgressBar>().firstOrNull()
        }
        private var currentIdx: Int = -1
        private var currentDrawable: Drawable? = null

        fun showLoading() { loadingBar?.visibility = View.VISIBLE }
        fun hideLoading() { loadingBar?.visibility = View.GONE }

        /** ★ 设置 idx 的 drawable——记住 idx 防错配，set 到 ImageView 让 framework 管 start/stop */
        fun setDrawable(idx: Int, drawable: Drawable) {
            if (currentIdx == idx && currentDrawable === drawable) return  // 同实例 SKIP
            reset()
            currentIdx = idx
            currentDrawable = drawable
            iv?.setImageDrawable(drawable)
            hideLoading()
            // ★ 不主动 start——holder attach 到 RV 后 ImageView 会调 drawable.setVisible(true)
            //   → FrameAnimationDrawable 内部 autoPlay=true → innerStart() 自动跑
            //   但本调用发生时 holder 已经 attach（onBindViewHolder 在可见前调）
            //   实测部分情况 setVisible 未触发，安全兜底：若 isRunning=false 直接 start
            (drawable as? android.graphics.drawable.Animatable)?.let { anim ->
                if (!anim.isRunning) {
                    try { anim.start() } catch (_: Throwable) {}
                }
            }
        }

        /** ★ 静图 fallback：decode 到 Bitmap 设到 ImageView */
        fun setStaticBitmap(idx: Int, bmp: android.graphics.Bitmap?) {
            reset()
            currentIdx = idx
            if (bmp != null) { iv?.setImageBitmap(bmp); currentDrawable = android.graphics.drawable.BitmapDrawable(iv?.resources, bmp) }
            hideLoading()
        }

        /** 清掉本 holder 的 drawable——detach 时被 onViewRecycled 调；或下次 onBind 防残留调 */
        fun reset() {
            currentDrawable?.let { d ->
                (d as? android.graphics.drawable.Animatable)?.let {
                    try { if (it.isRunning) it.stop() } catch (_: Throwable) {}
                }
                iv?.setImageDrawable(null)
            }
            currentIdx = -1
            currentDrawable = null
        }
    }

    fun setCenterGapType(type: Int) { this.centerGapType = type }
}
