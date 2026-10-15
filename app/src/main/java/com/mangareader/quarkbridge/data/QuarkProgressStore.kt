package com.mangareader.quarkbridge.data

import android.content.Context
import com.mangareader.quarkbridge.QuarkConstants

data class QuarkReadingProgress(
    val lastPage: Int,
    val gridIndex: Int,
    val scrollOffset: Int,
    val updatedAt: Long,
)

class QuarkProgressStore(context: Context) {

    private val prefs = context.getSharedPreferences(QuarkConstants.PREFS_PROGRESS, Context.MODE_PRIVATE)

    fun getProgress(path: String): QuarkReadingProgress? {
        val lastPage = prefs.getInt("${path}:lastPage", -1)
        if (lastPage < 0) return null
        return QuarkReadingProgress(
            lastPage = lastPage,
            gridIndex = prefs.getInt("${path}:gridIndex", 0),
            scrollOffset = prefs.getInt("${path}:scrollOffset", 0),
            updatedAt = prefs.getLong("${path}:updatedAt", 0L),
        )
    }

    fun saveProgress(path: String, progress: QuarkReadingProgress) {
        prefs.edit()
            .putInt("${path}:lastPage", progress.lastPage)
            .putInt("${path}:gridIndex", progress.gridIndex)
            .putInt("${path}:scrollOffset", progress.scrollOffset)
            .putLong("${path}:updatedAt", progress.updatedAt)
            .apply()
    }

    fun saveProgress(path: String, lastPage: Int) {
        saveProgress(path, QuarkReadingProgress(lastPage, 0, 0, System.currentTimeMillis()))
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    fun clearPath(path: String) {
        prefs.edit()
            .remove("${path}:lastPage")
            .remove("${path}:gridIndex")
            .remove("${path}:scrollOffset")
            .remove("${path}:updatedAt")
            .apply()
    }
}