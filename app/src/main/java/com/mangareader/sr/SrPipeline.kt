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
    private var sessionHasBatch = false

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

    /** 作用域切换：关闭=取消在跑任务并把当前页换回原图；开启=按当前设置立刻算当前页并按需预载 */
    fun onScopeChanged(newScope: Int) {
        if (newScope == SrUiState.SCOPE_OFF) {
            SrCore.manager.cancelProcessingJobs()
            revertCurrentToOriginal()
            return
        }
        SrCore.applySettings(sessionBookId)
        onPageChanged(currentPageIdx, sessionPageList.size)
        if (sessionActive) {
            synchronized(originals) { originals["$sessionChapterId:$currentPageIdx"] }
                ?.let { scheduleInternal(currentPageIdx, it) }
        }
    }

    /** 用户侧最近请求页（改设置后重算它） */
    @Volatile
    private var lastUserPage: Int = -1

    /** 前后对比用：超分前的原图（最多 8 页） */
    private val originals = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean = size > 8
    }

    fun peekOriginal(pageIndex: Int): Bitmap? = synchronized(originals) { originals["$sessionChapterId:$pageIndex"] }

    /** 对比叠层"右侧"取图：超分结果优先；黑白独立模式下退而求其次取黑白优化结果 */
    fun peekSr(pageIndex: Int): Bitmap? {
        SrCore.dispatcher.getSrBitmap(sessionChapterId, pageIndex)?.let { return it }
        if (!SrCore.settings.upscaleEnabled && SrCore.settings.bwEnabled) {
            val key = "bwonly_${sessionChapterId}_${pageIndex}_${SrCore.settings.bwGrayLevels}_${if (SrCore.settings.bwDensityCorrection) 1 else 0}"
            SrCore.manager.getCachedResult(key)?.let { return it }
            return SrCore.diskCache.get(key)
        }
        return null
    }

    /** 输出封顶（条漫/超大图 OOM 防线）：屏幕最长边 2 倍，夹在 [2048, 8192] */
    @Volatile
    var maxOutputEdge: Int = 4096
        private set

    fun beginSession(context: Context, treeStr: String, bookId: Long, bookTitle: String, pages: List<ArchiveEngine.PageMeta>) {
        if (engine == null || sessionBookId != bookId || sessionTreeStr != treeStr) {
            engine = ArchiveEngine(context.applicationContext)
        }
        sessionChapterId = stableChapterId(treeStr)
        sessionBookId = bookId
        sessionTreeStr = treeStr
        sessionBookTitle = bookTitle
        sessionPageList = pages
        sessionActive = true
        sessionHasBatch = try {
            SrCore.initialized &&
                SrCore.diskCache.hasBatchChapter(bookTitle, SRQueueItem.chapterKey(bookId, treeStr))
        } catch (_: Throwable) {
            false
        }
        synchronized(SrUiState.computingPages) { SrUiState.computingPages.clear() }
        cancelScope()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        lastUserPage = -1
        currentPageIdx = 0

        // 作用域按需求每次进书默认关闭（手动开启才实时超分；离线批量产物不受影响）
        SrUiState.resetForBook(bookId, bookTitle)

        val dm = context.resources.displayMetrics
        maxOutputEdge = (maxOf(dm.widthPixels, dm.heightPixels) * 2).coerceIn(2048, 8192)

        if (!SrCore.initialized) SrCore.init(context)
        // 注入真实预载取料（修复源项目缺口：回调接管后真正做到"主动预载"）
        SrCore.dispatcher.onPreloadRequested = { chapterId, pageIndex ->
            if (chapterId == sessionChapterId && sessionActive) {
                scheduleInternal(pageIndex, callerBitmap = null)
            }
        }
        Log.i(TAG, "session begin: cid=$sessionChapterId book=$bookId maxEdge=$maxOutputEdge pages=${pages.size}")

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
        // ★ F4.6：批量产物"永远生效"——作用域/开关关闭时也要为批量章节轮询回填
        if (SrUiState.scope.intValue == SrUiState.SCOPE_OFF && !sessionHasBatch) return
        if (!SrCore.settings.upscaleEnabled && !SrCore.settings.bwEnabled && !sessionHasBatch) return
        val key = "$sessionChapterId:$pageIndex"
        if (!pollKeys.add(key)) return
        scope.launch(Dispatchers.IO) {
            try {
                repeat(60) {
                    if (!sessionActive) return@launch
                    val hit = getCached(pageIndex)
                    if (hit != null && !hit.isRecycled) {
                        onReady(hit)
                        return@launch
                    }
                    delay(800)
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

    /** 该页是否适合超分（动图/PDF 跳过） */
    fun isApplicable(meta: ArchiveEngine.PageMeta): Boolean {
        if (meta.source == "pdf") return false
        return when (meta.ext.lowercase()) {
            "gif", "apng" -> false
            else -> true
        }
    }

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
        if (SrUiState.scope.intValue == SrUiState.SCOPE_BOOK) {
            SrCore.dispatcher.onPageChanged(sessionChapterId, centerIdx, totalPages)
        }
    }

    /** 三级缓存直查：实时缓存/黑白独立缓存仅在作用域开启时生效；批量离线产物永远生效 */
    fun getCached(pageIndex: Int): Bitmap? {
        if (!sessionActive) return null
        if (SrUiState.scope.intValue > SrUiState.SCOPE_OFF) {
            SrCore.dispatcher.getSrBitmap(sessionChapterId, pageIndex)?.let { return it }
            // "仅黑白优化"产物的独立缓存键
            if (!SrCore.settings.upscaleEnabled && SrCore.settings.bwEnabled) {
                val key = "bwonly_${sessionChapterId}_${pageIndex}_${SrCore.settings.bwGrayLevels}_${if (SrCore.settings.bwDensityCorrection) 1 else 0}"
                SrCore.manager.getCachedResult(key)?.let { return it }
                return SrCore.diskCache.get(key)
            }
        }
        val chKey = SRQueueItem.chapterKey(sessionBookId, sessionTreeStr)
        if (SrCore.diskCache.hasBatchChapter(sessionBookTitle, chKey)) {
            SrCore.diskCache.getBatchPage(sessionBookTitle, chKey, pageIndex)?.let { bmp ->
                // 同步回填内存结果缓存（只进内存，不写实时磁盘区——批量产物有自己的领地，不重复占用配额）。
                // 回填后状态指示/前后对比也能看到批量产物，不再出现"已显示超分却提示未启动"。
                try {
                    SrCore.manager.putCachedResult(
                        SrCore.manager.buildCacheKey(sessionChapterId, pageIndex), bmp,
                    )
                } catch (_: Throwable) {}
                return bmp
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
        synchronized(originals) { originals["$cid:$pageIndex"] = callerBitmap }
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
            // 磁盘已有结果？只 stat 文件不解码；命中（含批量产物页）交给 pollForResult 异步回填
            val diskLikely = runCatching {
                (SrUiState.scope.intValue > SrUiState.SCOPE_OFF &&
                    SrCore.diskCache.contains(SrCore.manager.buildCacheKey(cid, pageIndex))) || sessionHasBatch
            }.getOrDefault(false)
            if (diskLikely) {
                pollForResult(pageIndex) { b -> SrCore.emitReady(cid, pageIndex, b) }
                return null
            }
        }

        // 已有离线产物的章节不再实时超分（直接吃产物；产物缺的页就显示原图）
        if (sessionHasBatch) return null

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

    /** 作用域被切回"关闭"：把当前页换回注入前的原图 */
    fun revertCurrentToOriginal() {
        val p = lastUserPage
        if (p < 0 || !sessionActive) return
        val orig = synchronized(originals) { originals["$sessionChapterId:$p"] } ?: return
        if (!orig.isRecycled) {
            SrCore.emitReady(sessionChapterId, p, orig)
        }
    }

    /** "重算本页"（面板手动按钮）：已有结果→补发注入事件立刻替换；没有→清在途标记强制重算。
     *  显式用户动作，绕过 batch 章节跳过策略与作用域守卫（调用方 UI 已在作用域关闭时灰掉）。 */
    fun forceRetryCurrentPage(): String {
        if (!sessionActive) return "当前没有打开的书"
        if (sessionPageList.isEmpty()) return "没有可用页"
        val p = currentPageIdx.coerceIn(0, sessionPageList.size - 1)
        val cached = getCached(p)
        if (cached != null && !cached.isRecycled) {
            SrCore.emitReady(sessionChapterId, p, cached)
            return "本页已有超分结果，已刷新显示"
        }
        inFlight.remove("$sessionChapterId:$p")
        val orig = synchronized(originals) { originals["$sessionChapterId:$p"] }
        scheduleInternal(p, orig, force = true)
        return "已开始重算本页，完成后自动替换"
    }

    private fun scheduleInternal(pageIndex: Int, callerBitmap: Bitmap?, force: Boolean = false) {
        if (sessionHasBatch && !force) return
        val cid = sessionChapterId
        val key = "$cid:$pageIndex"
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
                    eng.loadPage(meta, sessionTreeStr)
                }
                if (original == null || original.isRecycled) return@launch

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

                    SrCore.dispatcher.putSrResult(cid, pageIndex, result)
                    SrCore.emitReady(cid, pageIndex, result)
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
