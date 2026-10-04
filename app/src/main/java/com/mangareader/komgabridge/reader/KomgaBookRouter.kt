package com.mangareader.komgabridge.reader

import android.util.Log
import com.mangareader.komgabridge.KomgaBridges
import com.mangareader.komgabridge.KomgaConstants
import com.mangareader.komgabridge.api.KomgaApiClient
import com.mangareader.komgabridge.api.KomgaException
import com.mangareader.komgabridge.api.KomgaHttpClient
import com.mangareader.komgabridge.data.KomgaConnectionStore
import com.mangareader.data.Book
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

object KomgaBookRouter {

    sealed interface OpenResult {
        class Supported(val supply: KomgaPageAdapter, val bookId: Long) : OpenResult
        object Unsupported : OpenResult
        class Failed(val message: String) : OpenResult
    }

    // ★ P7（2026-10-04）：页表进程内缓存——旧版每次开书都 GET /pages + GET /book 两个串行请求。
    //   TTL 10 分钟内重开零网络往返；服务端重扫导致页数变化的最坏感知窗口也是 10 分钟。
    private class CachedPages(
        val fetchedAt: Long,
        val pages: List<com.mangareader.komgabridge.api.model.PageDto>,
        val isPdf: Boolean,
        val title: String,
    )
    private val pagesCache = java.util.concurrent.ConcurrentHashMap<String, CachedPages>()
    private const val PAGES_TTL_MS = 10 * 60_000L

    suspend fun openKomgaBook(filePath: String, startPage: Int = 0): OpenResult {
        val ref = KomgaPaths.parseBookPath(filePath) ?: return OpenResult.Failed("无效路径")

        val store = KomgaConnectionStore(KomgaBridges.context)
        val connection = store.getConnection(ref.serverId) ?: return OpenResult.Failed("服务器不存在")

        // ★ P7：client 进程内共享（KomgaHttpClient 缓存）——连接池跨开书/浏览复用，免重复握手。
        //   共享后任何路径都不得 close()（旧版失败时 client.close() 会杀掉共享池）。
        val client = KomgaHttpClient.client(connection.allowSelfSignedCert)
        val apiClient = KomgaApiClient(connection, client)

        return try {
            val dao = KomgaBridges.database.mangaDao()
            val existing = dao.getKomgaBookByPath(filePath)
            val coverPath = "cv:komga:${ref.serverId}:${ref.bookId}"
            val cacheKey = "${ref.serverId}:${ref.bookId}"
            val cached = pagesCache[cacheKey]?.takeIf { System.currentTimeMillis() - it.fetchedAt < PAGES_TTL_MS }

            val pages: List<com.mangareader.komgabridge.api.model.PageDto>
            val isPdf: Boolean
            val title: String
            if (cached != null) {
                // 缓存命中：零网络往返
                pages = cached.pages; isPdf = cached.isPdf; title = cached.title
                if (existing != null && existing.coverPath != coverPath) dao.updateBookCoverPath(existing.id, coverPath)
            } else {
                // ★ P7：页表与书籍信息并行拉取（旧版两次串行 GET）
                val (p, book) = coroutineScope {
                    val pagesDef = async { apiClient.getBookPages(ref.bookId) }
                    val bookDef = async { apiClient.getBook(ref.bookId) }
                    pagesDef.await() to bookDef.await()
                }
                if (p.isEmpty()) {
                    if (book.media.mediaProfile == "EPUB") return OpenResult.Unsupported
                    return OpenResult.Failed("该章节无页面")
                }
                pages = p
                isPdf = book.media.mediaType == "application/pdf"
                title = book.metadata.title.ifEmpty { book.name }
                // ★ 独立审查中#2：缓存有界——超限先清扫过期条目，仍超限整体清空（防无界累积）
                if (pagesCache.size > 64) {
                    val now = System.currentTimeMillis()
                    pagesCache.entries.removeIf { now - it.value.fetchedAt > PAGES_TTL_MS }
                    if (pagesCache.size > 64) pagesCache.clear()
                }
                pagesCache[cacheKey] = CachedPages(System.currentTimeMillis(), pages, isPdf, title)
                if (existing != null && existing.coverPath != coverPath) dao.updateBookCoverPath(existing.id, coverPath)
            }

            val bookId = existing?.id ?: dao.insertBookWithStableId(Book(
                title = title,
                filePath = filePath,
                source = "komga",
                totalPages = pages.size,
                coverPath = coverPath,
            ))

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val progressWriter = KomgaProgressWriter(apiClient, ref.bookId, pages.size, scope)
            val adapter = KomgaPageAdapter(pages, apiClient, ref.bookId, isPdf, progressWriter)

            OpenResult.Supported(adapter, bookId)
        } catch (e: KomgaException) {
            Log.w(KomgaConstants.LOG_TAG, "openKomgaBook failed: ${e.message}")
            OpenResult.Failed(humanize(e))
        } catch (t: Throwable) {
            Log.w(KomgaConstants.LOG_TAG, "openKomgaBook failed", t)
            OpenResult.Failed(humanize(t))
        }
    }

    private fun humanize(t: Throwable): String {
        val msg = t.message ?: ""
        return when {
            msg.contains("auth") || t is com.mangareader.komgabridge.api.KomgaAuthException -> "认证失败"
            msg.contains("timeout") || msg.contains("connection") -> "连接超时"
            msg.contains("not found") -> "资源不存在"
            else -> msg.ifEmpty { "打开失败" }
        }
    }
}