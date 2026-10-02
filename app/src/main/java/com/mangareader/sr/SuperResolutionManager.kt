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

    // ★ NPU（2026-10 引入）：会话级熔断语义与 GPU 相同；回落链 NPU→GPU→CPU
    @Volatile
    private var npuFused: Boolean = false
    private val npuFailStreak = java.util.concurrent.atomic.AtomicInteger(0)

    /** 当前在位引擎（loadPendingModel 决定；诊断与面板展示用） */
    @Volatile
    var activeEngine: SrEngine = SrEngine.CPU
        private set

    /**
     * 引擎解析（唯一路由）：看门狗强制 CPU > 用户所选引擎可用性（NPU 需设备可探到 HTP
     * 代 + 插件里有该档位/降噪/代号的 context）> GPU（需 Vulkan 且档位是神经网络档）> CPU。
     * 仅在 loadPendingModel 的 IO 块内调用——NPU 探测会 dlopen QNN 运行时，禁主线程。
     */
    private fun resolveEngine(model: SRModel): SrEngine {
        if (forceCpu) return SrEngine.CPU
        val want = SrCore.settings.engine
        if (want == SrEngine.NPU) {
            // 插件清单不能依赖面板扫描（开进程直进书不碰面板时 registry 是空的，
            // 曾致"面板选了 NPU、实际永远静默回 GPU"），路由前必须自扫一次。
            NpuModelRegistry.ensureScanned(context)
            val cdsp = QnnEngine.isCdspAvailable
            val arch = QnnEngine.architecture
            val entry = NpuModelRegistry.resolve(model, currentDenoiseLevel, arch)
            if (!npuFused && arch > 0 && entry != null) {
                return SrEngine.NPU
            }
            if (!npuFused) {
                // 中性打点（对应表 enq）：cdsp=/dev 节点预检（仅提示）；arch=QNN 报的 HTP
                // 代号（0=探不到）；reg=当前档位+降噪+代号有无插件 context；t=实测通过
                // （2026-10-02 起为参考项不拦截——用户要求未实测/失败也允许走 NPU，
                // 装载失败由 loadPendingModel 就地回 GPU 并熔断）。
                runCatching {
                    com.mangareader.DiagTracer.markStatic(
                        "enhance: npu skip cdsp=$cdsp arch=$arch reg=${entry != null} " +
                            "t=${SrCore.settings.npuTestOk}"
                    )
                }
            }
        }
        // NPU 选了但不可用/已熔断 → 依回落链先试 GPU（含 engine=GPU 的正常路径）
        if (!gpuFused && want != SrEngine.CPU && isVulkanAvailable && model.requiresVulkan) {
            return SrEngine.GPU
        }
        return SrEngine.CPU
    }

    /** 本会话熔断复位（面板"恢复加速"按钮；切档也会自动解除） */
    fun resetSessionFuses() {
        gpuFused = false
        gpuFailStreak.set(0)
        npuFused = false
        npuFailStreak.set(0)
    }

    /** 每次真实推理（版本未变的）结束后由管线回报结果；returnedInput=true 视为一次失败 */
    fun recordInferenceOutcome(returnedInput: Boolean) {
        when (activeEngine) {
            SrEngine.NPU -> {
                if (returnedInput) {
                    val n = npuFailStreak.incrementAndGet()
                    if (n in 1..2) runCatching { com.mangareader.DiagTracer.markStatic("enhance: npu fail n=$n") }
                    if (n >= 3) {
                        npuFused = true
                        npuFailStreak.set(0)
                        Log.w(TAG, "NPU 连续失败 3 次，本会话熔断回落 GPU/CPU（重新切档自动恢复）")
                        runCatching { com.mangareader.DiagTracer.markStatic("enhance: npu off n=3") }
                    }
                } else {
                    npuFailStreak.set(0)
                }
            }
            SrEngine.GPU -> {
                if (returnedInput) {
                    val n = gpuFailStreak.incrementAndGet()
                    if (n in 1..2) runCatching { com.mangareader.DiagTracer.markStatic("enhance: acc fail n=$n") }
                    if (n >= 3) {
                        gpuFused = true
                        gpuFailStreak.set(0)
                        Log.w(TAG, "GPU 连续失败 3 次，本会话熔断强制 CPU（重新切档自动恢复）")
                        runCatching { com.mangareader.DiagTracer.markStatic("enhance: acc off n=3") }
                    }
                } else {
                    gpuFailStreak.set(0)
                }
            }
            SrEngine.CPU -> {
                gpuFailStreak.set(0)
                npuFailStreak.set(0)
            }
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
        // ★ F4.7②：切档解除本会话的引擎失败熔断（看门狗 forceCpu 不受影响，仍等用户手动恢复）
        gpuFused = false
        gpuFailStreak.set(0)
        npuFused = false
        npuFailStreak.set(0)
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
            var engine = resolveEngine(model)
            val loadStart = System.currentTimeMillis()
            try {
                when (engine) {
                    SrEngine.NPU -> {
                        // 先解析插件 context；失败（包被卸载/换包）抛出走就地回退
                        val arch = QnnEngine.architecture
                        try {
                            val qnn = QnnProcessor.create(context, model, currentDenoiseLevel, arch)
                                ?: throw IllegalStateException("no NPU context for ${model.key} v$arch")
                            // ncnn 占位处理器未 initialize 即无资源，release 幂等
                            try { processor.release() } catch (_: Throwable) {}
                            qnn.initialize("", 0)
                            currentProcessor = qnn
                        } catch (t: Throwable) {
                            if (t is kotlinx.coroutines.CancellationException) throw t
                            // NPU 装载失败不落 NoOp：本次就地回 GPU/CPU（用户要求未实测/
                            // 实测被拒也允许选 NPU），并本会话熔断 NPU 防止每次装载空转；
                            // 恢复加速/切档解除后下次装载会再试 NPU。
                            Log.w(TAG, "SR: NPU init failed (${t.message}), fallback GPU/CPU")
                            npuFused = true
                            npuFailStreak.set(0)
                            val gpu = isVulkanAvailable && model.requiresVulkan
                            engine = if (gpu) SrEngine.GPU else SrEngine.CPU
                            val gpuid = if (gpu) 0 else -1
                            try { processor.release() } catch (_: Throwable) {}
                            processor.initialize(modelPath, gpuid)
                            currentProcessor = processor
                        }
                    }
                    else -> {
                        // 规则：engine=GPU 走 Vulkan；CPU/回落路径 gpuid=-1 纯 CPU（默认更安全）
                        val gpuid = if (engine == SrEngine.GPU) 0 else -1
                        processor.initialize(modelPath, gpuid)
                        currentProcessor = processor
                    }
                }
                activeEngine = engine
                currentModel = model
                Log.i(TAG, "SR: ${model.key} loaded in ${System.currentTimeMillis() - loadStart}ms, " +
                    "ready=${currentProcessor?.isReady}, engine=$engine")
                // ★ 诊断打点（中性措辞）：引擎/是否图形加速/耗时——远端排障关键证据
                runCatching {
                    com.mangareader.DiagTracer.markStatic(
                        "enhance: on engine=${engine.key} acc=${engine == SrEngine.GPU} " +
                            "${System.currentTimeMillis() - loadStart}ms"
                    )
                }
            } catch (e: Throwable) {
                // Throwable 而非 Exception：JNI 层的 UnsatisfiedLinkError 等是 Error，
                // 接不住就是进程闪退（Kotlin internal 修饰 JNI 函数名事故的教训）；
                // 任何引擎初始化失败都必须落到 NoOp 兜底。
                Log.e(TAG, "SR: init ${model.key} failed ($engine), fallback NoOp: ${e.message}")
                runCatching { com.mangareader.DiagTracer.markStatic("enhance: off (init)") }
                try { processor.release() } catch (_: Throwable) {}
                currentProcessor = NoOpProcessor()
                currentModel = model
                activeEngine = SrEngine.CPU
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
            } catch (e: Throwable) {
                // Throwable：native 层抛的是 Error 家族（UnsatisfiedLinkError 等），接不住=闪退
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
        if (model.modelType == "lanczos") return LanczosProcessor(model)
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
        // 引擎指纹：NPU 与 GPU/CPU 产物不混用（同一档位两边产物存在差异）；
        // _ng = 偏色守护版（上游 v1.3.10 周期网点守护），守护前的 NPU 产物键不同自然失效
        val enginePart = if (activeEngine == SrEngine.NPU) "enpu_ng" else activeEngine.key
        return "page_${chapterId}_${pageIndex}_${modelKey}_${activeScale}_${denoise}$bwPart" +
            "_e$enginePart"
    }

    fun ensureModelsExtracted() {
        ModelManager.getInstance(context).ensureModelsExtracted()
    }

    companion object {
        private const val TAG = "SrManager"
    }
}
