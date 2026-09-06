package com.mangareader

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mangareader.data.MangaDatabase
import com.mangareader.viewer.ArchiveEngine
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.gif.AnimatedImageDecoder
import coil3.memory.MemoryCache
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
                    .maxSizeBytes((Runtime.getRuntime().maxMemory() * 0.3).toLong())
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.toOkioPath() / "image_cache")
                    .maxSizeBytes(5L * 1024 * 1024 * 1024)
                    .build()
            }
            .crossfade(300)
            .serviceLoaderEnabled(false)
            .components {

                add(com.mangareader.viewer.CoverFetcher.Factory(engine))
                add(com.mangareader.viewer.CoverDataKeyer)
                add(com.mangareader.coil.AnimatedWebPDecoder.Factory)
                add(AnimatedImageDecoder.Factory(false))
            }
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        // SMB/WebDAV 集成胶水层初始化（任务1.5）：DB 注入 + appCtx 锚点
        com.mangareader.smbbridge.SmbAppContextBridge.init(this)
        com.mangareader.smbbridge.SmbDbBridge.mangaDatabase = db
        com.mangareader.webdavbridge.WebDavDbBridge.mangaDatabase = db
        SignatureVerifier.verify(this, "F6D95465BE7D17C1B3BE2B0C59969C22")
        startBackgroundVerify()
        crashHandler.init()
        crashHandler.clearOldCrashLogs()
        startAnrWatchdog()
        try { com.mangareader.sr.SrCore.init(this) } catch (_: Throwable) {}
        // ★ 书库一次性去重（幂等；延迟 2s 避开启动 IO 高峰。DB 迁移在单事务内，失败整体回滚下次重试）
        appScope.launch {
            kotlinx.coroutines.delay(2000L)
            try { com.mangareader.data.BookDeduper.runOnce(this@App, db.mangaDao()) } catch (_: Throwable) {}
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
        val isEink = getSharedPreferences("manga_reader", MODE_PRIVATE).getBoolean("eink_mode", false)
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
