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
}