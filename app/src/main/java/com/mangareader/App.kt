package com.mangareader

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mangareader.data.MangaDatabase
import com.mangareader.viewer.ArchiveEngine
import android.app.ActivityManager
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.gif.AnimatedImageDecoder
import coil3.memory.MemoryCache
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.serviceLoaderEnabled
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath

class App : Application(), SingletonImageLoader.Factory {
    val db by lazy { MangaDatabase.getDatabase(this) }
    val crashHandler by lazy { CrashHandler(this) }
    val engine by lazy { ArchiveEngine(this) }
    val appScope by lazy {
        CoroutineScope(
            SupervisorJob() +
            Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> Log.e("AppScope", "save/exit err", e) }
        )
    }

    override fun newImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.toOkioPath() / "image_cache")
                    .maxSizeBytes(10L * 1024 * 1024 * 1024)
                    .build()
            }
            .crossfade(300)
            .allowRgb565(run {
                val mi = ActivityManager.MemoryInfo()
                (getSystemService(ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(mi)
                mi.totalMem < 3L * 1024 * 1024 * 1024
            })
            .fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(12))
            .decoderCoroutineContext(Dispatchers.IO.limitedParallelism(3))
            .apply {
                // ★ 封面解码排查（2026-09-19）：coil 默认吞错误，挂上 logger 才能看到
                //   AnimatedCover/ImageDecoder 的真实异常
                if (com.mangareader.BuildConfig.DEBUG) {
                    logger(coil3.util.DebugLogger())
                }
            }
            .serviceLoaderEnabled(false)
            .components {

                add(com.mangareader.viewer.CoverFetcher.Factory(engine))
                add(com.mangareader.viewer.CoverDataKeyer)
                add(com.mangareader.viewer.ItemCoverFetcher.Factory(engine))
                add(com.mangareader.viewer.ItemCoverKeyer)
                // AnimatedWebPDecoder 已退注册（对齐上游 "platform animated decoder for aWEBP"）：
                // 其 Factory 依赖 rust webp_decoder so（ENABLE_RUST_WEBP 未开启）恒返回 null，纯死配置；
                // 动图统一走下方平台 AnimatedImageDecoder（尊重请求尺寸，不会单张封面吃满 CPU）。
                // APNG decoder 必须在 AnimatedImageDecoder 之前：平台 ImageDecoder 不支持 APNG，
                // 不抢先注册远端 APNG 只会显示静态首帧（对齐上游动图嗅探的 apng 分支）。
                add(com.mangareader.coil.AnimatedApngDecoder.Factory)
                add(AnimatedImageDecoder.Factory(false))
                // 静态封面/图片升级 HARDWARE 位图（GPU 纹理），书架滚动更省 CPU（对齐上游）。
                // 动图 drawable 与带 gainmap 的 Ultra HDR 不受影响（拦截器自过滤）。
                add(com.hippo.ehviewer.coil.HardwareBitmapInterceptor)
            }
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        // ★ 崩溃处理器最先安装（2026-09-25 闪退排查教训）：原先排在签名校验之后，
        //   onCreate 早期崩溃（含 verify 逃逸的 Error 类异常）完全无 crash_logs，取证盲区。
        //   loadLibrary 已自行 catch，故此处为首个可崩点之前。
        crashHandler.init()
        // ★ 无日志闪退专项取证：阶段标记 + 主线程心跳（native crash/系统杀也截断文件，必留痕）
        DiagTracer.mark(this, "app.onCreate: enter")
        try { System.loadLibrary("archive_stream") } catch (_: Throwable) {}
        DiagTracer.mark(this, "app.onCreate: lib archive_stream loaded")
        // SMB 连接池预热（对齐上游 prewarm）：SmbConfig.builder 走 ServiceLoader 读 APK，
        // 懒构建防主线程磁盘 IO；启动即后台构建，首次进 SMB 浏览不再现等
        appScope.launch(Dispatchers.IO) {
            runCatching { com.hippo.ehviewer.smb.SmbGateway.prewarm() }
        }
        DiagTracer.mark(this, "app.onCreate: before signature verify")
        SignatureVerifier.verify(this, "F6D95465BE7D17C1B3BE2B0C59969C22")
        DiagTracer.mark(this, "app.onCreate: signature verify passed")
        startBackgroundVerify()
        startAnrWatchdog()
        DiagTracer.mark(this, "app.onCreate: watchdog+bgverify started")
        appScope.launch {
            com.mangareader.smbbridge.SmbAppContextBridge.init(this@App)
            com.mangareader.smbbridge.SmbDbBridge.mangaDatabase = db
            com.mangareader.webdavbridge.WebDavDbBridge.mangaDatabase = db
            com.mangareader.smbbridge.SmbDownloadQueue.init(this@App)
            com.mangareader.smbbridge.SmbDownloadQueue.setDownloadFn { taskId, task, ctx ->
                com.mangareader.smbbridge.RemoteDownloadExecutor.execute(taskId, task, ctx)
            }
            crashHandler.clearOldCrashLogs()
            try { com.mangareader.sr.SrCore.init(this@App) } catch (_: Throwable) {}
            com.mangareader.komgabridge.KomgaBridges.init(this@App, db)
            com.mangareader.opdsbridge.OpdsBridges.init(this@App, db)
            kotlinx.coroutines.delay(2000L)
            try { com.mangareader.data.BookDeduper.runOnce(this@App, db.mangaDao()) } catch (_: Throwable) {}
            try { com.mangareader.updatecheck.UpdateAutoTrigger.maybeCheck(this@App) } catch (_: Throwable) {}
        }
    }

    private fun startBackgroundVerify() {
        Thread {
            while (true) {
                try {
                    Thread.sleep(45_000)
                    SignatureVerifier.periodicCheck(this, "F6D95465BE7D17C1B3BE2B0C59969C22")
                } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; name = "SigVerifyBg"; start() }
    }

    private fun startAnrWatchdog() {
        val mainHandler = Handler(Looper.getMainLooper())
        val isEink = getSharedPreferences("manga_reader", MODE_PRIVATE).getBoolean("isEinkMode", false)
        val checkInterval = if (isEink) 5000L else 2000L
        val blockThreshold = if (isEink) 8000L else 3000L
        val watchdog = Thread {
            while (true) {
                try {
                    Thread.sleep(checkInterval)
                    val responded = java.util.concurrent.atomic.AtomicBoolean(false)
                    mainHandler.post { responded.set(true) }
                    Thread.sleep(blockThreshold)
                    if (!responded.get()) {
                        val stack = Looper.getMainLooper().thread.stackTrace
                        val trace = stack.joinToString("\n") { "    at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
                        val msg = "=== 主线程卡顿 ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} ===\n$trace\n\n"
                        Log.e("AnrWatchdog", msg)
                        try {
                            val dir = getExternalFilesDir(null)
                            if (dir != null && !dir.exists()) dir.mkdirs()
                            val f = java.io.File(dir ?: return@Thread, "anr_log.txt")
                            f.appendText(msg)
                        } catch (_: Exception) {}
                    }
                } catch (_: InterruptedException) { break }
            }
        }
        watchdog.isDaemon = true
        watchdog.name = "AnrWatchdog"
        watchdog.start()
    }
}
