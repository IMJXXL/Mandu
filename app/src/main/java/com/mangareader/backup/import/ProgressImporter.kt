package com.mangareader.backup.`import`

import com.mangareader.backup.format.ProgressPayload
import com.mangareader.backup.format.ConflictStrategy
import com.mangareader.data.MangaDao
import com.mangareader.data.PageSoloFlag
import com.mangareader.data.ReadingProgress
import com.mangareader.data.ReadingHistory
import com.mangareader.data.Bookmark

object ProgressImporter {
    suspend fun `import`(
        dao: MangaDao,
        payload: ProgressPayload,
        strategy: ConflictStrategy,
        // ★ 预算好的 bookId 重映射表（BackupImporter 构建，ShelfImporter 共用）；null=内部自建
        precomputedBookIdMap: Map<Long, Long>? = null,
    ): Int {
        var count = 0
        android.util.Log.i("ProgressImport", "进度${payload.readingProgress.size}条 历史${payload.readingHistory.size}条 书签${payload.bookmarks.size}条 聚合${payload.mangaBooksAggregate.size}条")

        val bookIdMap = precomputedBookIdMap?.toMutableMap() ?: mutableMapOf<Long, Long>()
        val hasFilePath = payload.mangaBooksAggregate.any { it.filePath.isNotEmpty() }

        if (payload.mangaBooksAggregate.isNotEmpty()) {
            for (dto in payload.mangaBooksAggregate) {
                if (hasFilePath && dto.filePath.isNotEmpty()) {
                    // 预计算表已含映射（BackupImporter 按 filePath 查得）：直接沿用并补聚合更新；
                    // 未预计算时现场按 filePath 查
                    val mapped = precomputedBookIdMap?.get(dto.id)
                    val book = if (mapped != null) null else dao.getBookByPath(dto.filePath)
                    val target = mapped ?: book?.id
                    if (target != null) {
                        if (book != null) bookIdMap[dto.id] = book.id
                        dao.updateBookAggregate(target, dto.readPages, dto.lastReadAt)
                        // ★ 自定义封面路径恢复（v2 备份补列；封面文件本体由重扫/网络补图）
                        if (dto.customCoverPath != null) dao.updateBookCustomCover(target, dto.customCoverPath)
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
                    ReadingProgress(it.id, newBookId, it.path, it.lastPage, it.completed, it.updatedAt, it.animDialogChoice, it.totalPages, halfRight = it.halfRight)
                } else null
            }
            if (items.isNotEmpty()) {
                when (strategy) {
                    ConflictStrategy.OVERWRITE -> dao.replaceProgressAtomic(items)
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
                    ConflictStrategy.OVERWRITE -> dao.replaceHistoryAtomic(items)
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
                    ConflictStrategy.OVERWRITE -> dao.replaceBookmarksAtomic(items)
                    ConflictStrategy.SKIP -> dao.insertBookmarksIgnore(items)
                    ConflictStrategy.MERGE -> dao.insertBookmarksReplace(items)
                }
                count += items.size
            }
        }
        if (payload.soloFlags.isNotEmpty()) {
            val flags = payload.soloFlags.map { PageSoloFlag(it.chPath, it.pageIdx) }
            when (strategy) {
                ConflictStrategy.OVERWRITE -> { dao.clearAllSoloFlags(); dao.insertSoloFlagsReplace(flags) }
                ConflictStrategy.SKIP -> dao.insertSoloFlagsIgnore(flags)
                ConflictStrategy.MERGE -> dao.insertSoloFlagsReplace(flags)
            }
            count += flags.size
        }
        return count
    }
}