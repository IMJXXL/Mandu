package com.mangareader.backup.transfer

import com.ehviewer.core.database.model.WebDavSourceEntity
import com.hippo.ehviewer.webdav.WebDavClient
import java.io.File

sealed class TransferResult {
    data class Success(val fileSize: Long, val durationMs: Long) : TransferResult()
    data class Failure(val error: String, val message: String) : TransferResult()
}

object WebDavBackupTransfer {
    suspend fun ensureRemoteDir(
        source: WebDavSourceEntity,
        password: String,
        remoteDir: String,
    ): Boolean {
        val parts = remoteDir.trim('/').split('/').filter { it.isNotEmpty() }
        var current = ""
        for (part in parts) {
            current = "$current/$part"
            WebDavClient.mkcol(source, password, current)
        }
        return true
    }

    suspend fun upload(
        source: WebDavSourceEntity,
        password: String,
        localFile: File,
        remotePath: String,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): TransferResult {
        val startTime = System.currentTimeMillis()
        try {
            if (!source.baseUrl.startsWith("https://")) {
                return TransferResult.Failure("HTTP_NOT_ALLOWED", "WebDAV 连接未使用 HTTPS，明文传输存在风险")
            }
            val remoteDir = remotePath.substringBeforeLast('/', "")
            if (remoteDir.isNotEmpty()) ensureRemoteDir(source, password, remoteDir)
            val size = WebDavClient.uploadFile(source, password, remotePath, localFile, onProgress)
            val duration = System.currentTimeMillis() - startTime
            return TransferResult.Success(size, duration)
        } catch (e: Exception) {
            return TransferResult.Failure("UPLOAD_FAILED", e.message ?: "上传失败")
        }
    }

    suspend fun download(
        source: WebDavSourceEntity,
        password: String,
        remotePath: String,
        localFile: File,
    ): TransferResult {
        val startTime = System.currentTimeMillis()
        try {
            if (!source.baseUrl.startsWith("https://")) {
                return TransferResult.Failure("HTTP_NOT_ALLOWED", "WebDAV 连接未使用 HTTPS，明文传输存在风险")
            }
            localFile.parentFile?.mkdirs()
            java.io.FileOutputStream(localFile).use { out ->
                WebDavClient.downloadFile(source, password, remotePath, out)
            }
            val duration = System.currentTimeMillis() - startTime
            return TransferResult.Success(localFile.length(), duration)
        } catch (e: Exception) {
            localFile.delete()
            return TransferResult.Failure("DOWNLOAD_FAILED", e.message ?: "下载失败")
        }
    }
}