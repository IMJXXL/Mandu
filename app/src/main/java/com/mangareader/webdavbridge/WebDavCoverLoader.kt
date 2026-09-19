package com.mangareader.webdavbridge

import com.hippo.ehviewer.library.ArchiveCoverCache
import com.hippo.ehviewer.library.CoverEnsureResult
import com.hippo.ehviewer.library.ReadAheadArchiveByteSource
import com.hippo.ehviewer.library.ZipAsDirListing
import com.hippo.ehviewer.library.isDocumentFileName
import com.hippo.ehviewer.library.isSolidArchiveFileName
import com.hippo.ehviewer.library.isTarArchiveFileName
import com.hippo.ehviewer.library.isZipArchiveFileName
import com.hippo.ehviewer.webdav.WebDavArchiveByteSource
import com.hippo.ehviewer.webdav.WebDavCache
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path

/**
 * WebDAV 封面加载（design.md 2.2.2 WebDavCoverLoader / spec 5.10）。
 *
 * 归档/文档格式走 [ArchiveCoverCache] 轻量路径（与参考项目 BrowseCover.WebDavArchive 对齐）：
 * - ZIP/CBZ：EOCD+CD 仅扫中央目录 + seek index 复用
 * - TAR/CBT：扫到首张图即停
 * - RAR/7z/CBR：[ArchiveCoverCache.ensureSolidStreamCover] 顺序首个 playable 成员
 * - PDF/EPUB：[ArchiveCoverCache.ensureDocumentStreamCover] coverOnly 引擎
 * - 缩略图 JPEG 落盘 `cache/archive_thumb/`（与阅读器 solid/document extract 共享）
 *
 * 文件夹书（ext 为空）无归档可扫，仍走 [WebDavBookRouter.openTransient] 列目录取首图。
 * 需全量下载格式（mobi/azw3）返回 null → 占位图。
 */
object WebDavCoverLoader {

    private val dedup = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    @Volatile
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun cancelAll() {
        val old = scope
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        old.cancel()
    }

