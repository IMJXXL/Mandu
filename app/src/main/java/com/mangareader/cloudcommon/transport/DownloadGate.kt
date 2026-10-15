package com.mangareader.cloudcommon.transport

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class DownloadGate(maxPermits: Int) {
    private val semaphore = Semaphore(maxPermits)

    suspend fun <T> withPermit(block: suspend () -> T): T = semaphore.withPermit { block() }
}