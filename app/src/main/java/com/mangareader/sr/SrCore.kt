package com.mangareader.sr

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * 超分中枢单例：持有引擎/缓存/预载/队列/档位，App.onCreate 里 init。
 * 渐进替换走事件流（修复源项目 srResultFlow 无订阅者的缺口）：超分完成即发射 SrReadyEvent，
 * 阅读器收到当前页事件就调 injectUpscaledBitmap 原位替换。
 */
object SrCore {

    private const val TAG = "SrCore"

    lateinit var appContext: Context
        private set
    lateinit var settings: SrSettings
        private set
    lateinit var manager: SuperResolutionManager
        private set
    lateinit var diskCache: SRDiskCache
        private set
    lateinit var dispatcher: SRPreloadDispatcher
        private set
    lateinit var queueStore: SRQueueStore
        private set
    lateinit var profileManager: DeviceProfileManager
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 超分完成事件（chapterId, pageIndex, bitmap）——渐进式替换的信号源 */
    data class SrReadyEvent(val chapterId: Long, val pageIndex: Int, val bitmap: android.graphics.Bitmap)
    private val _readyEvents = MutableSharedFlow<SrReadyEvent>(extraBufferCapacity = 64)
    val readyEvents: SharedFlow<SrReadyEvent> = _readyEvents

    /** 批量队列进度事件（供队列页刷新） */
    data class QueueProgress(val item: SRQueueItem, val running: Boolean)
    private val _queueProgress = MutableSharedFlow<QueueProgress>(extraBufferCapacity = 32)
    val queueProgress: SharedFlow<QueueProgress> = _queueProgress

