package com.mangareader

import android.content.Context

/**
 * 诊断日志导出（2026-09-25 无日志闪退专项）：汇总设备/版本信息 + crash_logs + ANR 记录 +
 * 启动阶段标记，写入公共 Download（API 29+ 经 MediaStore），
 * 文件管理器直接可见，无需 adb/root。关于页「导出诊断日志」按钮调用。
 * 返回给用户看的结果消息（含落盘位置）。
 */
object DiagExporter {

    fun export(ctx: Context): String {
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())

        // ★ 大文件防护：只读末尾 N KB（RandomAccessFile seek），防膨胀文件拖垮导出
        // ★ 脱敏：包内类名 → 模块码（防从崩溃堆栈读出技术路线）；用户文件路径 → <book>（防暴露浏览内容）
        fun sanitize(text: String): String = text
            .replace(Regex("""com\.mangareader\.(sr|native|viewer|engine|webdavbridge|smbbridge|komgabridge|opdsbridge|filter|data|ui|backup|updatecheck|autocat)\.[A-Za-z0-9_$]+"""), "m.*")
            .replace(Regex("""com\.hippo\.ehviewer\.(webdav|library)\.[A-Za-z0-9_$]+"""), "h.*")
            .replace(Regex("""/storage/[^\s"]*"""), "<book>")
            .replace(Regex("""/data/user/\d+/[^\s"]*"""), "<app>")

        fun tailOf(f: java.io.File, maxKb: Int): String {
            val max = maxKb * 1024
            if (f.length() <= max) return runCatching { sanitize(f.readText()) }.getOrElse { "<读取失败: ${it.message}>" }
            val raf = java.io.RandomAccessFile(f, "r")
            return try {
                raf.seek(f.length() - max)
                val bytes = ByteArray(max)
                raf.readFully(bytes)
                "…（前段已截断，仅末尾 ${maxKb}KB）…" + "\n" + sanitize(String(bytes))
            } finally { raf.close() }
        }

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
            val files = dir.listFiles()?.filter { it.name.startsWith("crash_") }?.sortedByDescending { it.name }?.take(5)
            if (files.isNullOrEmpty()) sb.appendLine("[crash_logs] 无崩溃记录文件")
            else files.forEach { f ->
                sb.appendLine("----- ${f.name} -----")
                runCatching { sb.appendLine(tailOf(f, 64)) }.onFailure { sb.appendLine("<读取失败: ${it.message}>") }
                sb.appendLine()
            }
        }
        // ANR 看门狗记录
        runCatching {
            val f = java.io.File(ctx.getExternalFilesDir(null) ?: return@runCatching, "anr_log.txt")
            if (f.exists() && f.length() > 0) {
                sb.appendLine("----- anr_log.txt（末尾 8KB）-----")
                sb.appendLine(tailOf(f, 8))
            } else sb.appendLine("[anr_log] 无记录")
        }
        // 启动阶段标记（无日志闪退专项取证）
        runCatching {
            val f = java.io.File(ctx.filesDir, "diag_stages.txt")
            if (f.exists()) {
                sb.appendLine("----- diag_stages.txt（末尾 256KB）-----")
                sb.appendLine(tailOf(f, 256))
            } else sb.appendLine("[diag_stages] 无（未到达任何启动标记点 = 崩溃极早期）")
        }


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
