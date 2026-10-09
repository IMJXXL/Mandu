package com.mangareader.data

import android.content.Context
import com.mangareader.viewer.ArchiveEngine

sealed class ApplyResult {
    object Success : ApplyResult()
    data class PartialFailure(val succeeded: Int, val failed: Int) : ApplyResult()
    data class Failed(val message: String) : ApplyResult()
}

object PerBookConfigApplier {

    suspend fun apply(
        context: Context,
        book: com.mangareader.data.Book,
        engine: ArchiveEngine,
        oldConfig: ReaderConfig,
        newConfig: ReaderConfig,
        newTapScrollRatio: Float? = null,
    ): ApplyResult {
        val prefs = context.getSharedPreferences("manga_reader", Context.MODE_PRIVATE)
        val changedKeys = ReaderConfig.changedFields(oldConfig, newConfig)
        val accessor = ReaderConfig.FIELD_ACCESSORS

        val subBookIds = mutableSetOf(book.id)
        try {
            val items = engine.children(book.filePath)
            for (item in items) {
                if (!item.dir) {
                    val childPath = "d:${item.auth}|${item.tree}|${item.doc}"
                    subBookIds.add(stableBookId(childPath))
                } else if (book.filePath.startsWith("z:")) {
                    val childPath = "z:${book.filePath.removePrefix("z:")}//${item.subPath.ifEmpty { item.name }}"
                    subBookIds.add(stableBookId(childPath))
                }
            }
        } catch (_: Exception) {
        }

        var succeeded = 0
        var failed = 0
        for (subBookId in subBookIds) {
            try {
                for (key in changedKeys) {
                    val v = accessor.first { it.first == key }.second(newConfig)
                    ReaderScope.write(prefs, subBookId, key, v)
                }
                if (newTapScrollRatio != null) {
                    prefs.edit().putFloat("book_${subBookId}_webtoon_tap_scroll_ratio", newTapScrollRatio).apply()
                }
                succeeded++
            } catch (_: Exception) {
                failed++
            }
        }

        return when {
            failed == 0 -> ApplyResult.Success
            succeeded == 0 -> ApplyResult.Failed("全部写入失败")
            else -> ApplyResult.PartialFailure(succeeded, failed)
        }
    }

    fun clearOverrides(context: Context, book: com.mangareader.data.Book) {
        val prefs = context.getSharedPreferences("manga_reader", Context.MODE_PRIVATE)
        ReaderScope.clearAllBook(prefs, book.id)
        prefs.edit().remove("book_${book.id}_webtoon_tap_scroll_ratio").apply()
    }
}