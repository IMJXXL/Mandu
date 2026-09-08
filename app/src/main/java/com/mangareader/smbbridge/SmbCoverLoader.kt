package com.mangareader.smbbridge

import com.hippo.ehviewer.library.ArchiveCoverCache
import com.hippo.ehviewer.library.CoverEnsureResult
import com.hippo.ehviewer.library.ReadAheadArchiveByteSource
import com.hippo.ehviewer.library.ZipAsDirListing
import com.hippo.ehviewer.library.isDocumentFileName
import com.hippo.ehviewer.library.isSolidArchiveFileName
import com.hippo.ehviewer.smb.SmbArchiveByteSource
import com.hippo.ehviewer.smb.SmbCache
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path

/**
 * SMB 封面加载（design.md 2.2.2 SmbCoverLoader / spec 5.4）。
 *
 * 归档/文档格式走 [ArchiveCoverCache] 轻量路径（与参考项目 BrowseCover.SmbArchive 对齐）：
 * - ZIP/CBZ：EOCD+CD 仅扫中央目录 + seek index 复用
 * - TAR/CBT：扫到首张图即停（[ArchiveCoverCache.NETWORK_NON_ZIP_SCAN_CAP] 预算）
 * - RAR/7z/CBR：[ArchiveCoverCache.ensureSolidStreamCover] 顺序首个 playable 成员
 * - PDF/EPUB：[ArchiveCoverCache.ensureDocumentStreamCover] coverOnly 引擎
 * - 缩略图 JPEG 落盘 `cache/archive_thumb/`（与阅读器 solid/document extract 共享）
 *
 * 文件夹书（ext 为空）无归档可扫，仍走 [SmbBookRouter.openTransient] 列目录取首图。
 *
 * 并发去重：同一封面 key 的并发请求串行化。失败/超时 → null（CoverFetcher fallback 占位图）。
 */
object SmbCoverLoader {

    private val dedup = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    suspend fun loadCoverRawSmb(coverPath: String, wantRaw: Boolean = false): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        if (!SmbVersionGuard.isSupported()) return@withContext null
        val ref = SmbPaths.parseCoverPath(coverPath) ?: return@withContext null
        if (SmbUnsupportedFormatHandler.isUnsupported(ref.ext)) return@withContext null

        val mutex = dedup.getOrPut(coverPath) { Mutex() }
        try {
            mutex.withLock {
                // 归档/文档格式永远先走缩略图降采样（loadCoverByArchiveCache），
                // 避免下载 10MB+ 原始页（wantRaw 仅对文件夹书/直接图片文件有意义，
                // 它们的 loadCoverByArchiveCache 返回 null 后 fallback 到原始字节路径）。
                loadCoverByArchiveCache(ref) ?: loadCoverByTransientSession(ref)
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "loadCoverRawSmb failed: ${t.message}")
            null
        }
    }

    /**
     * 归档/文档封面：[ArchiveCoverCache] 轻量路径。文件夹书返回 null（由 transient session 兜底）。
     */
    private suspend fun loadCoverByArchiveCache(ref: SmbBookRef): Pair<ByteArray, String>? {
        if (ref.ext.isEmpty()) return null
        val source = SmbRepository.load(ref.serverId) ?: return null
        val password = try { SmbPasswordStore.get(source.id) } catch (_: Throwable) { "" }

        val remoteRaw = if (source.share.isBlank()) ref.fullRel else ref.rel
        val zipSplit = ZipAsDirListing.splitZipBrowsePath(remoteRaw)
        val remote = if (zipSplit != null && zipSplit.second.isNotEmpty()) zipSplit.first else remoteRaw
        val cacheKey = "smb:${source.id}:$remote"
        val name = remote.substringAfterLast('/').substringAfterLast('\\')

        val diskHit = ArchiveCoverCache.tryDiskCover(cacheKey)
        if (diskHit != null) return readJpegBytes(diskHit)

        val solid = isSolidArchiveFileName(name)
        val result = if (solid) {
            ArchiveCoverCache.ensureSolidStreamCover(cacheKey) {
                SmbArchiveByteSource(
                    source = source,
                    password = password,
                    remoteRelativeFile = remote,
                    preferSequential = true,
                    pipeline = false,
                    sequentialWindow = ReadAheadArchiveByteSource.COVER_WINDOW,
                    yieldable = true,
                )
            }
        } else {
            ArchiveCoverCache.ensureStreamCover(cacheKey) {
                SmbArchiveByteSource(
                    source = source,
                    password = password,
                    remoteRelativeFile = remote,
                    pipeline = !isDocumentFileName(name),
                    yieldable = true,
                )
            }
        }
        return when (result) {
            is CoverEnsureResult.Hit -> readJpegBytes(result.path)
            CoverEnsureResult.NoImages, CoverEnsureResult.Skip -> null
        }
    }

    /**
     * 文件夹书封面（ext 为空）：临时会话列目录取首图。
     * 归档格式理论上不应走到这里（[loadCoverByArchiveCache] 已处理），但作为兜底保留。
     */
    private suspend fun loadCoverByTransientSession(ref: SmbBookRef): Pair<ByteArray, String>? {
        val remoteFile = ref.fullRel.substringBefore(':')
        val cached = SmbCache.thumbCachePath(ref.serverId, remoteFile)
        if (SmbCache.isCachedOnDisk(cached)) {
            return cached.readBytesOrNull()?.let { Pair(it, mimeForExt(ref.ext)) }
        }
        val raw = SmbCache.withBrowseThumbFetchSlot {
            SmbBookRouter.openTransient(ref) { _, adapter ->
                adapter.loadPageRaw(0)
            }
        } ?: return null
        runCatching {
            SmbCache.ensureBrowseThumb(ref.serverId, remoteFile) { out -> out.write(raw) }
        }
        return Pair(raw, mimeForExt(ref.ext))
    }

    private fun readJpegBytes(path: Path): Pair<ByteArray, String>? {
        val f = File(path.toString())
        if (!f.isFile || f.length() == 0L) return null
        return Pair(f.readBytes(), "image/jpeg")
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
