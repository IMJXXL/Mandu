package com.mangareader.bridge115.reader

import com.hippo.ehviewer.library.RemoteRangeNotSupportedException
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
    suspend fun readRange(cdnUrl: String, offset: Long, len: Int): ByteArray {
        rateLimiter.acquire()
        val end = offset + len - 1
        val response = httpClient.get(cdnUrl) {
            header("Range", "bytes=$offset-$end")
            header("User-Agent", ua)
        }
        when (response.status.value) {
            206 -> return response.body()
            200 -> throw RemoteRangeNotSupportedException(cdnUrl, offset)
            416 -> return ByteArray(0)
            403 -> throw java.io.IOException("download link expired")
            else -> throw java.io.IOException("CDN read failed: ${response.status.value}")
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