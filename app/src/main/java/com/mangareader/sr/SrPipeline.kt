package com.mangareader.sr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.mangareader.viewer.ArchiveEngine
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 超分运行时管线：阅读器 ← → 引擎的胶水层。
 *
 * 规则（按用户需求定稿）：
 *  - 实时超分默认关闭；每本书每次进阅读器都要在面板手动选作用域（仅当前页 / 当前整本）；
 *  - 批量离线产物永远生效（完整或部分整本都算，命中的页直接出超分图）；
 *  - 实时三级缓存只在作用域非"关闭"时生效；
 *  - 渐进替换：先原图，算完经 SrCore.readyEvents 原位替换；
 *  - 安全：只读源文件、推理失败回退原图、全透明坏图拦截、输出最长边封顶防 OOM。
 */
object SrPipeline {
    private const val TAG = "SrPipeline"

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** 进行中轮询的页（防同一页起多个轮询协程） */
    private val pollKeys = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var engine: ArchiveEngine? = null

    @Volatile
    var sessionChapterId: Long = 0L
        private set

    @Volatile
    var sessionBookId: Long = -1L
        private set

    @Volatile
    private var sessionTreeStr: String = ""

    @Volatile
    private var sessionPageList: List<ArchiveEngine.PageMeta> = emptyList()

    @Volatile
    private var sessionBookTitle: String = ""

    @Volatile
    private var sessionActive = false

    /** 本章是否已有离线批量产物（有就不再实时超分，省算力） */
    @Volatile

    var sessionHasBatch = false
        private set

    /** ★ N1 无缝续读：跨卷切换时暂存用户此前的作用域，beginSession 里恢复
     *  （resetForBook 会把作用域重置为关闭；无缝场景需要保留用户的选择） */
    @Volatile
    var pendingScopeRestore: Int? = null

    /** 用户正在看的页（由阅读器 onPageChanged 钩子喂入） */
    @Volatile
    var currentPageIdx: Int = 0
        private set

    /** 当前正占用引擎推理的页 key（翻页让位：中止在途旧页推理用） */
    @Volatile
    private var runningKey: String? = null
    private val runningKeyLock = Any()

    /** 在途/排队任务的 Job（翻页让位：取消旧页排队任务） */
    private val pageJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /** 作用域切换：关闭=取消在跑任务并把当前页换回原图；开启=按当前设置立刻算当前页并按需预载。
     *  ★ 有离线批量产物时：不切换模型（不清缓存）、不实时超分——直接吃离线产物 */
    fun onScopeChanged(newScope: Int) {
        if (newScope == SrUiState.SCOPE_OFF) {
            SrCore.manager.cancelProcessingJobs()
            revertCurrentToOriginal()
            return
        }
        if (!sessionHasBatch) {
            SrCore.applySettings(sessionBookId)
            onPageChanged(currentPageIdx, sessionPageList.size)
            if (sessionActive) {
                synchronized(originals) { originals["$sessionChapterId:$currentPageIdx"] }
                    ?.let { scheduleInternal(currentPageIdx, it) }
            }
        } else {
            onPageChanged(currentPageIdx, sessionPageList.size)
        }
    }

    /** 用户侧最近请求页（改设置后重算它） */
    @Volatile
    private var lastUserPage: Int = -1

