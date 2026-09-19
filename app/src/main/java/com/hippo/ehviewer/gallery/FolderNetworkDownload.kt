package com.hippo.ehviewer.gallery

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@PublishedApi
internal const val RAM_PREFETCH_PERMITS = 2

@PublishedApi
internal suspend fun <T> withFolderNetworkPermit(
    isAnchor: Boolean,
    cacheOff: Boolean,
    libHdr: Boolean,
    interactiveSlots: Semaphore,
    ramPrefetchSlots: Semaphore,
    libHdrPrefetchSlots: Semaphore,
    prefetchSlots: Semaphore,
    block: suspend () -> T,
): T {
    if (isAnchor && interactiveSlots.tryAcquire()) {
        try {
            return block()
        } finally {
            interactiveSlots.release()
        }
    }
    val fallback = when {
        cacheOff -> ramPrefetchSlots
        libHdr -> libHdrPrefetchSlots
        else -> prefetchSlots
    }
    return fallback.withPermit { block() }
}