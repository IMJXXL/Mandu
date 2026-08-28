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

    /** 用户正在看的页（由阅读器 onPageChanged 钩子喂入） */
    @Volatile
    var currentPageIdx: Int = 0
        private set

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

    /** 阅读器翻页钩子：更新当前页，且只有"整本"作用域才做前后预载 */
    fun onPageChanged(centerIdx: Int, totalPages: Int) {
        currentPageIdx = centerIdx
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
            SrCore.diskCache.getBatchPage(sessionBookTitle, chKey, pageIndex)?.let {
                return it
            }
        }
        return null
    }

    /** 主入口：有缓存返回超分图；未命中则按作用域决定是否后台调度（完成后走 readyEvents 原位替换） */
    fun attachOrSchedule(pageIndex: Int, callerBitmap: Bitmap?): Bitmap? {
        if (!sessionActive || !SrCore.initialized) return null
        val meta = sessionPageList.getOrNull(pageIndex) ?: return null
        if (!isApplicable(meta) || callerBitmap == null) return null

        val cid = sessionChapterId
        synchronized(originals) { originals["$cid:$pageIndex"] = callerBitmap }
        lastUserPage = pageIndex

        getCached(pageIndex)?.let { return it }

        // 已有离线产物的章节不再实时超分（直接吃产物；产物缺的页就显示原图）
        if (sessionHasBatch) return null

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

    private fun scheduleInternal(pageIndex: Int, callerBitmap: Bitmap?) {
        if (sessionHasBatch) return
        val cid = sessionChapterId
        val key = "$cid:$pageIndex"
        if (!inFlight.add(key)) return
        synchronized(SrUiState.computingPages) { SrUiState.computingPages.add(pageIndex) }

        scope.launch(Dispatchers.IO) {
            try {
                val original = callerBitmap ?: run {
                    val eng = engine ?: return@run null
                    val meta = sessionPageList.getOrNull(pageIndex) ?: return@run null
                    if (!isApplicable(meta)) return@run null
                    eng.loadPage(meta, sessionTreeStr)
                }
                if (original == null || original.isRecycled) return@launch

                if (original.width < 100 || original.height < 100) return@launch
                if (maxOf(original.width, original.height) > 4096) return@launch

                val upscaleOn = SrCore.settings.upscaleEnabled
                val bwOn = SrCore.settings.bwEnabled
                if (!upscaleOn && !bwOn) return@launch

                if (upscaleOn) {
                    // A. 超分放大（可选叠加黑白后处理；黑白对彩页自动跳过）
                    val bwForceOff = bwOn && !isGrayscaleish(original)
                    // NPU 优先（仅默认铺底模型有离线 context；其它模型回落 Vulkan/CPU）
                    var result: Bitmap? = null
                    val activeModelKey = SrCore.manager.activeModel?.key ?: "realcugan_2x_conservative"
                    val npuModelKey = if (activeModelKey == "realcugan_2x_conservative") activeModelKey else null
                    if (npuModelKey != null &&
                        SrNpuProcessor.check(SrCore.appContext) &&
                        SrNpuProcessor.activate(SrCore.appContext, npuModelKey)) {
                        // 与 Vulkan 一样打看门狗标记；崩溃后自动把 NPU 拉黑
                        if (!SrCore.inferenceInFlight) SrCore.markInferenceStart()
                        result = SrNpuProcessor.process(original)
                        SrCore.markInferenceEnd()
                    }
                    if (result == null || result.isRecycled) {
                        val version = SrCore.manager.currentModelVersion()
                        // 看门狗：推理前打标记（崩溃后下次启动强制 CPU 兜底）
                        if (!SrCore.inferenceInFlight) SrCore.markInferenceStart()
                        result = SrCore.manager.process(original, version, bwForceOff)
                        SrCore.markInferenceEnd()
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
                synchronized(SrUiState.computingPages) { SrUiState.computingPages.remove(pageIndex) }
            }
        }
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
