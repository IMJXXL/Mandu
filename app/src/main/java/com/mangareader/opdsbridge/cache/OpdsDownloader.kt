package com.mangareader.opdsbridge.cache

import com.mangareader.opdsbridge.OpdsConstants
import com.mangareader.opdsbridge.api.OpdsApiClient
import com.mangareader.opdsbridge.api.OpdsException
import java.io.File

sealed class OpdsDownloadResult {
    data class Cached(val path: File) : OpdsDownloadResult()
    data class Downloaded(val path: File) : OpdsDownloadResult()
    data class Failed(val message: String) : OpdsDownloadResult()
}

object OpdsDownloader {

    fun extForMimeType(mimeType: String): String {
        return when {
            mimeType.contains("cbz", true) || mimeType.contains("comicbook+zip", true) -> "cbz"
            mimeType.contains("zip", true) -> "zip"
            mimeType.contains("rar", true) -> "rar"
            mimeType.contains("7z", true) -> "7z"
            mimeType.contains("epub", true) -> "epub"
            mimeType.contains("pdf", true) -> "pdf"
            else -> "bin"
        }
    }

    suspend fun ensurePublication(
        apiClient: OpdsApiClient,
        serverId: String,
        entryId: String,
        acquisitionUrl: String,
        mimeType: String,
        onProgress: ((Long) -> Unit)? = null,
    ): OpdsDownloadResult {
        val ext = extForMimeType(mimeType)
        val cachePath = OpdsCache.cachePathFor(serverId, entryId, ext)

        if (OpdsCache.isCachedOnDisk(cachePath)) {
            OpdsCache.touch(cachePath)
            return OpdsDownloadResult.Cached(cachePath)
        }

        if (!OpdsCache.ensureSpace(50L * 1024 * 1024)) {
            return OpdsDownloadResult.Failed("存储空间不足")
        }

        val tempFile = File(cachePath.parentFile, "${cachePath.name}.tmp")
        try {
            apiClient.downloadToFile(acquisitionUrl, tempFile, onProgress)
            if (!tempFile.exists() || tempFile.length() == 0L) {
                tempFile.delete()
                return OpdsDownloadResult.Failed("下载文件为空")
            }
            if (!tempFile.renameTo(cachePath)) {
                tempFile.copyTo(cachePath, overwrite = true)
                tempFile.delete()
            }
            return OpdsDownloadResult.Downloaded(cachePath)
        } catch (e: OpdsException) {
            tempFile.delete()
            return OpdsDownloadResult.Failed(e.message ?: "下载失败")
        } catch (t: Throwable) {
            tempFile.delete()
            return OpdsDownloadResult.Failed("下载失败: ${t.message}")
        }
    }
}