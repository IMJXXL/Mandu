package com.mangareader.backup.`import`

import com.mangareader.backup.format.ShelfPayload
import com.mangareader.backup.format.ConflictStrategy
import com.mangareader.data.MangaDao
import com.mangareader.data.Bookshelf
import com.mangareader.data.BookshelfItem
import com.mangareader.data.Tag
import com.mangareader.data.TagLink

object ShelfImporter {
    suspend fun `import`(
        dao: MangaDao,
        payload: ShelfPayload,
        strategy: ConflictStrategy,
        // ★ bookId 重映射表（BackupImporter 按 filePath 预算，与进度恢复同源——此前书架/标签
        //   直接用备份原始 id，跨设备恢复挂错书/挂到不存在的书，2026-10-11 审查）
        bookIdMap: Map<Long, Long> = emptyMap(),
    ): Int {
        var count = 0
        // hasFilePath=备份带 filePath 映射（跨设备恢复语义）；false=同库恢复用原始 id
        val hasFilePath = bookIdMap.isNotEmpty()
        fun mapBook(id: Long): Long? = if (hasFilePath) bookIdMap[id] else id
        if (payload.bookshelves.isNotEmpty()) {
            val items = payload.bookshelves.map { Bookshelf(it.id, it.name, it.createdAt, it.sortOrder, it.isHidden) }
            when (strategy) {
                ConflictStrategy.OVERWRITE -> dao.replaceBookshelvesAtomic(items)
                ConflictStrategy.SKIP -> dao.insertBookshelvesIgnore(items)
                ConflictStrategy.MERGE -> dao.insertBookshelvesReplace(items)
            }
            count += items.size
        }
        if (payload.bookshelfItems.isNotEmpty()) {
            val items = payload.bookshelfItems.mapNotNull { dto ->
                mapBook(dto.bookId)?.let { BookshelfItem(dto.id, dto.bookshelfId, it) }
            }
            if (items.isNotEmpty()) {
                when (strategy) {
                    ConflictStrategy.OVERWRITE -> dao.replaceBookshelfItemsAtomic(items)
                    ConflictStrategy.SKIP -> dao.insertBookshelfItemsIgnore(items)
                    ConflictStrategy.MERGE -> dao.insertBookshelfItemsReplace(items)
                }
                count += items.size
            }
        }
        if (payload.tags.isNotEmpty()) {
            val items = payload.tags.map { Tag(it.id, it.name, it.color, it.isHidden, it.sortOrder) }
            when (strategy) {
                ConflictStrategy.OVERWRITE -> dao.replaceTagsAtomic(items)
                ConflictStrategy.SKIP -> dao.insertTagsIgnore(items)
                ConflictStrategy.MERGE -> dao.insertTagsReplace(items)
            }
            count += items.size
        }
        if (payload.tagLinks.isNotEmpty()) {
            val items = payload.tagLinks.mapNotNull { dto ->
                mapBook(dto.bookId)?.let { TagLink(dto.id, dto.tagId, it, dto.source) }
            }
            if (items.isNotEmpty()) {
                when (strategy) {
                    ConflictStrategy.OVERWRITE -> dao.replaceTagLinksAtomic(items)
                    ConflictStrategy.SKIP -> dao.insertTagLinksIgnore(items)
                    ConflictStrategy.MERGE -> dao.insertTagLinksReplace(items)
                }
                count += items.size
            }
        }
        if (payload.mangaBooksShelfFlags.isNotEmpty()) {
            for (dto in payload.mangaBooksShelfFlags) {
                val target = mapBook(dto.id) ?: continue
                dao.updateBookShelfFlags(target, dto.isFavorite, dto.isHidden)
            }
            count += payload.mangaBooksShelfFlags.size
        }
        return count
    }
}