    suspend fun loadCoverRawWebDav(coverPath: String, wantRaw: Boolean = false): Pair<ByteArray, String>? {
        val currentScope = scope
        val deferred = currentScope.async {
            loadCoverRawWebDavInternal(coverPath, wantRaw)
        }
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            deferred.cancel()
            null
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "loadCoverRawWebDav failed: ${t.message}")
            null
        }
    }

    private suspend fun loadCoverRawWebDavInternal(coverPath: String, wantRaw: Boolean = false): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        val ref = WebDavPaths.parseCoverPath(coverPath) ?: return@withContext null
        if (WebDavUnsupportedFormatHandler.isUnsupported(ref.ext)) return@withContext null

        val mutex = dedup.getOrPut(coverPath) { Mutex() }
        try {
            mutex.withLock {
                loadCoverByArchiveCache(ref, wantRaw) ?: loadCoverByTransientSession(ref, wantRaw)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "loadCoverRawWebDav failed: ${t.message}")
            null
        }
    }

    private suspend fun loadCoverByArchiveCache(ref: WebDavBookRef, wantRaw: Boolean): Pair<ByteArray, String>? {
        if (ref.ext.isEmpty()) return null
        val source = WebDavRepository.load(ref.serverId) ?: return null
        val password = try { WebDavPasswordStore.get(source.id) } catch (_: Throwable) { "" }

        val remoteRaw = ref.remotePath
        val zipSplit = ZipAsDirListing.splitZipBrowsePath(remoteRaw)
        val remote = if (zipSplit != null && zipSplit.second.isNotEmpty()) zipSplit.first else remoteRaw
        val cacheKey = "webdav:${source.id}:$remote"
        val name = remote.substringAfterLast('/').substringAfterLast('\\')

        // ★ 动图首页分流（wantRaw=true）：动图 → 原始字节；静图 → 既有 JPEG 物化
        if (wantRaw && !com.mangareader.viewer.CoverAnimRawCache.isStatic(cacheKey)) {
            com.mangareader.viewer.CoverAnimRawCache.readRaw(cacheKey)
                ?.let { return Pair(it, com.mangareader.viewer.CoverAnimRawCache.mimeOf(it)) }
            val probeable = isZipArchiveFileName(name) || isTarArchiveFileName(name) || isSolidArchiveFileName(name)
            if (probeable) {
                fetchFirstPageRaw(ref)?.let { raw ->
                    if (com.mangareader.viewer.ArchiveEngine.isAnimatedBytes(raw)) {
                        com.mangareader.viewer.CoverAnimRawCache.writeRaw(cacheKey, raw)
                        return Pair(raw, com.mangareader.viewer.CoverAnimRawCache.mimeOf(raw))
                    }
                    com.mangareader.viewer.CoverAnimRawCache.markStatic(cacheKey)
                }
            } else {
                com.mangareader.viewer.CoverAnimRawCache.markStatic(cacheKey)
            }
        }

        val diskHit = ArchiveCoverCache.tryDiskCover(cacheKey)
        if (diskHit != null) return readJpegBytes(diskHit)

        val solid = isSolidArchiveFileName(name)
        val result = if (solid) {
            ArchiveCoverCache.ensureSolidStreamCover(cacheKey) {
                WebDavArchiveByteSource(
                    source = source,
                    password = password,
                    remoteRelativeFile = remote,
                    preferSequential = true,
                    pipeline = false,
                    sequentialWindow = ReadAheadArchiveByteSource.COVER_WINDOW,
                )
            }
        } else {
            ArchiveCoverCache.ensureStreamCover(cacheKey) {
                WebDavArchiveByteSource(
                    source = source,
                    password = password,
                    remoteRelativeFile = remote,
                    pipeline = !isDocumentFileName(name),
                )
            }
        }
        return when (result) {
            is CoverEnsureResult.Hit -> readJpegBytes(result.path)
            CoverEnsureResult.NoImages, CoverEnsureResult.Skip -> null
        }
    }

    private suspend fun loadCoverByTransientSession(ref: WebDavBookRef, wantRaw: Boolean): Pair<ByteArray, String>? {
        val remoteFile = ref.remotePath.substringBefore(':')
        // ★ 动图首页分流（wantRaw=true，文件夹书）：同归档分支
        val animKey = "webdav:${ref.serverId}:$remoteFile"
        if (wantRaw && !com.mangareader.viewer.CoverAnimRawCache.isStatic(animKey)) {
            com.mangareader.viewer.CoverAnimRawCache.readRaw(animKey)
                ?.let { return Pair(it, com.mangareader.viewer.CoverAnimRawCache.mimeOf(it)) }
            fetchFirstPageRaw(ref)?.let { raw ->
                if (com.mangareader.viewer.ArchiveEngine.isAnimatedBytes(raw)) {
                    com.mangareader.viewer.CoverAnimRawCache.writeRaw(animKey, raw)
                    return Pair(raw, com.mangareader.viewer.CoverAnimRawCache.mimeOf(raw))
                }
                com.mangareader.viewer.CoverAnimRawCache.markStatic(animKey)
            }
        }
        val cached = WebDavCache.thumbCachePath(ref.serverId, remoteFile)
        if (WebDavCache.isCachedOnDisk(cached)) {
            return cached.readBytesOrNull()?.let { Pair(it, mimeForExt(ref.ext)) }
        }
        val raw = WebDavCache.withBrowseThumbFetchSlot {
            WebDavBookRouter.openTransient(ref) { _, adapter ->
                adapter.loadPageRaw(0)
            }
        } ?: return null
        runCatching {
            WebDavCache.ensureBrowseThumb(ref.serverId, remoteFile) { out -> out.write(raw) }
        }
        return Pair(raw, mimeForExt(ref.ext))
    }

    /**
     * 临时会话取第一页原始字节（动图探测共用）；失败返回 null 不标记，下次重试。
     */
    private suspend fun fetchFirstPageRaw(ref: WebDavBookRef): ByteArray? = try {
        WebDavCache.withBrowseThumbFetchSlot {
            WebDavBookRouter.openTransient(ref) { _, adapter -> adapter.loadPageRaw(0) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
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

    private const val TAG = "WebDavCoverLoader"
}
