package com.mangareader.autocat

import android.content.Context
import android.util.Log
import com.mangareader.data.Book
import com.mangareader.data.Bookshelf
import com.mangareader.data.BookshelfItem
import com.mangareader.data.MangaDatabase
import com.mangareader.metadata.ComicInfo

object AutoCategorizer {

    private const val TAG = "AutoCategorizer"

    suspend fun categorize(ctx: Context, book: Book, info: ComicInfo, config: AutoCategorizeConfig) {
        val shelfNames = collectShelfNames(info, config)
        if (shelfNames.isEmpty()) return
        try {
            val dao = MangaDatabase.getDatabase(ctx).mangaDao()
            val allShelves = dao.getAllBookshelvesList()
            for (name in shelfNames) {
                val existing = allShelves.find { it.name == name }
                val shelfId = existing?.id ?: dao.insertBookshelf(Bookshelf(name = name))
                val existingItems = dao.getBooksInShelfList(shelfId)
                if (existingItems.none { it.bookId == book.id }) {
                    dao.insertBookshelfItem(BookshelfItem(bookshelfId = shelfId, bookId = book.id))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "categorize failed for book ${book.title}: ${e.message}")
        }
    }

    private fun collectShelfNames(info: ComicInfo, config: AutoCategorizeConfig): List<String> {
        val names = LinkedHashSet<String>()
        if (config.enableSeries && !info.series.isNullOrBlank()) {
            val s = info.series!!.trim()
            val stripped = if (s.endsWith("系列")) s.removeSuffix("系列").trim() else s
            if (stripped.isNotEmpty()) names.add(stripped)
        }
        if (config.enableWriter && !info.writer.isNullOrBlank()) names.add(info.writer!!.trim())
        if (config.enableGenre && !info.genre.isNullOrBlank()) {
            info.genre!!.split(",").firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { names.add(it) }
        }
        if (config.enablePublisher && !info.publisher.isNullOrBlank()) names.add(info.publisher!!.trim())
        for (fieldName in config.customFieldNames) {
            if (fieldName !in config.enabledCustomFields) continue
            info.extraFields[fieldName]?.trim()?.takeIf { it.isNotEmpty() }?.let { names.add(it) }
        }
        return names.toList()
    }
}
