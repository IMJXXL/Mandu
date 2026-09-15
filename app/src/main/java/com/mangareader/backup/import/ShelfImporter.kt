package com.mangareader.backup.`import`

import com.mangareader.backup.format.ShelfPayload
import com.mangareader.backup.format.ConflictStrategy
import com.mangareader.data.MangaDao
import com.mangareader.data.Bookshelf
import com.mangareader.data.BookshelfItem
import com.mangareader.data.Tag
import com.mangareader.data.TagLink

object ShelfImporter {
    suspend fun `import`(dao: MangaDao, payload: ShelfPayload, strategy: ConflictStrategy): Int {
        var count = 0
        if (payload.bookshelves.isNotEmpty()) {
            val items = payload.bookshelves.map { Bookshelf(it.id, it.name, it.createdAt, it.sortOrder) }
            when (strategy) {
                ConflictStrategy.OVERWRITE -> { dao.clearAllBookshelves(); dao.insertBookshelvesReplace(items) }
                ConflictStrategy.SKIP -> dao.insertBookshelvesIgnore(items)
                ConflictStrategy.MERGE -> dao.insertBookshelvesReplace(items)
            }
            count += items.size
        }
        if (payload.bookshelfItems.isNotEmpty()) {
            val items = payload.bookshelfItems.map { BookshelfItem(it.id, it.bookshelfId, it.bookId) }
            when (strategy) {
                ConflictStrategy.OVERWRITE -> { dao.clearAllBookshelfItems(); dao.insertBookshelfItemsReplace(items) }
                ConflictStrategy.SKIP -> dao.insertBookshelfItemsIgnore(items)
                ConflictStrategy.MERGE -> dao.insertBookshelfItemsReplace(items)
            }
            count += items.size
        }
        if (payload.tags.isNotEmpty()) {
            val items = payload.tags.map { Tag(it.id, it.name, it.color) }
            when (strategy) {
                ConflictStrategy.OVERWRITE -> { dao.clearAllTags(); dao.insertTagsReplace(items) }
                ConflictStrategy.SKIP -> dao.insertTagsIgnore(items)
                ConflictStrategy.MERGE -> dao.insertTagsReplace(items)
            }
            count += items.size
        }
        if (payload.tagLinks.isNotEmpty()) {
            val items = payload.tagLinks.map { TagLink(it.id, it.tagId, it.bookId) }
            when (strategy) {
                ConflictStrategy.OVERWRITE -> { dao.clearAllTagLinks(); dao.insertTagLinksReplace(items) }
                ConflictStrategy.SKIP -> dao.insertTagLinksIgnore(items)
                ConflictStrategy.MERGE -> dao.insertTagLinksReplace(items)
            }
            count += items.size
        }
        if (payload.mangaBooksShelfFlags.isNotEmpty()) {
            for (dto in payload.mangaBooksShelfFlags) {
                dao.updateBookShelfFlags(dto.id, dto.isFavorite, dto.isHidden)
            }
            count += payload.mangaBooksShelfFlags.size
        }
        return count
    }
}