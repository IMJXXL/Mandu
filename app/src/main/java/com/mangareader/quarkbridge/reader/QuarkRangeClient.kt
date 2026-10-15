package com.mangareader.quarkbridge.reader

import com.hippo.ehviewer.library.RemoteRangeNotSupportedException
import com.mangareader.cloudcommon.transport.ContentRangeParser
import com.mangareader.cloudcommon.transport.DownloadGate
import com.mangareader.cloudcommon.transport.RetryableJudge
import com.mangareader.cloudcommon.transport.TransportRetry
import com.mangareader.cloudcommon.transport.RequestDiagTracer
import com.mangareader.quarkbridge.QuarkConstants
import com.mangareader.quarkbridge.api.QuarkHttpClient
import com.mangareader.quarkbridge.api.QuarkRateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.head
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode

class QuarkRangeClient(
    private val httpClient: HttpClient = QuarkHttpClient.client(),
    private val rateLimiter: QuarkRateLimiter = QuarkRateLimiter(),
) {
    private val downloadGate = DownloadGate(4)
    private val diagTracer = RequestDiagTracer("QuarkRange")

    init {
        android.util.Log.d("QuarkRange", "sticky session active: HttpClient reused for connection pooling")
    }

    suspend fun readRange(cdnUrl: String, tempCookie: String, offset: Long, len: Int): ByteArray {
        val diagStart = diagTracer.begin()
        try {
        rateLimiter.acquire()
        return downloadGate.withPermit {
            TransportRetry.withRetry(
                maxRetry = 3,
                backoffMs = listOf(500L, 1000L, 2000L),
                retryable = RetryableJudge::isRetryable,
            ) {
                val end = offset + len - 1
                val response = httpClient.get(cdnUrl) {
                    header("Range", "bytes=$offset-$end")
                    header("User-Agent", QuarkConstants.QUARK_UA)
                    header("Referer", QuarkConstants.QUARK_REFERER)
                    if (tempCookie.isNotEmpty()) header("Cookie", tempCookie)
                }
                when (response.status.value) {
                    206 -> {
                        val cr = response.headers["Content-Range"] ?: response.headers["content-range"]
                        val rangeStart = ContentRangeParser.parseStart(cr)
                        if (rangeStart == null || rangeStart != offset) {
                            throw RemoteRangeNotSupportedException(cdnUrl, offset)
                        }
                        response.body()
                    }
                    200 -> throw RemoteRangeNotSupportedException(cdnUrl, offset)
                    416 -> ByteArray(0)
                    else -> throw java.io.IOException("CDN read failed: ${response.status.value}")
                }
            }
        }
        } finally {
            diagTracer.end(diagStart, false, 0)
        }
    }

    suspend fun probeSize(cdnUrl: String, tempCookie: String): Long {
        rateLimiter.acquire()
        val response = httpClient.head(cdnUrl) {
            header("User-Agent", QuarkConstants.QUARK_UA)
            if (tempCookie.isNotEmpty()) header("Cookie", tempCookie)
        }
        return response.headers["Content-Length"]?.toLongOrNull() ?: -1L
    }

    suspend fun probeRangeSupport(cdnUrl: String, tempCookie: String): Boolean {
        rateLimiter.acquire()
        val response = httpClient.get(cdnUrl) {
            header("Range", "bytes=0-0")
            header("User-Agent", QuarkConstants.QUARK_UA)
            header("Referer", QuarkConstants.QUARK_REFERER)
            if (tempCookie.isNotEmpty()) header("Cookie", tempCookie)
        }
        return response.status == HttpStatusCode.PartialContent
    }
}