package com.mangareader.sr

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.mangareader.updatecheck.UpdateConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * NPU 模型包应用内下载与安装（2026-10-03）。
 *
 * 托管：GitHub Release 公开附件（IMJXXL/mandu，tag `npu-model`），直链无需任何鉴权——
 * 不要把任何 token 打进 APK（解包即泄露）。下载复用 [UpdateConstants.MIRRORS] 的
 * ghfast.top / ghproxy.net 加速线路，官方线路失败自动换下一路。
 *
 * 安装：FileProvider + ACTION_VIEW 交系统安装器；API 26+ 需先授予「安装未知应用」。
 * 装好后 [NpuModelRegistry.refresh] 重扫即可见（同签校验由插件扫描器完成）。
 */
object NpuModelDownload {

    private const val TAG = "NpuModelDownload"
    private const val RELEASE_BASE = "https://github.com/IMJXXL/mandu/releases/download/npu-model"
    private const val FILE_PREFIX = "Mandu-NPU-v"
    private const val FILE_SUFFIX = ".apk"
    private const val CONNECT_TIMEOUT = 15000
    private const val READ_TIMEOUT = 30000

    /** Release 页（含全部代次模型包与 v 代↔处理器对照表），浏览器打开自行挑包 */
    const val RELEASE_PAGE_URL = "https://github.com/IMJXXL/mandu/releases/tag/npu-model"

    fun urlFor(arch: Int): String = "$RELEASE_BASE/$FILE_PREFIX$arch$FILE_SUFFIX"

    /** 浏览器直下用 URL 列表（官方 + 加速线路，同 UpdateConstants.MIRRORS 口径） */
    fun browserUrls(arch: Int): List<Pair<String, String>> =
        UpdateConstants.MIRRORS.map { (prefix, label) -> label to (prefix + urlFor(arch)) }

    /** 交给浏览器自己下载（GitHub 302、断点续传都由浏览器处理），下载完手动点开 APK 安装 */
    fun openInBrowser(context: Context, url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w(TAG, "open browser failed: ${it.message}") }
    }

    suspend fun download(context: Context, arch: Int, onProgress: (Int) -> Unit): File? =
        withContext(Dispatchers.IO) {
            val fileName = "$FILE_PREFIX$arch$FILE_SUFFIX"
            val dir = File(context.cacheDir, "update_apk").apply { mkdirs() }
            val out = File(dir, fileName)
            val tmp = File(dir, "$fileName.tmp")
            val sources = UpdateConstants.MIRRORS.map { (prefix, _) -> prefix + urlFor(arch) }
            for (src in sources) {
                try {
                    val conn = URL(src).openConnection() as HttpURLConnection
                    conn.connectTimeout = CONNECT_TIMEOUT
                    conn.readTimeout = READ_TIMEOUT
                    conn.instanceFollowRedirects = true
                    if (conn.responseCode !in 200..299) {
                        conn.disconnect()
                        continue
                    }
                    val total = conn.contentLengthLong
                    var copied = 0L
                    conn.inputStream.use { input ->
                        tmp.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                copied += n
                                if (total > 0) {
                                    onProgress((copied * 100 / total).toInt().coerceIn(0, 100))
                                }
                            }
                        }
                    }
                    conn.disconnect()
                    if (!tmp.renameTo(out)) {
                        tmp.copyTo(out, overwrite = true)
                        tmp.delete()
                    }
                    return@withContext out
                } catch (e: Exception) {
                    Log.w(TAG, "download failed: ${e.message}")
                    tmp.delete()
                }
            }
            null
        }

    enum class InstallResult { LAUNCHED, NEED_PERMISSION, FAILED }

    fun install(context: Context, apk: File): InstallResult {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()
            ) {
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return InstallResult.NEED_PERMISSION
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            InstallResult.LAUNCHED
        } catch (e: Exception) {
            Log.e(TAG, "install failed: ${e.message}")
            InstallResult.FAILED
        }
    }
}
