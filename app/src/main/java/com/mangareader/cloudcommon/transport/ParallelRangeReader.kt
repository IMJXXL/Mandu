package com.mangareader.cloudcommon.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

object ParallelRangeReader {
    private const val CHUNK_SIZE = 512 * 1024
    private const val PARALLEL_THRESHOLD = 2 * 1024 * 1024

    @Volatile
    var enabled = false

    suspend fun readParallel(
        offset: Long,
        len: Int,
        readChunk: suspend (offset: Long, len: Int) -> ByteArray,
    ): ByteArray = withContext(Dispatchers.IO) {
        if (!enabled || len < PARALLEL_THRESHOLD) {
            return@withContext readChunk(offset, len)
        }

        val chunks = mutableListOf<Pair<Long, Int>>()
        var pos = offset
        var remaining = len
        while (remaining > 0) {
            val chunkLen = minOf(remaining, CHUNK_SIZE)
            chunks.add(pos to chunkLen)
            pos += chunkLen
            remaining -= chunkLen
        }

        coroutineScope {
            val results = chunks.map { (chunkOffset, chunkLen) ->
                async { readChunk(chunkOffset, chunkLen) }
            }.awaitAll()

            val buf = ByteArray(len)
            var written = 0
            for (bytes in results) {
                System.arraycopy(bytes, 0, buf, written, bytes.size)
                written += bytes.size
            }
            buf
        }
    }
}