    @Volatile
    var initialized = false
        private set

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            settings = SrSettings(appContext)
            manager = SuperResolutionManager(appContext)
            // 产物落 filesDir/page_cache/sr（ArchiveEngine 预留的 pageCacheDir，此前从未使用）
            val srDir = File(File(appContext.filesDir, "page_cache"), "sr")
            diskCache = SRDiskCache(
                srDir,
                artifactLimitBytes = {
                    // 页级持久产物上限跟随用户设置（MB → Byte）
                    settings.artifactCacheLimitMb.toLong() * 1024 * 1024
                },
                realtimeLimitBytes = {
                    // 实时缓存上限跟随用户设置（MB → Byte）
                    settings.realtimeCacheLimitMb.toLong() * 1024 * 1024
                },
            )
            dispatcher = SRPreloadDispatcher(manager, diskCache)
            queueStore = SRQueueStore(appContext)
            profileManager = DeviceProfileManager(appContext)
            dispatcher.preloadWindow = effectivePreloadWindow()
            initialized = true
        }
        // 模型解包放后台，不阻塞启动
        scope.launch(Dispatchers.IO) {
            try {
                manager.ensureModelsExtracted()
                if (settings.enabled) applySettings(null)
            } catch (e: Exception) {
                Log.e(TAG, "init extract failed", e)
            }
        }

        // 启动即检查上次是不是被 native 推理带走了（安全处置：强制 CPU）
        try { checkNativeWatchdog() } catch (_: Throwable) {}
    }

    // ── native 崩溃看门狗（native 崩溃会带走整个 App 进程，Kotlin 接不住，只能靠"哨兵标记"）──
    // 推理前 sync 写 sr_native_inflight=true；推理成功后写 false。
    // 下次启动发现它还是 true → 上次推理把进程带走了 → 自动强制 CPU 模式。

    private val crashPrefs by lazy { appContext.getSharedPreferences("sr_watchdog", Context.MODE_PRIVATE) }

    /** 上次是否因超分 native 崩溃过（本进程启动时检测） */
    @Volatile
    var hadNativeCrash: Boolean = false
        private set

    /** 推理计数器（看门狗用） */
    @Volatile
    var inferenceInFlight: Boolean = false
        private set

    fun checkNativeWatchdog() {
        try {
            if (crashPrefs.getBoolean("sr_native_inflight", false)) {
                hadNativeCrash = true
                manager.forceCpu = true
                Log.w(TAG, "native crash detected last session → force CPU mode")
                runCatching { com.mangareader.DiagTracer.markStatic("enhance: safe mode (previous session abnormal)") }
                // 清标记，别把这次正常会话也算成崩溃
                crashPrefs.edit().putBoolean("sr_native_inflight", false).commit()
            }
        } catch (_: Throwable) {}
    }

    fun markInferenceStart() {
        inferenceInFlight = true
        try { crashPrefs.edit().putBoolean("sr_native_inflight", true).commit() } catch (_: Throwable) {}
    }

    fun markInferenceEnd() {
        inferenceInFlight = false
        try { crashPrefs.edit().putBoolean("sr_native_inflight", false).commit(); } catch (_: Throwable) {}
    }

    /** 手动解除 CPU 强制（用户确认/换设备后） */
    fun clearForceCpu() {
        manager.forceCpu = false
        hadNativeCrash = false
    }

    // ── 队列页实时刷新（暂停/继续状态）──
    private val _batchPausedFlow = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    val batchPausedFlow: SharedFlow<Boolean> = _batchPausedFlow
    fun emitBatchPaused(paused: Boolean) {
        _batchPausedFlow.tryEmit(paused)
    }

    fun effectivePreloadWindow(): Int {
        val base = if (settings.preloadOverride >= 0) settings.preloadOverride
        else profileManager.getPreloadWindow()
        return if (com.hippo.ehviewer.Settings.isEinkMode) minOf(base, 1) else base
    }

    /** ★ F4.7③：省电模式/低 RAM 设备自动停用"新起"的实时超分（缓存/批量产物不受影响）。
     *  调用方在调度入口检查；已算好的结果照常显示。 */
    fun shouldThrottleRealtimeSr(): Boolean = runCatching {
        if (!initialized) return false
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        if (am.isLowRamDevice) return true
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.isPowerSaveMode
    }.getOrDefault(false)

    /** 把当前设置应用到引擎（模型/降噪/黑白配置）。bookId 用于单作品覆盖。
     *  应用完成后通知管线重算当前页，让新设置即时生效。 */
    fun applySettings(bookId: Long?) {
        if (!initialized) return
        val model = settings.effectiveModel(bookId).let { m ->
            if (NativeLibraryStatus.isModelAvailable(m)) m else NativeLibraryStatus.getFirstAvailableModel()
        }
        dispatcher.preloadWindow = effectivePreloadWindow()
        scope.launch {
            try {
                manager.switchModel(
                    model = model,
                    scale = model.scale,
                    denoiseLevel = settings.denoise,
                    bwConfig = settings.bwConfigOrNull(),
                )
                // 当前页按新设置重算（缓存键含模型/降噪/黑白，旧结果自然不命中）
                SrPipeline.onSettingsChanged()
            } catch (e: Exception) {
                Log.e(TAG, "applySettings failed", e)
            }
        }
    }

    fun isUsable(bookId: Long?): Boolean {
        if (!initialized) return false
        if (!settings.effectiveEnabled(bookId)) return false
        if (!NativeLibraryStatus.isRealESRGANAvailable) return false
        // 无 Vulkan 时仍可用 CPU 慢速推理（native gpuid=-1），不强制禁用
        return true
    }

    /** Vulkan 缺失时 UI 总开关灰掉的判定 */
    fun isVulkanMissing(): Boolean = !VulkanHelper.isVulkanSupported(appContext)

    fun emitReady(chapterId: Long, pageIndex: Int, bitmap: android.graphics.Bitmap) {
        val ok = _readyEvents.tryEmit(SrReadyEvent(chapterId, pageIndex, bitmap))
        android.util.Log.i("SrCore", "emitReady cid=$chapterId p=$pageIndex bmp=${bitmap.width}x${bitmap.height} emitted=$ok")
    }

    /** 批量进度广播（服务调用，队列页订阅） */
    fun emitBatchProgress(item: SRQueueItem) {
        _queueProgress.tryEmit(QueueProgress(item, true))
    }
}
