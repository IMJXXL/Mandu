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
        val shelfName = pickShelfName(info, config) ?: return
        try {
            val dao = MangaDatabase.getDatabase(ctx).mangaDao()
            val existing = dao.getAllBookshelvesList().find { it.name == shelfName }
            val shelfId = existing?.id ?: dao.insertBookshelf(Bookshelf(name = shelfName))
            val existingItems = dao.getBooksInShelfList(shelfId)
            if (existingItems.none { it.bookId == book.id }) {
                dao.insertBookshelfItem(BookshelfItem(bookshelfId = shelfId, bookId = book.id))
            }
        } catch (e: Exception) {
            Log.w(TAG, "categorize failed for book ${book.title}: ${e.message}")
        }
    }

    private fun pickShelfName(info: ComicInfo, config: AutoCategorizeConfig): String? {
        if (config.enableSeries && !info.series.isNullOrBlank()) return info.series!!.trim()
        if (config.enableWriter && !info.writer.isNullOrBlank()) return info.writer!!.trim()
        if (config.enableGenre && !info.genre.isNullOrBlank()) return info.genre!!.split(",").firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        if (config.enablePublisher && !info.publisher.isNullOrBlank()) return info.publisher!!.trim()
        return null
    }
}
