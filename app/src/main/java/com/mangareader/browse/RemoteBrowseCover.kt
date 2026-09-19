package com.mangareader.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.library.ArchiveCoverCache
import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.library.ZipAsDirListing
import com.hippo.ehviewer.library.isImageFileName
import com.hippo.ehviewer.library.isZipArchiveFileName
import com.hippo.ehviewer.smb.SmbCache
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.webdav.WebDavCache
import com.hippo.ehviewer.webdav.WebDavClient
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path
import okio.Path.Companion.toPath
import splitties.init.appCtx

/**
 * 浏览页封面（对齐上游 BrowseCover/BrowseCoverThumb，结合本项目加载器，2026-09-19）。
 *
 * 上游浏览网格的封面都是**小尺寸 JPEG 缩略图**（OriginCache/thumb 管线），静态绘制；
 * 动图播放属书架/子项封面（Coil 平台动图解码），浏览缩略图不承担动画 —— 本实现同构：
 * - 图片/zip 成员封面：`SmbCache/WebDavCache.ensureBrowseThumb`（B3 预埋：LRU + 去重 +
 *   thumbFetchSlots 并发闸 + HdrConvertCache 小图编码），下载走 `downloadFile`
 *   （内部自带 zip 成员路径处理）
 * - 归档封面：`SmbCoverLoader/WebDavCoverLoader`（ArchiveCoverCache 物化 JPEG 首页，
 *   rar/7z/pdf/epub/zip 全格式，磁盘命中零网络）
 * - 流量门：图片封面 `downloadRemoteThumbs`、归档首页 `downloadNetworkArchiveThumbs`
 *   （磁盘已缓存的封面不受门限制，离线可见）
 */
sealed interface BrowseCover {
    /** 远程图片文件或 zip 成员图片（`a.zip/Album/1.jpg` 亦可，downloadFile 可取）。 */
    data class SmbImage(val sourceId: Long, val remote: String) : BrowseCover

    /** 归档文件首页（remote = 扫描空间相对路径）。 */
    data class SmbArchive(val sourceId: Long, val remote: String) : BrowseCover

    data class DavImage(val sourceId: Long, val remote: String) : BrowseCover

    data class DavArchive(val sourceId: Long, val remote: String) : BrowseCover

    val cacheKey: String
        get() = when (this) {
            is SmbImage -> "smbi\u0000$sourceId\u0000$remote"
            is SmbArchive -> "smba\u0000$sourceId\u0000$remote"
            is DavImage -> "davi\u0000$sourceId\u0000$remote"
            is DavArchive -> "dava\u0000$sourceId\u0000$remote"
        }
}

object RemoteBrowseCoverLoader {

    /**
     * 确保封面缩略图存在并返回 JPEG 路径；不满足流量门/取数失败返回 null（UI 显示占位）。
     * 结果文件按 cover 内容寻址，进程内重复进入不再重取。
     */
    suspend fun ensure(cover: BrowseCover): Path? = withContext(Dispatchers.IO) {
        runCatching { ensureInternal(cover) }.getOrNull()
    }

    private suspend fun ensureInternal(cover: BrowseCover): Path? = when (cover) {
        is BrowseCover.SmbImage -> smbImageThumb(cover.sourceId, cover.remote)
        is BrowseCover.DavImage -> davImageThumb(cover.sourceId, cover.remote)
        is BrowseCover.SmbArchive -> archiveThumb(
            kind = "smba",
            // cacheKey 与 SmbCoverLoader 内部口径一致：fixed-share 源的 cover.remote 已是
            // 去 share 的 prefix-space 路径；server-root 源首段即 share，两者均可直拼
            cached = { ArchiveCoverCache.tryDiskCover("smb:${cover.sourceId}:${cover.remote}") != null },
            gate = Settings.downloadNetworkArchiveThumbs.value,
            bytes = { loadSmbArchiveCoverBytes(cover.sourceId, cover.remote) },
        )
        is BrowseCover.DavArchive -> archiveThumb(
            kind = "dava",
            cached = { davArchiveCoverCached(cover.sourceId, cover.remote) },
            gate = Settings.downloadNetworkArchiveThumbs.value,
            bytes = { loadDavArchiveCoverBytes(cover.sourceId, cover.remote) },
        )
    }

