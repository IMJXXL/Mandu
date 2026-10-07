package com.mangareader.webdavbridge

import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.library.IMAGE_EXTENSIONS
import com.hippo.ehviewer.webdav.WebDavGateway
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

    // ★ 封面失败冷却（10 分钟）：书架重组不再反复打服务器
    private val failedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val COVER_RETRY_COOLDOWN_MS = 10L * 60_000L

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
        // ★ 失败冷却（2026-10-07 工单"封面失败反复打服务器"）：10 分钟内不再重试同路径
        failedAt[coverPath]?.let { ts ->
            if (System.currentTimeMillis() - ts < COVER_RETRY_COOLDOWN_MS) return@withContext null
        }
        try {
            loadCoverRawWebDavInner(coverPath, wantRaw)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            failedAt[coverPath] = System.currentTimeMillis()
            throw t
        }
    }

    private suspend fun loadCoverRawWebDavInner(coverPath: String, wantRaw: Boolean): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        val ref = WebDavPaths.parseCoverPath(coverPath) ?: return@withContext null
        if (WebDavUnsupportedFormatHandler.isUnsupported(ref.ext)) return@withContext null
        // ★ 目录封面递归（2026-10-07 工单"TOC 中间层目录占位"）：目录 ref 不再直撞文件夹
        //   分支的"无直连图"check——先列目录，直连图→直连包→递归子目录（深度 5，对齐本地
        //   findFirstCoverInSubDirs），结果由上层磁盘缓存兜住只付一次
        if (ref.ext.isEmpty()) {
            return@withContext dirCoverRecursive(ref, 0)?.also { failedAt.remove(coverPath) }
                ?: null.also { failedAt[coverPath] = System.currentTimeMillis() }
        }

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

    /** ★ 目录封面递归（深度 5）：直连图 → 直连可扫包 → 子目录自然序递归；全空返回 null 占位 */
    private suspend fun dirCoverRecursive(ref: WebDavBookRef, depth: Int): Pair<ByteArray, String>? {
        if (depth > 5) return null
        // ★ P5：目录路径 dedup 互斥（原 mutex 只护文件封面路径），并发同目录不重复列目录
        val dirMutex = dedup.getOrPut("dir:${ref.remotePath}") { Mutex() }
        dirMutex.withLock {
        val source = WebDavRepository.load(ref.serverId) ?: return null
        val password = try { WebDavPasswordStore.get(source.id) } catch (_: Throwable) { "" }
        val entries = try {
            WebDavGateway.listDirectory(source, password, ref.remotePath)
        } catch (_: Throwable) { return null }
        val children = entries.filter { !it.virtual && !it.hidden }
        val fileNameExt: (String) -> String = { it.substringAfterLast('.', "").lowercase() }
        // ① 直连图片（自然序第一个）
        val img = children.filterIsInstance<BrowseEntryRemote.RegularFile>()
            .firstOrNull { fileNameExt(it.name) in IMAGE_EXTENSIONS }
        if (img != null) {
            val sub = WebDavBookRef(ref.serverId, joinRemoteDir(ref.remotePath, img.fileName), fileNameExt(img.name))
            return runCatching { loadCoverByArchiveCache(sub, false) ?: loadCoverByTransientSession(sub, false) }.getOrNull()
        }
        // ② 直连可扫文件
        val arch = children.filterIsInstance<BrowseEntryRemote.RegularFile>()
            .firstOrNull { fileNameExt(it.name) in WebDavScanner.SCANNABLE_FORMATS }
        if (arch != null) {
            val sub = WebDavBookRef(ref.serverId, joinRemoteDir(ref.remotePath, arch.fileName), fileNameExt(arch.name))
            return runCatching { loadCoverByArchiveCache(sub, false) ?: loadCoverByTransientSession(sub, false) }.getOrNull()
        }
        // ③ 子目录递归
        for (d in children.filterIsInstance<BrowseEntryRemote.Directory>().sortedBy { it.name.lowercase() }) {
            if (d.unreachable) continue
            val sub = WebDavBookRef(ref.serverId, joinRemoteDir(ref.remotePath, d.relativeName), "")
            dirCoverRecursive(sub, depth + 1)?.let { return it }
        }
        return null
        }
    }

    private fun joinRemoteDir(dir: String, name: String): String =
        if (dir.endsWith('/')) dir + name else "$dir/$name"

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
