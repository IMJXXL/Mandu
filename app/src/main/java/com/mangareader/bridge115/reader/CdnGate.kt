package com.mangareader.bridge115.reader

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class CdnGate(
    ratePerSecond: Int = 4,
    burst: Int = 6,
    maxInFlight: Int = 2,
) {
    private val inFlight = Semaphore(maxInFlight)
    private val minIntervalMs = 1000L / ratePerSecond
    private val burstAllowance = burst
    private var tokens = burst
    private var lastRefillAt = System.currentTimeMillis()
    private val lock = Any()

    private fun acquireToken() {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val elapsed = now - lastRefillAt
            val refilled = (elapsed / minIntervalMs).toInt()
            if (refilled > 0) {
                tokens = minOf(tokens + refilled, burstAllowance)
                lastRefillAt += refilled * minIntervalMs
            }
            if (tokens > 0) {
                tokens--
            }
        }
    }

    suspend fun <T> withGate(block: suspend () -> T): T {
        acquireToken()
        return inFlight.withPermit { block() }
    }
}