    /** 图片/成员封面：thumb 磁盘命中直接用；未命中受 downloadRemoteThumbs 门控。 */
    private suspend fun smbImageThumb(sourceId: Long, remote: String): Path? {
        val hit = SmbCache.isCachedOnDisk(SmbCache.thumbCachePath(sourceId, remote))
        if (!hit && !Settings.downloadRemoteThumbs.value) return null
        return SmbCache.ensureBrowseThumb(sourceId, remote, cacheOriginal = false) { out ->
            val source = SmbRepository.load(sourceId) ?: return@ensureBrowseThumb
            val password = try { SmbPasswordStore.get(sourceId) } catch (_: Throwable) { "" }
            SmbGateway.downloadFile(source, password, remote, out)
        }
    }

    private suspend fun davImageThumb(sourceId: Long, remote: String): Path? {
        val hit = WebDavCache.isCachedOnDisk(WebDavCache.thumbCachePath(sourceId, remote))
        if (!hit && !Settings.downloadRemoteThumbs.value) return null
        return WebDavCache.ensureBrowseThumb(sourceId, remote, cacheOriginal = false) { out ->
            val source = WebDavRepository.load(sourceId) ?: return@ensureBrowseThumb
            val password = try { WebDavPasswordStore.get(sourceId) } catch (_: Throwable) { "" }
            WebDavClient.downloadFile(source, password, remote, out)
        }
    }

    /**
     * 归档封面：SmbCoverLoader/WebDavCoverLoader 返回物化 JPEG 字节；
     * 写入内容寻址的 browse_thumbs 文件后复用（磁盘命中时零网络字节）。
     */
    private suspend fun archiveThumb(
        kind: String,
        cached: suspend () -> Boolean,
        gate: Boolean,
        bytes: suspend () -> Pair<ByteArray, String>?,
    ): Path? {
        val diskHit = cached()
        if (!diskHit && !gate) return null
        val raw = bytes() ?: return null
        val (data, _) = raw
        val dest = browseThumbFile("${kind}_${sha256(data).take(20)}")
        if (dest.isFile && dest.length() > 0L) return dest.absolutePath.toPath()
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, "${dest.name}.tmp.${System.nanoTime()}")
        try {
            tmp.writeBytes(data)
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        return dest.absolutePath.toPath()
    }

    private suspend fun loadSmbArchiveCoverBytes(sourceId: Long, remote: String): Pair<ByteArray, String>? {
        val source = SmbRepository.load(sourceId) ?: return null
        val cv = com.mangareader.smbbridge.SmbPaths.coverPath(sourceId, source.share, remote)
        return com.mangareader.smbbridge.SmbCoverLoader.loadCoverRawSmb(cv)
    }

    private suspend fun loadDavArchiveCoverBytes(sourceId: Long, remote: String): Pair<ByteArray, String>? {
        val cv = com.mangareader.webdavbridge.WebDavPaths.coverPath(sourceId, remote)
        return com.mangareader.webdavbridge.WebDavCoverLoader.loadCoverRawWebDav(cv)
    }

    /**
     * WebDAV 归档封面磁盘命中探测：WebDavCoverLoader 无独立 tryDiskCover 入口，
     * 用封面 cv 路径直查 ArchiveCoverCache（cacheKey "webdav:{id}:{remote}" 与其内部一致）。
     */
    private suspend fun davArchiveCoverCached(sourceId: Long, remote: String): Boolean =
        ArchiveCoverCache.tryDiskCover("webdav:$sourceId:$remote") != null

    private fun browseThumbFile(name: String): File = File(File(appCtx.applicationInfo.dataDir, "cache/browse_thumbs"), "$name.jpg")

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}

/**
 * 列表条目 → 封面解析（对齐上游 dirCoverFor/archiveCoverFor 的组合规则）：
 * - zip/cbz 目录行 / 压缩包行 → 归档首页
 * - 目录/文件夹画廊行带 coverFileName → 图片或 zip 成员路径
 * - 独立图片文件 → 图片封面；其余 null（占位图标）
 */
object BrowseCovers {

