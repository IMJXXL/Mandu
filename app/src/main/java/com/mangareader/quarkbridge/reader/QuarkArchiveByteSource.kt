package com.mangareader.quarkbridge.reader

import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.BlockCacheArchiveByteSource
import com.hippo.ehviewer.library.ReadAheadArchiveByteSource
import com.hippo.ehviewer.library.RemoteRangeNotSupportedException
import com.mangareader.quarkbridge.api.QuarkDownloadInfo
import com.mangareader.quarkbridge.api.QuarkRateLimiter
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking

class QuarkArchiveByteSource(
    initialUrl: String,
    initialCookie: String,
    private val urlProvider: (suspend () -> QuarkDownloadInfo)? = null,
    private val rateLimiter: QuarkRateLimiter = QuarkRateLimiter(),
    preferSequential: Boolean = false,
    pipeline: Boolean = true,
    sequentialWindow: Int = ReadAheadArchiveByteSource.SEQUENTIAL_WINDOW,
    knownSize: Long = -1L,
    readahead: Boolean = true,
) : ArchiveByteSource {
    private val inner: ArchiveByteSource = run {
        val raw = RawQuarkArchiveByteSource(
            initialUrl, initialCookie, urlProvider, rateLimiter, knownSize,
        )
        if (readahead) {
            val readAhead = ReadAheadArchiveByteSource(
                inner = raw,
                sequentialWindow = sequentialWindow,
                preferSequential = preferSequential,
                pipeline = pipeline,
            )
            BlockCacheArchiveByteSource(
                inner = readAhead,
                knownSize = knownSize,
                blockSize = BlockCacheArchiveByteSource.ARCHIVE_STREAM_BLOCK_SIZE,
                maxBlocks = BlockCacheArchiveByteSource.ARCHIVE_STREAM_MAX_BLOCKS,
            )
        } else {
            raw
        }
    }

    override val size: Long get() = inner.size
    override val isRandomAccess: Boolean get() = inner.isRandomAccess
    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int = inner.readAt(offset, buf, off, len)
    override fun warm(offset: Long, length: Int) = inner.warm(offset, length)
    override fun dropQueuedReads() = inner.dropQueuedReads()
    override fun requestReconnect() = inner.requestReconnect()
    override fun close() = inner.close()
}

private class RawQuarkArchiveByteSource(
    initialUrl: String,
    initialCookie: String,
    private val urlProvider: (suspend () -> QuarkDownloadInfo)?,
    private val rateLimiter: QuarkRateLimiter,
    knownSize: Long = -1L,
) : ArchiveByteSource {
    @Volatile private var cdnUrl: String = initialUrl
    @Volatile private var tempCookie: String = initialCookie
    private val rangeClient = QuarkRangeClient(rateLimiter = rateLimiter)
    private val sizeBytes = AtomicLong(if (knownSize > 0L) knownSize else 0L)
    private val failFastUntilMs = AtomicLong(0L)
    private val closed = AtomicBoolean(false)
    private val activeJobs = ConcurrentHashMap.newKeySet<Job>()

    override val size: Long
        get() {
            if (closed.get()) throw IOException("Quark archive source closed")
            val cached = sizeBytes.get()
            if (cached > 0L) return cached
            val now = System.currentTimeMillis()
            if (now < failFastUntilMs.get()) throw IOException("Cannot stat Quark archive (recent fail)")
            val s = resolveSizeWithRetry()
            if (closed.get()) throw IOException("Quark archive source closed")
            if (s != null && s > 0L) {
                sizeBytes.compareAndSet(0L, s)
                val after = sizeBytes.get()
                return if (after > 0L) after else s
            }
            failFastUntilMs.set(now + FAIL_FAST_MS)
            throw IOException("Cannot stat Quark archive")
        }

    private fun resolveSizeWithRetry(): Long? = try {
        withTrackedJob {
            var last: Long? = null
            repeat(SIZE_ATTEMPTS) { attempt ->
                if (closed.get()) return@withTrackedJob null
                val size = runCatching { rangeClient.probeSize(cdnUrl, tempCookie) }.getOrNull()
                last = size
                if (size != null && size > 0L) return@withTrackedJob size
                if (attempt < SIZE_ATTEMPTS - 1) delay(SIZE_BACKOFF_MS * (attempt + 1))
            }
            last?.takeIf { it > 0L }
        }
    } catch (_: CancellationException) {
        null
    } catch (_: Throwable) {
        null
    }

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        if (closed.get()) return -1
        return try {
            val fileSize = size
            if (offset >= fileSize) return 0
            val toRead = minOf(len.toLong(), fileSize - offset).toInt()
            withTrackedJob {
                if (closed.get()) return@withTrackedJob -1
                doReadWithRefresh(offset, buf, off, toRead)
            }
        } catch (e: RemoteRangeNotSupportedException) {
            throw e
        } catch (e: CancellationException) {
            -1
        } catch (_: Throwable) {
            if (closed.get()) return -1
            -1
        }
    }

    private suspend fun doReadWithRefresh(offset: Long, buf: ByteArray, off: Int, toRead: Int): Int {
        return try {
            val bytes = rangeClient.readRange(cdnUrl, tempCookie, offset, toRead)
            System.arraycopy(bytes, 0, buf, off, bytes.size)
            bytes.size
        } catch (e: RemoteRangeNotSupportedException) {
            throw e
        } catch (e: Throwable) {
            if (urlProvider != null && !closed.get()) {
                refreshUrl()
                val bytes = rangeClient.readRange(cdnUrl, tempCookie, offset, toRead)
                System.arraycopy(bytes, 0, buf, off, bytes.size)
                bytes.size
            } else {
                throw e
            }
        }
    }

    private suspend fun refreshUrl() {
        val info = urlProvider?.invoke() ?: return
        cdnUrl = info.url
        tempCookie = info.tempCookie
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val jobs = activeJobs.toTypedArray()
        activeJobs.clear()
        for (job in jobs) job.cancel()
    }

    private fun <T> withTrackedJob(block: suspend () -> T): T {
        if (closed.get()) throw CancellationException("Quark archive source closed")
        return runBlocking {
            val job = currentCoroutineContext().job
            activeJobs.add(job)
            try {
                if (closed.get()) throw CancellationException("Quark archive source closed")
                block()
            } finally {
                activeJobs.remove(job)
            }
        }
    }

    private companion object {
        const val SIZE_ATTEMPTS = 3
        const val SIZE_BACKOFF_MS = 250L
        const val FAIL_FAST_MS = 1_500L
    }
}