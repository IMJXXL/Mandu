package com.mangareader.baidubridge.reader

import com.hippo.ehviewer.library.RemoteRangeNotSupportedException
import com.mangareader.cloudcommon.transport.ContentRangeParser
import com.mangareader.cloudcommon.transport.DownloadGate
import com.mangareader.cloudcommon.transport.RetryableJudge
import com.mangareader.cloudcommon.transport.TransportRetry
import com.mangareader.cloudcommon.transport.RequestDiagTracer
import com.mangareader.baidubridge.BaiduConstants
import com.mangareader.baidubridge.api.BaiduHttpClient
import com.mangareader.baidubridge.api.BaiduRateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.head
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode

class BaiduRangeClient(
    private val httpClient: HttpClient = BaiduHttpClient.client(),
    private val rateLimiter: BaiduRateLimiter = BaiduRateLimiter(maxPerSecond = 5, minIntervalMs = 200L, maxIntervalMs = 300L),
) {
    private val downloadGate = DownloadGate(3)
    private val diagTracer = RequestDiagTracer("BaiduRange")

    init {
        android.util.Log.d("BaiduRange", "sticky session active: HttpClient reused for connection pooling")
    }

    suspend fun readRange(cdnUrl: String, offset: Long, len: Int): ByteArray {
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
                    header("User-Agent", BaiduConstants.BAIDU_UA)
                }
                val contentType = response.headers["Content-Type"] ?: response.headers["content-type"]
                if (contentType?.contains("application/json") == true) {
                    throw java.io.IOException("Baidu API rate limited (json response)")
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
                    403 -> throw java.io.IOException("dlink signature expired")
                    else -> throw java.io.IOException("CDN read failed: ${response.status.value}")
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
            header("User-Agent", BaiduConstants.BAIDU_UA)
        }
        return response.headers["Content-Length"]?.toLongOrNull() ?: -1L
    }

    suspend fun probeRangeSupport(cdnUrl: String): Boolean {
        rateLimiter.acquire()
        val response = httpClient.get(cdnUrl) {
            header("Range", "bytes=0-0")
            header("User-Agent", BaiduConstants.BAIDU_UA)
        }
        return response.status == HttpStatusCode.PartialContent
    }
}