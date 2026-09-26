package com.mangareader.opdsbridge.reader

import android.util.Log
import com.mangareader.data.Book
import com.mangareader.opdsbridge.OpdsBridges
import com.mangareader.opdsbridge.OpdsConstants
import com.mangareader.opdsbridge.api.OpdsApiClient
import com.mangareader.opdsbridge.api.OpdsException
import com.mangareader.opdsbridge.api.OpdsHttpClient
import com.mangareader.opdsbridge.cache.OpdsDownloadResult
import com.mangareader.opdsbridge.cache.OpdsDownloader
import com.mangareader.opdsbridge.cover.OpdsEntryLinkCache
import com.mangareader.opdsbridge.data.OpdsConnectionStore
import java.io.File

object OpdsBookRouter {

    sealed interface OpenResult {
        data class Ready(val localCachePath: String, val bookId: Long) : OpenResult
        data class Failed(val message: String) : OpenResult
    }

    suspend fun openOpdsBook(filePath: String, startPage: Int = 0): OpenResult {
        val ref = OpdsPaths.parseBookPath(filePath) ?: return OpenResult.Failed("无效路径")

        val store = OpdsConnectionStore(OpdsBridges.context)
        val conn = store.getConnection(ref.serverId) ?: return OpenResult.Failed("服务器不存在")

        val entry = OpdsEntryLinkCache.get(ref.serverId, ref.entryId)
        if (entry == null || entry.acquisitionLinks.isEmpty()) {
            return OpenResult.Failed("该条目无可下载内容")
        }

        val acquisitionLink = selectAcquisitionLink(entry.acquisitionLinks)
            ?: return OpenResult.Failed("不支持的文件格式")

        val client = OpdsHttpClient.client(conn.allowSelfSignedCert)
        val apiClient = OpdsApiClient(conn, client)

        return try {
            val downloadResult = OpdsDownloader.ensurePublication(
                apiClient = apiClient,
                serverId = ref.serverId,
                entryId = ref.entryId,
                acquisitionUrl = acquisitionLink.url,
                mimeType = acquisitionLink.mimeType,
            )

            when (downloadResult) {
                is OpdsDownloadResult.Failed -> {
                    client.close()
                    OpenResult.Failed(downloadResult.message)
                }
                is OpdsDownloadResult.Cached -> {
                    val bookId = upsertBook(ref, entry.title, downloadResult.path)
                    client.close()
                    OpenResult.Ready(downloadResult.path.absolutePath, bookId)
                }
                is OpdsDownloadResult.Downloaded -> {
                    val bookId = upsertBook(ref, entry.title, downloadResult.path)
                    client.close()
                    OpenResult.Ready(downloadResult.path.absolutePath, bookId)
                }
            }
        } catch (e: OpdsException) {
            Log.w(OpdsConstants.LOG_TAG, "openOpdsBook failed: ${e.message}")
            client.close()
            OpenResult.Failed(humanize(e))
        } catch (t: Throwable) {
            Log.w(OpdsConstants.LOG_TAG, "openOpdsBook failed", t)
            client.close()
            OpenResult.Failed(humanize(t))
        }
    }

    /**
     * 只查本地缓存、绝不触发下载（封面编辑"卷内选图"取材用，2026-09-26）：
     * 命中返回缓存文件路径（与阅读器共用同一份缓存），未命中/路径无效返回 null。
     * 格式选择与 openOpdsBook 走同一 selectAcquisitionLink → 同一 ext → 同一缓存路径。
     */
    suspend fun cachedLocalPath(filePath: String): String? {
        val ref = OpdsPaths.parseBookPath(filePath) ?: return null
        val entry = OpdsEntryLinkCache.get(ref.serverId, ref.entryId) ?: return null
        val link = selectAcquisitionLink(entry.acquisitionLinks) ?: return null
        val cachePath = com.mangareader.opdsbridge.cache.OpdsCache.cachePathFor(
            ref.serverId, ref.entryId, OpdsDownloader.extForMimeType(link.mimeType),
        )
        return if (com.mangareader.opdsbridge.cache.OpdsCache.isCachedOnDisk(cachePath)) cachePath.absolutePath else null
    }

    private suspend fun upsertBook(ref: OpdsBookRef, title: String, cachePath: File): Long {
        val dao = OpdsBridges.database.mangaDao()
        val coverPath = "${OpdsConstants.COVER_SCHEME}${ref.serverId}:${ref.entryId}"
        val bookPath = OpdsPaths.bookPath(ref.serverId, ref.entryId)

        val existing = dao.getBookByPath(bookPath)
        return if (existing != null) {
            if (existing.coverPath != coverPath) {
                dao.updateBookCoverPath(existing.id, coverPath)
            }
            existing.id
        } else {
            dao.insertBookWithStableId(Book(
                title = title,
                filePath = bookPath,
                source = "opds",
                coverPath = coverPath,
                originalPath = cachePath.absolutePath,
            ))
        }
    }

    private fun selectAcquisitionLink(links: List<com.mangareader.opdsbridge.parser.OpdsAcquisitionLink>): com.mangareader.opdsbridge.parser.OpdsAcquisitionLink? {
        for (preferred in OpdsConstants.ACQUISITION_PRIORITY) {
            val match = links.find { it.mimeType.equals(preferred, true) }
            if (match != null) return match
        }
        return links.firstOrNull()
    }

    private fun humanize(t: Throwable): String {
        val msg = t.message ?: ""
        return when {
            t is OpdsException && t is com.mangareader.opdsbridge.api.OpdsAuthException -> "认证失败"
            msg.contains("timeout") || msg.contains("连接失败") -> "连接超时"
            msg.contains("不存在") -> "资源不存在"
            msg.contains("空间不足") -> "存储空间不足"
            else -> msg.ifEmpty { "打开失败" }
        }
    }
}