    fun smbCover(sourceId: Long, relativeDir: String, e: BrowseEntryRemote): BrowseCover? = when (e) {
        is BrowseEntryRemote.Directory -> when {
            // zip/cbz 伪装目录行：内部封面名优先（zip 成员路径，downloadFile 可取），否则归档首页兜底
            ZipAsDirListing.zipFileRootName(e.relativeName) != null -> {
                val zipRoot = ZipAsDirListing.zipFileRootName(e.relativeName)!!
                val coverName = e.coverFileName
                when {
                    coverName != null && isImageFileName(coverName) ->
                        BrowseCover.SmbImage(sourceId, join(join(relativeDir, zipRoot), coverName))
                    else -> BrowseCover.SmbArchive(sourceId, join(relativeDir, zipRoot))
                }
            }
            e.coverFileName != null && isImageFileName(e.coverFileName) ->
                BrowseCover.SmbImage(sourceId, join(join(relativeDir, e.relativeName), e.coverFileName!!))
            else -> null
        }
        is BrowseEntryRemote.FolderGallery -> when {
            e.coverFileName != null && isImageFileName(e.coverFileName) ->
                BrowseCover.SmbImage(sourceId, join(join(relativeDir, e.relativeName), e.coverFileName!!))
            // zip-as-dir 画廊行无封面名 → 归档首页
            ZipAsDirListing.zipFileRootName(e.relativeName) != null ->
                BrowseCover.SmbArchive(sourceId, join(relativeDir, ZipAsDirListing.zipFileRootName(e.relativeName)!!))
            else -> null
        }
        is BrowseEntryRemote.ArchiveGallery ->
            BrowseCover.SmbArchive(sourceId, join(relativeDir, e.fileName))
        is BrowseEntryRemote.RegularFile ->
            if (isImageFileName(e.fileName)) BrowseCover.SmbImage(sourceId, join(relativeDir, e.fileName)) else null
        else -> null
    }

    fun davCover(sourceId: Long, relativeDir: String, e: BrowseEntryRemote): BrowseCover? = when (e) {
        is BrowseEntryRemote.Directory -> when {
            ZipAsDirListing.zipFileRootName(e.relativeName) != null -> {
                val zipRoot = ZipAsDirListing.zipFileRootName(e.relativeName)!!
                val coverName = e.coverFileName
                when {
                    coverName != null && isImageFileName(coverName) ->
                        BrowseCover.DavImage(sourceId, join(join(relativeDir, zipRoot), coverName))
                    else -> BrowseCover.DavArchive(sourceId, join(relativeDir, zipRoot))
                }
            }
            e.coverFileName != null && isImageFileName(e.coverFileName) ->
                BrowseCover.DavImage(sourceId, join(join(relativeDir, e.relativeName), e.coverFileName!!))
            else -> null
        }
        is BrowseEntryRemote.FolderGallery -> when {
            e.coverFileName != null && isImageFileName(e.coverFileName) ->
                BrowseCover.DavImage(sourceId, join(join(relativeDir, e.relativeName), e.coverFileName!!))
            ZipAsDirListing.zipFileRootName(e.relativeName) != null ->
                BrowseCover.DavArchive(sourceId, join(relativeDir, ZipAsDirListing.zipFileRootName(e.relativeName)!!))
            else -> null
        }
        is BrowseEntryRemote.ArchiveGallery ->
            BrowseCover.DavArchive(sourceId, join(relativeDir, e.fileName))
        is BrowseEntryRemote.RegularFile ->
            if (isImageFileName(e.fileName)) BrowseCover.DavImage(sourceId, join(relativeDir, e.fileName)) else null
        else -> null
    }

    private fun join(parent: String, child: String): String =
        if (parent.isEmpty()) child.trim('/') else "${parent.trim('/')}/${child.trim('/')}"
}

/**
 * 浏览行封面缩略图（56dp 列表位 / 网格单元通用）：JPEG 小图 + 占位/失败图标。
 * 上游 BrowseCoverThumb 的瘦身版：磁盘命中 → 门控下载 → 占位，失败粘滞由 retryKey 重试。
 */
@Composable
fun BrowseCoverThumb(
    cover: BrowseCover?,
    modifier: Modifier = Modifier,
    placeholder: ImageVector = Icons.Default.PhotoLibrary,
    placeholderSize: Dp = 28.dp,
) {
    val key = cover?.cacheKey ?: "none"
    var thumbPath by remember(key) { mutableStateOf<Path?>(null) }
    var failed by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        failed = false
        thumbPath = null
        if (cover != null) {
            thumbPath = RemoteBrowseCoverLoader.ensure(cover)
            if (thumbPath == null) failed = true
        }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val p = thumbPath
        if (p != null) {
            AsyncImage(
                model = File(p.toString()),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                if (failed) Icons.Default.BrokenImage else placeholder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(placeholderSize),
            )
        }
    }
}
