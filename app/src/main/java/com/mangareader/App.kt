package com.mangareader

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mangareader.data.MangaDatabase
import com.mangareader.viewer.ArchiveEngine
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    val db by lazy { MangaDatabase.getDatabase(this) }
    val crashHandler by lazy { CrashHandler(this) }
    val engine by lazy { ArchiveEngine(this) }
    // ★ 3+5 改动1：appScope —— 独立于 Composable scope 的应用级协程作用域
    //   Reader Composable dispose 时 scope 会被 cancel，导致 saveJob/markAsReadNow 被砍断。
    //   appScope 用 SupervisorJob + Default 调度器 + CoroutineExceptionHandler 兜底，
    //   让 save/兜底 save/markAsReadNow 在 Reader 退出后仍能完成写盘。
    val appScope by lazy {
        CoroutineScope(
            SupervisorJob() +
            Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> Log.e("AppScope", "save/exit err", e) }
        )
    }
    val imageLoader by lazy {
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizeBytes((Runtime.getRuntime().maxMemory() * 0.3).toInt())
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(5L * 1024 * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .components {
                // ★ Kototoro 路线：注册 AnimatedImageDecoder 让 Coil 能解 WebP/APNG/GIF 动图
                //   API≥28（设备 API31）走系统 ImageDecoder——稳定性远胜 APNG4Android 的自实现
                //   ★ Coil 2 在 io.coil-kt:coil-gif 里提供 ImageDecoderDecoder（API≥28 用系统 ImageDecoder）
                //     和 GifDecoder（API<28 回退，只支持 GIF）。Kototoro 用 coil3.gif.AnimatedImageDecoder（Coil3 名），
                //     Coil 2 对应的就是这里.ImageDecoderDecoder——同一原理。
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    add(coil.decode.ImageDecoderDecoder.Factory())
                } else {
                    add(coil.decode.GifDecoder.Factory())
                }
            }
            .build()
    }
    
    override fun onCreate() {
        super.onCreate()
        SignatureVerifier.verify(this, "F6D95465BE7D17C1B3BE2B0C59969C22")
        startBackgroundVerify()
        crashHandler.init()
        crashHandler.clearOldCrashLogs()
        startAnrWatchdog()
        preloadGifDecoder()
    }

    // ★ GIF 闪退防御（2026-08-27 红米 diag）：gif native 库在 GifFrame 类初化时按需加载，
    //   一旦失败 UnsatisfiedLinkError 会在后台解码线程直接炸沉进程。
    //   提前在主线程预热：加载失败只落日志；后续若仍触发 GifFrame 初化失败，
    //   APNG4Android 走 Drawable 构造 try-catch → 动图页空白降级而非闪退。
    private fun preloadGifDecoder() {
        try {
            System.loadLibrary("animation-decoder-gif")
        } catch (t: Throwable) {
            android.util.Log.e("App", "preload animation-decoder-gif failed", t)
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
