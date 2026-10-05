package com.mangareader.webdavbridge

import android.os.StatFs
import com.hippo.ehviewer.library.RemoteArchiveOpen
import com.hippo.ehviewer.webdav.WebDavCache
import com.hippo.ehviewer.webdav.WebDavClient
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WebDAV 需全量下载格式处理（design.md 2.2.2 WebDavUnsupportedFormatHandler / spec 5.11）。
 * 与 SmbUnsupportedFormatHandler 对称；提示文案由 UI 侧增加"此格式需要完整下载"。
 *
 * `mobi/azw/azw3` 已在 mobi 流式 M2（f48a008b）接入 DocumentExtractPageLoader 记录级流式
 * 管线——此前 dispatch 层流式分支已就绪但本集合未清空，openWebDavBook 的 Unsupported
 * 前置守卫把 mobi 全部拦死在占位+下载（2026-10-06 工单实锤）。集合清空后全链自动放开。
 */
object WebDavUnsupportedFormatHandler {

    val UNSUPPORTED_FORMATS: Set<String> = emptySet()

    fun isUnsupported(ext: String): Boolean = ext.lowercase() in UNSUPPORTED_FORMATS

    /**
     * 完整下载 WebDAV 文件至本地（HTTP GET，WebDavCache 断点续传）。
     * @return 本地路径；null = 失败/空间不足
     */
    suspend fun downloadWhole(
        filePath: String,
        onProgress: ((Float) -> Unit)? = null,
    ): String? = withContext(Dispatchers.IO) {
        val ref = WebDavPaths.parseBookPath(filePath) ?: return@withContext null
        val source = WebDavRepository.load(ref.serverId) ?: return@withContext null
        val password = try { WebDavPasswordStore.get(source.id) } catch (_: Throwable) { "" }

        // 下载前空间检查
        val remoteSize = runCatching {
            WebDavClient.fileSizeOrNull(source, password, ref.remotePath)
        }.getOrNull()
        if (remoteSize != null && remoteSize > 0) {
            val probeDir = generateSequence(
                File(WebDavCache.cachePathForRemoteFile(ref.serverId, ref.remotePath).toString()),
            ) { it.parentFile }.firstOrNull { it.exists() }
            if (probeDir != null) {
                val available = StatFs(probeDir.absolutePath).availableBytes
                if (available < remoteSize + 256L * 1024 * 1024) return@withContext null
            }
        }

        val local = runCatching {
            RemoteArchiveOpen.ensureWebDavArchive(
                source = source,
                password = password,
                remoteRelativeFile = ref.remotePath,
                allowLarge = true,
                onProgress = onProgress,
            )
        }.getOrElse { t ->
            android.util.Log.w("WebDavUnsupported", "downloadWhole failed: ${t.message}")
            return@withContext null
        }
        onProgress?.invoke(1f)
        local.path.toString()
    }
}
