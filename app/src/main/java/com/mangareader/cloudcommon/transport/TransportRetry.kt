package com.mangareader.cloudcommon.transport

import kotlinx.coroutines.delay
import java.io.IOException

object TransportRetry {

    suspend fun <T> withRetry(
        maxRetry: Int = 3,
        backoffMs: List<Long> = listOf(500L, 1000L, 2000L),
        retryable: (Throwable) -> Boolean = RetryableJudge::isRetryable,
        block: suspend () -> T,
    ): T {
        var lastError: Throwable? = null
        for (attempt in 0..maxRetry) {
            try {
                return block()
            } catch (e: Throwable) {
                lastError = e
                if (!retryable(e)) throw e
                if (attempt >= maxRetry) break
                val delayMs = backoffMs.getOrElse(attempt) { backoffMs.last() }
                delay(delayMs)
            }
        }
        val e = lastError ?: IOException("TransportRetry exhausted with no error")
        throw IOException("TransportRetry exhausted after ${maxRetry + 1} attempts: ${e.message}", e)
    }
}