package com.mangareader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File

/**
 * 极简取证追踪器（2026-09-25 无日志闪退专项）：
 *
 * 背景：崩溃发生在 CrashHandler 与签名诊断都覆盖不到的路径（native crash / 系统直接杀），
 * app 侧无任何文件残留。本工具提供两条不依赖崩溃处理器的取证线：
 *
 * 1. [mark] 阶段标记：启动/关键路径每步 append 一行到 diag_stages.txt，进程死亡时
 *    文件最后一条即崩点（native crash 与 kill 同样截断写入）。
 * 2. [startHeartbeat] 主线程心跳：每秒覆写 diag_heartbeat.txt（单行时间戳）。主线程
 *    存活才更新——死亡后文件的最后时刻即主线程停止时刻；配合 stages 可区分
 *    「主线程卡死后被杀」与「瞬间消失」。
 *
 * 全部写入 filesDir（GameViewer 传输/关于页导出均可取），IO 为 KB 级覆写/追加。
 */
object DiagTracer {

    private const val STAGES = "diag_stages.txt"
    private const val HEARTBEAT = "diag_heartbeat.txt"
    private val handler = Handler(Looper.getMainLooper())
    private var heartbeatRunning = false

    fun mark(context: Context, stage: String) {
        try {
            val ts = SystemClock.elapsedRealtime()
            val wall = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
                .format(java.util.Date())
            File(context.filesDir, STAGES).appendText("[$wall uptime=${ts}ms] $stage\n")
        } catch (_: Throwable) {}
    }

    /** 启动主线程心跳（重复调用幂等）。主线程每秒覆写一次心跳文件。 */
    fun startHeartbeat(context: Context) {
        if (heartbeatRunning) return
        heartbeatRunning = true
        val file = File(context.filesDir, HEARTBEAT)
        val tick = object : Runnable {
            override fun run() {
                try {
                    val wall = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
                        .format(java.util.Date())
                    file.writeText("$wall\n")
                } catch (_: Throwable) {}
                handler.postDelayed(this, 1000L)
            }
        }
        handler.post(tick)
    }
}
