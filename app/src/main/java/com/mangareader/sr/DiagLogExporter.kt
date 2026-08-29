package com.mangareader.sr

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 【临时诊断功能 0829，用完整体删除】
 * 删除范围：本文件 + SettingsScreen 关于弹窗里的"导出诊断日志（临时）"按钮。
 *
 * 导出内容 = 诊断头（设备/版本/native 加载状态/设置快照/已解包模型）
 *          + 快速优化档现场自检（64x64 位图直走 native 清理路径，成败一锤定音）
 *          + 全量 logcat（main+crash，应用自身 UID 范围）。
 * 输出：Android 10+ 写入公共"下载"目录（MediaStore），更低版本写应用外部目录。
 */
object DiagLogExporter {

    /** @return 展示给用户的保存位置；失败时以"导出失败"开头 */
    fun export(context: Context): String {
        val sb = StringBuilder(1 shl 20)
        sb.appendLine("==== 漫读诊断日志导出 ====")
        runCatching { appendDeviceHeader(sb) }.onFailure { sb.appendLine("设备信息读取失败: $it") }
        runCatching { appendSrState(sb, context) }.onFailure { sb.appendLine("超分状态读取失败: $it") }
        runCatching { appendCleanupSelfTest(sb) }.onFailure { sb.appendLine("清理档自检失败: $it") }
        sb.appendLine()
        sb.appendLine("==== logcat (main+crash) ====")
        runCatching { appendLogcat(sb) }.onFailure { sb.appendLine("logcat 读取失败: $it") }

        val stamp = SimpleDateFormat("MMdd-HHmmss", Locale.getDefault()).format(Date())
        return writeOut(context, "漫读诊断日志_$stamp.log", sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun appendDeviceHeader(sb: StringBuilder) {
        sb.appendLine("版本: ${com.mangareader.BuildConfig.VERSION_NAME} (versionCode ${com.mangareader.BuildConfig.VERSION_CODE})")
        sb.appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
    }

    private fun appendSrState(sb: StringBuilder, context: Context) {
        sb.appendLine()
        sb.appendLine("---- 超分状态 ----")
        sb.appendLine("sr_engine so 加载(nativeLibraryLoaded): ${runCatching { RealESRGANProcessor.nativeLibraryLoaded }.getOrDefault("?")}")
        sb.appendLine("Vulkan 支持: ${runCatching { VulkanHelper.isVulkanSupported(context) }.getOrDefault("?")}")
        runCatching { VulkanHelper.describe(context) }.onSuccess { if (it.isNotBlank()) sb.appendLine("Vulkan 详情: $it") }
        runCatching {
            if (!SrCore.initialized) SrCore.init(context)
            val s = SrCore.settings
            sb.appendLine("设置: 总开关=${s.enabled} 放大=${s.upscaleEnabled} 模型=${s.model.key}(${s.model.displayName}) 降噪=${s.denoise} GPU优先=${s.enableGpu} 黑白=${s.bwEnabled}")
        }.onFailure { sb.appendLine("设置读取失败: ${it.message}") }
        sb.appendLine("可用档位: ${runCatching { NativeLibraryStatus.getAvailableModels().joinToString { it.displayName } }.getOrDefault("?")}")
        sb.appendLine("已解包模型目录: ${runCatching { File(context.filesDir, "models").list()?.joinToString() ?: "（无）" }.getOrDefault("?")}")
    }

    private fun appendCleanupSelfTest(sb: StringBuilder) {
        sb.appendLine()
        sb.appendLine("---- 快速优化档现场自检（64x64 直走 native 清理路径）----")
        var src: Bitmap? = null
        try {
            src = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            val c = Canvas(src)
            c.drawColor(Color.WHITE)
            c.drawText("漫", 4f, 40f, Paint().apply { color = Color.BLACK; textSize = 32f })
            val t0 = SystemClock.elapsedRealtime()
            val out = try {
                SrCleanupProcessor.process(src, 1)
            } catch (t: Throwable) {
                sb.appendLine("自检抛异常: ${t.javaClass.simpleName}: ${t.message}")
                null
            }
            val ms = SystemClock.elapsedRealtime() - t0
            if (out != null) {
                sb.appendLine("自检: 成功 输出=${out.width}x${out.height} 耗时=${ms}ms")
                sb.appendLine("→ native 清理路径是通的，哑火在调度/显示层（重点查 R8 视图层）")
                if (out !== src) out.recycle()
            } else {
                sb.appendLine("自检: 返回 null 耗时=${ms}ms")
                sb.appendLine("→ 若上面加载状态为 true，则 native 执行失败/返回空，release 编译问题实锤")
            }
        } finally {
            runCatching { src?.recycle() }
        }
    }

    private fun appendLogcat(sb: StringBuilder) {
        val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-b", "main", "-b", "crash", "-v", "time"))
        val text = proc.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        proc.waitFor()
        // logcat 环形缓冲可能很大，封顶保留最近 6MB
        val capped = if (text.length > 6_000_000) "…（前段截断，保留最近 6MB）…\n" + text.takeLast(6_000_000) else text
        sb.append(capped)
        if (capped.isBlank()) sb.appendLine("（logcat 为空：系统未返回本应用日志，需换抓取方式）")
    }

    private fun writeOut(context: Context, fileName: String, bytes: ByteArray): String {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return "导出失败: 无法写入下载目录"
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: return "导出失败: 打开输出流失败"
                "已保存到 手机下载/Download 目录: $fileName"
            } else {
                val f = File(context.getExternalFilesDir(null) ?: context.filesDir, fileName)
                f.writeBytes(bytes)
                "已保存到 ${f.absolutePath}"
            }
        } catch (t: Throwable) {
            "导出失败: ${t.message}"
        }
    }
}
