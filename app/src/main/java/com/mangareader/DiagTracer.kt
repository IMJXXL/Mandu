package com.mangareader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File

/**
 * 极简取证追踪器（2026-09-25 无日志闪退专项）：
 *
 * 1. [mark] 阶段标记：关键路径每步 append 到 filesDir/diag_stages.txt，进程死亡时
 *    文件最后一行即最后到达的阶段（native crash 与系统杀同样截断写入）。
 * mark 可能来自主线程/后台线程，统一 synchronized 串行化。
 */
object DiagTracer {

    private const val STAGES = "diag_stages.txt"
    private val lock = Any()

    fun mark(context: Context, stage: String) {
        try {
            synchronized(lock) {
                val wall = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
                    .format(java.util.Date())
                val line = "[$wall uptime=${SystemClock.elapsedRealtime()}ms] $stage"
                File(context.filesDir, STAGES).appendText(line + "\n")
            }
        } catch (_: Throwable) {}
    }

    /** 无 Context 场景（底层模块诊断）：App.onCreate 时注入 filesDir */
    @Volatile
    private var appFilesDir: File? = null

    fun bindFilesDir(dir: File) {
        appFilesDir = dir
    }

    /** 启动轮转：diag_stages 超 2MB 时只保留末尾 256KB（打点含高频计时，防无界增长）。
     *  持同一 lock——与 mark/markStatic 串行，避免写头时并发 append。调用方应在后台线程。 */
    fun rotateIfNeeded(dir: File) {
        runCatching {
            synchronized(lock) {
                val f = File(dir, STAGES)
                if (f.length() > 2L * 1024 * 1024) {
                    val raf = java.io.RandomAccessFile(f, "r")
                    val tail = try {
                        raf.seek(f.length() - 256 * 1024)
                        val bytes = ByteArray(256 * 1024)
                        raf.readFully(bytes)
                        String(bytes)
                    } finally { raf.close() }
                    f.writeText("…（历史已截断）…" + "\n" + tail)
                }
            }
        }
    }

    fun markStatic(stage: String) {
        val dir = appFilesDir ?: return
        try {
            synchronized(lock) {
                val wall = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
                    .format(java.util.Date())
                val line = "[$wall uptime=${SystemClock.elapsedRealtime()}ms] $stage"
                File(dir, STAGES).appendText(line + "\n")
            }
        } catch (_: Throwable) {}
    }
}