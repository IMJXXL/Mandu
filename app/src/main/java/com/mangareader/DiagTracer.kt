package com.mangareader

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import java.io.File

/**
 * 极简取证追踪器（2026-09-25 无日志闪退专项）：
 *
 * 崩溃发生在所有常规日志都覆盖不到的路径（native crash / 系统杀 / 早期 Provider 阶段），
 * 本工具提供不依赖崩溃处理器的取证线：
 *
 * 1. [mark] 阶段标记：关键路径每步 append 到 filesDir/diag_stages.txt，并**镜像全文到
 *    公共 Download 的「漫读_启动追踪.txt」**（MediaStore 同名覆写）——用户文件管理器
 *    直接可见，绕开 Android/data 的 Scoped Storage 黑洞。进程死亡时镜像停在最后一次
 *    mark，最后一行即最后到达的阶段。
 * 2. [startHeartbeat] 主线程心跳：每秒覆写 filesDir/diag_heartbeat.txt，主线程存活才更新。
 *
 * mark 可能来自 Provider（极早期）/主线程/后台线程，统一 synchronized 串行化。
 */
object DiagTracer {

    private const val STAGES = "diag_stages.txt"
    private const val HEARTBEAT = "diag_heartbeat.txt"
    private const val DOWNLOAD_MIRROR = "漫读_启动追踪.txt"
    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private var heartbeatRunning = false
    private val stageLines = StringBuilder()

    fun mark(context: Context, stage: String) {
        try {
            synchronized(lock) {
                val wall = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
                    .format(java.util.Date())
                val line = "[$wall uptime=${SystemClock.elapsedRealtime()}ms] $stage"
                stageLines.appendLine(line)
                File(context.filesDir, STAGES).appendText(line + "\n")
                mirrorToDownload(context)
            }
        } catch (_: Throwable) {}
    }

    /** 把 stages 全文镜像到公共 Download（同名覆写，用户文件管理器直接可见） */
    private fun mirrorToDownload(context: Context) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                var uri: Uri? = null
                resolver.query(
                    collection, arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(DOWNLOAD_MIRROR), null
                )?.use { c -> if (c.moveToFirst()) uri = ContentUris.withAppendedId(collection, c.getLong(0)) }
                if (uri == null) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, DOWNLOAD_MIRROR)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    }
                    uri = resolver.insert(collection, values)
                }
                uri?.let {
                    resolver.openOutputStream(it, "wt")?.use { os ->
                        os.write(stageLines.toString().toByteArray())
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val dl = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                dl.mkdirs()
                File(dl, DOWNLOAD_MIRROR).writeText(stageLines.toString())
            }
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
                    val rt = Runtime.getRuntime()
                    val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576
                    val maxMb = rt.maxMemory() / 1048576
                    file.writeText("$wall heapUsed=${usedMb}MB heapMax=${maxMb}MB\n")
                } catch (_: Throwable) {}
                handler.postDelayed(this, 1000L)
            }
        }
        handler.post(tick)
    }
}
