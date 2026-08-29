package com.mangareader.sr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 超分引擎管理器（移植自 mihon-super-resolution，日志改为 android.util.Log）。
 * 单例语义由 SrCore 保证。所有 process 走 mutex 串行（GPU 独占）。
 */
class SuperResolutionManager(
    private val context: Context,
) {
    private val mutex = Mutex()
    private val cacheLock = Any()
    private val maxCacheSize = 32

    private val srCache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean {
            return size > maxCacheSize
        }
    }

    private var currentProcessor: SuperResolutionProcessor? = null
    private var currentModel: SRModel? = null
    var currentScale: Int = 2
        private set
    private var currentDenoiseLevel: DenoiseLevel = DenoiseLevel.LIGHT

    @Volatile
    var currentBwConfig: MangaBWPostProcessConfig? = null
        private set

    /** 熔断：native 崩溃过一次后强制 CPU，直到用户手动恢复 GPU */
    @Volatile
    var forceCpu: Boolean = false

    // ★ F4.7②：GPU 连续失败熔断（推理连续 3 次原样返回 → 本次会话强制 CPU，切档自动解除）
    @Volatile
    private var gpuFused: Boolean = false
    private val gpuFailStreak = java.util.concurrent.atomic.AtomicInteger(0)

    private fun gpuCurrentlyUsed(): Boolean =
        !forceCpu && !gpuFused && SrCore.settings.enableGpu && isVulkanAvailable && currentModel?.requiresVulkan == true

    /** 每次真实推理（版本未变的）结束后由管线回报结果；returnedInput=true 视为一次失败 */
    fun recordInferenceOutcome(returnedInput: Boolean) {
        if (!gpuCurrentlyUsed()) { gpuFailStreak.set(0); return }
        if (returnedInput) {
            val n = gpuFailStreak.incrementAndGet()
            if (n >= 3) {
                gpuFused = true
                gpuFailStreak.set(0)
                Log.w(TAG, "GPU 连续失败 3 次，本会话熔断强制 CPU（重新切档自动恢复）")
            }
        } else {
            gpuFailStreak.set(0)
        }
    }

    private var processingJobs = mutableListOf<Job>()
    private val jobsMutex = Any()

    @Volatile
    private var modelVersion: Long = 0

    @Volatile
    private var pendingModelKey: String? = null

    var onModelSwitching: (() -> Unit)? = null

    val isReady: Boolean
        get() = currentProcessor?.isReady == true

    val activeModel: SRModel?
        get() = currentModel

    val activeScale: Int
        get() = currentScale

    val activeDenoiseLevel: DenoiseLevel
        get() = currentDenoiseLevel

    val isVulkanAvailable: Boolean by lazy {
        VulkanHelper.isVulkanSupported(context) && VulkanHelper.gpuCount > 0
    }

    fun getCacheUsage(): Pair<Int, Long> = synchronized(cacheLock) {
        srCache.size to srCache.values.sumOf { it.allocationByteCount.toLong() }
    }

    fun getCachedResult(cacheKey: String): Bitmap? = synchronized(cacheLock) {
        val bmp = srCache[cacheKey]
        if (bmp != null && bmp.isRecycled) {
            // ★ F1.1 毒图防护：结果实例同时被视图缓存持有，视图侧 clearBitmapCacheRecycling/trim
            //   recycle 后这里若原样返回会二次分发毒图（画布崩溃/调度卡死）——命中即剔除按未命中处理
            srCache.remove(cacheKey)
            null
        } else bmp
    }

    fun putCachedResult(cacheKey: String, bitmap: Bitmap) = synchronized(cacheLock) {
        srCache[cacheKey] = bitmap
    }

    fun removeCachedResult(cacheKey: String) = synchronized(cacheLock) {
        srCache.remove(cacheKey)
    }

    fun clearSrCache() = synchronized(cacheLock) {
        srCache.clear()
    }

    fun registerProcessingJob(job: Job) {
        synchronized(jobsMutex) { processingJobs.add(job) }
        job.invokeOnCompletion {
            synchronized(jobsMutex) { processingJobs.remove(job) }
        }
    }

    fun cancelProcessingJobs() {
        synchronized(jobsMutex) {
            processingJobs.forEach { it.cancel() }
            processingJobs.clear()
        }
    }

    suspend fun switchModel(
        model: SRModel,
        scale: Int = 2,
        denoiseLevel: DenoiseLevel = DenoiseLevel.LIGHT,
        bwConfig: MangaBWPostProcessConfig? = null,
    ) = mutex.withLock {
        Log.i(TAG, "SR: switchModel model=${model.key}, scale=$scale, denoise=$denoiseLevel")

        if (currentModel == model && currentScale == scale && !model.hasDenoiseVariants &&
            currentProcessor?.isReady == true) {
            // 引擎不变，仅更新降噪/黑白配置（实时生效，无需重新加载模型）
            currentDenoiseLevel = denoiseLevel
            currentBwConfig = bwConfig
            return@withLock
        }

        pendingModelKey = model.key
        cancelProcessingJobs()
        onModelSwitching?.invoke()
        // ★ F4.7②：切档解除本会话的 GPU 失败熔断（看门狗 forceCpu 不受影响，仍等用户手动恢复）
        gpuFused = false
        gpuFailStreak.set(0)
        currentProcessor?.release()
        currentProcessor = null
        currentModel = model
        currentDenoiseLevel = denoiseLevel
        currentBwConfig = bwConfig
        currentScale = scale
        clearSrCache()
        modelVersion++
    }

    private val loadMutex = Mutex()

    suspend fun ensureModelLoaded() {
        if (pendingModelKey != null && currentProcessor == null) {
            loadMutex.withLock {
                if (pendingModelKey != null && currentProcessor == null) {
                    loadPendingModel()
                }
            }
        }
    }

    private suspend fun loadPendingModel() {
        val model = SRModel.fromKey(pendingModelKey!!)
        val processor = createProcessor(model)
        withContext(Dispatchers.IO) {
            // 幂等保底：模型文件未解包时先解包再加载（首次启动如果 applySettings 先于后台解包也能用）
            try { ensureModelsExtracted() } catch (_: Exception) {}
            val modelPath = getModelPath(model)
            try {
                // 规则：用户没手动开 GPU 就用 CPU（默认更安全）；崩溃防护已加 forceCpu 逻辑
                val useGpu = com.mangareader.sr.SrCore.settings.enableGpu && isVulkanAvailable && model.requiresVulkan
                val gpuid = if (!forceCpu && !gpuFused && useGpu) 0 else -1
                val loadStart = System.currentTimeMillis()
                processor.initialize(modelPath, gpuid)
                currentProcessor = processor
                currentModel = model
                Log.i(TAG, "SR: ${model.key} loaded in ${System.currentTimeMillis() - loadStart}ms, ready=${processor.isReady}, gpu=${gpuid})")
            } catch (e: Exception) {
                Log.e(TAG, "SR: init ${model.key} failed, fallback NoOp: ${e.message}")
                try { processor.release() } catch (_: Exception) {}
                currentProcessor = NoOpProcessor()
                currentModel = model
            }
        }
        pendingModelKey = null
    }

    suspend fun process(input: Bitmap, versionAtStart: Long, bwForceOff: Boolean = false): Bitmap = mutex.withLock {
        ensureModelLoaded()

        // ★ 快速优化档（algorithm）：纯 CPU 经典算法（双边滤波+锐化），不经 ncnn、不看 Vulkan。
        //   任何失败回退原图，绝不空白。
        if (currentModel?.modelType == "algorithm") {
            if (versionAtStart != modelVersion) return@withLock input
            val strength = when (currentDenoiseLevel) {
                DenoiseLevel.OFF -> 0
                DenoiseLevel.LIGHT -> 1
                DenoiseLevel.STRONG -> 2
            }
            return@withLock withContext(Dispatchers.IO) {
                // 与 SR 路径同语义：bwForceOff 已编码"彩色页跳过"，灰阶级数>0 才应用黑白量化
                val bwForThisCall = if (bwForceOff) MangaBWPostProcessConfig(0, false) else currentBwConfig
                val cleaned = try { SrCleanupProcessor.process(input, strength) } catch (_: Throwable) { null }
                val finalBmp = if (cleaned != null && bwForThisCall != null && bwForThisCall.grayLevels > 0) {
                    try { SrBwProcessor.process(cleaned, bwForThisCall.grayLevels, bwForThisCall.densityCorrection) ?: cleaned } catch (_: Throwable) { cleaned }
                } else cleaned
                finalBmp ?: input
            }
        }

        val processor = currentProcessor
        if (processor == null || !processor.isReady) {
            return input
        }
        if (versionAtStart != modelVersion) {
            return input
        }

        withContext(Dispatchers.IO) {
            try {
                withTimeout(120_000) {
                    val denoiseStrength = when (currentDenoiseLevel) {
                        DenoiseLevel.OFF -> 0f
                        DenoiseLevel.LIGHT -> 0.5f
                        DenoiseLevel.STRONG -> 1.0f
                    }
                    // 黑白优化逐页生效：彩色页强制关闭黑白后处理
                    val bwForThisCall = if (bwForceOff) MangaBWPostProcessConfig(0, false) else currentBwConfig
                    // 看门狗标记（覆盖所有调用路径：阅读器/批量/自动评测）
                    try { SrCore.markInferenceStart() } catch (_: Throwable) {}
                    val result = processor.process(
                        input, currentScale,
                        currentDenoiseLevel, denoiseStrength, bwForThisCall,
                    )
                    try { SrCore.markInferenceEnd() } catch (_: Throwable) {}
                    if (versionAtStart != modelVersion) {
                        return@withTimeout input
                    }
                    result
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "SR: processing timeout after 120s")
                input
            } catch (e: CancellationException) {
                input
            } catch (e: Exception) {
                Log.e(TAG, "SR processing failed: ${e.message}")
                input
            }
        }
    }

    /** 请求中止在途推理（仅当前页翻页让位；引擎在下一瓦片边界停下让位给当前页） */
    fun abortInFlight() {
        val p = currentProcessor ?: return
        try { p.abort() } catch (_: Throwable) {}
    }

    fun currentModelVersion(): Long = modelVersion

    fun release() {
        cancelProcessingJobs()
        currentProcessor?.release()
        currentProcessor = null
        currentModel = null
        modelVersion++
        clearSrCache()
    }

    private fun createProcessor(model: SRModel): SuperResolutionProcessor {
        // ★ 快速优化档：无 ncnn 处理器，占位 NoOp（process() 内直接走 SrCleanupProcessor）
        if (model.modelType == "algorithm") return NoOpProcessor()
        return try {
            RealESRGANProcessor(model)
        } catch (e: Throwable) {
            Log.w(TAG, "SR: cannot create processor ${model.key}: ${e.message}")
            NoOpProcessor()
        }
    }

    private fun getModelPath(model: SRModel): String {
        // ★ 降噪强度实装：有变体档的模型按当前强度解析实际目录（"关闭"=原默认模型，行为不变）
        val dirName = model.resolveDirName(currentDenoiseLevel)
        return File(context.filesDir, "models/$dirName").absolutePath
    }

    fun buildCacheKey(chapterId: Long, pageIndex: Int): String {
        val modelKey = activeModel?.key ?: "unknown"
        val bw = currentBwConfig
        val bwPart = if (bw != null) "_bw${bw.grayLevels}${if (bw.densityCorrection) "d" else ""}" else ""
        val denoise = currentDenoiseLevel.key
        return "page_${chapterId}_${pageIndex}_${modelKey}_${activeScale}_${denoise}$bwPart"
    }

    fun ensureModelsExtracted() {
        ModelManager.getInstance(context).ensureModelsExtracted()
    }

    companion object {
        private const val TAG = "SrManager"
    }
}
