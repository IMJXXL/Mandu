package com.mangareader.cloudcommon.folder

import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.ReadAheadArchiveByteSource
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.head
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.IOException

class FolderByteSource(
    private val url: String,
    private val fileSize: Long,
    private val httpClient: HttpClient,
    private val headers: Map<String, String>,
) : ArchiveByteSource {

    override val size: Long = fileSize
    override val isRandomAccess: Boolean = true

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (offset >= size) return 0
        val actualLen = minOf(len.toLong(), size - offset).toInt()
        if (actualLen <= 0) return 0
        val hdrs = this.headers
        return runBlocking(Dispatchers.IO) {
            try {
                val end = offset + actualLen - 1
                val response = httpClient.get(url) {
                    header("Range", "bytes=$offset-$end")
                    hdrs.forEach { (k, v) -> header(k, v) }
                }
                if (response.status.value != 206 && response.status.value != 200) {
                    throw IOException("Range read failed: ${response.status.value}")
                }
                val bytes = response.body<ByteArray>()
                val copyLen = minOf(bytes.size, actualLen)
                System.arraycopy(bytes, 0, buf, off, copyLen)
                copyLen
            } catch (e: Throwable) {
                throw IOException("FolderByteSource readAt failed at offset=$offset: ${e.message}", e)
            }
        }
    }

    override fun warm(offset: Long, length: Int) = Unit
    override fun dropQueuedReads() = Unit
    override fun requestReconnect() = Unit
    override fun close() {}

    companion object {
        suspend fun probeSize(url: String, httpClient: HttpClient, headers: Map<String, String>): Long {
        val hdrs = headers
        return try {
            val response = httpClient.head(url) {
                hdrs.forEach { (k, v) -> header(k, v) }
            }
                response.headers["Content-Length"]?.toLongOrNull() ?: -1L
            } catch (e: CancellationException) { throw e
            } catch (_: Throwable) { -1L }
        }
    }
}