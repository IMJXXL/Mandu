package com.mangareader

import android.content.Context

/**
 * 诊断日志导出（2026-09-25 无日志闪退专项）：汇总设备/版本信息 + crash_logs + ANR 记录 +
 * 启动阶段标记，写入公共 Download（API 29+ 经 MediaStore），
 * 文件管理器直接可见，无需 adb/root。关于页「导出诊断日志」按钮调用。
 * 返回给用户看的结果消息（含落盘位置）。
 *
 * 2026-09-30：导出目录可选——部分设备写不了公共 Download。优先级：
 * ① 用户自选目录（SAF 授权持久化，关于页「选择日志导出目录」）
 * ② 公共 Download
 * ③ 应用私有目录兜底（一定可写，消息里给全路径）
 */
object DiagExporter {

    private const val PREFS = "manga_reader"
    private const val KEY_DIR = "diag_export_dir"

    /** 用户自选导出目录的显示名（未选返回 null） */
    fun chosenDirLabel(ctx: Context): String? {
        val saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DIR, null) ?: return null
        return dirLabel(ctx, android.net.Uri.parse(saved)) ?: "已选目录"
    }

    /** 记住用户选的导出目录（OpenDocumentTree 回调里调，持久化读写授权） */
    fun saveChosenDir(ctx: Context, uri: android.net.Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DIR, uri.toString()).apply()
        }
    }

    /** 恢复默认（导出到公共下载） */
    fun clearChosenDir(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_DIR).apply()
    }

    private fun dirLabel(ctx: Context, tree: android.net.Uri): String? = runCatching {
        val docId = android.provider.DocumentsContract.getTreeDocumentId(tree)
        val docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        ctx.contentResolver.query(docUri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    fun export(ctx: Context): String {
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())

        // ★ 大文件防护：只读末尾 N KB（RandomAccessFile seek），防膨胀文件拖垮导出
        // ★ 脱敏：包内类名（含根包/全部子包）→ 模块码；用户文件路径 → <book>；服务器地址/URL/IP → <server>
        fun sanitize(text: String): String = text
            .replace(Regex("""com\.mangareader\.[A-Za-z0-9_$.]+"""), "m.*")
            .replace(Regex("""com\.hippo\.[A-Za-z0-9_$.]+"""), "h.*")
            .replace(Regex("""/storage/[^\s"]*"""), "<book>")
            .replace(Regex("""/data/user/\d+/[^\s"]*"""), "<app>")
            .replace(Regex("""\b(?:https?|smb|ftp|ftps|webdav|webdavs)://[^\s"'）)\\]+"""), "<server>")
            .replace(Regex("""\\\\[\w.-]+[^\s"']*"""), "<server>")
            .replace(Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(?::\d+)?\b"""), "<server>")
            .replace(Regex("""\b[\w-]+\.local\b"""), "<server>")

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

        // ★ 设置快照（2026-09-30 排障定位用：翻页/纹理相关生效状态，短值不涉隐私）
        runCatching {
            val p = ctx.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE)
            fun b(k: String, d: Boolean) = try { p.getBoolean(k, d) } catch (_: Exception) { d }
            fun i(k: String, d: Int) = try { p.getInt(k, d) } catch (_: Exception) { d }
            fun f2(k: String, d: Float) = try { p.getFloat(k, d) } catch (_: Exception) { d }
            sb.appendLine("----- 设置快照（排障） -----")
            sb.appendLine("curl动画=${b("isPageCurlAnimation", false)} 背面透出=${(f2("curlBackContentAlpha", 0.1f) * 100).toInt()}% 阴影=${(f2("curlShadowAlpha", 0.2f) * 100).toInt()}%")
            sb.appendLine("纸张纹理=${b("textureEnabled", false)} 生效范围=${i("textureScope", 0)} 浓度=${i("textureConcentration", 100)}")
            sb.appendLine("双页=${i("pageLayout", 0)} 中缝=${i("centerGapType", 0)}")
            sb.appendLine()
        }

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
        // NPU 模型包扫描失败取证（扫描器扫不到任何模型包时写，成功即删）
        runCatching {
            val f = java.io.File(ctx.getExternalFilesDir(null) ?: return@runCatching, "npu-diag.txt")
            if (f.exists() && f.length() > 0) {
                sb.appendLine("----- npu-diag.txt（模型包扫描失败原因）-----")
                sb.appendLine(tailOf(f, 16))
            }
        }


        val text = sb.toString()
        val name = "漫读_诊断日志_$ts.txt"
        val bytes = text.toByteArray()

        // ① 用户自选目录优先
        val saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DIR, null)
        if (saved != null) {
            val wrote = runCatching {
                val tree = android.net.Uri.parse(saved)
                val docId = android.provider.DocumentsContract.getTreeDocumentId(tree)
                val dirUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                val created = android.provider.DocumentsContract.createDocument(ctx.contentResolver, dirUri, "text/plain", name)
                    ?: error("createDocument 返回空")
                ctx.contentResolver.openOutputStream(created)?.use { it.write(bytes) } ?: error("输出流打开失败")
            }
            if (wrote.isSuccess) {
                val label = dirLabel(ctx, android.net.Uri.parse(saved)) ?: "所选目录"
                return "已导出到所选目录（$label）/$name"
            }
            clearChosenDir(ctx)  // 授权失效/目录被删 → 清掉回落默认，下次重选
        }

        // ② 公共 Download
        val toDownload = if (android.os.Build.VERSION.SDK_INT >= 29) {
            runCatching {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                }
                val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore 插入失败")
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("输出流打开失败")
            }.isSuccess
        } else {
            runCatching {
                @Suppress("DEPRECATION")
                val dl = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                dl.mkdirs()
                java.io.File(dl, name).writeBytes(bytes)
            }.isSuccess
        }
        if (toDownload) return "已导出到 下载/$name（文件管理器可见，请把该文件发给开发者）"

        // ③ 兜底：应用私有目录（一定可写）
        return runCatching {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            java.io.File(dir, name).writeBytes(bytes)
            "公共下载目录写入失败，已导出到应用目录：${dir.absolutePath}/$name"
        }.getOrElse { "导出失败：${it.message}" }
    }
}
