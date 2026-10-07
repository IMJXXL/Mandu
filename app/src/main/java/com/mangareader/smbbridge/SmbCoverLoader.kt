package com.mangareader.smbbridge

import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.library.IMAGE_EXTENSIONS
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.library.ArchiveCoverCache
import com.hippo.ehviewer.library.CoverEnsureResult
import com.hippo.ehviewer.library.ReadAheadArchiveByteSource
import com.hippo.ehviewer.library.ZipAsDirListing
import com.hippo.ehviewer.library.isDocumentFileName
import com.hippo.ehviewer.library.isTarArchiveFileName
import com.hippo.ehviewer.library.isZipArchiveFileName
import com.hippo.ehviewer.library.isSolidArchiveFileName
import com.hippo.ehviewer.smb.SmbArchiveByteSource
import com.hippo.ehviewer.smb.SmbCache
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
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

    suspend fun loadCoverRawSmb(coverPath: String, wantRaw: Boolean = false): Pair<ByteArray, String>? {
        val currentScope = scope
        val deferred = currentScope.async {
            loadCoverRawSmbInternal(coverPath, wantRaw)
        }
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            deferred.cancel()
            null
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "loadCoverRawSmb failed: ${t.message}")
            null
        }
    }

    private suspend fun loadCoverRawSmbInternal(coverPath: String, wantRaw: Boolean = false): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        // ★ 失败冷却：10 分钟内不重试同路径
        failedAt[coverPath]?.let { ts ->
            if (System.currentTimeMillis() - ts < COVER_RETRY_COOLDOWN_MS) return@withContext null
        }
        try {
            loadCoverRawSmbInner(coverPath, wantRaw)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            failedAt[coverPath] = System.currentTimeMillis()
            throw t
        }
    }

    private suspend fun loadCoverRawSmbInner(coverPath: String, wantRaw: Boolean): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        val ref = SmbPaths.parseCoverPath(coverPath) ?: return@withContext null
        if (SmbUnsupportedFormatHandler.isUnsupported(ref.ext)) return@withContext null
        // ★ 目录封面递归（深度 5，对齐本地算法与 WebDAV 侧）：无直连图向下抓
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
            android.util.Log.w(TAG, "loadCoverRawSmb failed: ${t.message}")
            null
        }
    }

    /** ★ 目录封面递归（深度 5）：直连图 → 直连可扫包 → 子目录自然序递归；全空 null 占位 */
    private suspend fun dirCoverRecursive(ref: SmbBookRef, depth: Int): Pair<ByteArray, String>? {
        if (depth > 5) return null
        // ★ P5：目录路径 dedup 互斥（原 mutex 只护文件封面路径），并发同目录不重复列目录
        val dirMutex = dedup.getOrPut("dir:${ref.fullRel}") { Mutex() }
        dirMutex.withLock {
        val source = SmbRepository.load(ref.serverId) ?: return null
        val password = try { SmbPasswordStore.get(source.id) } catch (_: Throwable) { "" }
        val baseDir = if (source.share.isBlank()) ref.fullRel else ref.rel
        val entries = try {
            SmbGateway.listDirectory(source, password, baseDir)
        } catch (_: Throwable) { return null }
        val children = entries.filter { !it.virtual && !it.hidden }
        val fileNameExt: (String) -> String = { it.substringAfterLast('.', "").lowercase() }
        fun subRef(name: String, ext: String): SmbBookRef {
            val joined = joinRemoteDir(baseDir, name)
            return if (source.share.isBlank())
                SmbBookRef(ref.serverId, "", joined.substringAfter('/'), joined, ext)
            else SmbBookRef(ref.serverId, ref.share, joined, "${ref.share}/$joined", ext)
        }
        // ① 直连图片
        children.filterIsInstance<BrowseEntryRemote.RegularFile>()
            .firstOrNull { fileNameExt(it.name) in IMAGE_EXTENSIONS }?.let { img ->
                return runCatching { loadCoverByArchiveCache(subRef(img.fileName, fileNameExt(img.name)), false)
                    ?: loadCoverByTransientSession(subRef(img.fileName, fileNameExt(img.name)), false) }.getOrNull()
            }
        // ② 直连可扫文件
        children.filterIsInstance<BrowseEntryRemote.RegularFile>()
            .firstOrNull { fileNameExt(it.name) in SmbScanner.SCANNABLE_FORMATS }?.let { arch ->
                return runCatching { loadCoverByArchiveCache(subRef(arch.fileName, fileNameExt(arch.name)), false)
                    ?: loadCoverByTransientSession(subRef(arch.fileName, fileNameExt(arch.name)), false) }.getOrNull()
            }
        // ②.5 直连归档（ArchiveGallery 类型，DirectoryListing 把 zip/cbz/rar 等分类为此类型而非 RegularFile）
        children.filterIsInstance<BrowseEntryRemote.ArchiveGallery>()
            .firstOrNull { fileNameExt(it.name) in SmbScanner.SCANNABLE_FORMATS }?.let { arch ->
                return runCatching { loadCoverByArchiveCache(subRef(arch.fileName, fileNameExt(arch.name)), false)
                    ?: loadCoverByTransientSession(subRef(arch.fileName, fileNameExt(arch.name)), false) }.getOrNull()
            }
        // ③ 子目录递归
        for (d in children.filterIsInstance<BrowseEntryRemote.Directory>().sortedBy { it.name.lowercase() }) {
            if (d.unreachable) continue
            dirCoverRecursive(subRef(d.relativeName, ""), depth + 1)?.let { return it }
        }
        return null
        }
    }

    private fun joinRemoteDir(dir: String, name: String): String =
        if (dir.endsWith('/')) dir + name else "$dir/$name"

    /**
     * 归档/文档封面：[ArchiveCoverCache] 轻量路径。文件夹书返回 null（由 transient session 兜底）。
     */
    private suspend fun loadCoverByArchiveCache(ref: SmbBookRef, wantRaw: Boolean): Pair<ByteArray, String>? {
        if (ref.ext.isEmpty()) return null
        val source = SmbRepository.load(ref.serverId) ?: return null
        val password = try { SmbPasswordStore.get(source.id) } catch (_: Throwable) { "" }

        val remoteRaw = if (source.share.isBlank()) ref.fullRel else ref.rel
        val zipSplit = ZipAsDirListing.splitZipBrowsePath(remoteRaw)
        val remote = if (zipSplit != null && zipSplit.second.isNotEmpty()) zipSplit.first else remoteRaw
        val cacheKey = "smb:${source.id}:$remote"
        val name = remote.substringAfterLast('/').substringAfterLast('\\')

        // ★ 动图首页分流（wantRaw=true）：副档动图 → 原始字节（Coil 平台解码按请求尺寸
        //   播放）；探测确认静图 → 标记后走既有 JPEG 物化路径。每封面至多探测一次。
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
    private suspend fun loadCoverByTransientSession(ref: SmbBookRef, wantRaw: Boolean): Pair<ByteArray, String>? {
        val remoteFile = ref.fullRel.substringBefore(':')
        // ★ 动图首页分流（wantRaw=true，文件夹书）：同归档分支
        val animKey = "smb:${ref.serverId}:$remoteFile"
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

    /**
     * 临时会话取第一页原始字节（动图探测共用）；失败返回 null 不标记，下次重试。
     */
    private suspend fun fetchFirstPageRaw(ref: SmbBookRef): ByteArray? = try {
        SmbCache.withBrowseThumbFetchSlot {
            SmbBookRouter.openTransient(ref) { _, adapter -> adapter.loadPageRaw(0) }
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

    private const val TAG = "SmbCoverLoader"
}
