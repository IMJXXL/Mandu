package com.mangareader.smbbridge

import android.os.StatFs
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SMB 不支持格式占位与下载（design.md 2.2.2 SmbUnsupportedFormatHandler / spec 5.5）。
 *
 * `mobi/azw3` 漫读远程阅读器不支持流式提取：Router 返回 Unsupported，UI 显示占位 +
 * "下载到本地后查看"按钮；用户点击后 [downloadWhole] 经 SmbCache 完整下载至本地缓存，
 * 返回本地路径由漫读本地阅读器打开（filePath 改写为本地路径）。
 */
object SmbUnsupportedFormatHandler {

    /** 漫读远程阅读器不支持的格式（epub/pdf 漫读已支持，不入此集合） */
    val UNSUPPORTED_FORMATS: Set<String> = setOf("mobi", "azw3")

    fun isUnsupported(ext: String): Boolean = ext.lowercase() in UNSUPPORTED_FORMATS

    /**
     * 完整下载 SMB 文件至本地。
     * @param filePath smb: 路径
     * @param onProgress 进度回调（0f..1f；整档下载由 RemoteArchiveOpen 内置断点续传，进度只有起止两拍）
     * @return 下载后的本地路径（供本地阅读器打开）；null = 失败/空间不足
     */
    suspend fun downloadWhole(
        filePath: String,
        onProgress: ((Float) -> Unit)? = null,
    ): String? = withContext(Dispatchers.IO) {
        if (!SmbVersionGuard.isSupported()) return@withContext null
        val ref = SmbPaths.parseBookPath(filePath) ?: return@withContext null
        val source = SmbRepository.load(ref.serverId) ?: return@withContext null
        val password = try { SmbPasswordStore.get(source.id) } catch (_: Throwable) { "" }
        // gateway remote：fixed-share 源用去 share 段的 rel；server-root 源用完整路径（首段即 share）
        val remote = if (source.share.isBlank()) ref.fullRel else ref.rel

        // 下载前空间检查（spec 5.5.3 异常 2）
        val remoteSize = runCatching { SmbGateway.fileSizeOrNull(source, password, remote) }.getOrDefault(null)
        if (remoteSize != null && remoteSize > 0) {
            val probeDir = generateSequence(File(SmbCacheProbeRoot(source.id, remote))) { it.parentFile }
                .firstOrNull { it.exists() }
            if (probeDir != null) {
                val available = StatFs(probeDir.absolutePath).availableBytes
                if (available < remoteSize + 256L * 1024 * 1024) return@withContext null // 空间不足
            }
        }

        // 整档下载复用 RemoteArchiveOpen（断点续传、SmbCache 串行化、缓存命中短路均已内置）
        val local = runCatching {
            com.hippo.ehviewer.library.RemoteArchiveOpen.ensureSmbArchive(
                source = source,
                password = password,
                remoteRelativeFile = remote,
                allowLarge = true,
            )
        }.getOrElse { t ->
            android.util.Log.w("SmbUnsupported", "downloadWhole failed: ${t.message}")
            return@withContext null
        }
        onProgress?.invoke(1f)
        local.path.toString()
    }

    private fun SmbCacheProbeRoot(serverId: Long, remote: String): String =
        com.hippo.ehviewer.smb.SmbCache.cachePathForRemoteFile(serverId, remote).toString()
}
