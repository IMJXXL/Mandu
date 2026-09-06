package com.mangareader.webdavbridge

import com.hippo.ehviewer.webdav.WebDavCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path

/**
 * WebDAV 封面加载（design.md 2.2.2 WebDavCoverLoader / spec 5.10）。
 * 与 SmbCoverLoader 同构：缓存 WebDavCache + 与阅读同构的临时会话提取第 0 页（HTTP RANGE GET）。
 * 需全量下载格式（mobi/azw3）返回 null → 占位图。
 */
object WebDavCoverLoader {

    private val dedup = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    suspend fun loadCoverRawWebDav(coverPath: String): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        val ref = WebDavPaths.parseCoverPath(coverPath) ?: return@withContext null
        if (WebDavUnsupportedFormatHandler.isUnsupported(ref.ext)) return@withContext null
        val remoteFile = ref.remotePath.substringBefore(':')

        val mutex = dedup.getOrPut(coverPath) { Mutex() }
        try {
            mutex.withLock {
                val cached = WebDavCache.thumbCachePath(ref.serverId, remoteFile)
                if (WebDavCache.isCachedOnDisk(cached)) {
                    return@withLock cached.readBytesOrNull()?.let { Pair(it, mimeForExt(ref.ext)) }
                }
                val raw = WebDavCache.withBrowseThumbFetchSlot {
                    WebDavBookRouter.openTransient(ref) { _, adapter ->
                        adapter.loadPageRaw(0)
                    }
                } ?: return@withLock null
                runCatching {
                    WebDavCache.ensureBrowseThumb(ref.serverId, remoteFile) { out -> out.write(raw) }
                }
                Pair(raw, mimeForExt(ref.ext))
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "loadCoverRawWebDav failed: ${t.message}")
            null
        }
    }

    private fun Path.readBytesOrNull(): ByteArray? = runCatching {
        java.io.File(toString()).takeIf { it.isFile }?.readBytes()
    }.getOrNull()

    private fun mimeForExt(ext: String): String = when (ext) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "svg", "svgz" -> "image/svg+xml"
        "avif" -> "image/avif"
        "heic", "heif", "hif" -> "image/heic"
        "jxl" -> "image/jxl"
        else -> "image/jpeg"
    }

    private const val TAG = "WebDavCoverLoader"
}
