package com.mangareader.bridge115.reader

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class CdnPriority {
    FOREGROUND,
    PREFETCH,
    COVER,
}

class CdnPriorityAging(
    val foregroundSlots: Int = 2,
    val prefetchSlots: Int = 1,
    val coverSlots: Int = 1,
) {
    private val foregroundGate = Semaphore(foregroundSlots)
    private val prefetchGate = Semaphore(prefetchSlots)
    private val coverGate = Semaphore(coverSlots)

    suspend fun <T> withPriority(priority: CdnPriority, block: suspend () -> T): T {
        val gate = when (priority) {
            CdnPriority.FOREGROUND -> foregroundGate
            CdnPriority.PREFETCH -> prefetchGate
            CdnPriority.COVER -> coverGate
        }
        return gate.withPermit { block() }
    }
}