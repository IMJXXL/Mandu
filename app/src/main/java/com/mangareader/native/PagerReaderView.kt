package com.mangareader.native

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnLayout
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.viewpager2.widget.ViewPager2
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.mangareader.eink.applyEinkDither
import com.mangareader.viewer.AnimKind
import com.mangareader.viewer.ArchiveEngine

import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PagerReaderView(context: Context) : FrameLayout(context), ReaderViewDelegate {

    // ★ 旋转重算（2026-10-06 工单"横屏没跟随适应宽度/横屏单页不能翻页/竖滑不能拖图"同根）：
    //   manifest configChanges 拦了旋转，视图不重建、已绑定 holder 不再走 onBindViewHolder——
    //   viewportBaseScale 停留旧屏向值（竖屏贴宽≠横屏贴宽），base≠旋转后新 scale → 未放大被误判
    //   放大 → 根检测器门闩+SSIV 分流全堵（翻页死/拖图死），"重新调一下缩放档才好"＝切设置触发
    //   重载掩盖了问题。尺寸真实变化时重载当前窗口：缓存命中零重解码，onReady→applyPlanScale
    //   以新屏尺寸重设 base/scale（同 setTrimWhiteBorder 无白屏模式）。
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (ow <= 0 || oh <= 0 || (w == ow && h == oh)) return
        if (pageList.isEmpty() || pagerAdapter == null) return
        // ★ 尺寸变更=显示配置代数递增（2026-10-06 刀3配套）：旋转后 (cur-1..cur+1) 的重绑
        //   走同图短路时必须重算适配（新视口 base/定位），cfgVer 守卫据此放行
        displayCfgVer++
        // ★ v1.383 根修（海贼王横屏"划不动+乱跳"）：loadPageAsync 缓存命中零动作，载重空转；
        //   布局落定后（post 时 SSIV 已是新尺寸）对存活 holder 强制重算 base/scale/定位。
        //   applyPlanScale/applySplitScale 全量路径幂等，瓦片/超分 miss 页由 loadPageAsync 照常补。
        viewPager.post {
            try {
                val rv = viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView
                    ?: return@post
                for (i in 0 until rv.childCount) {
                    (rv.getChildViewHolder(rv.getChildAt(i)) as? PagerViewHolder)?.recalcForViewport()
                }
            } catch (_: Exception) {}
        }
        val cur = currentPage
        (cur - 1..cur + 1).forEach { if (it in pageList.indices) loadPageAsync(it) }
    }

    private val viewPager: ViewPager2
    private var pagerAdapter: PagerAdapter? = null

    private var engine: ArchiveEngine? = null
    private var treePath: String = ""
    /** ★ SMB/WebDAV 页面供给抽象（RemotePageSupply）：非 null 时 loadPageAsync 走远程会话，ArchiveEngine 不参与 */
    @Volatile
    var remotePageSupply: RemotePageSupply? = null
    // 超分：渐进替换事件订阅（release/setup 重建时取消）
    private var srEventJob: kotlinx.coroutines.Job? = null

    /** ★ P1-1-lite：同页在途加载去重 + 僵尸坑超时（白屏工单任务3）：
     *  idx → 入坑时间戳；同页 5s 内已在加载→复用在途任务；坑龄≥5s 视为挂死残留，覆盖放行自愈。
     *  值仍等于自己那次时间戳才 remove（防误删后来者的坑）。 */
    private val inflightLoads = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    // ★ 2026-09-30：双页丝滑——预载并发 1→2（pageDecode p50≈400ms 串行时一跨页要 0.8s，
    //   快翻必等页；2 路并行把缓冲构建速度翻倍，交互解码仍由 decodeSemaphore(2) 与焦点优先保障）
    private val preloadSemaphore = kotlinx.coroutines.sync.Semaphore(2)
    private var pageList: List<ArchiveEngine.PageMeta> = emptyList()
    private var currentPage = 0

    /** 只读暴露当前页（遮罩超时重载等外部只读场景用；写入仍走内部流程/ goToPage） */
    val currentPageForKick: Int get() = currentPage
    private var isRtl = false
    @Volatile private var isScrolling = false
    private var doublePageMode = false
    private var shiftDoublePage = false
    // ★ 双页显式跨页表（2026-10-05 重做）：adapter 位次/页号映射/点击翻页/章末判定全从表取值，
    //   取代散落 6 处的 2s/2s+1 序号算术。智能配对（宽图/长图 solo）判定在 SpreadTable，
    //   尺寸由 Reader.kt 后台扫描经 setPageDims 回填
    private var spreadTable: SpreadTable = SpreadTable.build(0)
    private var pageDims: IntArray? = null
    private var smartMergeEnabled = true
    private var useSSIV = true
    private var landscapeZoomEnabled = true
    /** ★ 显示配置代数（2026-10-06 乱闪根修刀3）：applyLivePrefs 变更时自增——
     *  同图短路的"需要重设基准"判据，位图与配置全同的重绑零动作 */
    private var displayCfgVer = 0
    // ★ 双击放大开关（config.isDoubleTapZoom）：false=SSIV 双击不缩放，连点=两次点击翻页
    var doubleTapZoom = true
        private set
    // ★ 画面缩放档位（ScalePlan 唯一公式出处；2026-10-05 替代 fitScreen 布尔）
    var scaleMode = ScalePlan.MODE_FIT_WIDTH
        private set
    var zoomStart = ScalePlan.ZOOM_DEFAULT
        private set
    private var tileModeEnabled = false
    private val tileFileCache = java.util.concurrent.ConcurrentHashMap<Int, java.io.File>()
    private val tileTempFiles = java.util.Collections.synchronizedSet(HashSet<java.io.File>())
    private var trimWhiteBorder = false
    private var trimThreshold: Int = 220
    private var trimWhiteRatio: Float = 0.90f
    // ★ 固定四边裁剪（仅本书功能 2026-10-04）：解码后按比例裁掉四边再入缓存——
    //   画面里不存在"放大态"，翻页手势不受影响。条漫分割段图（segmentTotal>1）只裁左右。
    private var cropL = 0f
    private var cropT = 0f
    private var cropR = 0f
    private var cropB = 0f
    private val fixedCropActive: Boolean
        get() = cropL > 0.001f || cropT > 0.001f || cropR > 0.001f || cropB > 0.001f
    private var panNavigation = false
    /** 最近一次 onPageSelected 的 position——bind 时算翻页方向（landscapeZoom 对齐用，mihon 同款语义） */
    @Volatile private var lastSelectedPosition = -1

    /** bind 时翻页方向：position≥上次选中位=前进（首次打开恒前进）；landscapeZoom 落点用 */
    fun bindForwardFor(position: Int): Boolean = position >= lastSelectedPosition
    /** ★ 墨水屏灰阶抖动参数（解码管线参数，非 UI 状态；变更经 applyEinkDitherNow 即时生效） */
    private var einkDither = false
    private var einkDitherAlgorithm = 0
    private var einkDitherLevels = 16
    private var einkEndpointPassthrough = 0
    fun setEinkDither(enabled: Boolean, algorithm: Int, levels: Int, endpointPassthrough: Int = 0) {
        einkDither = enabled; einkDitherAlgorithm = algorithm; einkDitherLevels = levels; einkEndpointPassthrough = endpointPassthrough
    }

    /** ★ 抖动参数即时生效：同 setTrimWhiteBorder 的无白屏策略——全缓存失效（显示中的 holder
     *  继续持有旧图直到重绑），当前±窗口立即重载，其余页滑到时 cache miss 按新参数解码 */
    fun applyEinkDitherNow(enabled: Boolean, algorithm: Int, levels: Int, endpointPassthrough: Int = 0) {
        if (einkDither == enabled && einkDitherAlgorithm == algorithm && einkDitherLevels == levels && einkEndpointPassthrough == endpointPassthrough) return
        setEinkDither(enabled, algorithm, levels, endpointPassthrough)
        bitmapCache.evictAll()
        val cur = currentPage
        (cur - 2..cur + 5).forEach {
            if (it in pageList.indices) { inflightLoads.remove(it); loadPageAsync(it) }
        }
    }

    /** ★ 纸张纹理即时生效：全局 PaperPageTexture 已由 NES 先行更新（含升代），此处全缓存
     *  失效 + 当前±窗口重载，其余页滑到时 cache miss 按新纹理解码（eink 抖动同款无白屏策略） */
    fun applyPaperTextureNow() {
        bitmapCache.evictAll()
        val cur = currentPage
        (cur - 2..cur + 5).forEach {
            if (it in pageList.indices) { inflightLoads.remove(it); loadPageAsync(it) }
        }
        pagerAdapter?.notifyDataSetChanged()  // ★ 整屏幕纹理背景随 holder 重绑刷新
    }

    private var pageLayoutMode = 0 // 0=SINGLE, 1=DOUBLE, 2=AUTO
    var pageBgColor: Int = android.graphics.Color.parseColor("#FFFFFF")
    var imageMarginH: Int = 0

    private var loadScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** ★ 跳页调度（2026-09-09）：inflight 加载 job 注册表，prioritizeLoad 取消窗口外 job 释放 IO 资源 */
    private val loadJobs = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.Job>()
    private val decodeSemaphore = Semaphore(2)

    // ★ B4②（2026-10-04）：续读半页位——NES 在全部 reset 型配置注入（setDoublePageSplit/
    //   setDoublePageModeAndShift 会 resetSplitHalf）之后调用本方法直接生效；旧版"setup 消费"
    //   会被同一次 effect 内更晚的 setDoublePageSplit 清零（独立审查实锤，功能失效）。
    //   仅双页布局未展开表有实际效果；展开表（宽图二分）下视图交替休眠无影响。
    fun setInitialSplitHalfRight(v: Boolean) {
        if (!v) return
        splitHalfRight = true
        // 双页模式就位的落地事件（setDoublePageModeAndShift 的 post setCurrentItem →
        // onPageSelected）会用 pendingLandingSplitHalfRight 重写本位——恢复位必须同时
        // 登记为落地意图，否则停在跨页第二半的书重进后被落地清回第一半
        pendingLandingSplitHalfRight = true
        // 防御：若首个绑定先于本调用发生，重绑一次让右半生效（同一主线程帧内通常尚未绑定，空操作）
        val pos = positionOf(currentPage)
        if (pos >= 0) pagerAdapter?.notifyItemChanged(pos)
    }

    // ★ §24 二代（翻页化）：动图状态——只在翻页模式下处理动图播放。条漫不再处理（条漫看到动图按静图渲染首帧）
    private data class AnimFlag(val pageIdx: Int, val kind: AnimKind)
    @Volatile private var pageAnimFlags: List<AnimFlag> = emptyList()
    @Volatile private var animatedCount: Int = 0
    /** 持有"正在播"的动图 Drawable（idx -> drawable），同时最多一个 isRunning（当前页）
     *  ★ 改用 coil AnimatedImageDecoder 路线后，drawable 是 Android 系统 AnimatedDrawable（Animatable）
     *  ★ LRU 淘汰：上限 7 页（可见 ±附近），超出时淘汰最旧条目并 stop 释放解码器。
     *    对齐参考项目 LruCache 字节预算淘汰——原无上限 LinkedHashMap 导致 40 解码器累积 OOM。 */
    private val pageAnimDrawables = object : java.util.LinkedHashMap<Int, Drawable>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: Map.Entry<Int, Drawable>): Boolean {
            if (size > 3) {
                if (eldest.key == currentPage) return false
                try { (eldest.value as? android.graphics.drawable.Animatable)?.stop() } catch (_: Throwable) {}
                inflightLoads.remove(eldest.key)
                return true
            }
            return false
        }
    }
    @Volatile private var currentPlayingAnimIdx: Int = -1
    /** P9 弹窗 hook：用户切条漫遇动图时由 Reader.kt 弹"只能静态看，是否切换"对话框 */
    var onAnimDetected: ((animatedCount: Int) -> Unit)? = null
    // ★ Bug#9 修复：同 idx 重复 loadAnim 导致重复 IO + 反复 set/new drawable → 翻页卡顿、回已停页不动。
    //   animRawCache：idx → raw bytes（首次 load 后复用，避免每次 onBindViewHolder 重读、重建 Drawable）
    //   inflightAnimLoads：正在 IO 中的 idx Set，防同 idx 并发多次触发 loadAnim
    private val animRawCache = java.util.LinkedHashMap<Int, ByteArray>(16, 0.75f, true)
    private val inflightAnimLoads = java.util.Collections.synchronizedSet(HashSet<Int>())
    /** 动图 drawable 构造失败页：翻回静图路径后不再反复重试动图（防"失败→重探→再失败"死循环） */
    private val animFailedIdx = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Int, Boolean>())

    /**
     * ★ §34.1 动图修复：APNG4Android 旁路 Coil（2026-09-05 恢复 v1.2 方案，仅本地路径使用）。
     *   真根因（字节码级定性）：Coil 的 ImageDecoderDecoder.wrapDrawable 会把动图 AnimatedImageDrawable
     *   包进 coil.drawable.ScaleDrawable（implements Animatable，start/stop 转发给 child）——
     *   但 AnimatedImageDrawable 首帧解码前 getIntrinsicWidth/Height 返回 0 → ScaleDrawable.onBoundsChange
     *   算出 0×0 bounds → draw 画到 0×0 区域 → 画面空白（动图确在动但不可见）。
     *   解法：直接走 APNG4Android 的 WebPDrawable/APNGDrawable/GifDrawable（Java 解码器，全 API 兼容），
     *   FrameAnimationDrawable 自带 Matrix 缩放（setBounds 时自算），不走 Coil 就不被 ScaleDrawable 包装。
     *   参考：EhViewer 自写 Decoder 绕开 Coil wrap；APNG4Android 库本设计即不依赖任何上层 loader。
     *   （34a6c83 曾换成 ImageDecoder——动图 WebP 需 API 30+ 且行为随系统；用户裁定回退 v1.2 引擎）
     */
    private fun makeAnimDrawable(raw: ByteArray, kind: AnimKind, file: java.io.File? = null): Drawable? {
        return try {
            // ★ mmap 优先：12MB 落 native memory 不占 Java 堆（对齐参考项目 LocalViewer）
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
            // ★ §34.1 初始化步骤（非日志）：drawable 构造后主线程同步触发 decoder 解析 header
            //   （getIntrinsicWidth 阻塞读 header），拿到 canvas 尺寸后才返回。
            //   若得到 0 或异常，说明 WebP header 解析失败 → draw 时 bitmap=null → 全白。
            try { drawable.intrinsicWidth } catch (_: Throwable) {}
            try { drawable.intrinsicHeight } catch (_: Throwable) {}
            drawable
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * ★ §24 二代：单页懒 peek → 写入 pageAnimFlags（单元素追加）
     *   命中动图则 notifyItemChanged("anim") 让 onBindViewHolder 重走动图路径
     *   累计 ≥3 张动图时触发 onAnimDetected 回调（弹"切条漫只能静态看"对话框）
     */
    private fun ensureAnimKindAsync(idx: Int) {
        // ★ SMB/WebDAV：远程页由 Coil 解码自动识别动图，无需 peek（peek 会撞 ArchiveEngine 守卫）
        if (remotePageSupply != null) return
        if (animFailedIdx.contains(idx)) return  // 构造失败过：静图路径接管，不再重试
        val e = engine ?: return
        val meta = pageList.getOrNull(idx) ?: return
        loadScope.launch {
            val kind = withContext(Dispatchers.IO) {
                try { e.peekAnimKindFast(treePath, meta) } catch (_: Exception) {
                    AnimKind.NONE
                }
            }
            if (pageAnimFlags.any { it.pageIdx == idx }) return@launch  // 已判过
            pageAnimFlags = pageAnimFlags + AnimFlag(idx, kind)
            if (kind != AnimKind.NONE) {
                animatedCount++

                withContext(Dispatchers.Main) { pagerAdapter?.notifyItemChanged(idx, "anim") }
                if (animatedCount >= com.mangareader.ui.ReaderParams.ANIM_PROMPT_THRESHOLD) {
                    onAnimDetected?.invoke(animatedCount)
                }
            }
        }
    }

    /** ★ §24 二代动图加载路径：loadPageRaw（全质量） → 主线程构造 drawable → setDrawable */
    private fun loadAnimPageAsync(idx: Int, holder: PagerViewHolder) {
        val e = engine ?: return
        val meta = pageList.getOrNull(idx) ?: return
        val kind = pageAnimFlags.firstOrNull { it.pageIdx == idx }?.kind ?: return
        if (animFailedIdx.contains(idx)) return  // 构造失败过：静图路径接管，不再重试
        // ★ Bug#9：命中 raw cache 直接复用，不重走 IO；命中已构造 drawable 直接补 start
        if (pageAnimDrawables[idx] != null) {
            val existingDrawable = pageAnimDrawables[idx]!!
            // ★ Bug C-1a 修复：drawable 虽在 map 里，但 holder 可能是新复用的（ImageView 上没有这个 drawable）。
            //   原版只 startAnimIfNotRunning 不 setAnimDrawable → 回翻时 holder 的 ImageView 空白 → 闪白。
            //   若 holder 当前 held 的不是这个 drawable，则 setAnimDrawable 重设到 ImageView（内部 SKIP 同实例）。
            if (holder.heldIdx != idx || holder.heldAnimDrawable !== existingDrawable) {
                holder.setAnimDrawable(idx, existingDrawable)
            }
            if (idx == currentPage) startAnimIfNotRunning(idx)
            return
        }
        val cachedRaw = synchronized(animRawCache) { animRawCache[idx] }
        if (cachedRaw != null) {
            loadScope.launch {
                val drawable = withContext(Dispatchers.IO) {
                    try { makeAnimDrawable(cachedRaw, kind) } catch (_: Exception) { null }
                }
                if (drawable == null) {
                    markAnimFailed(idx)
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    val pos = holder.bindingAdapterPosition
                    val fresh = pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION && pos in pageList.indices
                            && (if (isRtl) pageList.size - 1 - pos else pos) == idx
                    if (fresh) {
                        holder.setAnimDrawable(idx, drawable)
                        if (idx == currentPage) startAnimIfNotRunning(idx)
                    }
                }
            }
            return
        }
        // ★ Bug#9 inflight 守卫：同 idx 正在 IO 中，这帧不再二次触发，IO 完成时 holder 已经/会再次被 bind 触发
        if (!inflightAnimLoads.add(idx)) {
            return
        }
        loadScope.launch {
            val t0 = android.os.SystemClock.uptimeMillis()
            try {
                // ★ mmap 优先：File 可用时跳过 readBytes，12MB 落 native memory 不占 Java 堆
                val realFile = withContext(Dispatchers.IO) { e.getRealFileForPage(treePath, meta) }
                val drawable = if (realFile != null) {
                    withContext(Dispatchers.IO) {
                        try { makeAnimDrawable(ByteArray(0), kind, realFile) } catch (_: Exception) { null }
                    }
                } else {
                    val raw = withContext(Dispatchers.IO) {
                        try { e.loadPageRaw(meta, treePath) } catch (_: Exception) { null }
                    }
                    if (raw == null) {
                        markAnimFailed(idx)
                        return@launch
                    }
                    synchronized(animRawCache) {
                        animRawCache[idx] = raw
                        while (animRawCache.size > 6) {
                            val it = animRawCache.entries.iterator()
                            if (it.hasNext()) { it.next(); it.remove() } else break
                        }
                    }
                    withContext(Dispatchers.IO) {
                        try { makeAnimDrawable(raw, kind) } catch (_: Exception) { null }
                    }
                }
                if (drawable == null) {
                    markAnimFailed(idx)
                    return@launch
                }
                val ms = android.os.SystemClock.uptimeMillis() - t0
                if (ms > 2500) runCatching {
                    // 慢动图取证（"打开 GIF 几十秒"专项）：超 2.5s 落持久短码
                    com.mangareader.DiagTracer.markStatic("reader: animslow ms=$ms kind=$kind idx=$idx")
                }
                withContext(Dispatchers.Main) {
                    // ★ Bug#7 holder-freshness 守卫：异步载入期间 holder 可能已被 Recycler 复用到别的页位置。
                    //   若 holder.bindingAdapterPosition 已 NO_POSITION 或映射到的 realIdx ≠ 当前请求的 idx，
                    //   说明这帧动图属于已被滚走的页 → 丢弃，避免把错误位置里的 drawable 塞给当前 holder。
                    //   非动图分支下 onBindViewHolder 已过滤 doublePage，故此处只在 non-double 模式到达，
                    //   flat 公式为 realIdx = isRtl ? size-1-pos : pos。
                    //   注：suspend inline withContext 块内不允许 non-local return@launch，故用 if 条件执行
                    val pos = holder.bindingAdapterPosition
                    val freshOuter = pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION && pos in pageList.indices
                            && (if (isRtl) pageList.size - 1 - pos else pos) == idx
                    if (freshOuter) {
                        holder.setAnimDrawable(idx, drawable)
                        if (idx == currentPage) startAnimIfNotRunning(idx)
                    }
                }
            } finally {
                inflightAnimLoads.remove(idx)
            }
        }
    }

    /** 动图构造失败：翻回静图路径（移除动图标记 + 重绑），animFailedIdx 挡住重探防死循环 */
    private fun markAnimFailed(idx: Int) {
        animFailedIdx.add(idx)
        inflightAnimLoads.remove(idx)
        pageAnimFlags = pageAnimFlags.filter { it.pageIdx != idx }
        post { pagerAdapter?.notifyItemChanged(idx) }
    }

    /** 启动当前页动图（PagerReaderView 单页同时只有一页可见，逻辑极简）
     *  ★ §34.1 APNG4Android 关键调整：**不 stop 其他页的 drawable**。
     *    FrameAnimationDrawable.stop() 会 innerStop：frames.clear() + bitmap.recycle() + frameBuffer=null
     *    → drawable 进入"死掉"状态，下次再 start() 要从头 initCanvasBounds + 异步 onRender 才能填 bitmap
     *    → 期间 draw() 因 bitmap==null 不画 → 回翻该页画面全白 ~200-700ms。
     *    解决：保持所有已构造 drawable 持续 running（最多 offscreenPageLimit=2 + 预构页 ≈ 6 个同时跑，
     *      WebPDrawable 单个解码 buffer ~3-5MB × 6 ≈ 30MB，可控）。
     *      可见页就是用户当前看的，其它页解码线程在跑但 ImageView 不显示——浪费 CPU 但确保回翻无空窗。
     *    ★ 替代方案（如内存紧张可改）：drawable 不 stop，但若 onPageSelected 显示回来后 isRunning=false → START。
     *      这里走"不 stop "+ "isRunning false 才 START" 双保险。 */
    private fun startAnimIfNotRunning(idx: Int) {
        // ★ §34.1：不再 forEach stop 其他页——保留它们持续 running 以避免 stop/start 重启空窗
        val d = pageAnimDrawables[idx]
        if (d == null) {
            currentPlayingAnimIdx = -1
            return
        }
        val anim = d as? Animatable
        if (anim != null && anim.isRunning) {
            currentPlayingAnimIdx = idx
            return
        }
        try {
            anim?.start()
        } catch (t: Throwable) {
            // ★ Bug#10 兜底：start 异常时把坏 drawable 从 map 移除，下次进这页能重新 load/build
            pageAnimDrawables.remove(idx)
        }
        currentPlayingAnimIdx = idx
    }

    // Zoom indicator state (driven by SSIV scale change detection)
    internal var zoomIndicatorTime = 0L
    private var zoomIndicatorAlpha = 0f
    private var lastReportedScale = 1f

    // SSIV zoom state for progress save/restore
    var currentZoomScale: Float = 1f
        private set
    var currentCenterX: Float = 0f
        private set
    var currentCenterY: Float = 0f
        private set

    /** Read zoom state from the currently visible SSIV */
    fun updateZoomStateFromSSIV() {
        if (!useSSIV) return
        try {
            val rv = viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return
            val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
            val pos = lm.findFirstVisibleItemPosition()
            if (pos == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return
            val holder = rv.findViewHolderForAdapterPosition(pos) as? PagerViewHolder ?: return
            val ssiv = holder.ssiv ?: return
            if (ssiv.visibility == View.VISIBLE && ssiv.scale > 1.01f) {
                currentZoomScale = ssiv.scale
                val center = ssiv.center ?: return
                currentCenterX = center.x
                currentCenterY = center.y
            } else {
                currentZoomScale = 1f
                currentCenterX = 0f
                currentCenterY = 0f
            }
        } catch (_: Exception) {}
    }

    // ★ F3.1 修正版（0829 双页闪退实锤）：LruCache 按张数限容（8 张），sizeOf 恒返回 1。
    //   首版按 byteCount 计重——位图 recycle 后 byteCount 归 0，LruCache 内部计重失配，
    //   evictAll 时抛 "sizeOf() is reporting inconsistent results!" 闪退；常量计重永不失配。
    //   单页内存上限由 F3.2 解码闸（≤6M px ≈ 24MB）兜底，8 张最坏 ~192MB，远好于原来无上限。
    private val bitmapCache = object : android.util.LruCache<Int, Bitmap>(16) {
        override fun sizeOf(key: Int, value: Bitmap): Int = 1
    }

    // ★ 双页合并产物缓存：64MB 上限（全屏 ARGB_8888 约 3-6 张），重绑零重复分配。
    //   按字节计重（非 recycle 策略同 bitmapCache，byteCount 恒定无失配风险）
    private val mergedCache = object : androidx.collection.LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    // ★ ⑧ 最小修复辅助：clear 缓存。
    //   ★ v102 关键修复：不再 recycle——v101 起 SMB 图片 decode 到 bitmapCache 的 Bitmap 会通过
    //   SSIV.setImage(ImageSource.bitmap(bmp)) 交给 SSIV 渲染，SSIV/RenderThread 异步持有该 Bitmap
    //   引用（tile 化后台线程读像素）。此处 recycle 而 SSIV 还在用 → native 像素内存 UAF →
    //   RenderThread/HeapTaskDaemon/Jit SIGSEGV（fault addr 呈 ASCII 字符串 = 已被复用内存）。
    //   只 evict 让 GC 管：SSIV 引用保活到用完，无人引用后 NativeAllocationRegistry 自动释放 native 像素。
    private fun clearBitmapCacheRecycling() {
        // ★ 白屏工单任务2附属：指认"谁在清缓存"（C4 定位用：size + 视图实例 hash）
        Log.w("Recycle", "pager clearBitmapCacheRecycling n=${bitmapCache.size()} view=${System.identityHashCode(this)}")

        bitmapCache.evictAll()
        splitActivePositions.clear()
        clearTileFileCache()
    }

    private fun clearTileFileCache() {
        tileFileCache.clear()
        synchronized(tileTempFiles) {
            tileTempFiles.forEach { f -> try { f.delete() } catch (_: Exception) {} }
            tileTempFiles.clear()
        }
    }

    // Zoom indicator paint
    private val zoomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 42f; textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }
    private val zoomBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 0, 0, 0) }

    override var onPageChanged: ((Int) -> Unit)? = null
    override var onPageCountChanged: ((Int) -> Unit)? = null
    override var onToggleUi: (() -> Unit)? = null
    var onChapterEnd: ((Boolean) -> Unit)? = null  // true=last page, false=first page
    var onPageLongPress: ((Int) -> Unit)? = null  // long press on page

    // Tap + long press detection via GestureDetector (TSY approach)
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        // ★ 根检测器滑动兜底（2026-10-05）：dispatchTouchEvent 在转发链根上恒收全部事件
        //   （点击链路长期正常证明 DOWN/UP 可达根），下层 SSIV 的手势与兜底在部分设备上
        //   收不到完整 MOVE 序列（日志实锤：126 次放行 0 次接管 0 次兜底）。沿翻页主轴的
        //   fling / 大幅位移在"静止 + 当前页未放大"时直接翻一页；RV 已接管（DRAGGED/
        //   SETTLING）或放大态拖图不受影响。与 SSIV 自带兜底靠 IDLE 守卫天然防重。
        private var rootFired = false
        override fun onDown(e: MotionEvent): Boolean { rootFired = false; return super.onDown(e) }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (rootFired || activePagePanning()) return false
            val dAlong = if (!verticalPager) (e2.x - (e1?.x ?: e2.x)) else (e2.y - (e1?.y ?: e2.y))
            val dOrtho = if (!verticalPager) (e2.y - (e1?.y ?: e2.y)) else (e2.x - (e1?.x ?: e2.x))
            val extent = if (!verticalPager) width else height
            // ★ 沿轴净位移需占优：适应宽度竖拖画面时轨迹稍斜不应误翻（mihon 同款平移优先）
            if (extent > 0 && kotlin.math.abs(dAlong) > extent * 0.08f && kotlin.math.abs(dAlong) >= kotlin.math.abs(dOrtho)) {
                if (tryRootFlip(if (dAlong < 0) 1 else -1)) rootFired = true
            }
            return false
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val dxTotal = e2.x - (e1?.x ?: e2.x)
            val dyTotal = e2.y - (e1?.y ?: e2.y)
            // ★ 速度沿轴 + 整段位移沿轴双条件：竖拖画面松手(纵向速度大)永不误翻
            val alongAxis = if (!verticalPager)
                kotlin.math.abs(vx) >= kotlin.math.abs(vy) && kotlin.math.abs(dxTotal) >= kotlin.math.abs(dyTotal)
            else
                kotlin.math.abs(vy) >= kotlin.math.abs(vx) && kotlin.math.abs(dyTotal) >= kotlin.math.abs(dxTotal)
            if (alongAxis && !rootFired && !activePagePanning()) {
                val v = if (!verticalPager) vx else vy
                if (tryRootFlip(if (v < 0) 1 else -1)) rootFired = true
            }
            return false
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (width == 0 || height == 0) return false
            val fx = e.x / width
            val fy = e.y / height
            val action = navigation.getAction(fx, fy)
            // ★ 点击链路诊断（2026-10-05 触控笔点击排查）：记录输入源/工具类型/落点分区——
            //   笔点屏若到达本视图，日志直接可见 src/tool/分区；日志里没有 = 事件根本没进窗口
            try { com.mangareader.DiagTracer.mark(context, "tap src=${e.source} tool=${e.getToolType(0)} fx=${(fx * 100).toInt()} fy=${(fy * 100).toInt()} act=$action") } catch (_: Throwable) {}
            when (action) {
                // ★ §34.3：竖向翻页时 PREV/NEXT 不受 RTL 影响（上下方向无 RTL 概念）
                NavigationAction.PREV -> { if (!verticalPager && isRtl) nextPage() else prevPage() }
                NavigationAction.NEXT -> { if (!verticalPager && isRtl) prevPage() else nextPage() }
                NavigationAction.MENU -> { onToggleUi?.invoke() }
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            // ★ T8 副修复：currentPage 本身已是物理页号，不能再镜像一次（RTL 下长按存的是镜像页）
            val pageIdx = currentPage
            onPageLongPress?.invoke(pageIdx)
            try { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) } catch (_: Exception) {}
        }
    })
    private var navigation: ViewerNavigation = DefaultNavigation
    /** ★ §34.3：VERT_PAGER（单页从上到下）专用导航。fy<0.4→PREV、fy>0.6→NEXT、中间菜单。
     *  与横向 DefaultNavigation 不同——竖向翻页时 fx 无意义，只用 fy。 */
    private var verticalPager: Boolean = false
        set(v) {
            field = v
            // ★ 同步已挂载 SSIV 的手势分流主轴（横向 pager=横滑翻页/纵拖看图；竖向 pager 反向）
            try {
                val rv = viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView
                if (rv != null) {
                    for (i in 0 until rv.childCount) {
                        (rv.getChildAt(i) as? ViewGroup)?.findViewById<PagerSSIV>(com.mangareader.R.id.ssiv_pager)
                            ?.axisPassthroughHorizontal = !v
                    }
                }
            } catch (_: Exception) {}
        }
    private var centerGapType: Int = 0
    private var doublePageReverseEnabled: Boolean = false
    private var doublePageSplitEnabled: Boolean = false
    // ★ 拆分翻页状态机（2026-09-30 修"翻一次翻两页"；2026-10-04 修"第二次翻页不显示右半"）：
    //   拆分跨页一次只显示半页，翻页应先切到同份文件的另一半（同一跨页内），再进下一跨页。
    //   splitHalfRight=当前跨页正显示右半；splitActivePositions=bind 时走了拆分分支的位置
    //   集合（位图未就绪时的判定兜底）；pendingLandingSplitHalfRight=跨页回退时预挂的半页位，
    //   onPageSelected 落地时消费——回退进上一跨页应从其右半继续阅读序，而落地回调无条件
    //   重置会把它清掉（回退显示左半=顺序错乱感的根源），改由 pending 精确传达。
    private var splitHalfRight = false
    private var pendingLandingSplitHalfRight = false
    private val splitActivePositions = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Int, Boolean>())

    private fun isCurrentPageZoomed(): Boolean {
        return try {
            val rv = viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return false
            val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return false
            val pos = lm.findFirstVisibleItemPosition()
            if (pos == -1 || pos == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return false
            val holder = rv.findViewHolderForAdapterPosition(pos) as? PagerViewHolder ?: return false
            val ssiv = holder.ssiv ?: return false
            ssiv.visibility == View.VISIBLE && ssiv.scale > ((ssiv.viewportBaseScale.takeIf { it > 0f } ?: ssiv.minScale) + 0.01f)
        } catch (_: Exception) { false }
    }

    /** ★ SSIV 未放大态平移接管中（2026-10-06）：根检测器让路用。与 isCurrentPageZoomed
     *  同款 holder 定位——双翻页源（根检测器/SSIV 分流）判定窗对斜滑不一致，适应宽度页
     *  竖拖平移中轨迹稍斜，根检测器沿轴占优判定会抢翻打断平移；转发层丢流的设备 SSIV
     *  收不到 MOVE 不置位，根检测器照常兜底翻页，本机故障路径不受影响。 */
    private fun activePagePanning(): Boolean {
        return try {
            val rv = viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return false
            val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return false
            val pos = lm.findFirstVisibleItemPosition()
            if (pos == -1 || pos == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return false
            (rv.findViewHolderForAdapterPosition(pos) as? PagerViewHolder)?.ssiv?.panActive == true
        } catch (_: Exception) { false }
    }

    /** ★ 根检测器翻页动作：仅静止（IDLE）且当前页未放大时生效——放大态由 SSIV 平移/拖图，
     *  RV 接管后的滚动（DRAGGED/SETTLING）由 ViewPager2 自己完成，这里一律让位。 */
    private fun tryRootFlip(dir: Int): Boolean {
        return try {
            val count = pagerAdapter?.itemCount ?: 0
            if (count > 0 && viewPager.scrollState == ViewPager2.SCROLL_STATE_IDLE && !isCurrentPageZoomed()) {
                val target = (viewPager.currentItem + dir).coerceIn(0, count - 1)
                if (target != viewPager.currentItem) {
                    viewPager.setCurrentItem(target, true)
                    true
                } else false
            } else false
        } catch (_: Exception) { false }
    }

    private val fadeOutRunnable = Runnable { invalidate() }

    init {
        viewPager = ViewPager2(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            offscreenPageLimit = 2
            isUserInputEnabled = true
            // 初始不设置 PageTransformer，等 setup 完成后再应用
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    // ★ setup 锚定窗口：只记方向参照，不写 curPage/不报章末（见 setupAnchoring 注释）
                    // ★ 表切换窗口（2026-10-06 乱跳页根因）：rebindForNewTable 的 notifyDataSetChanged
                    //   会让 VP2 以旧 position 数值触发本回调，而 spreadTable 已是新表——旧位置按
                    //   新表解释出错误页号（1006 日志实锤 start=37→3→0 十五毫秒三连、33↔35 振荡）
                    //   外报到 Reader.cur，经重组 setup 回灌放大，直到收敛不动点第 0 页。窗口内
                    //   只记位置不写页；锚定 setCurrentItem(positionOf(currentPage)) 后位置本就对齐，
                    //   currentPage 无需更新，零丢报
                    if (setupAnchoring || layoutSwitchPending) { lastSelectedPosition = position; return }
                    // ★ T8：position → spread 序号（RTL 唯一镜像点）→ 跨页表取该位阅读序首页
                    //   （2026-10-05 重做：奇偶/首张单页/智能跳位全在表内，这里只做 RTL 镜像）
                    val itemCount = pagerAdapter?.itemCount ?: spreadTable.slotCount
                    val s = if (isRtl) itemCount - 1 - position else position
                    // 位置记录先于越界守卫（旧算术无条件记录）：瞬态窗口漏记会让下一次
                    // bind 的 forward 方向判定用旧值，landscapeZoom 落边错一次
                    lastSelectedPosition = position
                    // 模式切换/表重排的瞬态窗口里 position 可能超出新表（旧算术产生越界页号被
                    // indices 守卫自然过滤；表取值 clamp 会误跳第 0 页）——越界直接不处理
                    if (s < 0 || s >= spreadTable.slotCount) return
                    val pageIdx = spreadTable.firstPage(s)
                    val realPos = pageIdx
                    com.mangareader.viewer.ArchiveEngine.d("PagerView", "onPageSelected pos=$position pageIdx=$pageIdx realPos=$realPos rtl=$isRtl double=$doublePageMode total=${pageList.size} curPage=$currentPage")
                    if (realPos in pageList.indices && realPos != currentPage) {
                        // ★ 跨页落地半页位（2026-10-04）：按 pending 恢复——前进/跳页=左半，
                        //   拆分回退=右半（从上一跨页右半继续阅读序）。旧逻辑无条件 reset
                        //   把回退的右半意图清掉，回退恒显示左半
                        splitHalfRight = pendingLandingSplitHalfRight
                        pendingLandingSplitHalfRight = false
                        currentPage = realPos; onPageChanged?.invoke(currentPage)

                        // ★ 2026-09-09 跳页调度：翻页选中即取消远端 inflight，不等 IDLE
                        prioritizeLoad(realPos)
                        // ★ N1：双页模式末跨页判据走跨页表（奇偶错位/智能 solo 尾位全消化）；
                        //   模式切换窗口内（layoutSwitchPending）抑制章末回调，防并发闪退
                        if (!layoutSwitchPending) {
                            if (spreadTable.pageToSpread(currentPage) >= spreadTable.slotCount - 1) onChapterEnd?.invoke(true)
                            if (currentPage == 0) onChapterEnd?.invoke(false)
                        }
                    }
                    // ★ §24 二代：翻到新页时启动该页动图、停止其他正在播的
                    if (realPos in pageList.indices) {
                        startAnimIfNotRunning(realPos)
                        // ★ Bug A 修复：onBindViewHolder 的 ensureAnimKindAsync 受 !isScrolling 守卫，
                        //   快速翻页 / 预载范围外的页 (
                        //   idx ≥ offscreenPageLimit+2）翻到位时还没被 peek → pageAnimFlags 无 entry
                        //   → IDLE 后 Bug#14 的 notifyItemChanged 因 flag==null 被跳过 → 永远 NOT IN MAP。
                        //   解法：onPageSelected 是用户实际翻到该页的权威时机，主动 enqueue peek。
                        //   ensureAnimKindAsync 内部 (第 128 行) 已有 "已判过 return@launch" 幂等守卫，重复触发安全。
                        if (pageAnimFlags.none { it.pageIdx == realPos }) {
                            ensureAnimKindAsync(realPos)
                        }
                    }
                }
                override fun onPageScrollStateChanged(state: Int) {
                    isScrolling = state != ViewPager2.SCROLL_STATE_IDLE
                    // ★ 滑动诊断（2026-10-05）：DRAGGED=ViewPager2 确实接管了拖动（放行成功）；
                    //   配合 PagerSSIV 的 swipe 短码定位"滑动不翻页"断在哪一层
                    if (state != ViewPager2.SCROLL_STATE_IDLE) {
                        try {
                            com.mangareader.DiagTracer.mark(context, "pagerScroll=" + if (state == ViewPager2.SCROLL_STATE_DRAGGING) "dragging" else "settling")
                        } catch (_: Throwable) {}
                    }
                    if (!isScrolling) {
                        // ★ 翻页中渐进收集的尺寸在落位后补排（用户停在末页不再解码也不丢）
                        if (scannedDirty) flushScannedDims()
                        preloadAround(currentPage)

                        // ★ Bug#14：如果当前页或附近页是动图但 IDLE 之前 onBindViewHolder 因 isScrolling=true
                        //   skip 了 loadAnimPageAsync，现在 IDLE 后无法再触发它（preloadAround 只走静图路径）。
                        //   主动 notifyItemChanged 让 RV 重 bind 当前页 → 走动图分支的 loadAnimPageAsync。
                        val rv = viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return
                        val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
                        for (delta in -1..1) {
                            val target = currentPage + delta
                            if (target !in pageList.indices) continue
                            val flag = pageAnimFlags.firstOrNull { it.pageIdx == target } ?: continue
                            if (flag.kind == AnimKind.NONE) continue
                            val pos = if (isRtl) pageList.size - 1 - target else target
                            if (lm.findViewByPosition(pos) != null) {
                                rv.post { pagerAdapter?.notifyItemChanged(pos, "anim") }
                            }
                        }
                    }
                }
            })
        }
        addView(viewPager)
        isFocusable = true; isFocusableInTouchMode = true
    }

    var flipAnimationEnabled = true
        set(value) {
            field = value
            // ★ §34.3：竖向翻页禁 FlipPageTransformer（3D 左右翻对竖向不符）
            val effective = value && !verticalPager
            viewPager.setPageTransformer(if (effective) FlipPageTransformer() else NoOpTransformer())
        }

    private class NoOpTransformer : ViewPager2.PageTransformer {
        override fun transformPage(page: View, position: Float) {
            page.apply {
                rotationY = 0f
                scaleX = 1f
                scaleY = 1f
                alpha = 1f
                translationX = 0f
            }
        }
    }

    private class FlipPageTransformer : ViewPager2.PageTransformer {
        override fun transformPage(page: View, position: Float) {
            val absPos = kotlin.math.abs(position)
            page.apply {
                // 3D翻转效果
                cameraDistance = 12000f
                rotationY = position * -30f
                scaleX = 1f - absPos * 0.15f
                scaleY = 1f - absPos * 0.15f
                alpha = 1f - absPos * 0.4f
                translationX = -position * width * 0.1f
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        super.dispatchTouchEvent(ev)
        gestureDetector.onTouchEvent(ev)
        return true
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
    }

    // ★ 点击翻页区域（0=关 1=左右分区 4=L型；2/3 旧对角线不活跃映射为左右分区）+ 水平对调
    private var clickFlipType = 1
    private var clickFlipInvertH = false

    override fun setClickFlipInvert(invert: Boolean) {
        if (clickFlipInvertH == invert) return
        clickFlipInvertH = invert
        applyNavigation()
    }

    /** 点击翻页区域切换实时生效（对调走 setClickFlipInvert 通道） */
    override fun setClickFlipTypeLive(type: Int) {
        if (clickFlipType == type) return
        clickFlipType = type
        applyNavigation()
    }

    private fun applyNavigation(verticalPagerNow: Boolean = verticalPager) {
        // ★ 映射收敛到 buildNavigation（新增边沿/Kindlish 模式只改一处）
        this.navigation = buildNavigation(clickFlipType, clickFlipInvertH, verticalPagerNow)
    }

    // ★ 2026-10-05 setup 锚定窗口：adapter 挂上到 setCurrentItem 落位期间抑制 onPageSelected
    //   副作用——ViewPager2 挂 adapter 必先回调 onPageSelected(0)，RTL 下旧写法把 curPage
    //   写成镜像末页（实锤：切方向瞬间 2 页跳 198 页，"乱跳页"根源之一）
    private var setupAnchoring = false

    override fun setup(engine: ArchiveEngine, treePath: String, pages: List<ArchiveEngine.PageMeta>, startPage: Int, vertical: Boolean, clickFlip: Int, rtl: Boolean, verticalPager: Boolean) {
        val pagerOrientChanged = this.verticalPager != verticalPager
        this.verticalPager = verticalPager
        // ★ §34.3：竖向翻页用全宽三分纵切导航；横向保持默认左右三分
        this.clickFlipType = clickFlip
        applyNavigation(verticalPager)
        // ★ §34.3：ViewPager2 方向——竖向翻页时 VERTICAL（上下滑切页），横向时 HORIZONTAL
        viewPager.orientation = if (verticalPager) ViewPager2.ORIENTATION_VERTICAL else ViewPager2.ORIENTATION_HORIZONTAL
        // ★ §34.3：竖向翻页禁用 FlipPageTransformer（3D 翻转对竖向不符）；后面 flipAnimationEnabled setter 会读 verticalPager
        this.useSSIV = true
        val pagesChanged = this.pageList.size != pages.size || this.treePath != treePath
        val rtlChanged = this.isRtl != rtl
        val engineChanged = this.engine != engine
        this.engine = engine; this.treePath = treePath; this.pageList = pages; this.isRtl = rtl
        // ★ 跨页表随页表重建（页长变了旧表失效；pageDims 长度不匹配时自动回退朴素配对）
        rebuildSpreadTable()
        // ★ 上报本视图真实页数：expandedPageCount 只被条漫视图回调更新，pager/curl 章
        //   setup 不上报会让上一章条漫的展开页数残留 → 进度条越界、goToPage 吞页、done 误标读完
        onPageCountChanged?.invoke(pageList.size)
        // SMB/WebDAV solid archive: page count grows as extract progresses
        remotePageSupply?.onPageCountGrow?.add { newCount ->
            if (newCount > pageList.size) {
                val oldSize = pageList.size
                val grown = pageList + (pageList.size until newCount).map {
                    // ★ 远程拆分接入：新页同样带 source/originalPageIdx 戳（未展开书 idx 等值，
                    //   解码/焦点映射统一走元数据还原）
                    ArchiveEngine.PageMeta(
                        it, treePath.substringAfterLast('.', "").lowercase().let { e -> if (e == "cbz") "zip" else e },
                        "", localIdx = it, originalPageIdx = it, name = ""
                    )
                }
                pageList = grown
                // 远程页表增长：同步扩跨页表（新页尺寸未知=朴素配对，与旧行为一致）
                rebuildSpreadTable()
                post { pagerAdapter?.notifyItemRangeInserted(oldSize, newCount - oldSize) }
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
        if (pagesChanged || engineChanged) {
            // 页数或引擎变化：完全重建
            this.currentPage = startPage
            loadScope.cancel()
            loadScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            loadJobs.clear() // ★ 调度修复：旧 scope 的 job 全部作废，注册表同步清空
            // ★ ⑧ 最小修复：切章 clear 前 recycle 所有 bitmap，避免裸 clear 导致 Bitmap 泄漏（内存只涨不回收）
            clearBitmapCacheRecycling()
            // ★ §24 二代 Bug#6：切卷时必须清空动图状态——否则上一卷的 pageAnimDrawables 不释放（decoder/内存泄露）、
            //   pageAnimFlags 残留污染新卷 idx 判定、animatedCount 不归零导致提前弹"切翻页"对话框
            pageAnimDrawables.values.forEach { try { if (it is Animatable && it.isRunning) it.stop() } catch (_: Throwable) {} }
            pageAnimDrawables.clear()
            pageAnimFlags = emptyList()
            animatedCount = 0
            animFailedIdx.clear()
            currentPlayingAnimIdx = -1
            // ★ ① 修复：切章必须清 inflightAnimLoads 和 animRawCache。
            //   原因：viewPager/NativeReaderScreen 实例跨 setup 复用（remember{} 创建同一 PagerReaderView），
            //   这两个字段是 view 实例字段也跨 setup 复用。旧卷残留 inflightAnimLoads[idx] 守卫把
            //   新卷同 idx 的 loadAnimPageAsync 跳过（行 141/210 守卫），导致新卷动图页不加载 → 永久白屏。
            //   触发条件：用户拒绝 §34.5 动图专属视图（choice==4）+ PagerReaderView 自己跑 fallback 动图 +
            //   切到另一个含动图页的卷。修复后 inflightAnimLoads 清空，新卷动图 idx 不再被旧 idx 守卫跳过。
            //   ★ 边界条件（known limitation）：loadScope.cancel() 不会等已飞到 main 分支的旧协程取消完，
            //     理论上旧协程仍可能在 clear 后 setAnimDrawable 塞错 holder——根治需要 generation token。
            //     但此 race 在修复前就同样存在，本改动不引入新 race。
            inflightAnimLoads.clear()
            inflightLoads.clear() // ★ 白屏工单任务3：切章清加载坑（对齐 inflightAnimLoads 现有处理）
            scannedDims.clear() // ★ 渐进尺寸随章换身份，残留会把新章表按旧章尺寸排
            synchronized(animRawCache) { animRawCache.clear() }
            viewPager.setPageTransformer(NoOpTransformer())
            pagerAdapter = PagerAdapter()
            setupAnchoring = true
            viewPager.adapter = pagerAdapter
            viewPager.setCurrentItem(positionOf(startPage).coerceIn(0, pagerAdapter?.itemCount?.minus(1) ?: 0), false)
            viewPager.post { setupAnchoring = false }
            if (flipAnimationEnabled && !verticalPager) {
                viewPager.postDelayed({ viewPager.setPageTransformer(FlipPageTransformer()) }, 300)
            }
        } else if (rtlChanged) {
            // 仅RTL变化：保留缓存，只更新位置映射
            this.currentPage = startPage
            val pos = positionOf(startPage)
            viewPager.setCurrentItem(pos.coerceIn(0, pagerAdapter?.itemCount?.minus(1) ?: 0), false)
        } else {
            this.currentPage = startPage
            val pos = positionOf(startPage)
            viewPager.setCurrentItem(pos.coerceIn(0, pagerAdapter?.itemCount?.minus(1) ?: 0), false)
        }
        // ★ v1.13 C-1：无条件补载（loadPageAsync 自带 cache-miss 幂等守卫，代价近零）——
        //   双保险：即使未来某路径 release 未清身份字段，也不会再出现"轻量分支零补载"死局
        preloadAround(startPage)

    }

    fun setNavigationMode(mode: Int) {
        applyNavigation()
    }

    fun setCenterGapType(type: Int) { applyLivePrefs(gapType = type) }

    /** 侧边留白实时生效：margin 在 onCreateViewHolder 定死，变化需重建条目（var 属性 setter 冲突故改名 apply） */
    fun applyImageMarginH(marginDp: Int) {
        applyLivePrefs(marginDp = marginDp)
    }

    /**
     * ★ 四项画面参数合并应用：统一 changed 检测后至多一次 notifyDataSetChanged——
     *   独立 setter 在进书 Live-sync 连发时会触发 2-3 次全量重绑（每次可见 spread 全部
     *   重新 mergeBitmaps 全屏分配，实测 30-180MB 瞬时垃圾）。单次重绑消除峰值。
     */
    fun applyLivePrefs(marginDp: Int? = null, gapType: Int? = null, mode: Int? = null, zoom: Boolean? = null, zoomStart: Int? = null) {
        var changed = false
        if (marginDp != null && imageMarginH != marginDp) { imageMarginH = marginDp; changed = true }
        if (gapType != null && centerGapType != gapType) { centerGapType = gapType; changed = true }
        if (mode != null && scaleMode != mode) { scaleMode = mode; changed = true }
        if (zoom != null && landscapeZoomEnabled != zoom) { landscapeZoomEnabled = zoom; changed = true }
        if (zoomStart != null && this.zoomStart != zoomStart) { this.zoomStart = zoomStart; changed = true }
        if (changed) {
            // ★ 显示配置代数（2026-10-06 乱闪根修刀3）：同图短路凭它区分"重绑但什么都不用做"
            //   与"重绑且需重设基准"——位图与配置都没变时同图重绑零动作，不再无脑 applyScale
            //   把 landscapeZoom 已放大的画面打回 contain 再等 500ms 弹回（可见弹跳）
            displayCfgVer++
            pagerAdapter?.notifyDataSetChanged()
        }
    }

    fun setLandscapeZoom(enabled: Boolean) { applyLivePrefs(zoom = enabled) }

    /** ★ 双击放大开关：新 holder 由 createSsiv/创建路径带入，存活 holder 就地同步（免重绑即时生效）；
     *  2026-10-05 去 early-return：即便字段未变也强制遍历存活 holder（治"改了没反应"类时序残留） */
    fun setDoubleTapZoom(enabled: Boolean) {
        doubleTapZoom = enabled
        try {
            val rv = viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return
            for (i in 0 until rv.childCount) {
                (rv.getChildViewHolder(rv.getChildAt(i)) as? PagerViewHolder)?.ssiv?.let { s ->
                    s.doubleTapZoom = enabled
                    s.isQuickScaleEnabled = enabled
                }
            }
        } catch (_: Exception) {}
    }

    /** ★ 滑动兜底统一接线（PagerSSIV.pagerSwipeFallback）：仅在 VP2 拦截未接管的异常
     *  设备上触发；IDLE 守卫防与原生滚动打架。创建路径与 bind 兜底共用（setDoubleTapZoom
     *  式就地同步会覆盖 lambda，抽方法保证一致）。 */
    private fun installSwipeFallback(s: PagerSSIV) {
        s.pagerSwipeFallback = { dir ->
            try {
                val count = pagerAdapter?.itemCount ?: 0
                if (count > 0 && viewPager.scrollState == ViewPager2.SCROLL_STATE_IDLE) {
                    val target = (viewPager.currentItem + dir).coerceIn(0, count - 1)
                    if (target != viewPager.currentItem) viewPager.setCurrentItem(target, true)
                }
            } catch (_: Exception) {}
        }
        // ★ v1.381 放大贴边接力：SSIV 手势起点即贴边时用 fake drag 自驱翻页（页面跟手，
        //  松手按位移/速度落页）。fakeDragBy(x) 内部 scrollBy(-x)（VP2 1.1.0 字节码核实），
        //  故喂 -delta 才是内容跟手；起拖仅真实拖动进行中会失败（SETTLING 也能起，内部自停滚动）。
        s.edgeDrag = object : PagerSSIV.EdgeDragController {
            override fun start(): Boolean {
                return try { viewPager.beginFakeDrag() } catch (_: Exception) { false }
            }
            override fun dragBy(delta: Float) {
                try { if (viewPager.isFakeDragging) viewPager.fakeDragBy(-delta) } catch (_: Exception) {}
            }
            override fun end() {
                try { if (viewPager.isFakeDragging) viewPager.endFakeDrag() } catch (_: Exception) {}
            }
        }
    }

    fun setScaleMode(mode: Int) { applyLivePrefs(mode = mode) }

    fun setZoomStart(start: Int) { applyLivePrefs(zoomStart = start) }

    fun setTileModeEnabled(enabled: Boolean) {
        if (this.tileModeEnabled == enabled) return
        this.tileModeEnabled = enabled
        // ★ 即时生效（2026-10-05 对齐"调整即时刷新"）：瓦片开关只影响加载路径，已解码位图
        //   留在缓存里会让当前窗口继续走旧路径——照 setTrimWhiteBorder 的无白屏窗口重载
        clearTileFileCache()
        val cur = currentPage
        (cur - 2..cur + 5).forEach {
            if (it in pageList.indices) { bitmapCache.remove(it); inflightLoads.remove(it); loadPageAsync(it) }
        }
    }


    fun setTrimWhiteBorder(trim: Boolean, threshold: Int = trimThreshold, whiteRatio: Float = trimWhiteRatio) {
        com.mangareader.viewer.ArchiveEngine.d("PagerView", "setTrim trim=$trim threshold=$threshold ratio=$whiteRatio prev=$trimWhiteBorder")

        if (trimWhiteBorder == trim && trimThreshold == threshold && trimWhiteRatio == whiteRatio) return
        this.trimWhiteBorder = trim
        this.trimThreshold = threshold
        this.trimWhiteRatio = whiteRatio
        clearTileFileCache()
        // ★ 修复白屏：不清除全部缓存+notifyDataSetChanged（会白屏），
        //   逐页 remove+loadPageAsync，旧 bitmap 在 SSIV 中保留显示直到新 bitmap 加载完成
        val cur = currentPage
        (cur-2..cur+5).forEach {
            if (it in pageList.indices) { bitmapCache.remove(it); inflightLoads.remove(it); loadPageAsync(it) }
        }
    }

    /** ★ 固定裁剪即时生效（仅本书功能）：同 setTrimWhiteBorder 的无白屏策略——瓦片缓存作废
     *  （裁剪与条漫分割瓦片互斥）、当前±窗口逐页重载，旧图保持显示直到新图就绪。
     *  滑条拖动逐次调用安全：值未变时直接返回。 */
    fun setFixedCrop(l: Float, t: Float, r: Float, b: Float) {
        if (cropL == l && cropT == t && cropR == r && cropB == b) return
        cropL = l; cropT = t; cropR = r; cropB = b
        clearTileFileCache()
        val cur = currentPage
        (cur - 2..cur + 5).forEach {
            if (it in pageList.indices) { bitmapCache.remove(it); inflightLoads.remove(it); loadPageAsync(it) }
        }
    }

    fun setPanNavigation(pan: Boolean) {
        this.panNavigation = pan
    }

    fun setDoublePageReverse(reverse: Boolean) {
        this.doublePageReverseEnabled = reverse
        pagerAdapter?.notifyDataSetChanged()
    }

    fun setDoublePageSplit(split: Boolean) {
        if (this.doublePageSplitEnabled == split) return
        this.doublePageSplitEnabled = split
        resetSplitHalf()
        splitActivePositions.clear()
        pagerAdapter?.notifyDataSetChanged()
    }

    fun setShiftDoublePage(enabled: Boolean) {
        if (shiftDoublePage == enabled) return
        shiftDoublePage = enabled
        // ★ 同 setDoublePageMode：保留位图缓存，切换零重解码
        rebuildSpreadTable()
        resetSplitHalf()
        splitActivePositions.clear()
        pagerAdapter?.notifyDataSetChanged()
        val newPos = positionOf(currentPage)
        val finalPos = newPos.coerceIn(0, pagerAdapter?.itemCount?.minus(1) ?: 0)
        viewPager.post { viewPager.setCurrentItem(finalPos, false) }
    }

    /** ★ 双页合并起始页（2026-10-06，"跳过首页"泛化）：第 N 页起两两合并，前 N-1 页单独显示。
     *  仅 shiftDoublePage（"双页+起始页"档）生效；2=旧跳过首页行为。1..10，1 等价纯双页不在此档。 */
    var doublePageStart: Int = 2
        private set

    fun setDoublePageStart(start: Int) {
        val v = start.coerceIn(2, 10)
        if (doublePageStart == v) return
        doublePageStart = v
        rebuildSpreadTable()
        resetSplitHalf()
        splitActivePositions.clear()
        pagerAdapter?.notifyDataSetChanged()
        val newPos = positionOf(currentPage)
        val finalPos = newPos.coerceIn(0, pagerAdapter?.itemCount?.minus(1) ?: 0)
        viewPager.post { viewPager.setCurrentItem(finalPos, false) }
    }

    /** ★ 单本单页豁免（2026-10-06）：当前章不参与双页合并的物理页集合（用户"不能影响别人"——
     *  键按章隔离，本视图只持有当前章的集合并写入跨页表；DB 读写都在 Reader.kt） */
    var soloPages: Set<Int> = emptySet()
        private set

    /** 豁免集合变化（目录面板长按切换/全重置）：表映射变了才重绑锚定，实时生效 */
    fun setSoloPages(pages: Set<Int>) {
        val fresh = pages.filter { it in pageList.indices }.toSet()
        if (soloPages == fresh) return
        val old = spreadTable
        soloPages = fresh
        rebuildSpreadTable()
        if (!spreadTable.sameMapping(old)) rebindForNewTable()
    }

    /** ★ 远程书渐进尺寸收集（2026-10-06 用户工单"不同尺寸匹配到一起"）：远程书被
     *  ArchiveEngine requireNotRemote 拒收、Reader 扫描跳过 smb:/webdav: → 跨页表恒朴素
     *  盲配，横宽跨页图被硬拼半屏。解法：解码时顺带记录原始尺寸（裁剪/白边前，零额外
     *  流量），节流重建跨页表——翻到哪配哪；PageSizeCacheStore 不含远程，渐进表随章存活 */
    private val scannedDims = java.util.concurrent.ConcurrentHashMap<Int, IntArray>()
    private var scannedDimsFlushAt = 0L
    private var scannedDirty = false

    private fun observePageDim(idx: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        scannedDims[idx]?.let { if (it[0] == w && it[1] == h) return }
        scannedDims[idx] = intArrayOf(w, h)
        val now = android.os.SystemClock.uptimeMillis()
        if (now - scannedDimsFlushAt < 1500L) return
        flushScannedDims()
    }

    /** 渐进尺寸落表（节流出口+IDLE 补做共用）：表变了才重排锚定。
     *  ★ 冻结当前位（2026-10-06 乱闪根修）：尺寸渐进到达引发的重新配对不得扰动可见构图 */
    private fun flushScannedDims() {
        if (scannedDims.isEmpty()) return
        if (isScrolling) { scannedDirty = true; return }
        scannedDirty = false
        scannedDimsFlushAt = android.os.SystemClock.uptimeMillis()
        val old = spreadTable
        rebuildSpreadTable(freezeCurrent = true)
        if (!spreadTable.sameMapping(old)) rebindForNewTable()
    }

    private fun scannedDimsArray(): IntArray? {
        if (scannedDims.isEmpty()) return null
        val out = IntArray(pageList.size * 2)
        for ((idx, wh) in scannedDims) {
            if (idx * 2 + 1 < out.size) { out[idx * 2] = wh[0]; out[idx * 2 + 1] = wh[1] }
        }
        return out
    }

    /** ★ 跨页表重算（唯一构建点）：单页=单位表每页一位；双页才启用起始页豁免/智能配对/页豁免。
     *  dims 优先 Reader 全量扫描（本地书）；远程书回退视图渐进收集
     *  ★ freezeCurrent（2026-10-06 乱闪根修）：渐进尺寸引发的重建冻结"当前位及之前"——
     *  试探合并→伙伴解码→拆开的翻转只发生在屏外位，可见构图绝不中途改变。
     *  用户主动动作（模式切换/页豁免/智能开关）不冻结——那是用户要求的重排 */
    private fun rebuildSpreadTable(freezeCurrent: Boolean = false) {
        val prev = spreadTable
        var freezeThrough = -1
        if (freezeCurrent && prev != null && doublePageMode && pageList.isNotEmpty()) {
            val cur = currentPage.coerceIn(0, pageList.size - 1)
            if (prev.containsPage(cur)) {
                val ps = prev.pageToSpread(cur)
                freezeThrough = maxOf(prev.firstPage(ps), prev.secondPage(ps))
            }
        }
        spreadTable = SpreadTable.build(
            pageList.size,
            double = doublePageMode,
            startOffset = if (doublePageMode && shiftDoublePage && pageList.size > 1) doublePageStart - 1 else 0,
            smart = doublePageMode && smartMergeEnabled,
            dims = pageDims?.takeIf { it.size >= pageList.size * 2 } ?: scannedDimsArray(),
            soloPages = if (doublePageMode) soloPages else emptySet(),
            prev = if (freezeThrough >= 0) prev else null,
            freezeThroughPage = freezeThrough,
        )
    }

    /** 表变化后的统一落地：重绑 + 立即 post 锚定当前页（notify 后 VP2 会把旧位置弹回，
     *  与 setDoublePageModeAndShift 同款校正；layoutSwitchPending 抑制脏章末回调）。
     *  ★ 翻页中的闪烁由 setImage/setImageSplit 同图短路根治（重绑不再重置 SSIV）——
     *  曾试过"翻页中延迟重排"，但挂旗路径在 notify 之前 return 会让表与 RV 的 itemCount
     *  短暂不一致（inconsistency 崩溃风险），二轮审查回退，闪烁只治显示层 */
    private fun rebindForNewTable() {
        layoutSwitchPending = true
        pagerAdapter?.notifyDataSetChanged()
        val count = pagerAdapter?.itemCount ?: 0
        val finalPos = if (count > 0) positionOf(currentPage).coerceIn(0, count - 1) else 0
        viewPager.post {
            viewPager.setCurrentItem(finalPos, false)
            viewPager.post { layoutSwitchPending = false }
        }
    }

    /** ★ 尺寸表回填（2026-10-05 智能配对）：Reader.kt 后台扫描页尺寸后推送。
     *  映射未变（常见：全竖版书扫描确认既有朴素配对）零扰动；变了才重绑重定位。
     *  长度与当前页表不符（切章窗口）直接不采纳，等下一次扫描。
     *  ★ 后台事件同样冻结当前位（2026-10-06）：扫描落表不得扰动可见构图 */
    fun setPageDims(dims: IntArray?) {
        val valid = dims?.takeIf { pageList.isEmpty() || it.size >= pageList.size * 2 }
        if (pageDims != null && valid != null && pageDims.contentEquals(valid)) return
        pageDims = valid
        if (!doublePageMode) return
        val old = spreadTable
        rebuildSpreadTable(freezeCurrent = true)
        if (!spreadTable.sameMapping(old)) rebindForNewTable()
    }

    /** ★ 智能配对开关（config.doubleMergeSmart）：即时重排跨页表 */
    fun setSmartMerge(enabled: Boolean) {
        if (smartMergeEnabled == enabled) return
        smartMergeEnabled = enabled
        if (!doublePageMode) return
        val old = spreadTable
        rebuildSpreadTable()
        if (!spreadTable.sameMapping(old)) rebindForNewTable()
    }

    /** ★ P0-2 模式切换位图交接：把活着的位图从缓存里"取走"（移除后 release() 不再回收它们），
     *  交给新视图 adopBitmaps，切换零重解码（治"切换瞬间当前±1 页白屏"）。 */
    override fun drainLiveBitmaps(): Map<Int, Bitmap> {
        val out = LinkedHashMap<Int, Bitmap>()
        for (k in bitmapCache.snapshot().keys) {
            val v = bitmapCache.remove(k) ?: continue
            if (!v.isRecycled) out[k] = v
        }
        Log.d("P02", "drain size=${out.size} keys=${out.keys}")
        return out
    }

    /** ★ P0-2：接管旧视图移交的位图（首次绑定即命中，不再走解码回传链） */
    override fun adoptBitmaps(map: Map<Int, Bitmap>) {
        Log.d("P02", "adopt size=${map.size} keys=${map.keys}")
        for ((k, v) in map) if (!v.isRecycled) bitmapCache.put(k, v)
    }

    /** 注入放大后的 bitmap 到缓存并刷新当前页显示 */
    fun injectUpscaledBitmap(pageIndex: Int, bitmap: Bitmap) {
        // 不主动回收旧bitmap，让GC自然处理（避免ViewHolder还在使用时被回收导致黑屏）
        // ★ 超分结果异步回填：SR 源图未贴纹理，注入缓存前补贴
        bitmapCache.put(pageIndex, com.mangareader.filter.PaperPageTexture.apply(bitmap))
        val pos = positionOf(pageIndex)
        android.util.Log.i("PagerView", "srInject p=$pageIndex pos=$pos bmp=${bitmap.width}x${bitmap.height}")
        if (pos >= 0 && pos < pageList.size) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                // ★ 不调 holder.clear()：clear 会重置 SSIV (resetScaleAndCenter + visibility=GONE)，
                //   随后 notifyItemChanged → setImage 时 SSIV 需异步重新初始化 decoder，
                //   初始化期间无内容 = 白屏。直接 notifyItemChanged 让 setImage 从旧图无缝切到新图，
                //   SSIV 保持旧图显示直到新图 onReady。heldBitmap/动图状态由 setImage 内部覆盖。
                val rv = viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView
                val holder = rv?.findViewHolderForAdapterPosition(pos) as? PagerViewHolder
                if (holder == null) android.util.Log.d("PagerView", "srInject holder null (offscreen) p=$pageIndex")
                pagerAdapter?.notifyItemChanged(pos, "loaded")
            }
        }
    }

    /** ★ v1.13：当前页是否已出图（遮罩撤除信号） */
    override fun isCurrentPageReady(): Boolean {
        val rv = viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return false
        val vh = rv.findViewHolderForAdapterPosition(viewPager.currentItem) as? PagerViewHolder ?: return false
        vh.ssiv?.let { s -> if (s.visibility == android.view.View.VISIBLE && s.hasImage()) return true }
        return vh.iv?.drawable != null
    }
    fun setPageLayout(layout: Int) {
        this.pageLayoutMode = layout
    }

    fun setDoublePageMode(enabled: Boolean) {
        if (doublePageMode == enabled) return
        doublePageMode = enabled
        // ★ 2026-09-30：不清缓存——单页位图仍是有效解码结果，合并路径直接复用
        //   （原 evictAll 把整本已解码页扔光，切双页要重解两页 ≈0.5s「要加载一下」）
        rebuildSpreadTable()
        resetSplitHalf()
        splitActivePositions.clear()
        pagerAdapter?.notifyDataSetChanged()
        val newPos = positionOf(currentPage)
        val finalPos = newPos.coerceIn(0, pagerAdapter?.itemCount?.minus(1) ?: 0)
        viewPager.post { viewPager.setCurrentItem(finalPos, false) }
    }

    // ★ N1 修复：双页模式切换期间位置重映射会产生瞬态 onPageSelected，曾误触章末处理
    //   （无缝静默切章与模式切换并发、过渡页在切换中弹出）——切换窗口内抑制章末回调
    private var layoutSwitchPending = false

    fun setDoublePageModeAndShift(doubleEnabled: Boolean, shiftEnabled: Boolean) {
        if (doublePageMode == doubleEnabled && shiftDoublePage == shiftEnabled) return
        val savedPage = currentPage
        layoutSwitchPending = true
        doublePageMode = doubleEnabled
        shiftDoublePage = shiftEnabled
        // ★ 同 setDoublePageMode：保留位图缓存，切换零重解码
        rebuildSpreadTable()
        resetSplitHalf()
        splitActivePositions.clear()
        pagerAdapter?.notifyDataSetChanged()
        val newPos = positionOf(savedPage)
        val finalPos = newPos.coerceIn(0, pagerAdapter?.itemCount?.minus(1) ?: 0)
        // ★ 2026-10-05（日志实锤 22:39:36.600）：notify 后 ViewPager2 会把旧位置(506)弹回 0
        //   ——RTL 双页映射下 pos 0=末页(521)，100ms 等待窗口内用户看见末页闪现、
        //   onPageSelected(521) 还把进度瞬写成 cur=末页 done=true（若此刻进程死掉书即被误标已读）。
        //   与 938 行姊妹函数同款立即 post 校正：校正在下一次遍历前入队，闪现与脏写窗口塌缩。
        viewPager.post {
            viewPager.setCurrentItem(finalPos, false)
            // 目标落位事件处理完再放行章末回调
            viewPager.post { layoutSwitchPending = false }
        }
    }

    override fun goToPage(page: Int) {
        goToPage(page, smooth = false)
    }

    /**
     * ★ 修复"点击翻页硬切、翻页动画不显示"（用户反馈 2026-09-06）：
     *   点击翻页此前恒为 setCurrentItem(pos, false)（瞬移），3D 翻转 PageTransformer 只在
     *   手指滑动或 smoothScroll 时生效——点击翻页永远硬切。现点击翻页（nextPage/prevPage）
     *   在翻页动画开启时走 smooth scroll，动画经 transformer 呈现；跳章/进度恢复仍为瞬移。
     */
    fun goToPage(page: Int, smooth: Boolean) {
        if (page < 0 || page >= pageList.size) return
        val distance = kotlin.math.abs(page - currentPage)
        if (distance > 5) {
            loadScope.coroutineContext.cancelChildren()
            inflightLoads.clear()
            inflightAnimLoads.clear()
        }
        val pos = positionOf(page)
        val animate = smooth && flipAnimationEnabled && !verticalPager
        viewPager.setCurrentItem(pos.coerceIn(0, pagerAdapter?.itemCount?.minus(1) ?: 0), animate)
        preloadAround(page)
    }

    /** ★ T8：物理页 → adapter 位置。RTL 唯一镜像点：全工程位置镜像只在这里发生一次；
     *  LTR/单页下退化为原公式（itemCount-1-s 在非 RTL 时就是 s）。 */
    private fun positionOf(page: Int): Int {
        val s = spreadTable.pageToSpread(page)
        return if (isRtl) (spreadTable.slotCount - 1 - s) else s
    }

    /** ★ 进度条拖动预解码：把目标页及其前后 spread 预热进缓存（不改变当前位置） */
    fun preloadPreview(page: Int) {
        if (page < 0 || page >= pageList.size) return
        preloadAround(page)
    }

    /** 跨页/页变更即重置半页交替（半页状态只在同一跨页内有效） */
    private fun resetSplitHalf() {
        splitHalfRight = false
        pendingLandingSplitHalfRight = false
    }

    private fun splitActiveNow(): Boolean {
        if (!doublePageSplitEnabled) return false
        // ★ 位图就绪以位图宽高为准（即时准确）；未就绪（快翻/刚跳页尚未重绑）用绑定
        //   登记兜底——原实现只看登记，进书后立刻翻页时登记缺失会绕过半页交替直跳，
        //   右半被整页跳过（"第二次翻页不显示右半"的时序根因）
        val bmp = bitmapCache[currentPage]
        if (bmp != null && !bmp.isRecycled) return bmp.width > bmp.height
        return positionOf(currentPage).let { it in splitActivePositions }
    }

    /** 当前半页位（仅拆分跨页激活时有语义）：true=正显示右半。进度保存采集用（恢复时精确落回看过的那半页） */
    val currentHalfRight: Boolean get() = splitHalfRight

    private fun rebindCurrent() {
        val pos = positionOf(currentPage)
        if (pos >= 0) pagerAdapter?.notifyItemChanged(pos)
    }

    fun nextPage() {
        // navigateToPan: 缩放时先平移到边缘再翻页
        if (panNavigation && tryPanIfZoomed(forward = !isRtl)) return
        // ★ 拆分跨页（2026-10-04 单一决策重写）：先翻到同文件右半（位置不动），再进下一跨页。
        //   跨页目标先算、可翻才动状态——到底/到顶失败不再残留半页标记
        //   （旧逻辑在边界失败后 splitHalfRight 残留，下一次翻页跳过右半）
        if (splitActiveNow() && !splitHalfRight) {
            splitHalfRight = true
            rebindCurrent()
            return
        }
        if (doublePageMode) {
            // ★ B5（2026-10-04）：状态清移入可翻分支——到末跨页翻不动时不再清 splitHalfRight，
            //   画面（右半）与状态保持一致；旧版边界失败即清，此时退出会把右半记成左半
            // ★ 2026-10-05 跨页表：目标=下一跨页阅读序首页（首张单页位移/智能 solo 跳位全在表内）
            val next = spreadTable.pageToSpread(currentPage) + 1
            val target = if (next < spreadTable.slotCount) spreadTable.firstPage(next) else -1
            if (target >= 0) {
                splitHalfRight = false
                pendingLandingSplitHalfRight = false  // 前进落地恒左半
                goToPage(target, smooth = true)
            }
        } else {
            if (currentPage < pageList.size - 1) {
                splitHalfRight = false
                pendingLandingSplitHalfRight = false
                goToPage(currentPage + 1, smooth = true)
            }
        }
    }
    fun prevPage() {
        // navigateToPan: 缩放时先平移到边缘再翻页
        if (panNavigation && tryPanIfZoomed(forward = isRtl)) return
        // ★ 拆分跨页回翻（2026-10-04 单一决策重写）：在右半则切回左半（位置不动）；
        //   在左半则回退进上一跨页、从其右半继续阅读序——半页位经 pendingLanding 在
        //   onPageSelected 落地时生效（旧逻辑落地即被 reset 清掉，回退恒显示左半）。
        //   到顶不可翻时不设 pending、不动 splitHalfRight（旧逻辑残留 true 导致之后
        //   前进跳过右半——"第二次翻页不显示右边那页"的根因）
        if (splitActiveNow() && splitHalfRight) {
            splitHalfRight = false
            rebindCurrent()
            return
        }
        if (doublePageMode) {
            // ★ 2026-10-05 跨页表：目标=上一跨页阅读序首页（在首跨页则不可翻）
            val curSlot = spreadTable.pageToSpread(currentPage)
            val target = if (curSlot > 0) spreadTable.firstPage(curSlot - 1) else -1
            if (target >= 0) {
                if (splitActiveNow()) pendingLandingSplitHalfRight = true
                goToPage(target, smooth = true)
            }
        } else {
            if (currentPage > 0) {
                if (splitActiveNow()) pendingLandingSplitHalfRight = true
                goToPage(currentPage - 1, smooth = true)
            }
        }
    }

    /**
     * navigateToPan: 如果当前页SSIV已缩放且还有可平移空间，执行平移动画并返回true。
     * 返回false表示无法平移，应正常翻页。
     */
    private fun tryPanIfZoomed(forward: Boolean): Boolean {
        return try {
            val rv = viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return false
            val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return false
            val pos = lm.findFirstVisibleItemPosition()
            if (pos == -1 || pos == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return false
            val holder = rv.findViewHolderForAdapterPosition(pos) as? PagerViewHolder ?: return false
            val ssiv = holder.ssiv ?: return false
            // ★ 裁切页放行：split 视口缩放恒 > minScale（见 isSplitPage 注释），不是用户放大
            if (holder.isSplitPage) return false
            // ★ 视口基准判定（2026-10-02）：铺满屏幕贴宽/拆分视口的初始比例不是"用户放大"，
            //   铺满模式下点击翻页不得被 pan 导航劫持成图内平移
            val base = (ssiv as? PagerSSIV)?.viewportBaseScale?.takeIf { it > 0f } ?: ssiv.minScale
            if (ssiv.visibility != View.VISIBLE || ssiv.scale <= base + 0.01f) return false
            val remaining = android.graphics.RectF()
            ssiv.getPanRemaining(remaining)
            val canPan = if (forward) remaining.right > 1f else remaining.left > 1f
            if (canPan) {
                val targetX = ssiv.center!!.x + if (forward) ssiv.width.toFloat() / ssiv.scale else -ssiv.width.toFloat() / ssiv.scale
                val target = PointF(targetX, ssiv.center!!.y)
                ssiv.animateCenter(target)
                    ?.withDuration(250)
                    ?.withInterruptible(true)
                    ?.start()
                true
            } else false
        } catch (_: Exception) { false }
    }

    private suspend fun obtainTileFile(idx: Int, supply: RemotePageSupply?): java.io.File? {
        val meta = pageList[idx]
        if (supply == null) {
            val realFile = withContext(Dispatchers.IO) { engine?.getRealFileForPage(treePath, meta) }
            if (realFile != null && realFile.exists() && realFile.length() > 0) return realFile
            val raw = withContext(Dispatchers.IO) { runCatching { engine?.loadPageRaw(meta, treePath) }.getOrNull() }
            if (raw != null) return writeTileTempFile(idx, raw)
        } else {
            // ★ 远程拆分接入：展开后流位≠原始页，按元数据还原（未展开书 originalPageIdx==idx 等值）
            val rawIdx = meta.originalPageIdx
            val raw = withContext(Dispatchers.IO) { runCatching { supply.loadPageRaw(rawIdx) }.getOrNull() }
            if (raw != null) return writeTileTempFile(idx, raw)
        }
        return null
    }

    private fun writeTileTempFile(idx: Int, raw: ByteArray): java.io.File? {
        return try {
            val file = java.io.File(context.cacheDir, "tile_${System.identityHashCode(this)}_${idx}.tmp")
            java.io.FileOutputStream(file).use { it.write(raw) }
            synchronized(tileTempFiles) { tileTempFiles.add(file) }
            file
        } catch (_: Exception) { null }
    }

    private fun loadPageAsync(idx: Int, isPreload: Boolean = false) {
        if (idx < 0 || idx >= pageList.size) return
        val cached = bitmapCache[idx]
        if (cached != null && !cached.isRecycled) {
            try {
                val srHit = com.mangareader.sr.SrPipeline.attachOrScheduleFast(idx, cached)
                if (srHit != null && !srHit.isRecycled && srHit !== cached) {
                    bitmapCache.put(idx, com.mangareader.filter.PaperPageTexture.apply(srHit))
                    val pos = if (isRtl) pageList.size - 1 - idx else idx
                    if (pos >= 0 && pos < pageList.size) pagerAdapter?.notifyItemChanged(pos, "loaded")
                }
            } catch (_: Exception) {}
            return
        }
        // ★ LV119：动图页已在 pageAnimDrawables 中，跳过静图加载
        if (pageAnimDrawables[idx] != null) return
        val meta = pageList[idx]
        val nowTs = android.os.SystemClock.uptimeMillis()
        val pitTs = inflightLoads[idx]
        if (pitTs != null && nowTs - pitTs < 5000L) {
            // ★ 2026-09-09 v3 调度：只有当前页请求才切焦点——预载页（preloadAround ±5 / onBind ±2）
            //   绝不抢焦点，否则 preloadAround 循环后焦点漂移到最后一页，当前页反而被冷落
            //   ★ 拆分接入：焦点按原始页号提升（展开后流位≠原始页）
            if (remotePageSupply != null && idx == currentPage) {
                remotePageSupply?.onFocus(pageList.getOrNull(idx)?.originalPageIdx ?: idx)
            }
            ArchiveEngine.d("Dedup", "drop idx=$idx age=${nowTs - pitTs}ms")
            return
        }
        inflightLoads[idx] = nowTs
        if (remotePageSupply != null && idx == currentPage) {
            remotePageSupply?.onFocus(pageList.getOrNull(idx)?.originalPageIdx ?: idx)
        }
        val job = loadScope.launch {

            var acquired = false
            if (isPreload) { preloadSemaphore.acquire(); acquired = true }
            try {
            // ★ P4（2026-10-04）：删除 ensureNativeArchive()——旧代码每次开书 mmap 全文件+完整
            //   解析中央目录，打开的 nativeArchive 字段全程无任何读取方（纯簿记），纯浪费一次
            //   全包索引且占 native 句柄表名额

            val supply = remotePageSupply
            if (tileModeEnabled && !trimWhiteBorder && !fixedCropActive && !doublePageMode &&
                pageList[idx].splitSide == 0 && pageList[idx].segmentIndex <= 0 &&
                pageAnimFlags.none { it.pageIdx == idx && it.kind != AnimKind.NONE }) {
                val tileFile = obtainTileFile(idx, supply)
                if (tileFile != null) {
                    com.mangareader.viewer.ArchiveEngine.d("PagerView", "tileHit idx=$idx tileOn=$tileModeEnabled trimOn=$trimWhiteBorder")

                    tileFileCache[idx] = tileFile
                    withContext(Dispatchers.Main) {
                        val adapterPos = positionOf(idx)
                        if (adapterPos >= 0 && adapterPos < (pagerAdapter?.itemCount ?: 0)) pagerAdapter?.notifyItemChanged(adapterPos, "loaded")
                    }
                    return@launch
                }
            }
            if (supply == null) {
                // ===== 本地路径（2026-09-05 回退 v1.2 方案：用户裁定 + OOM 修复）=====
                //   上个会话把本地翻页改成 loadPageRaw 读原图字节 → Coil 再解码（字节+Bitmap 双份内存），
                //   512MB 堆 OOM 闪退（log_export_0905_214705 实锤：loadDirPageRaw 12~17MB 分配失败）。
                //   v1.2 静图走 engine.loadPage（原生下采样 Bitmap）；动图走 peek + APNG4Android Drawable。
                val meta = pageList[idx]
                val knownKind = pageAnimFlags.firstOrNull { it.pageIdx == idx }?.kind
                val kind: AnimKind = if (knownKind != null) knownKind else withContext(Dispatchers.IO) {
                    runCatching { engine?.peekAnimKindFast(treePath, meta) }.getOrNull() ?: AnimKind.NONE
                }
                if (kind != AnimKind.NONE) {
                    // ★ mmap 优先：File 可用时跳过 readBytes，12MB 落 native memory 不占 Java 堆
                    val realFile = withContext(Dispatchers.IO) { engine?.getRealFileForPage(treePath, meta) }
                    val drawable: Drawable? = if (realFile != null) {
                        withContext(Dispatchers.IO) { runCatching { makeAnimDrawable(ByteArray(0), kind, realFile) }.getOrNull() }
                    } else {
                        val animRaw = withContext(Dispatchers.IO) {
                            runCatching { engine?.loadPageRaw(meta, treePath) }.getOrNull()
                        }
                        if (animRaw != null) {
                            synchronized(animRawCache) {
                                animRawCache[idx] = animRaw
                                while (animRawCache.size > 6) {
                                    val it = animRawCache.entries.iterator()
                                    if (it.hasNext()) { it.next(); it.remove() } else break
                                }
                            }
                        }
                        animRaw?.let { r ->
                            withContext(Dispatchers.IO) { runCatching { makeAnimDrawable(r, kind) }.getOrNull() }
                        }
                    }
                    if (drawable != null) {
                        pageAnimDrawables[idx] = drawable
                        if (pageAnimFlags.none { it.pageIdx == idx }) {
                            pageAnimFlags = pageAnimFlags + AnimFlag(idx, kind)
                            animatedCount++
                        }
                        withContext(Dispatchers.Main) {
                            val adapterPos = positionOf(idx)
                            if (adapterPos >= 0 && adapterPos < (pagerAdapter?.itemCount ?: 0)) pagerAdapter?.notifyItemChanged(adapterPos, "anim")
                        }
                        return@launch
                    }
                    // drawable 构造失败 → 落入下方静图路径（静态首帧兜底，v1.2 同款降级）
                }
                // ② 静图：engine.loadPage（原生下采样，防 OOM）
                var bmp = withContext(Dispatchers.IO) { engine?.loadPage(meta, treePath) }
                com.mangareader.viewer.ArchiveEngine.d("PagerView", "loaded idx=$idx bmp=${bmp?.width}x${bmp?.height} source=${meta.source} trimOn=$trimWhiteBorder tileOn=$tileModeEnabled")
                if (bmp == null || bmp.isRecycled) {
                    bitmapCache.remove(idx)
                    inflightLoads.remove(idx) // ★ 允许立即重试（失败/取消场景不留 5s 去重窗口）
                    return@launch
                }
                if (fixedCropActive && bmp.width > 10 && bmp.height > 10) {
                    try {
                        // 条漫分割段图（PDF/长图分段）：纵向切片，上下裁剪语义错 → 只裁左右
                        val segTall = meta.segmentTotal > 1
                        bmp = com.mangareader.viewer.ImageTrimmer.applyFixedCrop(bmp, cropL, if (segTall) 0f else cropT, cropR, if (segTall) 0f else cropB)
                    } catch (_: Exception) {}
                }
                if (trimWhiteBorder && bmp.width > 10 && bmp.height > 10) {
                    val w0 = bmp.width; val h0 = bmp.height
                    try {
                        bmp = com.mangareader.viewer.ImageTrimmer.ensurePixelReadable(bmp)
                        val trim = com.mangareader.viewer.ImageTrimmer.trimWhiteBorders(bmp, threshold = trimThreshold, whiteRatio = trimWhiteRatio, factor = 0.15f)
                        bmp = com.mangareader.viewer.ImageTrimmer.applyTrim(bmp, trim)
                        com.mangareader.viewer.ArchiveEngine.d("PagerView", "trimL idx=$idx ${w0}x${h0}->${bmp.width}x${bmp.height}")
                    } catch (_: Exception) {}
                }
                try {
                    val srHit = com.mangareader.sr.SrPipeline.attachOrSchedule(idx, bmp)
                    if (srHit != null && !srHit.isRecycled) {
                        bmp = srHit
                    } else {
                        com.mangareader.sr.SrPipeline.pollForResult(idx) { b -> injectUpscaledBitmap(idx, b) }
                    }
                } catch (_: Exception) {}
                // ★ 墨水屏灰阶抖动（本地路径）：SR 之后、入缓存之前
                if (einkDither && !bmp.isRecycled) {
                    try { bmp = bmp.applyEinkDither(einkDitherAlgorithm, einkDitherLevels, einkEndpointPassthrough) } catch (_: Exception) {}
                }
                // ★ 纸张纹理烘焙：管线末端、入缓存前（纸随页翻动，替代旧屏幕覆盖层）
                bmp = com.mangareader.filter.PaperPageTexture.apply(bmp)
                bitmapCache.put(idx, bmp)
                // ★ F 解码计时：慢书取证（epub/zip 解析慢）——请求→落缓存整链路 >300ms 记诊断
                inflightLoads[idx]?.let { t0 ->
                    val cost = android.os.SystemClock.uptimeMillis() - t0
                    if (cost > 300) com.mangareader.DiagTracer.mark(context, "pageDecode idx=$idx ${cost}ms")
                }
                withContext(Dispatchers.Main) {
                    val adapterPos = positionOf(idx)
                    if (adapterPos >= 0 && adapterPos < (pagerAdapter?.itemCount ?: 0)) pagerAdapter?.notifyItemChanged(adapterPos, "loaded")
                }
                return@launch
            }

            // ===== 远程（SMB/WebDAV）：原始字节 → Coil3 解码（动图自动识别），路径不变 =====
            //   ★ SMB/WebDAV 页面供给抽象：smb:/webdav: 路径被 ArchiveEngine 守卫拦截，严禁回退 engine
            //   ★ 远程拆分接入（2026-10-06）：展开后流位≠原始页——半页按元数据取原始页字节，
            //     解码后视图侧裁半（applySplitSide 同款 1.2 守卫+左右半规则）；动图页恒不拆，
            //     Coil 的 DrawableImage 分支不受影响
            val rawIdx = meta.originalPageIdx.coerceIn(0, (supply.pageCount - 1).coerceAtLeast(0))
            val raw = withContext(Dispatchers.IO) { supply.loadPageRaw(rawIdx) }
            if (raw == null) {
                bitmapCache.remove(idx)
                inflightLoads.remove(idx) // ★ 让位/失败允许立即重试（新焦点页接管后翻回可重载）
                return@launch
            }
            val loader = coil3.SingletonImageLoader.get(context)
            val result = withContext(Dispatchers.IO) {
                loader.execute(coil3.request.ImageRequest.Builder(context).data(raw).build())
            }
            val image = result.image
            if (image is coil3.DrawableImage) {
                // 动图：Coil3 decoders 自动识别 → DrawableImage
                val drawable = image.drawable
                synchronized(pageAnimDrawables) { pageAnimDrawables[idx] = drawable }
                if (pageAnimFlags.none { it.pageIdx == idx }) {
                    pageAnimFlags = pageAnimFlags + AnimFlag(idx, AnimKind.WEBP)
                    animatedCount++
                }
                withContext(Dispatchers.Main) {
                    val adapterPos = positionOf(idx)
                    if (adapterPos >= 0 && adapterPos < (pagerAdapter?.itemCount ?: 0)) pagerAdapter?.notifyItemChanged(adapterPos, "anim")
                }
            } else if (image is coil3.BitmapImage) {
                // 静图：提取 Bitmap → 超分 → bitmapCache → SSIV
                var bmp = image.bitmap
                // ★ 远程半页裁切（2026-10-06 拆分接入）：applySplitSide 同款——1.2 宽高比守卫，
                //   splitSide=1 左半 / 2 右半；源图不 recycle（与引擎实现一致，GC 兜底）
                if (bmp.width > 0 && meta.splitSide != 0 && bmp.width > bmp.height * 1.2f) {
                    try {
                        val halfWidth = bmp.width / 2
                        val startX = if (meta.splitSide == 1) 0 else halfWidth
                        bmp = android.graphics.Bitmap.createBitmap(bmp, startX, 0, halfWidth, bmp.height)
                    } catch (_: Exception) {}
                }
                com.mangareader.viewer.ArchiveEngine.d("PagerView", "loaded idx=$idx bmp=${bmp.width}x${bmp.height} source=${meta.source} trimOn=$trimWhiteBorder tileOn=$tileModeEnabled")
                // ★ 渐进尺寸收集：必须在 fixedCrop/裁白边之前（要的是原图尺寸）；
                //   段页/半页（宽图二分）的位图尺寸≠整页尺寸，写进表会把整页误判
                if (doublePageMode && pageList.getOrNull(idx)?.let { it.segmentIndex == 0 && it.splitSide == 0 } == true) {
                    observePageDim(idx, bmp.width, bmp.height)
                }
                if (fixedCropActive && bmp.width > 10 && bmp.height > 10) {
                    try {
                        val segTall = pageList[idx].segmentTotal > 1
                        bmp = com.mangareader.viewer.ImageTrimmer.applyFixedCrop(bmp, cropL, if (segTall) 0f else cropT, cropR, if (segTall) 0f else cropB)
                    } catch (_: Exception) {}
                }
                if (trimWhiteBorder && bmp.width > 10 && bmp.height > 10) {
                    val w0 = bmp.width; val h0 = bmp.height
                    try {
                        bmp = com.mangareader.viewer.ImageTrimmer.ensurePixelReadable(bmp)
                        val trim = com.mangareader.viewer.ImageTrimmer.trimWhiteBorders(bmp, threshold = trimThreshold, whiteRatio = trimWhiteRatio, factor = 0.15f)
                        bmp = com.mangareader.viewer.ImageTrimmer.applyTrim(bmp, trim)
                        com.mangareader.viewer.ArchiveEngine.d("PagerView", "trimR idx=$idx ${w0}x${h0}->${bmp.width}x${bmp.height}")
                    } catch (e: Exception) {
                        try { com.mangareader.DiagTracer.mark(context, "trimEx idx=$idx ${e.javaClass.simpleName}: ${e.message}") } catch (_: Exception) {}
                    }
                }
                try {
                    val srHit = com.mangareader.sr.SrPipeline.attachOrSchedule(idx, bmp)
                    if (srHit != null && !srHit.isRecycled) {
                        bmp = srHit
                    } else {
                        com.mangareader.sr.SrPipeline.pollForResult(idx) { b -> injectUpscaledBitmap(idx, b) }
                    }
                } catch (_: Exception) {}
                // ★ 墨水屏灰阶抖动（远程路径）：SR 之后、入缓存之前
                if (einkDither && !bmp.isRecycled) {
                    try { bmp = bmp.applyEinkDither(einkDitherAlgorithm, einkDitherLevels, einkEndpointPassthrough) } catch (_: Exception) {}
                }
                // ★ 纸张纹理烘焙：管线末端、入缓存前
                bmp = com.mangareader.filter.PaperPageTexture.apply(bmp)
                bitmapCache.put(idx, bmp)
                // ★ F 解码计时：慢书取证（epub/zip 解析慢）——请求→落缓存整链路 >300ms 记诊断
                inflightLoads[idx]?.let { t0 ->
                    val cost = android.os.SystemClock.uptimeMillis() - t0
                    if (cost > 300) com.mangareader.DiagTracer.mark(context, "pageDecode idx=$idx ${cost}ms")
                }
                withContext(Dispatchers.Main) {
                    val adapterPos = positionOf(idx)
                    if (adapterPos >= 0 && adapterPos < (pagerAdapter?.itemCount ?: 0)) pagerAdapter?.notifyItemChanged(adapterPos, "loaded")
                }
            }
            } finally {
                if (acquired) preloadSemaphore.release()
                if (inflightLoads[idx] == nowTs) inflightLoads.remove(idx)
                (kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job])?.let { loadJobs.remove(idx, it) }
            }
        }
        loadJobs[idx] = job
    }

    /** ★ 跳页调度（2026-09-09，对齐参考项目 prioritizeDecode/cancelStaleDownloads）：
     *  取消锚点 ±8 窗口外的 inflight 加载 job，释放 IO/下载资源给当前页；
     *  正常阅读窗口（preloadAround ±5 + 双页 spread ±3）不受影响。 */
    private fun prioritizeLoad(anchorIdx: Int) {
        val window = (anchorIdx - 8)..(anchorIdx + 8)
        loadJobs.entries.removeAll { (idx, job) ->
            if (!job.isActive) true
            else if (idx !in window) {
                job.cancel()
                inflightLoads.remove(idx)
                true
            } else false
        }
    }

    private fun preloadAround(centerIdx: Int) {
        if (isScrolling) return
        // ★ 2026-09-09 跳页调度：先取消窗口外 inflight（跳页 1→954 时释放远端下载资源给 954）
        prioritizeLoad(centerIdx)
        // ★ 2026-09-09 v3 调度：当前页立即成为 loader 焦点——用户需求"当前页绝对优先，
        //   加载出来后预载才继续，换页则焦点切换"。navigate(焦点页) 的预取窗口(sourceAhead)
        //   会自动带上 ±5 预载页；loader 内部 interactive slot 保留给焦点页。
        //   centerIdx 始终是用户所在页（onPageSelected/navigateToPage 传入），无条件切换安全。
        if (remotePageSupply != null) remotePageSupply?.onFocus(centerIdx)
        // 超分联动：报当前页；只有"整本"作用域下才前后预载
        try { com.mangareader.sr.SrPipeline.onPageChanged(centerIdx, pageList.size) } catch (_: Exception) {}
        if (doublePageMode) {
            // ★ 2026-10-05 跨页表：预载按表取页（旧 leftIdx±2/±3 假设两页对齐，
            //   首张单页位移与智能 solo 位全不成立）
            val curSlot = spreadTable.pageToSpread(centerIdx)
            loadPageAsync(spreadTable.firstPage(curSlot))
            val curSecond = spreadTable.secondPage(curSlot)
            if (curSecond >= 0) loadPageAsync(curSecond)
            // 相邻跨页（±1）：双页一次前进一跨页，±1 保证至少连续翻两次不重新解码。
            //   ★ 2026-10-05 卡顿工单收窄：曾拉到 ±2 跨页=一次预载 6 页（2057×3000 位图
            //   ≈290MB 峰值分配），进书/旋转/翻页即 GC 风暴，横屏高分页解码 p50 414ms
            //   叠加成"特别卡"。收窄到 ±1（4 页），快翻第三跨页才打空，实时解码补上。
            for (delta in intArrayOf(-1, 1)) {
                val s = curSlot + delta
                if (s < 0 || s >= spreadTable.slotCount) continue
                loadPageAsync(spreadTable.firstPage(s), isPreload = true)
                val sec = spreadTable.secondPage(s)
                if (sec >= 0) loadPageAsync(sec, isPreload = true)
            }
        } else {
            loadPageAsync(centerIdx)
            if (centerIdx + 1 < pageList.size) loadPageAsync(centerIdx + 1, isPreload = true)
            if (centerIdx - 1 >= 0) loadPageAsync(centerIdx - 1, isPreload = true)
        }
    }

    override fun release() {

        srEventJob?.cancel(); srEventJob = null
        // ★ §24 二代释放动图 Drawable（停 + 清 map），防 View 销毁后 decoder 跑漏内存
        pageAnimDrawables.values.forEach { try { if (it is Animatable && it.isRunning) it.stop() } catch (_: Throwable) {} }
        pageAnimDrawables.clear()
        currentPlayingAnimIdx = -1
        pageAnimFlags = emptyList()
        animatedCount = 0
        animFailedIdx.clear()

        loadScope.cancel()
        loadJobs.clear() // ★ 调度修复：release 时注册表同步清空
        inflightLoads.clear() // ★ 白屏工单任务3
        // ★ v102: 不 recycle（SSIV 可能仍持有引用，同 clearBitmapCacheRecycling）；evict 后 GC 自动回收
        bitmapCache.evictAll()
        clearTileFileCache()
        // ★ v1.13 C-1：身份字段随缓存一并作废——下次 setup 必命中 pagesChanged=true 走全量重建
        //   （重建 adapter + setCurrentItem + preloadAround 补载），根治模式切换白屏
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
        val keep = currentPage
        for ((k, _) in bitmapCache.snapshot()) {
            if (kotlin.math.abs(k - keep) > 2) {
                // ★ v102: 只 remove 不 recycle（SSIV 可能仍持有引用）；GC 兜底回收
                bitmapCache.remove(k)
            }
        }
        for ((k, f) in tileFileCache.toMap()) {
            if (kotlin.math.abs(k - keep) > 2) {
                tileFileCache.remove(k)
                if (synchronized(tileTempFiles) { tileTempFiles.remove(f) }) {
                    try { f.delete() } catch (_: Exception) {}
                }
            }
        }
        // ★ F3.4：低内存补收动图缓冲（pageAnimDrawables 已有 LRU 上限 7 + animRawCache 自估可上百 MB）。
        //   当前页动图会停帧，重绑自动重建恢复；CRITICAL 一下就把在途标记一并清了。
        try {
            synchronized(pageAnimDrawables) {
                pageAnimDrawables.values.forEach { d -> try { (d as? android.graphics.drawable.Animatable)?.stop() } catch (_: Throwable) {} }
                pageAnimDrawables.clear()
            }
            currentPlayingAnimIdx = -1
            synchronized(animRawCache) { animRawCache.clear() }
            inflightAnimLoads.clear()
        } catch (_: Throwable) {}
    }

    private fun mergeBitmaps(left: Bitmap, right: Bitmap, gapPx: Int = 0): Bitmap {
        val leftSw = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && left.config == Bitmap.Config.HARDWARE) left.copy(Bitmap.Config.ARGB_8888, false) else left
        val rightSw = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && right.config == Bitmap.Config.HARDWARE) right.copy(Bitmap.Config.ARGB_8888, false) else right
        val w = leftSw.width + rightSw.width + gapPx
        val h = maxOf(leftSw.height, rightSw.height)
        val merged = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(merged)
        // ★ 整屏幕：中缝/高度差留边随纸纹（页面各自已烘焙，只填空档不重复烘），关=纯平涂原样
        com.mangareader.filter.PaperPageTexture.fillMergeBackground(canvas, w, h, pageBgColor)
        canvas.drawBitmap(leftSw, 0f, (h - leftSw.height) / 2f, null)
        canvas.drawBitmap(rightSw, (leftSw.width + gapPx).toFloat(), (h - rightSw.height) / 2f, null)
        return merged
    }

    inner class PagerAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<PagerViewHolder>() {
        // ★ 2026-10-05：跨页表唯一出处（单页=单位表 slotCount==pageList.size，双页含 shift/智能 solo）
        override fun getItemCount() = spreadTable.slotCount
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PagerViewHolder {
            val container = android.widget.FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                com.mangareader.filter.PaperBgDrawable.apply(this, parent.context.applicationContext, pageBgColor, com.mangareader.filter.PaperPageTexture.overlayMode, com.mangareader.filter.PaperPageTexture.presetId, com.mangareader.filter.PaperPageTexture.concentration)
                val marginPx = (imageMarginH * parent.context.resources.displayMetrics.density).toInt()
                setPadding(marginPx, 0, marginPx, 0)
            }
            val iv = ImageView(parent.context).apply {
                id = android.R.id.content
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.VISIBLE
            }
            val ssiv = PagerSSIV(parent.context).apply {
                id = com.mangareader.R.id.ssiv_pager
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                isZoomEnabled = true
                isPanEnabled = true
                isQuickScaleEnabled = true
                setMaxScale(5f)
                setMaxTileSize(512)
                setMinimumTileDpi(160)
                setMinimumScaleType(1) // SCALE_TYPE_FIT_CENTER
                setDoubleTapZoomDuration(300)
                visibility = View.GONE
            }
            container.addView(iv)
            container.addView(ssiv)
            val density = parent.context.resources.displayMetrics.density
            val loadingBar = android.widget.ProgressBar(parent.context, null, android.R.attr.progressBarStyleLarge).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt()).apply {
                    gravity = android.view.Gravity.CENTER
                }
                indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFFBB86FC.toInt())
                visibility = View.GONE
            }
            container.addView(loadingBar)
            val vh = PagerViewHolder(container)
            vh.loadingBar = loadingBar
            // ★ 2026-10-05 对齐 mihon"设置即时刷新"：holder 从创建起就持有 SSIV（此前孤儿视图
            //   未赋 holder，首绑前 setDoubleTapZoom 遍历落空→"只当前页生效"类时序洞）。
            //   手势参数的最终一致性由 onBindViewHolder 尾部兜底同步保证。
            vh.ssiv = ssiv
            ssiv.isQuickScaleEnabled = doubleTapZoom
            ssiv.doubleTapZoom = doubleTapZoom
            ssiv.axisPassthroughHorizontal = !verticalPager
            installSwipeFallback(ssiv)
            return vh
        }

        override fun onBindViewHolder(holder: PagerViewHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains("loaded")) {
                onBindViewHolder(holder, position)
                return
            }
            // ★ §24 二代懒预读：peek 完一页后 notifyItemChanged("anim") → 走 onBindViewHolder(holder, position) 重绑
            if (payloads.contains("anim")) {
                onBindViewHolder(holder, position)
                return
            }
            onBindViewHolder(holder, position)
        }

        override fun onBindViewHolder(holder: PagerViewHolder, position: Int) {
            // Apply margin and background color immediately so settings take effect
            val container = holder.itemView as android.widget.FrameLayout
            val marginPx = (imageMarginH * container.context.resources.displayMetrics.density).toInt()
            container.setPadding(marginPx, 0, marginPx, 0)
            com.mangareader.filter.PaperBgDrawable.apply(container, container.context.applicationContext, pageBgColor, com.mangareader.filter.PaperPageTexture.overlayMode, com.mangareader.filter.PaperPageTexture.presetId, com.mangareader.filter.PaperPageTexture.concentration)

            // ★ 2026-10-05 对齐 mihon"设置即时刷新"bind 级兜底：无论本 bind 走哪个显示分支
            //   （下方多处提前 return），手势参数每次强制同步为视图字段当前值——根治
            //   "双击放大只当前页生效"类时序洞（holder 创建早于设置同步、复用残留等）
            holder.ssiv?.let { s ->
                s.doubleTapZoom = doubleTapZoom
                s.isQuickScaleEnabled = doubleTapZoom
                s.axisPassthroughHorizontal = !verticalPager
                installSwipeFallback(s)
            }

            val debugLabel = container.findViewById<android.widget.TextView>(0x7F000001)
            debugLabel?.visibility = View.GONE

            if (doublePageMode) {
                // ★ 跨页表取位（2026-10-05 重做）：奇偶/首张单页/智能 solo（宽图/长图）全在表内，
                //   RTL 物理排布在此唯一翻转（左位=阅读序次页）。solo 位（secondIdx<0）走
                //   合并路径自然退化=单图，旧 shift 首页特例分支删除
                val slotPos = if (isRtl) spreadTable.slotCount - 1 - position else position
                val firstIdx = spreadTable.firstPage(slotPos)
                val secondIdx = spreadTable.secondPage(slotPos)
                val leftIdx = if (isRtl) (if (secondIdx >= 0) secondIdx else firstIdx) else firstIdx
                val rightIdx = if (isRtl) (if (secondIdx >= 0) firstIdx else -1) else secondIdx
                // ★ 双页中缝分档实宽（1=窄 8dp / 2=中 16dp / 3=宽 28dp），0=贴合
                val gapPx = when (centerGapType) {
                    1 -> (8 * holder.itemView.context.resources.displayMetrics.density).toInt()
                    2 -> (16 * holder.itemView.context.resources.displayMetrics.density).toInt()
                    3 -> (28 * holder.itemView.context.resources.displayMetrics.density).toInt()
                    else -> 0
                }

                // wide image split: 全图加载到SSIV，视口定位显示左/右半边
                if (doublePageSplitEnabled) {
                    val leftBmp = bitmapCache[leftIdx]
                    if (leftBmp != null && !leftBmp.isRecycled && leftBmp.width > leftBmp.height) {
                        splitActivePositions.add(position)
                        // ★ 半页交替：翻页状态机切到右半时显示另一半（仅当前位）
                        val showRight = (isRtl != doublePageReverseEnabled) xor
                            (splitHalfRight && position == positionOf(currentPage))
                        holder.setImageSplit(leftBmp, showRight, useSSIV, landscapeZoomEnabled)
                        return
                    }
                }

                // Normal double-page: merge two pages
                val leftBmp = bitmapCache[leftIdx]
                val rightBmp = if (rightIdx in 0 until pageList.size) bitmapCache[rightIdx] else null
                if (leftBmp != null && !leftBmp.isRecycled) {
                    try {
                        // ★ 合并位图缓存：key 掺左右位图对象 hash——页重解码/超分替换/纹理重烘
                        //   会换对象自动 miss 重拼，无需失效逻辑；evict 不 recycle（GC 回收，同 bitmapCache 策略）
                        val cacheKey = "l$leftIdx@${System.identityHashCode(leftBmp)}" +
                                "r$rightIdx@${rightBmp?.let { System.identityHashCode(it) } ?: -1}" +
                                "g$gapPx rev$doublePageReverseEnabled"
                        val cachedMerge = mergedCache.get(cacheKey)
                        val merged = if (cachedMerge != null && !cachedMerge.isRecycled) {
                            cachedMerge
                        } else {
                            val mergeStart = android.os.SystemClock.uptimeMillis()
                            val fresh = if (rightBmp != null && !rightBmp.isRecycled) {
                                if (doublePageReverseEnabled) mergeBitmaps(rightBmp, leftBmp, gapPx)
                                else mergeBitmaps(leftBmp, rightBmp, gapPx)
                            } else leftBmp
                            // ★ 双页合并计时：切双页"要加载一下"取证（解码走 pageDecode，此处只记拼接）；
                            //   仅 >50ms 落盘，普通 bind 不写 IO
                            val mergeCost = android.os.SystemClock.uptimeMillis() - mergeStart
                            if (mergeCost > 50) runCatching { com.mangareader.DiagTracer.markStatic("merge ${mergeCost}ms") }
                            if (fresh !== leftBmp && fresh !== rightBmp) mergedCache.put(cacheKey, fresh)
                            fresh
                        }
                        holder.setImage(merged, useSSIV, landscapeZoomEnabled, forward = bindForwardFor(position))
                    } catch (_: OutOfMemoryError) {
                        holder.setImage(leftBmp, useSSIV, landscapeZoomEnabled, forward = bindForwardFor(position))
                    }
                    return
                }
                // Not loaded yet: load both
                bitmapCache.remove(leftIdx)
                if (rightIdx >= 0) bitmapCache.remove(rightIdx)
                holder.clear()
                if (!isScrolling) {
                    holder.loadingBar?.visibility = View.VISIBLE
                    loadPageAsync(leftIdx)
                    if (rightIdx in 0 until pageList.size) loadPageAsync(rightIdx)
                }
            } else {
                val realIdx = if (isRtl) pageList.size - 1 - position else position
                // ★ LV119 对齐参考项目：Coil3 自动识别动图 → pageAnimDrawables → ImageView 播放
                val animDrawable = pageAnimDrawables[realIdx]
                if (animDrawable != null) {
                    holder.setAnimDrawable(realIdx, animDrawable)
                    if (realIdx == currentPage) startAnimIfNotRunning(realIdx)
                    return
                }
                val tileFile = tileFileCache[realIdx]
                if (tileFile != null && tileFile.exists()) {
                    holder.setImageFile(tileFile, landscapeZoomEnabled)
                    return
                }
                val bmp = bitmapCache[realIdx]
                if (bmp != null && !bmp.isRecycled) {
                    val pageUseSsiv = useSSIV
                    if (doublePageSplitEnabled && bmp.width > bmp.height) {
                        splitActivePositions.add(realIdx)
                        // ★ 半页交替（同双页）：仅当前页应用
                        val showRight = (isRtl != doublePageReverseEnabled) xor
                            (splitHalfRight && realIdx == currentPage)
                        holder.setImageSplit(bmp, showRight, pageUseSsiv, landscapeZoomEnabled)
                    } else {
                        holder.setImage(bmp, pageUseSsiv, landscapeZoomEnabled, forward = bindForwardFor(position))
                    }
                    return
                }
                bitmapCache.remove(realIdx)
                holder.clear()
                if (!isScrolling) {
                    holder.loadingBar?.visibility = View.VISIBLE
                    // ★ 已知动图页跳过静解码（2026-10-03"打开 GIF 几十秒"专项）：长条 GIF 首帧
                    //   全尺寸解码可达百 MB 级——直接走动图加载；构造失败由 markAnimFailed 翻回静图
                    val knownAnim = pageAnimFlags.firstOrNull { it.pageIdx == realIdx }?.kind
                    if (knownAnim != null && knownAnim != AnimKind.NONE) {
                        loadAnimPageAsync(realIdx, holder)
                    } else {
                        loadPageAsync(realIdx)
                    }
                }
            }
        }
        override fun onViewRecycled(holder: PagerViewHolder) {
            // ★ Bug B/C 修复：onViewRecycled 之前 holder.clear() → disposeAnimDrawable() →
            //   pageAnimDrawables.remove(heldIdx) → ViewHolder 回收时把动图 drawable 从全局 map 删掉。
            //   后续 IDLE → notifyItemChanged → onBindViewHolder → loadAnimPageAsync NEW load
            //   → makeAnimDrawable + setAnimDrawable（先清后设）→ 屏幕闪一下空帧 + 动图回到第 1 帧。
            //   解法：回收时只清静图槽，保留动图槽与 pageAnimDrawables 的 entry。
            //   动图 drawable 的生命周期集中交给 pageAnimDrawables 统管（看 currentPage 时 start / 远离时 stop）。
            // ★ Bug C-1b：不再 setImageDrawable(null)——回翻时 holder 复用，drawable 还在 map，
            //   IDLE rebind → loadAnimPageAsync SKIP → setAnimDrawable 重设到 ImageView 即可。
            //   之前这里清 ImageView 导致 SKIP 路径一个空窗 → 闪白。
            holder.clearStatic()
        }
    }

    /** ★ §24 二代：改 inner class 让 ViewHolder 可访问外层 pageAnimDrawables 等动图状态 */
    inner class PagerViewHolder(itemView: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {
        private var heldBitmap: Bitmap? = null
        internal var ssiv: PagerSSIV? = itemView.findViewById(com.mangareader.R.id.ssiv_pager)
        // ★ 双页裁切标记：裁切页视口缩放恒大于 minScale（minScale 是整图 fit），
        //   tryPanIfZoomed 会误判"已放大"把翻页劫持成图内平移——此标记供其放行
        internal var isSplitPage = false
        internal val iv: ImageView? = itemView.findViewById(android.R.id.content)
        /** §24 二代动图槽：setAnimDrawable 时存入，clear 时 stop+释放
         *  ★ 改 Coil AnimatedImageDecoder 后类型从 FrameAnimationDrawable<*> → 普通 Drawable */
        internal var heldAnimDrawable: Drawable? = null
        /** ★ Bug#11：heldIdx 是 setAnimDrawable 进入时缓存的 page idx。
         *   disposeAnimDrawable 用它从 pageAnimDrawables 移除对应 entry，
         *   不用 bindingAdapterPosition（holder 复用后 dp 会变，错删别的页）。 */
        internal var heldIdx: Int = -1
        internal var loadingBar: android.widget.ProgressBar? = null
        /** ★ base/scale 代守卫（2026-10-05）：每次 applyPlanScale 递增；迟到的 post 重试/延时
         *  landscapeZoom 凭 gen 比对判定自己是否还属于当前这次 bind，重绑即作废 */
        private var scaleGen = 0
        /** ★ 绑定时的显示配置代数（2026-10-06 乱闪根修刀3）：同图短路用它判"零动作还是重设基准" */
        internal var boundCfgVer = -1
        /** ★ v1.383 旋转重算：拆分页记住半页侧（recalcForViewport 复用 applySplitScale 需要） */
        private var lastShowRight = false

        /** ★ v1.383 旋转/分屏重算兜底（海贼王横屏"划不动+乱跳上一页"根修）：
         *  onSizeChanged 的载重走 loadPageAsync，而缓存命中路径零动作直接 return（不触发重绑），
         *  displayCfgVer++ 没有任何消费者——旋转后可见页 SSIV 的 viewportBaseScale/scale 残留旧
         *  屏向值（实锤：竖 0.51→横 0.81，base 停 0.51）→ 未放大被误判放大 → 手势全落
         *  hold-zoomedPan 吞滑 + 贴边 fake drag 起拖后 fling 乱跳页（pos 3→0 复现实锤）。
         *  布局完成后按新视口强制重算基准与定位（applyPlanScale/applySplitScale 全量路径幂等）。 */
        fun recalcForViewport() {
            val bmp = heldBitmap ?: return
            if (bmp.isRecycled) return
            if (isSplitPage) ssiv?.let { applySplitScale(it, bmp, lastShowRight) }
            else ssiv?.let { applyScale(it, bmp, landscapeZoomEnabled, forward = false) }
        }

        private fun createSsiv(): PagerSSIV {
            return PagerSSIV(itemView.context).apply {
                id = com.mangareader.R.id.ssiv_pager
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                isZoomEnabled = true
                isPanEnabled = true
                // ★ 双击放大关闭时同时禁 SSIV 内部 quick-scale（放大态双击走的内部检测器）
                isQuickScaleEnabled = this@PagerReaderView.doubleTapZoom
                setMaxScale(5f)
                setMaxTileSize(512)
                setMinimumTileDpi(160)
                setMinimumScaleType(1) // SCALE_TYPE_FIT_CENTER
                setDoubleTapZoomDuration(300)
                axisPassthroughHorizontal = !verticalPager
                doubleTapZoom = this@PagerReaderView.doubleTapZoom
                // ★ 滑动兜底接线统一走 installSwipeFallback（与 bind 兜底共用）
                installSwipeFallback(this)
                visibility = View.GONE
            }
        }

        fun setImage(bmp: Bitmap, useSsiv: Boolean, landscapeZoom: Boolean = true, forward: Boolean = true) {
            isSplitPage = false
            if (bmp.isRecycled) return
            // ★ 同图重绑短路（2026-10-06 海贼王闪烁工单"内部机制打架"）：表重排/设置同步触发的
            //   notify 会让可见槽重新走 setImage——SSIV.setImage 会重置 decoder/tile 状态（可见帧
            //   闪一下）。同一位图已在显示时只重设基准/定位（applyScale），滚动帧零扰动。
            //   二轮审查补 useSsiv 条件：高清缩放开关实时切换（true→false）时同图不得错走 SSIV 短路
            //   ★ 刀3（2026-10-06 乱闪根修）：位图与显示配置全同的重绑零动作——applyScale 会把
            //   landscapeZoom 已放大的画面打回 contain 再等 500ms 弹回（重绑风暴下=可见弹跳循环）。
            //   只有显示配置代数变了（applyLivePrefs bump）才需要重设基准
            if (useSsiv) ssiv?.let { ssv ->
                if (ssv.visibility == View.VISIBLE && ssv.isReady && heldBitmap === bmp) {
                    if (boundCfgVer == displayCfgVer) return
                    applyScale(ssv, bmp, landscapeZoom, forward)
                    boundCfgVer = displayCfgVer
                    return
                }
            }
            heldBitmap = bmp
            boundCfgVer = displayCfgVer
            if (useSsiv) {
                val container = itemView as android.widget.FrameLayout
                // ★ 修复翻页闪烁：复用已有 SSIV（setImage 会重置内部 decoder/状态），
                //   原来每次 removeView+new SSIV 产生空白帧 = 闪烁
                val ssv = ssiv ?: createSsiv().also { container.addView(it); ssiv = it }
                ssv.visibility = View.VISIBLE
                iv?.visibility = View.GONE
                // ★ 2026-09-08：bitmap 已解码完成，直接隐藏 loadingBar（onReady 不可靠导致转圈不停）
                loadingBar?.visibility = View.GONE
                ssv.setOnImageEventListener(object : com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.DefaultOnImageEventListener() {
                    override fun onReady() {
                        ssv.setOnImageEventListener(null)
                        applyScale(ssv, bmp, landscapeZoom, forward)
                    }
                    override fun onImageLoadError(e: Exception) {
                        ssv.setOnImageEventListener(null)
                    }
                })
                // ★ base 同步双保险（2026-10-06 日志实锤 scale=0.81 base=0.75 六连误判）：
                //   缓存命中重绑的 onReady 到达前手势已可开滑，base 残留上一页值 → 未放大被
                //   误判放大 → hold-zoomedPan 吞滑数秒。onReady 后 applyPlanScale 以同公式设
                //   同值（幂等），此处只补窗口期的判定基准。
                //   ★ bsync 探针：此刻 SSIV 内部 scale 偏离新页基准超阈值 = onReady 全量路径
                //   没跑、scale 停留旧值（123307 日志 scale 卡 0.81 残留的直击取证），异常时
                //   显式落日志定位残留路径；正常页 scale≈base 零输出。
                if (ssv.width > 0 && ssv.height > 0) {
                    try {
                        val isTablet = resources.configuration.smallestScreenWidthDp >= 600
                        val bs = ScalePlan.baseScale(
                            scaleMode, ssv.width.toFloat(), ssv.height.toFloat(),
                            bmp.width.toFloat(), bmp.height.toFloat(), isTablet
                        )
                        val curScale = try { ssv.scale.toFloat() } catch (_: Exception) { 0f }
                        if (curScale > 0f && kotlin.math.abs(curScale - bs) > 0.02f) {
                            com.mangareader.DiagTracer.markStatic(
                                "bsync s=${"%.2f".format(curScale)} b=${"%.2f".format(bs)}"
                            )
                        }
                        ssv.viewportBaseScale = bs
                    } catch (_: Exception) {}
                }
                ssv.setImage(com.davemorrissey.labs.subscaleview.ImageSource.cachedBitmap(bmp))
            } else {
                // 不使用 SSIV：静态 ImageView 按档位生效（2026-10-05 重做前恒 FIT_CENTER，
                // "铺满屏幕"在此路径从未生效）。不可平移 → staticMode 收敛（贴高/智能→铺满防
                // 不可找回裁切），原尺寸=CENTER 1:1，铺满=MATRIX（顶/起始缘对齐）
                loadingBar?.visibility = View.GONE
                ssiv?.visibility = View.GONE
                iv?.visibility = View.VISIBLE
                applyStaticMode(iv, bmp)
                try { iv?.setImageBitmap(bmp) } catch (_: Exception) {}
            }
        }

        /** 非 SSIV 静态图的档位映射（ZoomStart 对齐/平板防糊与 SSIV 路径同源 ScalePlan） */
        private fun applyStaticMode(iv: ImageView?, bmp: Bitmap) {
            iv ?: return
            val effMode = ScalePlan.staticMode(scaleMode)
            when (effMode) {
                ScalePlan.MODE_ORIGINAL -> {
                    iv.scaleType = ImageView.ScaleType.CENTER
                    iv.imageMatrix = android.graphics.Matrix()
                }
                ScalePlan.MODE_CONTAIN -> {
                    iv.scaleType = ImageView.ScaleType.FIT_CENTER
                    iv.imageMatrix = android.graphics.Matrix()
                }
                else -> {
                    iv.scaleType = ImageView.ScaleType.MATRIX
                    iv.post {
                        val vw = iv.width.toFloat(); val vh = iv.height.toFloat()
                        if (vw <= 0f || vh <= 0f || bmp.isRecycled) return@post
                        val isTablet = resources.configuration.smallestScreenWidthDp >= 600
                        val scale = ScalePlan.baseScale(effMode, vw, vh, bmp.width.toFloat(), bmp.height.toFloat(), isTablet)
                        val m = android.graphics.Matrix()
                        m.setScale(scale, scale)
                        val gx = ScalePlan.edgeGaps(ScalePlan.alignX(zoomStart, isRtl), vw, bmp.width * scale)
                        val gy = ScalePlan.edgeGaps(ScalePlan.alignY(zoomStart), vh, bmp.height * scale)
                        m.postTranslate(gx.first, gy.first)
                        iv.imageMatrix = m
                    }
                }
            }
        }

        /**
         * 裁剪双页视口定位：全图加载到SSIV，用视口显示左/右半边。
         * 用户可以通过navigateToPan平移到另一半。
         */
        fun setImageSplit(bmp: Bitmap, showRight: Boolean, useSsiv: Boolean, landscapeZoom: Boolean) {
            isSplitPage = true
            lastShowRight = showRight
            if (bmp.isRecycled) return
            // ★ 同图重绑短路（同 setImage）：showRight 翻转也只需 applySplitScale 重定位视口，不必重置 SSIV
            //   ★ 刀3：位图与显示配置全同的重绑零动作（同 setImage，防 landscapeZoom 弹跳）
            if (useSsiv) ssiv?.let { ssv ->
                if (ssv.visibility == View.VISIBLE && ssv.isReady && heldBitmap === bmp) {
                    if (boundCfgVer == displayCfgVer && !showRight) return
                    applySplitScale(ssv, bmp, showRight)
                    boundCfgVer = displayCfgVer
                    return
                }
            }
            heldBitmap = bmp
            boundCfgVer = displayCfgVer
            if (useSsiv) {
                val container = itemView as android.widget.FrameLayout
                val ssv = ssiv ?: createSsiv().also { container.addView(it); ssiv = it }
                ssv.visibility = View.VISIBLE
                iv?.visibility = View.GONE
                // ★ 2026-09-08：bitmap 已解码完成，直接隐藏 loadingBar
                loadingBar?.visibility = View.GONE
                ssv.setOnImageEventListener(object : com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.DefaultOnImageEventListener() {
                    override fun onReady() {
                        ssv.setOnImageEventListener(null)
                        applySplitScale(ssv, bmp, showRight)
                    }
                    override fun onImageLoadError(e: Exception) {
                        ssv.setOnImageEventListener(null)
                    }
                })
                // ★ base 同步双保险（同 setImage 2026-10-06）：onReady 前以半页宽公式预置
                if (ssv.width > 0 && ssv.height > 0) {
                    try {
                        val isTablet = resources.configuration.smallestScreenWidthDp >= 600
                        ssv.viewportBaseScale = ScalePlan.baseScale(
                            scaleMode, ssv.width.toFloat(), ssv.height.toFloat(),
                            (bmp.width / 2f).coerceAtLeast(1f), bmp.height.toFloat(), isTablet
                        )
                    } catch (_: Exception) {}
                }
                ssv.setImage(com.davemorrissey.labs.subscaleview.ImageSource.cachedBitmap(bmp))
            } else {
                val halfW = bmp.width / 2
                val srcX = if (showRight) halfW else 0
                val splitBmp = Bitmap.createBitmap(bmp, srcX, 0, halfW, bmp.height)
                loadingBar?.visibility = View.GONE
                ssiv?.visibility = View.GONE
                iv?.visibility = View.VISIBLE
                iv?.scaleType = ImageView.ScaleType.FIT_CENTER
                try { iv?.setImageBitmap(splitBmp) } catch (_: Exception) {}
            }
        }

        private fun applySplitScale(ssiv: PagerSSIV, bmp: Bitmap, showRight: Boolean) {
            if (!ssiv.isReady || ssiv.width <= 0) {
                ssiv.post { applySplitScale(ssiv, bmp, showRight) }
                return
            }
            // ★ 拆分视口显示半页：以半页宽为"位图宽"走 ScalePlan 统一公式（完整显示=contain、
            //   铺满=贴宽带平板防糊封顶、贴高/原尺寸/智能照档位语义）；
            //   视口定位钉在半页（showRight），zoomStart 对齐不参与——视口本身就是"页"
            val halfW = (bmp.width / 2f).coerceAtLeast(1f)
            val isTablet = ssiv.resources.configuration.smallestScreenWidthDp >= 600
            val targetScale = ScalePlan.baseScale(scaleMode, ssiv.width.toFloat(), ssiv.height.toFloat(), halfW, bmp.height.toFloat(), isTablet)
            val centerX = if (showRight) bmp.width * 3f / 4f else bmp.width / 4f
            val centerY = bmp.height / 2f
            // ★ 视口基准：拆分视口的定位比例就是"未放大"态——横滑放行翻页（修滑动翻页失效）、
            //   纵拖看图，双击缩放以此为中心
            ssiv.viewportBaseScale = targetScale
            ssiv.setScaleAndCenter(targetScale, android.graphics.PointF(centerX, centerY))
        }


        private fun applyScale(ssiv: PagerSSIV, bmp: Bitmap, landscapeZoom: Boolean, forward: Boolean) {
            if (!ssiv.isReady || ssiv.width <= 0) {
                ssiv.post { applyScale(ssiv, bmp, landscapeZoom, forward) }
                return
            }
            applyPlanScale(ssiv, bmp.width.toFloat(), bmp.height.toFloat(), landscapeZoom, forward)
        }

        /**
         * ★ 画面缩放唯一入口（2026-10-05 重做）：比例/对齐/landscapeZoom 全部从 ScalePlan 派生，
         * 消灭 bitmap/瓦片/拆分三处内联公式（曾产生"仿真缺平板封顶"式分裂 bug）。
         * 等 layout 完成后再设 scale（避免 fitToLower 在 layout 时覆盖）。
         */
        private fun applyPlanScale(ssiv: PagerSSIV, iw: Float, ih: Float, landscapeZoom: Boolean, forward: Boolean) {
            // ★ 2026-10-05"双页能翻/单页死"根因（220654 日志 scale=0.81 base=0.53 类错配实锤）：
            //   base/scale 原本拖到 OnGlobalLayout 才设——复用 holder 重绑命中已解码页时没有布局
            //   路过（等值 padding no-op、loadingBar 不变化），监听器永不触发 → viewportBaseScale
            //   停留上一页旧值。单页各页页型不同、base 随页漂移，旧 base≠新 scale → 未放大被误判
            //   放大：根检测器 isCurrentPageZoomed 门闩关闭 + SSIV 分流全落 hold-zoomedPan →
            //   滑动翻页死；双页合并图页型恒定、旧 base≈新 base 恰好无恙（工单完全解释）。
            //   applyScale 的 post 重试闸门保证走全量路径时 width>0 且 isReady——同步直设；
            //   scaleGen 代守卫：迟到的重试/延时 landscapeZoom 不得打到重绑后的新图上。
            scaleGen++
            val gen = scaleGen
            if (ssiv.width <= 0 || ssiv.height <= 0 || iw <= 0f || ih <= 0f) {
                ssiv.post { if (gen == scaleGen) applyPlanScale(ssiv, iw, ih, landscapeZoom, forward) }
                return
            }
            val w = ssiv.width.toFloat()
            val h = ssiv.height.toFloat()
            val isTablet = ssiv.resources.configuration.smallestScreenWidthDp >= 600
            val scale = ScalePlan.baseScale(scaleMode, w, h, iw, ih, isTablet)
            // ★ 视口基准：基线比例就是"未放大"态（手势分流判定用）
            ssiv.viewportBaseScale = scale
            val cx = ScalePlan.centerX(zoomStart, isRtl, w, iw, scale)
            val cy = ScalePlan.centerY(zoomStart, h, ih, scale)
            ssiv.setScaleAndCenter(scale, android.graphics.PointF(cx, cy))
            // landscapeZoom：宽图自动放大适配高度（mihon 同款仅 contain 档插手；对齐 zoomStart，
            // 前进落阅读起始缘、回退落结束缘；延时 500ms+500ms 动画对齐 mihon landscapeZoom）
            if (landscapeZoom && ScalePlan.landscapeZoomable(scaleMode) && iw > ih) {
                ssiv.handler?.postDelayed({
                    // ★ gen 守卫 + isShown（mihon isVisibleOnScreen 同款）：500ms 内重绑/离屏不再放大
                    if (gen == scaleGen && ssiv.isReady && ssiv.isShown) {
                        val targetScale = ssiv.height.toFloat() / ih
                        // ★ base 随 landscapeZoom 终值更新（2026-10-06）：贴高态是本档"初始视图"的
                        //   一部分而非用户放大——base 停留 contain 值会把合法初始态误判成放大态，
                        //   横滑全落 hold-zoomedPan 吞掉（123307 日志 scale=0.81 base=0.75 交替实锤）。
                        //   同步后横滑走未放大分支的拖图接力、竖拖走正交轴拖图（贴高纵向溢出可拖）。
                        ssiv.viewportBaseScale = targetScale
                        try {
                            com.mangareader.DiagTracer.markStatic(
                                "lz t=${"%.2f".format(targetScale)} g$gen ${iw.toInt()}x${ih.toInt()}"
                            )
                        } catch (_: Exception) {}
                        val cx = ScalePlan.landscapeZoomCenterX(zoomStart, isRtl, forward, ssiv.width.toFloat(), iw, targetScale)
                        val cy = if (ScalePlan.alignY(zoomStart) == 0)
                            (ssiv.center?.y ?: ih / 2f)
                        else ssiv.height.toFloat() / targetScale / 2f
                        ssiv.animateScaleAndCenter(targetScale, android.graphics.PointF(cx, cy))
                            ?.withDuration(500)?.withEasing(com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_IN_OUT_QUAD)?.withInterruptible(true)?.start()
                    }
                }, 500)
            }
        }
        fun clear() {
            isSplitPage = false
            scaleGen++ // ★ 作废在途的 applyPlanScale 重试与延时 landscapeZoom
            disposeAnimDrawable()
            heldBitmap = null
            iv?.setImageBitmap(null)
            try { ssiv?.viewportBaseScale = 0f } catch (_: Exception) {}
            try { ssiv?.resetScaleAndCenter() } catch (_: Exception) {}
            ssiv?.visibility = View.GONE
            iv?.visibility = View.VISIBLE
            loadingBar?.visibility = View.GONE
        }
        /** ★ §24 二代动图 set —— 接 drawable，存到 heldAnimDrawable 和全局 pageAnimDrawables，用 ImageView 显示；不 start（翻到这页时 startAnimIfNotRunning 才 start）
         *   ★ Coil 路线后 drawable 是 Animatable（系统 AnimatedDrawable），不强制是 FrameAnimationDrawable */
        fun setAnimDrawable(idx: Int, drawable: Drawable) {
            isSplitPage = false
            // ★ Bug B 修复 2：若是同一 idx 的同一 drawable 实例，跳过 dispose+再设，
            //   避免 ImageView.setImageDrawable(null) → setImageDrawable(same) 这瞬空白 → 画面闪一下。
            if (heldIdx == idx && heldAnimDrawable === drawable) {
                return
            }
            // ★ Bug#11：先把上一张 drawable 从 map 清掉再用新 idx 入 map，
            //   不能依赖下游 disposeAnimDrawable（它用 heldIdx 是对的）。
            disposeAnimDrawable()
            heldIdx = idx
            heldAnimDrawable = drawable
            pageAnimDrawables[idx] = drawable
            // 动图页隐藏 SSIV 走 ImageView 显示
            ssiv?.visibility = View.GONE
            iv?.visibility = View.VISIBLE
            iv?.scaleType = ImageView.ScaleType.FIT_CENTER
            iv?.setImageDrawable(drawable)
            loadingBar?.visibility = View.GONE
        }
        /** 清理动图槽：stop 释放 decoder + 从全局 map 删除（用 heldIdx 不是 bindingAdapterPosition）
         *  ★ §34.2 T1 修复：不再调 iv.setImageDrawable(null)——dispose 与后续 setAnimDrawable 之间
         *    ImageView 短暂空白会闪白。setAnimDrawable 内先 disposeAnimDrawable() 再 setImageDrawable(new)，
         *    原代码 dispose 里清 null 产生一个空窗帧。现在 dispose 只停动图 + 清 map entry，不动 ImageView。
         *    setAnimDrawable 紧接着 setImageDrawable(new) 覆盖即可。 */
        private fun disposeAnimDrawable() {
            heldAnimDrawable?.let { d ->
                try { if (d is Animatable && d.isRunning) d.stop() } catch (_: Throwable) {}
            }
            heldIdx = -1
            heldAnimDrawable = null
        }
        /** §24 二代 clearStatic：动图路径前的清理（仅清静图状态不及动图槽）
         *  ★ §34.2 T2：动图 holder 回收时也走这里——clearStatic 不动动图槽（heldAnimDrawable/pageAnimDrawables），
         *    也不 setImageDrawable(null)（只 setImageBitmap(null)，不影响 drawable 槽）→ 回翻复用 holder 时
         *    drawable 仍在 map，SKIP 路径 setAnimDrawable 重设到 ImageView 无空窗。 */
        fun clearStatic() {
            heldBitmap = null
            iv?.setImageBitmap(null)
            try { ssiv?.resetScaleAndCenter() } catch (_: Exception) {}
            ssiv?.visibility = View.GONE
            iv?.visibility = View.VISIBLE
        }
        fun setImageFile(file: java.io.File, landscapeZoom: Boolean) {
            isSplitPage = false
            heldBitmap = null
            val container = itemView as android.widget.FrameLayout
            // ★ 修复翻页闪烁：复用已有 SSIV（setImage 会重置内部 decoder/状态），
            //   原来每次 removeView+new SSIV 产生空白帧 = 闪烁
            val ssv = ssiv ?: createSsiv().also { container.addView(it); ssiv = it }
            ssv.visibility = View.VISIBLE
            iv?.visibility = View.GONE
            // ★ 占位动画：SSIV setImage(uri) 异步 decode+tile 初始化，onReady 之前显示 loadingBar
            loadingBar?.visibility = View.VISIBLE
            // ★ base 同步双保险（同 setImage 2026-10-06）：瓦片路径 onReady 前拿不到源图尺寸，
            //   置 0 回落 minScale——setImage 后 SSIV 内部 minScale 即新图 contain，判定不残留旧页值
            try { ssv.viewportBaseScale = 0f } catch (_: Exception) {}
            ssv.setOnImageEventListener(object : com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    ssv.setOnImageEventListener(null)
                    loadingBar?.visibility = View.GONE
                    val sw = ssv.sWidth; val sh = ssv.sHeight
                    if (sw <= 0 || sh <= 0) return
                    // ★ 瓦片路径并入 applyPlanScale 统一公式（原内联版还有一处
                    //   "平板宽图+landscapeZoom关=contain"的分裂特例，一并消灭）
                    applyPlanScale(ssv, sw.toFloat(), sh.toFloat(), landscapeZoom, forward = true)
                }
                override fun onImageLoadError(e: Exception) { ssv.setOnImageEventListener(null); loadingBar?.visibility = View.GONE }
            })
            ssv.setImage(com.davemorrissey.labs.subscaleview.ImageSource.uri(itemView.context, android.net.Uri.fromFile(file)))
        }
    }
}
