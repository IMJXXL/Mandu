package com.mangareader.bridge115.reader

import com.hippo.ehviewer.library.RemoteRangeNotSupportedException
import com.mangareader.cloudcommon.transport.ContentRangeParser
import com.mangareader.cloudcommon.transport.DownloadGate
import com.mangareader.cloudcommon.transport.RetryableJudge
import com.mangareader.cloudcommon.transport.TransportRetry
import com.mangareader.cloudcommon.transport.RequestDiagTracer
import com.mangareader.bridge115.Constants115
import com.mangareader.bridge115.api.HttpClient115
import com.mangareader.bridge115.api.RateLimiter115
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.head
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode

class RangeClient115(
    private val httpClient: HttpClient = HttpClient115.client(),
    private val rateLimiter: RateLimiter115 = RateLimiter115(),
    private val ua: String = Constants115.UA_WEB,
) {
    private val downloadGate = DownloadGate(2)
    private val wafCooldown = WafCooldown()
    private val cdnGate = CdnGate()
    private val diagTracer = RequestDiagTracer("Range115")

    init {
        android.util.Log.d("Range115", "sticky session active: HttpClient reused for connection pooling")
    }

    suspend fun readRange(cdnUrl: String, offset: Long, len: Int): ByteArray {
        val diagStart = diagTracer.begin()
        try {
        wafCooldown.checkAndThrow()
        rateLimiter.acquire()
        return downloadGate.withPermit {
            cdnGate.withGate {
                TransportRetry.withRetry(
                maxRetry = 3,
                backoffMs = listOf(500L, 1000L, 2000L),
                retryable = RetryableJudge::isRetryable,
            ) {
                val end = offset + len - 1
                val response = httpClient.get(cdnUrl) {
                    header("Range", "bytes=$offset-$end")
                    header("User-Agent", ua)
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
                    405 -> {
                        wafCooldown.enterCooldown()
                        throw WafCooldownException()
                    }
                    416 -> ByteArray(0)
                    403 -> throw java.io.IOException("download link expired")
                    else -> throw java.io.IOException("CDN read failed: ${response.status.value}")
                }
                }
            }
        }
        } finally {
            diagTracer.end(diagStart, false, 0)
        }
    }

    suspend fun probeSize(cdnUrl: String): Long {
        rateLimiter.acquire()
        val response = httpClient.head(cdnUrl) {
            header("User-Agent", ua)
        }
        return response.headers["Content-Length"]?.toLongOrNull() ?: -1L
    }

    suspend fun probeRangeSupport(cdnUrl: String): Boolean {
        rateLimiter.acquire()
        val response = httpClient.get(cdnUrl) {
            header("Range", "bytes=0-0")
            header("User-Agent", ua)
        }
        return response.status == HttpStatusCode.PartialContent
    }
}