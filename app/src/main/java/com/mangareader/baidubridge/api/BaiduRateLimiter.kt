package com.mangareader.baidubridge.api

import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

class BaiduRateLimiter(
    private val maxPerSecond: Int = 1,
    private val minIntervalMs: Long = 1000L,
    private val maxIntervalMs: Long = 2000L
) {
    private val lastAcquireNs = AtomicLong(0L)
    @Volatile
    private var suspendedUntilMs: Long = 0L

    suspend fun acquire() {
        val now = System.currentTimeMillis()
        val until = suspendedUntilMs
        if (now < until) {
            delay(until - now)
        }

        val lastNs = lastAcquireNs.get()
        val elapsedMs = (System.nanoTime() - lastNs) / 1_000_000
        val jitterMs = Random.nextLong(minIntervalMs, maxIntervalMs + 1)
        val waitMs = jitterMs - elapsedMs
        if (waitMs > 0 && lastNs > 0) {
            delay(waitMs)
        }
        lastAcquireNs.set(System.nanoTime())
    }

    fun suspendFor(seconds: Long) {
        suspendedUntilMs = System.currentTimeMillis() + seconds * 1000
    }
}