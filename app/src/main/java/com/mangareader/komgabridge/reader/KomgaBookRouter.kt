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

object KomgaBookRouter {

    sealed interface OpenResult {
        class Supported(val supply: KomgaPageAdapter, val bookId: Long) : OpenResult
        object Unsupported : OpenResult
        class Failed(val message: String) : OpenResult
    }

    suspend fun openKomgaBook(filePath: String, startPage: Int = 0): OpenResult {
        val ref = KomgaPaths.parseBookPath(filePath) ?: return OpenResult.Failed("无效路径")

        val store = KomgaConnectionStore(KomgaBridges.context)
        val connection = store.getConnection(ref.serverId) ?: return OpenResult.Failed("服务器不存在")

        val client = KomgaHttpClient.client(connection.allowSelfSignedCert)
        val apiClient = KomgaApiClient(connection, client)

        return try {
            val pages = apiClient.getBookPages(ref.bookId)
            if (pages.isEmpty()) {
                val book = apiClient.getBook(ref.bookId)
                if (book.media.mediaProfile == "EPUB") return OpenResult.Unsupported
                return OpenResult.Failed("该章节无页面")
            }

            val bookInfo = apiClient.getBook(ref.bookId)
            val isPdf = bookInfo.media.mediaType == "application/pdf"

            val dao = KomgaBridges.database.mangaDao()
            val existing = dao.getKomgaBookByPath(filePath)
            val coverPath = "cv:komga:${ref.serverId}:${ref.bookId}"
            val bookId = if (existing != null) {
                if (existing.coverPath != coverPath) {
                    dao.updateBookCoverPath(existing.id, coverPath)
                }
                existing.id
            }
            else dao.insertBookWithStableId(Book(
                title = bookInfo.metadata.title.ifEmpty { bookInfo.name },
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
            client.close()
            Log.w(KomgaConstants.LOG_TAG, "openKomgaBook failed: ${e.message}")
            OpenResult.Failed(humanize(e))
        } catch (t: Throwable) {
            client.close()
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