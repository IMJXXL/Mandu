package com.mangareader.backup.`import`

import com.mangareader.backup.format.ProgressPayload
import com.mangareader.backup.format.ConflictStrategy
import com.mangareader.data.MangaDao
import com.mangareader.data.ReadingProgress
import com.mangareader.data.ReadingHistory
import com.mangareader.data.Bookmark

object ProgressImporter {
    suspend fun `import`(dao: MangaDao, payload: ProgressPayload, strategy: ConflictStrategy): Int {
        var count = 0
        android.util.Log.i("ProgressImport", "进度${payload.readingProgress.size}条 历史${payload.readingHistory.size}条 书签${payload.bookmarks.size}条 聚合${payload.mangaBooksAggregate.size}条")

        val bookIdMap = mutableMapOf<Long, Long>()
        val hasFilePath = payload.mangaBooksAggregate.any { it.filePath.isNotEmpty() }

        if (payload.mangaBooksAggregate.isNotEmpty()) {
            for (dto in payload.mangaBooksAggregate) {
                if (hasFilePath && dto.filePath.isNotEmpty()) {
                    val book = dao.getBookByPath(dto.filePath)
                    if (book != null) {
                        bookIdMap[dto.id] = book.id
                        dao.updateBookAggregate(book.id, dto.readPages, dto.lastReadAt)
                        count++
                    }
                } else {
                    dao.updateBookAggregate(dto.id, dto.readPages, dto.lastReadAt)
                    bookIdMap[dto.id] = dto.id
                    count++
                }
            }
        }

        if (payload.readingProgress.isNotEmpty()) {
            val items = payload.readingProgress.mapNotNull { it ->
                val newBookId = if (hasFilePath) bookIdMap[it.bookId] else it.bookId
                if (newBookId != null) {
                    ReadingProgress(it.id, newBookId, it.path, it.lastPage, it.completed, it.updatedAt, it.animDialogChoice, it.totalPages)
                } else null
            }
            if (items.isNotEmpty()) {
                when (strategy) {
                    ConflictStrategy.OVERWRITE -> { dao.clearAllProgress(); dao.insertProgressReplace(items) }
                    ConflictStrategy.SKIP -> dao.insertProgressIgnore(items)
                    ConflictStrategy.MERGE -> dao.insertProgressReplace(items)
                }
                count += items.size
            }
        }
        if (payload.readingHistory.isNotEmpty()) {
            val items = payload.readingHistory.mapNotNull { it ->
                val newBookId = if (hasFilePath) bookIdMap[it.bookId] else it.bookId
                if (newBookId != null) {
                    ReadingHistory(it.id, newBookId, it.pagesRead, it.timestamp, it.scrollX, it.scrollY, it.zoomLevel)
                } else null
            }
            if (items.isNotEmpty()) {
                when (strategy) {
                    ConflictStrategy.OVERWRITE -> { dao.clearAllHistory(); dao.insertHistoryReplace(items) }
                    ConflictStrategy.SKIP -> dao.insertHistoryIgnore(items)
                    ConflictStrategy.MERGE -> dao.insertHistoryReplace(items)
                }
                count += items.size
            }
        }
        if (payload.bookmarks.isNotEmpty()) {
            val items = payload.bookmarks.mapNotNull { it ->
                val newBookId = if (hasFilePath) bookIdMap[it.bookId] else it.bookId
                if (newBookId != null) {
                    Bookmark(it.id, newBookId, it.chapterPath, it.page, it.createdAt, it.note)
                } else null
            }
            if (items.isNotEmpty()) {
                when (strategy) {
                    ConflictStrategy.OVERWRITE -> { dao.clearAllBookmarks(); dao.insertBookmarksReplace(items) }
                    ConflictStrategy.SKIP -> dao.insertBookmarksIgnore(items)
                    ConflictStrategy.MERGE -> dao.insertBookmarksReplace(items)
                }
                count += items.size
            }
        }
        return count
    }
}