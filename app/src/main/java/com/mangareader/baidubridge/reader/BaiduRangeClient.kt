package com.mangareader.baidubridge.reader

import com.hippo.ehviewer.library.RemoteRangeNotSupportedException
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
    private val rateLimiter: BaiduRateLimiter = BaiduRateLimiter(),
) {
    suspend fun readRange(cdnUrl: String, offset: Long, len: Int): ByteArray {
        rateLimiter.acquire()
        val end = offset + len - 1
        val response = httpClient.get(cdnUrl) {
            header("Range", "bytes=$offset-$end")
            header("User-Agent", BaiduConstants.BAIDU_UA)
        }
        when (response.status.value) {
            206 -> return response.body()
            200 -> throw RemoteRangeNotSupportedException(cdnUrl, offset)
            416 -> return ByteArray(0)
            403 -> throw java.io.IOException("dlink signature expired")
            else -> throw java.io.IOException("CDN read failed: ${response.status.value}")
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