package com.mangareader.komgabridge.reader

import android.util.Log
import com.mangareader.komgabridge.KomgaConstants
import com.mangareader.komgabridge.api.KomgaApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


class KomgaProgressWriter(
    private val apiClient: KomgaApiClient,
    private val bookId: String,
    private val totalPages: Int,
    private val scope: CoroutineScope,
) {
    @Volatile
    private var currentPage: Int = 0

    @Volatile
    private var pendingJob: Job? = null

    fun onPageChanged(page: Int) {
        currentPage = page
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(KomgaConstants.PROGRESS_DEBOUNCE_MS)
            writeProgress(page)
        }
    }

    suspend fun flush() {
        pendingJob?.cancel()
        writeProgress(currentPage)
    }

    private suspend fun writeProgress(page: Int) {
        try {
            val completed = page >= totalPages - 1
            apiClient.updateReadProgress(bookId, page, completed)
        } catch (t: Throwable) {
            Log.w(KomgaConstants.LOG_TAG, "progress write failed: ${t.message}")
            delay(2000)
            runCatching { apiClient.updateReadProgress(bookId, page, page >= totalPages - 1) }
        }
    }
}