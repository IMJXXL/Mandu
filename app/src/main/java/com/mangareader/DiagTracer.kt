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
 * 2. [startLogcatCapture] logcat 持续抓取：独立子进程，app 被杀后仍继续记录。
 *
 * mark 可能来自主线程/后台线程，统一 synchronized 串行化。
 */
object DiagTracer {

    private const val STAGES = "diag_stages.txt"
    private val lock = Any()

    /**
     * ★ 启动 logcat 持续抓取（无 adb 闪退专项）：spawn 独立 logcat 子进程把本应用日志
     *   续写到 filesDir/diag_logcat.txt（2MB 轮转）。子进程在 app 被杀后依然存活继续记录，
     *   native crash 的 "Fatal signal" 行恰好由本进程自身写入 logd（同 UID 可读）——
     *   是无 adb 环境下拿到 native 崩溃遗言的唯一通道。重复启动幂等（同名文件续写）。
     */
    fun startLogcatCapture(context: Context) {
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val out = File(dir, "diag_logcat.txt")
            out.parentFile?.mkdirs()
            ProcessBuilder("logcat", "-T", "200", "-v", "time", "-r", "2048", "-n", "1", "-f", out.absolutePath)
                .redirectErrorStream(true)
                .start()
        }
    }

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
}