package com.mangareader.backup.snapshot

import com.mangareader.data.MangaDao
import com.mangareader.data.ReadingProgress
import com.mangareader.data.ReadingHistory
import com.mangareader.data.Bookmark
import com.mangareader.data.BookshelfItem
import com.mangareader.data.TagLink
import com.mangareader.backup.format.ReadingProgressDto
import com.mangareader.backup.format.ReadingHistoryDto
import com.mangareader.backup.format.BookmarkDto
import com.mangareader.backup.format.BookshelfItemDto
import com.mangareader.backup.format.TagLinkDto

object RescanSnapshotManager {
    @Volatile
    private var currentSnapshot: RescanSnapshot? = null

    suspend fun createSnapshot(dao: MangaDao): RescanSnapshot {
        val progress = dao.exportAllProgress().map {
            ReadingProgressDto(it.id, it.bookId, it.path, it.lastPage, it.completed, it.updatedAt, it.anim_dialog_choice, it.totalPages)
        }
        val history = dao.exportAllHistory().map {
            ReadingHistoryDto(it.id, it.bookId, it.pagesRead, it.timestamp, it.scrollX, it.scrollY, it.zoomLevel)
        }
        val bookmarks = dao.exportAllBookmarks().map {
            BookmarkDto(it.id, it.bookId, it.chapterPath, it.page, it.createdAt, it.note)
        }
        val shelfItems = dao.exportAllBookshelfItems().map {
            BookshelfItemDto(it.id, it.bookshelfId, it.bookId)
        }
        val tagLinks = dao.exportAllTagLinks().map {
            TagLinkDto(it.id, it.tagId, it.bookId)
        }
        val snapshot = RescanSnapshot(progress, history, bookmarks, shelfItems, tagLinks)
        currentSnapshot = snapshot
        return snapshot
    }

    fun isSnapshotActive(): Boolean = currentSnapshot != null

    fun markRestored() {}

    fun discardSnapshot() {
        currentSnapshot = null
    }
}