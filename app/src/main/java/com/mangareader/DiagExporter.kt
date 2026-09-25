package com.mangareader

import android.content.Context

/**
 * 诊断日志导出（2026-09-25 无日志闪退专项）：汇总设备/版本信息 + crash_logs + ANR 记录 +
 * 启动阶段标记 + 主线程心跳 + 签名校验失败诊断，写入公共 Download（API 29+ 经 MediaStore），
 * 文件管理器直接可见，无需 adb/root。书架顶栏与关于页共用。
 * 返回给用户看的结果消息（含落盘位置）。
 */
object DiagExporter {

    fun export(ctx: Context): String {
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
        val sb = StringBuilder()
        sb.appendLine("=== 漫读 诊断日志 $ts ===")
        sb.appendLine("品牌: ${android.os.Build.BRAND}  型号: ${android.os.Build.MODEL}")
        sb.appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})  ABI: ${android.os.Build.SUPPORTED_ABIS.firstOrNull()}")
        runCatching {
            val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            sb.appendLine("版本: ${pkg.versionName} (${pkg.longVersionCode})  安装: ${fmt.format(java.util.Date(pkg.firstInstallTime))}  更新: ${fmt.format(java.util.Date(pkg.lastUpdateTime))}")
        }
        sb.appendLine()

        // 崩溃日志（CrashHandler 落盘）
        runCatching {
            val dir = java.io.File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "crash_logs")
            val files = dir.listFiles()?.filter { it.name.startsWith("crash_") }?.sortedByDescending { it.name }?.take(10)
            if (files.isNullOrEmpty()) sb.appendLine("[crash_logs] 无崩溃记录文件")
            else files.forEach { f ->
                sb.appendLine("----- ${f.name} -----")
                runCatching { sb.appendLine(f.readText()) }.onFailure { sb.appendLine("<读取失败: ${it.message}>") }
                sb.appendLine()
            }
        }
        // ANR 看门狗记录
        runCatching {
            val f = java.io.File(ctx.getExternalFilesDir(null) ?: return@runCatching, "anr_log.txt")
            if (f.exists() && f.length() > 0) {
                sb.appendLine("----- anr_log.txt（末尾 8KB）-----")
                val bytes = f.readBytes()
                val start = if (bytes.size > 8192) bytes.size - 8192 else 0
                sb.appendLine(String(bytes, start, bytes.size - start))
            } else sb.appendLine("[anr_log] 无记录")
        }
        // 启动阶段标记 + 主线程心跳（无日志闪退专项取证）
        runCatching {
            val f = java.io.File(ctx.filesDir, "diag_stages.txt")
            if (f.exists()) {
                sb.appendLine("----- diag_stages.txt（末尾 4KB，最后一行即最后到达的阶段）-----")
                val bytes = f.readBytes()
                val start = if (bytes.size > 4096) bytes.size - 4096 else 0
                sb.appendLine(String(bytes, start, bytes.size - start))
            } else sb.appendLine("[diag_stages] 无（未到达任何启动标记点 = 崩溃极早期）")
        }
        runCatching {
            val f = java.io.File(ctx.filesDir, "diag_heartbeat.txt")
            if (f.exists()) sb.appendLine("[diag_heartbeat] 心跳最后时刻: ${f.readText().trim()}")
            else sb.appendLine("[diag_heartbeat] 无")
        }
        // 签名校验失败诊断（degrade 硬退出前落盘，filesDir）
        runCatching {
            val f = java.io.File(ctx.filesDir, "sig_check_failed.txt")
            if (f.exists()) {
                sb.appendLine("----- sig_check_failed.txt（签名校验失败诊断）-----")
                sb.appendLine(f.readText())
            } else sb.appendLine("[sig_check] 无校验失败记录（如闪退但无此文件，说明非签名路径）")
        }
        // ★ app 内 logcat 抓取：普通应用可读自己 UID 的日志（native 崩溃前的 libc/ART 输出、
        //   引擎日志等）。native crash 的 tombstone 属系统 crash_dump 进程读不到，但 app 进程
        //   死前最后输出有诊断价值。
        runCatching {
            val proc = ProcessBuilder("logcat", "-d", "-v", "time").redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            val interesting = out.lineSequence()
                .filter { it.contains("FATAL", true) || it.contains("Fatal signal", true) || it.contains("SIGSEGV") || it.contains("SIGABRT") || it.contains("SIGBUS") || it.contains("DEBUG", true) || it.contains("mangareader", true) }
                .toList()
                .takeLast(400)
                .toList()
            sb.appendLine("----- logcat（本应用相关，末 400 行）-----")
            if (interesting.isEmpty()) sb.appendLine("<无可读行>")
            else interesting.forEach { sb.appendLine(it) }
        }.onFailure { sb.appendLine("[logcat] 抓取失败: ${it.message}") }

        val text = sb.toString()
        val name = "漫读_诊断日志_$ts.txt"
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            }
            val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "导出失败：无法写入下载目录"
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: return "导出失败：无法打开输出流"
        } else {
            @Suppress("DEPRECATION")
            val dl = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            dl.mkdirs()
            java.io.File(dl, name).writeText(text)
        }
        return "已导出到 下载/$name（文件管理器可见，请把该文件发给开发者）"
    }
}
