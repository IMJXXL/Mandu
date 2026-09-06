package com.mangareader.smbbridge

import com.hippo.ehviewer.smb.SmbCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path

/**
 * SMB 封面加载（design.md 2.2.2 SmbCoverLoader / spec 5.4）。
 *
 * - coverPath：`cv:smb:{serverId}:{share}/{relativePath}`
 * - 缓存：SmbCache thumb 缓存（thumbCachePath，命中直接回字节）
 * - 提取：与阅读同构的临时会话（[SmbBookRouter.openTransient]）+ 第 0 页原始字节，
 *   流式格式仅下载第 0 页所需字节，solid/PDF 由对应 loader 的封面/顺序提取机制处理
 * - 并发去重：同一封面 key 的并发请求串行化，仅提取一次
 * - 失败/超时 → null（CoverFetcher fallback 占位图，不阻塞书架）
 */
object SmbCoverLoader {

    private val dedup = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    suspend fun loadCoverRawSmb(coverPath: String): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        if (!SmbVersionGuard.isSupported()) return@withContext null
        val ref = SmbPaths.parseCoverPath(coverPath) ?: return@withContext null
        if (SmbUnsupportedFormatHandler.isUnsupported(ref.ext)) return@withContext null
        // 缩略图缓存键用完整路径（稳定唯一；成员后缀剥除，`a.zip:inner` → `a.zip`）
        val remoteFile = ref.fullRel.substringBefore(':')

        val mutex = dedup.getOrPut(coverPath) { Mutex() }
        try {
            mutex.withLock {
                // 1) 缓存命中
                val cached = SmbCache.thumbCachePath(ref.serverId, remoteFile)
                if (SmbCache.isCachedOnDisk(cached)) {
                    return@withLock cached.readBytesOrNull()?.let { Pair(it, mimeForExt(ref.ext)) }
                }
                // 2) 临时会话提取第 0 页
                // ★ 并发节流（对齐参考项目）：书架滚动时多本封面同时提取会挤占 SMB 连接池，
                //   withBrowseThumbFetchSlot 限 3 并发，滚动更流畅
                val raw = SmbCache.withBrowseThumbFetchSlot {
                    SmbBookRouter.openTransient(ref) { _, adapter ->
                        adapter.loadPageRaw(0)
                    }
                } ?: return@withLock null
                // 3) 写缓存（fire-and-forget 失败不影响返回）
                runCatching {
                    SmbCache.ensureBrowseThumb(ref.serverId, remoteFile) { out -> out.write(raw) }
                }
                Pair(raw, mimeForExt(ref.ext))
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "loadCoverRawSmb failed: ${t.message}")
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

    private const val TAG = "SmbCoverLoader"
}
