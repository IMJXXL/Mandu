package com.mangareader.quarkbridge.reader

import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

class CookieGeneration {
    private val generation = AtomicInteger(0)

    fun current(): Int = generation.get()

    fun bump(): Int = generation.incrementAndGet()

    fun checkGeneration(myGeneration: Int) {
        if (myGeneration != current()) {
            throw CancellationException("cookie generation expired: $myGeneration -> ${current()}")
        }
    }
}