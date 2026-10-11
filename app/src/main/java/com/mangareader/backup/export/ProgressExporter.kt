package com.mangareader.backup.export

import com.mangareader.backup.format.ProgressPayload
import com.mangareader.backup.format.ReadingProgressDto
import com.mangareader.backup.format.ReadingHistoryDto
import com.mangareader.backup.format.BookmarkDto
import com.mangareader.backup.format.BookAggregateDto
import com.mangareader.backup.format.PageSoloFlagDto
import com.mangareader.data.MangaDao

object ProgressExporter {
    suspend fun export(dao: MangaDao): ProgressPayload {
        val progress = dao.exportAllProgress().map {
            ReadingProgressDto(it.id, it.bookId, it.path, it.lastPage, it.completed, it.updatedAt, it.anim_dialog_choice, it.totalPages, it.halfRight)
        }
        val history = dao.exportAllHistory().map {
            ReadingHistoryDto(it.id, it.bookId, it.pagesRead, it.timestamp, it.scrollX, it.scrollY, it.zoomLevel)
        }
        val bookmarks = dao.exportAllBookmarks().map {
            BookmarkDto(it.id, it.bookId, it.chapterPath, it.page, it.createdAt, it.note)
        }
        val aggregate = dao.exportAllBooksAggregate().map {
            BookAggregateDto(it.id, it.readPages, it.lastReadAt, it.filePath, it.customCoverPath)
        }
        val soloFlags = dao.exportAllSoloFlags().map {
            PageSoloFlagDto(it.chPath, it.pageIdx)
        }
        return ProgressPayload(progress, history, bookmarks, aggregate, soloFlags)
    }
}