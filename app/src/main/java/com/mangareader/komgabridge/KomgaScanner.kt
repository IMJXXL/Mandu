package com.mangareader.komgabridge

import android.util.Log
import com.mangareader.data.Book
import com.mangareader.komgabridge.api.KomgaApiClient
import com.mangareader.komgabridge.api.KomgaHttpClient
import com.mangareader.komgabridge.data.KomgaConnectionStore
import com.mangareader.komgabridge.reader.KomgaPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object KomgaScanner {
    private const val TAG = "KomgaScanner"

    suspend fun scanKomgaServer(serverId: String) = withContext(Dispatchers.IO) {
        val conn = KomgaConnectionStore(KomgaBridges.context).getConnection(serverId)
        if (conn == null) {
            Log.w(TAG, "server not found: $serverId")
            return@withContext
        }
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
                        var bookPage = 0
                        while (true) {
                            val booksResult = api.getSeriesBooks(series.id, page = bookPage, size = 200)
                            if (booksResult.content.isEmpty()) break

                            var inserted = 0
                            var skippedEpub = 0
                            for (book in booksResult.content) {
                                if (book.media.mediaProfile == "EPUB" && book.media.pagesCount <= 1) {
                                    skippedEpub++
                                    continue
                                }
                                val filePath = KomgaPaths.bookPath(serverId, book.id)
                                apiBookPaths.add(filePath)
                                val existing = dao.getKomgaBookByPath(filePath)
                                val coverPath = "cv:komga:${serverId}:${book.id}"
                                if (existing == null) {
                                    val title = book.metadata.title.ifEmpty { book.name }
                                    val newId = dao.insertBookWithStableId(Book(
                                        title = title,
                                        filePath = filePath,
                                        source = "komga",
                                        totalPages = book.media.pagesCount,
                                        coverPath = coverPath,
                                    ))
                                    if (newId > 0) inserted++
                                } else if (existing.coverPath != coverPath) {
                                    dao.updateBookCoverPath(existing.id, coverPath)
                                }
                            }

                            totalInserted += inserted
                            totalSkippedEpub += skippedEpub
                            totalBooks += booksResult.content.size

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
        } catch (t: Throwable) {
            Log.e(TAG, "scan failed server=$serverId", t)
        } finally {
            client.close()
        }
    }
}