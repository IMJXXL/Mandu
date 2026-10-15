package com.mangareader.bridge115.data

import android.content.Context
import com.mangareader.bridge115.Constants115

data class ReadingProgress115(
    val lastPage: Int,
    val gridIndex: Int,
    val scrollOffset: Int,
    val updatedAt: Long,
)

class ProgressStore115(context: Context) {

    private val prefs = context.getSharedPreferences(Constants115.PREFS_PROGRESS, Context.MODE_PRIVATE)

    fun getProgress(path: String): ReadingProgress115? {
        val lastPage = prefs.getInt("${path}:lastPage", -1)
        if (lastPage < 0) return null
        return ReadingProgress115(
            lastPage = lastPage,
            gridIndex = prefs.getInt("${path}:gridIndex", 0),
            scrollOffset = prefs.getInt("${path}:scrollOffset", 0),
            updatedAt = prefs.getLong("${path}:updatedAt", 0L),
        )
    }

    fun saveProgress(path: String, progress: ReadingProgress115) {
        prefs.edit()
            .putInt("${path}:lastPage", progress.lastPage)
            .putInt("${path}:gridIndex", progress.gridIndex)
            .putInt("${path}:scrollOffset", progress.scrollOffset)
            .putLong("${path}:updatedAt", progress.updatedAt)
            .apply()
    }

    fun saveProgress(path: String, lastPage: Int) {
        saveProgress(path, ReadingProgress115(lastPage, 0, 0, System.currentTimeMillis()))
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