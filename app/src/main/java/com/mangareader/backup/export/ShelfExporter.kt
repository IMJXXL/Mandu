package com.mangareader.backup.export

import com.mangareader.backup.format.ShelfPayload
import com.mangareader.backup.format.BookshelfDto
import com.mangareader.backup.format.BookshelfItemDto
import com.mangareader.backup.format.TagDto
import com.mangareader.backup.format.TagLinkDto
import com.mangareader.backup.format.BookShelfFlagsDto
import com.mangareader.data.MangaDao

object ShelfExporter {
    suspend fun export(dao: MangaDao): ShelfPayload {
        val bookshelves = dao.exportAllBookshelves().map {
            BookshelfDto(it.id, it.name, it.createdAt, it.sortOrder)
        }
        val bookshelfItems = dao.exportAllBookshelfItems().map {
            BookshelfItemDto(it.id, it.bookshelfId, it.bookId)
        }
        val tags = dao.exportAllTags().map {
            TagDto(it.id, it.name, it.color)
        }
        val tagLinks = dao.exportAllTagLinks().map {
            TagLinkDto(it.id, it.tagId, it.bookId, it.source)
        }
        val shelfFlags = dao.exportAllBooksShelfFlags().map {
            BookShelfFlagsDto(it.id, it.isFavorite, it.isHidden)
        }
        return ShelfPayload(bookshelves, bookshelfItems, tags, tagLinks, shelfFlags)
    }
}