    /** 前后对比用：超分前的原图（最多 8 页） */
    private val originals = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean = size > 8
    }

    fun peekOriginal(pageIndex: Int): Bitmap? = synchronized(originals) {
        val key = "$sessionChapterId:$pageIndex"
        val bmp = originals[key]
        if (bmp != null && bmp.isRecycled) {
            originals.remove(key)
            null
        } else bmp
    }

    /** 仅内存态结果查询（UI 角标用，零磁盘 IO）：实时内存缓存 + 管理器结果缓存。
     *  页级产物命中显示时会回填内存缓存（getCached 产物分支），故角标据此可判"优化完成"。 */
    fun peekSrMemory(pageIndex: Int): Bitmap? {
        SrCore.dispatcher.getSrBitmap(sessionChapterId, pageIndex)?.let { return it }
        return try {
            SrCore.manager.getCachedResult(SrCore.manager.buildCacheKey(sessionChapterId, pageIndex))
        } catch (_: Throwable) {
            null
        }
    }

    /** 对比叠层"右侧"取图：页级持久产物优先；超分结果次之；黑白独立模式下退而求其次取黑白优化结果；
     *  最后兜底查批量离线产物（手动开超分切换模型后内存缓存键不命中时仍可对比） */
    fun peekSr(pageIndex: Int): Bitmap? {
        SrCore.diskCache.getArtifact(
            sessionBookId, artifactChapterKey(), artifactFingerprint(), pageIndex,
        )?.let { return it }
        val srBmp = SrCore.dispatcher.getSrBitmap(sessionChapterId, pageIndex)
        if (srBmp != null) return srBmp
        if (!SrCore.settings.upscaleEnabled && SrCore.settings.bwEnabled) {
            val key = "bwonly_${sessionChapterId}_${pageIndex}_${SrCore.settings.bwGrayLevels}_${if (SrCore.settings.bwDensityCorrection) 1 else 0}"
            SrCore.manager.getCachedResult(key)?.let { return it }
            SrCore.diskCache.get(key)?.let { return it }
        }
        if (batchChapterOnDisk() && slotIsWholePage(pageIndex)) {
            SrCore.diskCache.getBatchPage(sessionBookTitle, SRQueueItem.chapterKey(sessionBookId, sessionTreeStr), batchPageIdx(pageIndex))?.let { return it }
        }
        return null
    }

    /** 输出封顶（条漫/超大图 OOM 防线）：屏幕最长边 2 倍，夹在 [2048, 8192] */
    @Volatile
    var maxOutputEdge: Int = 4096
        private set

    fun beginSession(context: Context, treeStr: String, bookId: Long, bookTitle: String, pages: List<ArchiveEngine.PageMeta>, startPage: Int = 0) {
        if (engine == null || sessionBookId != bookId || sessionTreeStr != treeStr) {
            engine = ArchiveEngine(context.applicationContext)
        }
        // ★ 2026-10-02 修"批量产物全会话失效"：init 必须在 sessionHasBatch 判定之前——
        //   原顺序冷启动直进书时 SrCore.initialized=false → 判定恒 false 且会话内无人更新，
        //   显示链三道守卫（Fast 同步查/轮询/跳过实时重算）全被这个坏开关锁死，
        //   用户观感="批量超分完只有个别页清晰、翻走就丢、退出重进才好"。init 幂等且轻量。
        if (!SrCore.initialized) SrCore.init(context)
        sessionChapterId = stableChapterId(treeStr)
        sessionBookId = bookId
        sessionTreeStr = treeStr
        sessionBookTitle = bookTitle
        sessionPageList = pages
        sessionExpandedTable = pages.any { it.segmentIndex > 0 || it.splitSide != 0 }
        sessionActive = true
        sessionHasBatch = try {
            SrCore.diskCache.hasBatchChapter(bookTitle, SRQueueItem.chapterKey(bookId, treeStr))
        } catch (_: Throwable) {
            false
        }
        synchronized(SrUiState.computingPages) { SrUiState.computingPages.clear() }
        cancelScope()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        lastUserPage = -1
        // ★ 2026-10-02：currentPageIdx 用真实起始页（onPageChanged 钩子此前从未被接线，
        //   恒 0 → "勾选作用域不立即开算/仅当前页守卫错杀/整本预载失效"）
        currentPageIdx = startPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))

        // 作用域按需求每次进书默认关闭（手动开启才实时超分；离线批量产物不受影响）
        SrUiState.resetForBook(bookId, bookTitle)

        val dm = context.resources.displayMetrics
        maxOutputEdge = (maxOf(dm.widthPixels, dm.heightPixels) * 2).coerceIn(2048, 8192)

        // 注入真实预载取料（修复源项目缺口：回调接管后真正做到"主动预载"）
        SrCore.dispatcher.onPreloadRequested = { chapterId, pageIndex ->
            if (chapterId == sessionChapterId && sessionActive) {
                scheduleInternal(pageIndex, callerBitmap = null)
            }
        }
        Log.i(TAG, "session begin: cid=$sessionChapterId book=$bookId maxEdge=$maxOutputEdge pages=${pages.size} batch=$sessionHasBatch start=$currentPageIdx")
        try { com.mangareader.DiagTracer.markStatic("enhance: batch=$sessionHasBatch pages=${pages.size}") } catch (_: Throwable) {}

        // ★ N1：无缝跨卷恢复实时超分作用域（resetForBook 刚把 scope 重置为 OFF；非关闭才恢复）
        pendingScopeRestore?.let { saved ->
            pendingScopeRestore = null
            if (saved != SrUiState.SCOPE_OFF) onScopeChanged(saved)
        }
    }

    fun endSession() {
        sessionActive = false
        cancelScope()
        synchronized(originals) { originals.clear() }
        lastUserPage = -1
    }

    private fun cancelScope() {
        scope.cancel()
        inFlight.clear()
        pollKeys.clear()
    }

    /** 显示层兜底轮询（照搬参考项目 ReaderPageImageView.scheduleSrRefresh 的思路）：
     *  readyEvents 事件可能被丢/视图当时不在屏，事件注入失败后就永远停在原图。
     *  每 800ms 查一次三级缓存（实时/黑白独立/批量离线），命中即回调，最多约 48s。
     *  与事件注入并存：两边都走到时 inject 幂等（同一张图重复写缓存+刷新，无副作用）。 */
    fun pollForResult(pageIndex: Int, onReady: (Bitmap) -> Unit) {
        if (!sessionActive) return
        // ★ F4.6：批量产物"永远生效"——作用域/开关关闭时也要为批量章节轮询回填；
        //   守卫改 stat（2026-10-02）：不再依赖会话级 sessionHasBatch（冷启动误判会挂全会话）
        //   页级持久产物同理永远生效（2026-10-03）：守卫加 artifactChapterOnDisk()
        if (SrUiState.scope.intValue == SrUiState.SCOPE_OFF && !batchChapterOnDisk() && !artifactChapterOnDisk()) return
        if (!SrCore.settings.upscaleEnabled && !SrCore.settings.bwEnabled && !batchChapterOnDisk() && !artifactChapterOnDisk()) return
        val key = "$sessionChapterId:$pageIndex"
        if (!pollKeys.add(key)) return
        scope.launch(Dispatchers.IO) {
            try {
                var hitFound = false
                repeat(60) {
                    if (!sessionActive) return@launch
                    val hit = getCached(pageIndex)
                    if (hit != null && !hit.isRecycled) {
                        onReady(hit)
                        hitFound = true
                        return@launch
                    }
                    delay(800)
                }
                // ★ 排障打点（2026-09-30）：可见页轮询48s仍未拿到增强结果=用户看到的"偶尔失效"；
                //   有此标记=队列/熔断侧没出图，无此标记=显示路径没走轮询
                if (!hitFound) {
                    runCatching { com.mangareader.DiagTracer.markStatic("enhance: poll miss p=$pageIndex") }
                }
            } finally {
                pollKeys.remove(key)
            }
        }
    }

    private fun stableChapterId(treeStr: String): Long {
        var h = 0xcbf29ce484222325uL.toLong()
        for (b in treeStr.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xFF)
            h *= 0x100000001b3L
        }
        return h
    }

    /** 该页是否适合超分（动图跳过） */
    fun isApplicable(meta: ArchiveEngine.PageMeta): Boolean {
        return when (meta.ext.lowercase()) {
            "gif", "apng" -> false
            else -> true
        }
    }

    /**
     * ★ 展开表守卫（2026-10-11 用户实报「点超分后页码内容串位/跳到别的图」）：
     * 段页（segmentIndex>0，条漫长图拆分/PDF 高分页切段）与半页（splitSide!=0，宽页拆分）
     * 的位次在页表里 ≠ 整页——loadPage/批量产物/页级产物按整页原图存取，把整页内容灌进
     * 段位就是串图。这些位次只有视图亲手切好的 callerBitmap 才是正确内容，任何"按位次
     * 自行取图"的通道都必须绕开。未展开表恒 true（位次=整页，原语义不变）。
     */
    private fun slotIsWholePage(pageIndex: Int): Boolean =
        sessionPageList.getOrNull(pageIndex)?.let { it.segmentIndex == 0 && it.splitSide == 0 } ?: true

    /** 阅读器翻页钩子：更新当前页；"整本"作用域做前后预载；"仅当前页"作用域旧页让位 */
    fun onPageChanged(centerIdx: Int, totalPages: Int) {
        currentPageIdx = centerIdx
        // ★ 仅当前页：翻页即让位——取消别的页"排队中"的任务、中止"在途"的旧推理，让当前页插队
        if (SrUiState.scope.intValue == SrUiState.SCOPE_PAGE) {
            val keepKey = "$sessionChapterId:$centerIdx"
            pageJobs.entries.removeAll { (k, job) ->
                if (k != keepKey) {
                    job.cancel()
                    inFlight.remove(k)
                    synchronized(SrUiState.computingPages) {
                        SrUiState.computingPages.removeAll { it == k.substringAfterLast(':').toIntOrNull() }
                    }
                    true
                } else false
            }
            val runningNow = synchronized(runningKeyLock) { runningKey }
            if (runningNow != null && runningNow != keepKey) {
                SrCore.manager.abortInFlight()
            }
        }
        // ★ 始终开启(SCOPE_ALWAYS)与整本同权：翻页预载整本推进
        if (SrUiState.scope.intValue >= SrUiState.SCOPE_BOOK) {
            SrCore.dispatcher.onPageChanged(sessionChapterId, centerIdx, totalPages)
        }
    }

    /** 当前会话页表是否经过展开（条漫竖切段页/宽图二分半页）——beginSession 时算一次 */
    @Volatile
    private var sessionExpandedTable = false

    /** ★ 批量产物页号映射（2026-10-02）：批量按**原始页号**落盘（engine.listPages 序），
     *  阅读侧 pageIndex 可能是展开表序号——不映射会取到错页图（"只有第一页对"的另一半
     *  根因，第一页序号恒对齐）。展开表内**所有**页（含透传页——expand 统一盖
     *  originalPageIdx 章，段/半页插队会让透传页流位≠原始位）都走映射；未展开表恒等。
     *  表级判定防"原始 meta 的 originalPageIdx 默认 0 被误映射"。 */
    private fun batchPageIdx(pageIndex: Int): Int {
        if (!sessionExpandedTable) return pageIndex
        val m = sessionPageList.getOrNull(pageIndex) ?: return pageIndex
        return if (m.originalPageIdx >= 0) m.originalPageIdx else pageIndex
    }

    /** 本章批量产物目录是否存在（O(1) stat，不解码不建目录）——显示链守卫用，
     *  不再依赖会话级 sessionHasBatch 布尔（冷启动误判会挂全会话） */
    private fun batchChapterOnDisk(): Boolean = try {
        SrCore.diskCache.hasBatchChapter(sessionBookTitle, SRQueueItem.chapterKey(sessionBookId, sessionTreeStr))
    } catch (_: Throwable) { false }

    // ════ 页级持久产物（2026-10-03）：实时增强成功即永久落盘，显示链最高优先 ════

    private fun artifactChapterKey(): String =
        SRQueueItem.chapterKey(sessionBookId, sessionTreeStr)

    /** 本章是否已有任何页级产物（O(1) 目录 stat）——pollForResult 守卫用 */
    private fun artifactChapterOnDisk(): Boolean = try {
        SrCore.diskCache.hasArtifactChapter(sessionBookId, artifactChapterKey())
    } catch (_: Throwable) { false }

    /** ★ 产物指纹：从 **设置** 派生（模型/倍率/降噪/黑白/引擎），与 manager.buildCacheKey 同语义
     *  但不依赖 manager 运行态——冷启动引擎未装载时 manager 的模型是 unknown，用缓存键做产物
     *  路径会在"退出重进"时算出不同键 → 产物永远 miss（2026-10-03 用户实测踩中，已改）。 */
    private fun artifactFingerprint(): String {
        val m = try {
            SrCore.settings.effectiveModel(sessionBookId)
        } catch (_: Throwable) {
            SrCore.settings.model
        }
        val bwPart = if (SrCore.settings.bwEnabled) {
            "_bw${SrCore.settings.bwGrayLevels}${if (SrCore.settings.bwDensityCorrection) "d" else ""}"
        } else ""
        // 与 manager.buildCacheKey 引擎段同口径：NPU 产物与 GPU/CPU 不混用
        val enginePart = if (SrCore.settings.engine == SrEngine.NPU) "enpu_ng" else SrCore.settings.engine.key
        return "${m.key}_${m.scale}_${SrCore.settings.denoise.key}$bwPart" + "_e$enginePart"
    }

    /** ★ 批量礼让信号（2026-10-02）：最近一次实时超分调度时间（SystemClock.elapsedRealtime）。
     *  批量服务页间检查：用户在阅读且 15 秒内有实时请求 → 让出引擎，
     *  防止"批量后台跑着、翻页实时超分排队卡顿"互相打架 */
    @Volatile
    var lastRealtimeRequestAt: Long = 0L

    // ★ 降低摩尔纹（Komiho 对齐 2026-10-07）：视图在 bind/attach 时登记视口（宽/高/是否
    //   条漫宽导向）。SR 结果入内存缓存与显示前按它高斯回缩（见 SrMoireDownscale，0.85 门）；
    //   未登记（=0）自动跳过。磁盘产物恒全尺寸
    @Volatile var viewportW: Int = 0
    @Volatile var viewportH: Int = 0
    @Volatile var viewportTall: Boolean = false

    fun noteViewport(w: Int, h: Int, tall: Boolean) {
        viewportW = w
        viewportH = h
        viewportTall = tall
    }

    /** 阅读实时超分是否活跃（批量礼让判定用）：开着作用域且最近有实时请求。
     *  看完一页停下 15 秒后即不活跃——批量继续跑，不饥饿 */
    fun realtimeActiveRecently(): Boolean =
        sessionActive && SrUiState.scope.intValue > SrUiState.SCOPE_OFF &&
            android.os.SystemClock.elapsedRealtime() - lastRealtimeRequestAt < 15_000L

    /** 四级缓存直查：★页级持久产物（永远生效，最高优先）/实时缓存/黑白独立缓存仅在作用域开启时生效；批量离线产物永远生效 */
    fun getCached(pageIndex: Int): Bitmap? {
        if (!sessionActive) return null
        // ★ 页级持久产物：任何作用域下最高优先（"只要有过且产物在就优先显示增强过的"）；
        //   指纹来自设置——改档位/降噪/引擎后自动 miss 走新键现算
        val fp = artifactFingerprint()
        SrCore.diskCache.getArtifact(sessionBookId, artifactChapterKey(), fp, pageIndex)?.let { bmp ->
            Log.i(TAG, "pc r hit p$pageIndex fp=$fp")
            // 回填内存结果缓存（同批量回填：状态指示/前后对比可见）；★摩尔纹：回缩副本进内存
            val disp = SrMoireDownscale.apply(bmp, viewportW, viewportH, viewportTall)
            try {
                SrCore.manager.putCachedResult(SrCore.manager.buildCacheKey(sessionChapterId, pageIndex), disp)
            } catch (_: Throwable) {}
            return disp
        }
        if (SrUiState.scope.intValue > SrUiState.SCOPE_OFF) {
            SrCore.dispatcher.getSrBitmap(sessionChapterId, pageIndex)?.let {
                return SrMoireDownscale.apply(it, viewportW, viewportH, viewportTall)
            }
            // "仅黑白优化"产物的独立缓存键
            if (!SrCore.settings.upscaleEnabled && SrCore.settings.bwEnabled) {
                val key = "bwonly_${sessionChapterId}_${pageIndex}_${SrCore.settings.bwGrayLevels}_${if (SrCore.settings.bwDensityCorrection) 1 else 0}"
                SrCore.manager.getCachedResult(key)?.let { return it }
                SrCore.diskCache.get(key)?.let {
                    return SrMoireDownscale.apply(it, viewportW, viewportH, viewportTall)
                }
                return null
            }
        }
        if (batchChapterOnDisk()) {
            // ★ 展开表守卫：批量产物按原始整页页序落盘，段/半页位吃到的必然是整页内容=串图
            if (slotIsWholePage(pageIndex)) {
                SrCore.diskCache.getBatchPage(sessionBookTitle, SRQueueItem.chapterKey(sessionBookId, sessionTreeStr), batchPageIdx(pageIndex))?.let { bmp ->
                    // 同步回填内存结果缓存（只进内存，不写实时磁盘区——批量产物有自己的领地，不重复占用配额）。
                    // 回填后状态指示/前后对比也能看到批量产物，不再出现"已显示超分却提示未启动"。
                    // ★摩尔纹：内存/显示走回缩副本，磁盘批量产物保持全尺寸
                    val disp = SrMoireDownscale.apply(bmp, viewportW, viewportH, viewportTall)
                    try {
                        SrCore.manager.putCachedResult(
                            SrCore.manager.buildCacheKey(sessionChapterId, pageIndex), disp,
                        )
                    } catch (_: Throwable) {}
                    return disp
                }
            }
        }
        return null
    }

    /** 主入口：有缓存返回超分图；未命中则按作用域决定是否后台调度（完成后走 readyEvents 原位替换） */
    fun attachOrSchedule(pageIndex: Int, callerBitmap: Bitmap?): Bitmap? =
        attachOrScheduleInternal(pageIndex, callerBitmap, allowSyncDisk = true)

    /** ★ F4.6：UI 线程专用入口。内存命中同步返回；磁盘/批量产物命中【不解码】——
     *  只 stat 文件确认存在，命中交给 pollForResult 在 IO 回填；完全未命中才调度。
     *  杜绝 onBindViewHolder 主线程 decodeFile 大 webw/jpg 掉帧。 */
    fun attachOrScheduleFast(pageIndex: Int, callerBitmap: Bitmap?): Bitmap? =
        attachOrScheduleInternal(pageIndex, callerBitmap, allowSyncDisk = false)

    private fun attachOrScheduleInternal(pageIndex: Int, callerBitmap: Bitmap?, allowSyncDisk: Boolean): Bitmap? {
        if (!sessionActive || !SrCore.initialized) return null
        val meta = sessionPageList.getOrNull(pageIndex) ?: return null
        if (!isApplicable(meta) || callerBitmap == null) return null

        val cid = sessionChapterId
        // 烘焙图（纸纹副本）不得注册为超分原图：缓存/批量产物路径传来的可能是烘焙副本，
        // 误注册后重算以它为源 = 纹理平方（明显加深）
        if (!com.mangareader.filter.PaperPageTexture.isBaked(callerBitmap)) {
            synchronized(originals) { originals.putIfAbsent("$cid:$pageIndex", callerBitmap) }
        }
        lastUserPage = pageIndex

        if (allowSyncDisk) {
            getCached(pageIndex)?.let { return it }
        } else {
            // 内存命中（快，不碰磁盘）
            if (SrUiState.scope.intValue > SrUiState.SCOPE_OFF) {
                SrCore.manager.getCachedResult(SrCore.manager.buildCacheKey(cid, pageIndex))?.let { return it }
                if (!SrCore.settings.upscaleEnabled && SrCore.settings.bwEnabled) {
                    val key = "bwonly_${cid}_${pageIndex}_${SrCore.settings.bwGrayLevels}_${if (SrCore.settings.bwDensityCorrection) 1 else 0}"
                    SrCore.manager.getCachedResult(key)?.let { return it }
                }
            }
            // ★ 页级持久产物守卫（2026-10-03）：设置派生指纹 + O(1) stat，命中即交给
            //   pollForResult 在 IO 解码回填——任何作用域下最高优先显示，且不重复实时烧算力
            if (SrCore.diskCache.hasArtifact(sessionBookId, artifactChapterKey(), artifactFingerprint(), pageIndex)) {
                pollForResult(pageIndex) { b -> SrCore.emitReady(cid, pageIndex, b) }
                return null
            }
            // ★ 批量产物守卫改 stat-only（2026-10-02）：目录存在 → 交给 pollForResult 在 IO
            //   解码回填。原实现在此同步调 getCached → getBatchPage 主线程解码大图，
            //   违反 Fast 路径"主线程零解码"设计原则（F4.6）；事件注入不可靠（SharedFlow
            //   无 replay），轮询从来就是兜底主通道。
            if (batchChapterOnDisk()) {
                pollForResult(pageIndex) { b -> SrCore.emitReady(cid, pageIndex, b) }
                return null
            }
            // 磁盘已有实时结果？只 stat 文件不解码；命中交给 pollForResult 异步回填
            val diskLikely = runCatching {
                SrUiState.scope.intValue > SrUiState.SCOPE_OFF &&
                    SrCore.diskCache.contains(SrCore.manager.buildCacheKey(cid, pageIndex))
            }.getOrDefault(false)
            if (diskLikely) {
                pollForResult(pageIndex) { b -> SrCore.emitReady(cid, pageIndex, b) }
                return null
            }
        }

        // 已有离线产物的章节不再实时超分（直接吃产物；产物缺的页就显示原图）——
        //   守卫改 stat（2026-10-02），防会话布尔误判时对已批量页重复实时烧算力
        if (batchChapterOnDisk()) return null

        // ★ F4.7③：省电模式/低 RAM 设备自动停用"新起"的实时超分（缓存/批量产物照常吃）
        if (SrCore.shouldThrottleRealtimeSr()) return null

        val scope = SrUiState.scope.intValue
        if (scope == SrUiState.SCOPE_OFF) return null
        if (scope == SrUiState.SCOPE_PAGE && pageIndex != currentPageIdx) return null

        scheduleInternal(pageIndex, callerBitmap)
        return null
    }

    /** 设置变更后：按新配置重算当前页（新缓存键不串旧图） */
    fun onSettingsChanged() {
        val p = lastUserPage
        if (p < 0 || !sessionActive) return
        if (SrUiState.scope.intValue == SrUiState.SCOPE_OFF) return
        val orig = synchronized(originals) { originals["$sessionChapterId:$p"] } ?: return
        scheduleInternal(p, orig)
    }

    /** 自动测速/面板采样：拿"最近一次用户路径的图"，不追到原页 */
    fun sampleSource(): Bitmap? {
        if (lastUserPage >= 0) return peekOriginal(lastUserPage)
        if (currentPageIdx > 0) return peekOriginal(currentPageIdx - 1)
        return null
    }

    /** UI 状态：当前页是不是在运算中 */
    fun computing(pageIndex: Int): Boolean =
        synchronized(SrUiState.computingPages) { pageIndex in SrUiState.computingPages }

    /** 作用域被切回"关闭"：把当前页换回注入前的原图。
     *  ★ 页级产物优先（2026-10-03）：已增强过的页即使关作用域也显示持久产物
     *  （"只要有过且产物在就优先"），无产物才回原图。 */
    fun revertCurrentToOriginal() {
        val p = lastUserPage
        if (p < 0 || !sessionActive) return
        val fp = artifactFingerprint()
        if (SrCore.diskCache.hasArtifact(sessionBookId, artifactChapterKey(), fp, p)) {
            scope.launch(Dispatchers.IO) {
                SrCore.diskCache.getArtifact(sessionBookId, artifactChapterKey(), fp, p)?.let {
                    SrCore.emitReady(sessionChapterId, p, SrMoireDownscale.apply(it, viewportW, viewportH, viewportTall))
                }
            }
            return
        }
        val orig = synchronized(originals) { originals["$sessionChapterId:$p"] } ?: return
        if (!orig.isRecycled) {
            SrCore.emitReady(sessionChapterId, p, orig)
        }
    }

    /** "重算本页"（面板手动按钮）：已有结果→补发注入事件立刻替换；没有→清在途标记强制重算。
     *  显式用户动作，绕过 batch 章节跳过策略与作用域守卫（调用方 UI 已在作用域关闭时灰掉）。
     *  ★ 诚实化（2026-10-03）：此前异步任务里有 7 个静默退出点（主开关没开/页太小/超输入上限/
     *  动图/翻走等），用户看到"已开始重算"实际没开始。现把可同步判定的分支全部前置到点击时刻，
     *  按真实情况返回不同文案，杜绝"提示已开始、实际没开始"。 */
    fun forceRetryCurrentPage(): String {
        if (!sessionActive) return "当前没有打开的书"
        if (sessionPageList.isEmpty()) return "没有可用页"
        // ★ 定位修正（2026-10-02）：currentPageIdx 接线后是真实显示页；lastUserPage 兜底
        //   （它会被左右相邻页的预绑定污染 ±2，只做退路）
        val p = sequenceOf(currentPageIdx, lastUserPage)
            .firstOrNull { it in sessionPageList.indices }
            ?: return "没有可用页"
        val meta = sessionPageList.getOrNull(p)
        if (meta != null && !isApplicable(meta)) return "本页是动图，不支持增强"
        if (!SrCore.settings.upscaleEnabled && !SrCore.settings.bwEnabled) {
            return "画质增强未启用：先打开「启用画质增强」或「黑白增强」"
        }
        val key = "$sessionChapterId:$p"
        if (inFlight.contains(key)) return "本页正在处理中，请稍候"
        val cached = getCached(p)
        if (cached != null && !cached.isRecycled) {
            SrCore.emitReady(sessionChapterId, p, cached)
            return "本页已有增强结果，已刷新显示"
        }
        val orig = synchronized(originals) { originals[key] }
        if (orig != null && !orig.isRecycled) {
            if (orig.width < 100 || orig.height < 100) return "本页图片太小，无需增强"
            // 与 scheduleInternal 同口径的输入上限（1x 放宽 8192；4x 收 2048；2x 4096）
            val inputCap = when {
                SrCore.manager.currentScale <= 1 -> 8192
                SrCore.manager.currentScale >= 4 -> 2048
                else -> 4096
            }
            if (maxOf(orig.width, orig.height) > inputCap) {
                return "本页原图超出当前档位的处理上限"
            }
        }
        inFlight.remove(key)
        scheduleInternal(p, orig, force = true)
        return "已开始重算本页，完成后自动替换"
    }

    private fun scheduleInternal(pageIndex: Int, callerBitmap: Bitmap?, force: Boolean = false) {
        if (sessionHasBatch && !force) return
        val cid = sessionChapterId
        val key = "$cid:$pageIndex"
        // ★ 批量礼让信号源：实时调度即刷新（翻页持续更新=批量持续让位）
        lastRealtimeRequestAt = android.os.SystemClock.elapsedRealtime()
        if (!inFlight.add(key)) return
        synchronized(SrUiState.computingPages) { SrUiState.computingPages.add(pageIndex) }
        SrUiState.bumpSrTick()

        // ★ 仅当前页模式：本页是否已让位（翻页后旧任务开跑前自检放弃，避免空转占引擎）
        fun staleForPageMode(): Boolean =
            SrUiState.scope.intValue == SrUiState.SCOPE_PAGE && pageIndex != currentPageIdx

        val job = scope.launch(Dispatchers.IO) {
            try {
                if (staleForPageMode()) return@launch

                val original = callerBitmap ?: run {
                    val eng = engine ?: return@run null
                    val meta = sessionPageList.getOrNull(pageIndex) ?: return@run null
                    if (!isApplicable(meta)) return@run null
                    // ★ 展开表守卫：段/半页位自动加载拿到的是整页原图（loadPage 不切段），
                    //   产物按位次灌回段位=串图；只处理视图传来的切好内容
                    if (meta.segmentIndex > 0 || meta.splitSide != 0) return@run null
                    eng.loadPage(meta, sessionTreeStr)
                }
                if (original == null || original.isRecycled) return@launch

                // ★ 修复对比失效：callerBitmap 来自视图 bitmapCache，超分完成后视图替换 bitmap 并
                //   recycle 旧的，originals 中存的引用变毒图。存独立 copy 不被视图持有，不会被 recycle。
                if (callerBitmap != null) {
                    runCatching {
                        val indep = original.copy(original.config ?: Bitmap.Config.ARGB_8888, false)
                        synchronized(originals) { originals["$cid:$pageIndex"] = indep }
                    }
                } else {
                    synchronized(originals) { originals["$cid:$pageIndex"] = original }
                }

                if (original.width < 100 || original.height < 100) return@launch
                // ★ 1x 档（快速优化/waifu2x 清理）输出不放大，输入上限放宽到 8192；
                //   F1.2：4x 档收 2048（约 3.1Mpx 输入，防 16384² 输出瞬间 OOM），2x 维持 4096
                val inputCap = when {
                    SrCore.manager.currentScale <= 1 -> 8192
                    SrCore.manager.currentScale >= 4 -> 2048
                    else -> 4096
                }
                if (maxOf(original.width, original.height) > inputCap) return@launch

                val upscaleOn = SrCore.settings.upscaleEnabled
                val bwOn = SrCore.settings.bwEnabled
                if (!upscaleOn && !bwOn) return@launch
                if (staleForPageMode()) return@launch

                if (upscaleOn) {
                    // A. 超分放大（可选叠加黑白后处理；黑白对彩页自动跳过）
                    val bwForceOff = bwOn && !isGrayscaleish(original)
                    val version = SrCore.manager.currentModelVersion()
                    // 看门狗：推理前打标记（崩溃后下次启动强制 CPU 兜底）
                    if (!SrCore.inferenceInFlight) SrCore.markInferenceStart()
                    // 等待引擎互斥锁前：若引擎正被别的页占用且当前是"仅当前页"模式，
                    // 对在途推理发中止信号（引擎逐瓦片检查，~一瓦片内停下让位）
                    val runningNow = synchronized(runningKeyLock) { runningKey }
                    if (runningNow != null && runningNow != key &&
                        SrUiState.scope.intValue == SrUiState.SCOPE_PAGE
                    ) {
                        SrCore.manager.abortInFlight()
                    }
                    synchronized(runningKeyLock) { runningKey = key }
                    var result = SrCore.manager.process(original, version, bwForceOff)
                    synchronized(runningKeyLock) { if (runningKey == key) runningKey = null }
                    SrCore.markInferenceEnd()
                    // ★ F4.7②：GPU 失败熔断计数（仅版本未变的真实结果才计；切档时管理器自动重置）
                    if (version == SrCore.manager.currentModelVersion()) {
                        SrCore.manager.recordInferenceOutcome(result === original)
                    }
                    if (result === original || result.isRecycled) return@launch

                    if (isFullyTransparent(result)) {
                        Log.w(TAG, "discard transparent result p$pageIndex")
                        result.recycle()
                        return@launch
                    }

                    val longest = maxOf(result.width, result.height)
                    if (longest > maxOutputEdge) {
                        val ratio = maxOutputEdge.toFloat() / longest
                        val w = (result.width * ratio).toInt().coerceAtLeast(1)
                        val h = (result.height * ratio).toInt().coerceAtLeast(1)
                        val capped = Bitmap.createScaledBitmap(result, w, h, true)
                        if (capped != result) result.recycle()
                        result = capped
                    }

                    // ★摩尔纹：内存缓存与注入事件用回缩副本（ IO 线程内完成，无主线程开销），
                    //   磁盘实时缓存与页级持久产物（下方 putArtifact）保持全尺寸 result
                    val display = SrMoireDownscale.apply(result, viewportW, viewportH, viewportTall)
                    SrCore.dispatcher.putSrResult(cid, pageIndex, result, display)
                    SrCore.emitReady(cid, pageIndex, display)
                    // ★ 页级持久产物（2026-10-03）：实时结果永久落盘（IO 线程）。
                    //   键含完整指纹——同键覆盖，改设置自动走新键；超总量上限自动清最旧。
                    //   批量章节不落这里（batchChapterOnDisk 时 scheduleInternal 不会跑到这）。
                    if (sessionBookId >= 0) {
                        runCatching {
                            SrCore.diskCache.putArtifact(
                                sessionBookId, artifactChapterKey(), artifactFingerprint(), pageIndex, result,
                            )
                        }
                    }
                } else {
                    // B. 仅黑白优化（不放大，原尺寸毫秒级；彩色页直接跳过）
                    if (!isGrayscaleish(original)) return@launch
                    val result = SrBwProcessor.process(
                        original,
                        SrCore.settings.bwGrayLevels,
                        SrCore.settings.bwDensityCorrection,
                    ) ?: return@launch
                    if (result.isRecycled) return@launch

                    val key = "bwonly_${cid}_${pageIndex}_${SrCore.settings.bwGrayLevels}_${if (SrCore.settings.bwDensityCorrection) 1 else 0}"
                    SrCore.manager.putCachedResult(key, result)
                    SrCore.diskCache.put(key, result)
                    SrCore.emitReady(cid, pageIndex, result)
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // 正常取消（翻书/退出/切模型），静默
            } catch (e: Exception) {
                Log.e(TAG, "schedule p$pageIndex failed", e)
            } finally {
                inFlight.remove(key)
                pageJobs.remove(key)
                synchronized(SrUiState.computingPages) { SrUiState.computingPages.remove(pageIndex) }
                SrUiState.bumpSrTick()
            }
        }
        pageJobs[key] = job
    }

    /** 抽样判定是否近乎灰度图（彩色像素占比 >0.5% 即视为彩页） */
    private fun isGrayscaleish(b: Bitmap): Boolean {
        if (b.width < 8 || b.height < 8) return true
        val stepX = b.width / 8
        val stepY = b.height / 8
        var colored = 0
        var sampled = 0
        for (x in 0 until 8) {
            for (y in 0 until 8) {
                val px = b.getPixel(x * stepX, y * stepY)
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val bl = px and 0xFF
                val mx = maxOf(r, g, bl)
                val mn = minOf(r, g, bl)
                if (mx - mn > 16) colored++
                sampled++
            }
        }
        return sampled == 0 || colored.toFloat() / sampled <= 0.005f
    }

    private fun isFullyTransparent(b: Bitmap): Boolean {
        if (b.width < 8 || b.height < 8) return false
        val stepX = b.width / 8
        val stepY = b.height / 8
        for (x in 0 until 8) {
            for (y in 0 until 8) {
                val px = b.getPixel(x * stepX, y * stepY)
                if ((px ushr 24) != 0) return false
            }
        }
        return true
    }

    fun currentChapterKey(): String = SRQueueItem.chapterKey(sessionBookId, sessionTreeStr)
    fun currentBookTitle(): String = sessionBookTitle

    fun clearMemoryCacheOnly() {
        SrCore.manager.clearSrCache()
    }
}
