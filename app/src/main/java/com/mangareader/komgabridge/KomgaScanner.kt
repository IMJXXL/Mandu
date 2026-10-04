package com.mangareader.komgabridge

import android.util.Log
import com.mangareader.data.Book
import com.mangareader.data.MangaDao
import com.mangareader.komgabridge.api.KomgaApiClient
import com.mangareader.komgabridge.api.KomgaHttpClient
import com.mangareader.komgabridge.api.model.BookDto
import com.mangareader.komgabridge.api.model.SeriesDto
import com.mangareader.komgabridge.data.KomgaConnectionStore
import com.mangareader.komgabridge.reader.KomgaPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object KomgaScanner {
    private const val TAG = "KomgaScanner"

    data class KomgaScanResult(
        val inserted: Int,
        val skippedEpub: Int,
        val removed: Int,
    )

    suspend fun scanKomgaServer(serverId: String): KomgaScanResult = withContext(Dispatchers.IO) {
        val conn = KomgaConnectionStore(KomgaBridges.context).getConnection(serverId)
            ?: throw IllegalStateException("服务器不存在: $serverId")
        val client = KomgaHttpClient.client(conn.allowSelfSignedCert)
        val api = KomgaApiClient(conn, client)
        val dao = KomgaBridges.database.mangaDao()

        try {
            val libraries = api.getLibraries()
            Log.i(TAG, "start server=$serverId libraries=${libraries.size}")

            var totalInserted = 0
            var totalSkippedEpub = 0
            var totalBooks = 0
            val apiBookPaths = HashSet<String>()

            for (lib in libraries) {
                Log.i(TAG, "scanning library id=${lib.id} name=${lib.name}")
                var seriesPage = 0
                while (true) {
                    val seriesResult = api.getSeries(libraryId = lib.id, page = seriesPage, size = 200)
                    if (seriesResult.content.isEmpty()) break

                    for (series in seriesResult.content) {
                        val seriesName = series.metadata.title.ifEmpty { series.name }
                        var bookPage = 0
                        while (true) {
                            val booksResult = api.getSeriesBooks(series.id, page = bookPage, size = 200)
                            if (booksResult.content.isEmpty()) break

                            for (book in booksResult.content) {
                                if (book.media.mediaProfile == "EPUB" && book.media.pagesCount <= 1) {
                                    totalSkippedEpub++
                                    continue
                                }
                                val filePath = KomgaPaths.bookPath(serverId, book.id)
                                apiBookPaths.add(filePath)
                                if (putKomgaBookWithSeries(dao, serverId, book, series.id, seriesName)) {
                                    totalInserted++
                                }
                                totalBooks++
                            }

                            if (bookPage >= booksResult.totalPages - 1) break
                            bookPage++
                        }
                    }

                    if (seriesPage >= seriesResult.totalPages - 1) break
                    seriesPage++
                }
            }

            val dbPaths = dao.getKomgaBookPathsForServer(serverId)
            var removed = 0
            for (dbPath in dbPaths) {
                if (dbPath !in apiBookPaths) {
                    dao.deleteBookByPath(dbPath)
                    removed++
                }
            }

            Log.i(TAG, "done server=$serverId books=$totalBooks inserted=$totalInserted skippedEpub=$totalSkippedEpub removed=$removed")
            KomgaScanResult(totalInserted, totalSkippedEpub, removed)
        } catch (t: Throwable) {
            Log.e(TAG, "scan failed server=$serverId", t)
            throw t
        } finally {
            // ★ P7：client 进程内共享（KomgaHttpClient 缓存），不再 close——关掉会杀共享连接池
        }
    }

    suspend fun putKomgaBookWithSeries(
        dao: MangaDao,
        serverId: String,
        book: BookDto,
        seriesId: String,
        seriesName: String,
    ): Boolean {
        val filePath = KomgaPaths.bookPath(serverId, book.id)
        val coverPath = "cv:komga:${serverId}:${book.id}"
        val existing = dao.getKomgaBookByPath(filePath)
        if (existing == null) {
            val title = book.metadata.title.ifEmpty { book.name }
            val newId = dao.insertBookWithStableId(Book(
                title = title,
                filePath = filePath,
                source = "komga",
                totalPages = book.media.pagesCount,
                coverPath = coverPath,
                seriesId = seriesId.ifEmpty { null },
                seriesName = seriesName.ifEmpty { null },
            ))
            return newId > 0
        }
        if (existing.coverPath != coverPath) {
            dao.updateBookCoverPath(existing.id, coverPath)
        }
        if (existing.seriesId != seriesId || existing.seriesName != seriesName) {
            dao.updateKomgaSeriesInfo(existing.id, seriesId, seriesName)
        }
        return false
    }
}
