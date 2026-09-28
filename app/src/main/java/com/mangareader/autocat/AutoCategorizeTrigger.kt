package com.mangareader.autocat

import android.content.Context
import android.util.Log
import com.mangareader.data.Book
import com.mangareader.metadata.ComicInfoReader

object AutoCategorizeTrigger {

    private const val TAG = "AutoCategorizeTrigger"

    suspend fun run(books: List<Book>, ctx: Context) {
        val config = AutoCategorizeConfig.load(ctx)
        if (!config.enableSeries && !config.enableWriter && !config.enableGenre && !config.enablePublisher) {
            return
        }
        for (book in books) {
            try {
                val info = ComicInfoReader.read(ctx, book.filePath)
                if (info == null || info.isEmpty()) continue
                AutoCategorizer.categorize(ctx, book, info, config)
            } catch (e: Exception) {
                Log.w(TAG, "auto-categorize skipped for ${book.title}: ${e.message}")
            }
        }
    }
}