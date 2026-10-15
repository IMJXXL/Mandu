package com.mangareader.cloudcommon.folder

import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import com.hippo.ehviewer.library.BlockCacheArchiveByteSource
import com.hippo.ehviewer.library.ReadAheadArchiveByteSource
import com.mangareader.native.RemotePageSupply
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

abstract class BaseFolderReader : RemotePageSupply {

    @Volatile
    protected var pageCountField: Int = 0

    override val pageCount: Int get() = pageCountField

    override val onPageCountGrow: MutableList<(newCount: Int) -> Unit> = java.util.concurrent.CopyOnWriteArrayList()

    private val pageCache = LruCache<Int, ByteArray>(10)

    protected abstract suspend fun getDownloadUrl(index: Int): String?
    protected abstract fun getAuthHeaders(url: String): Map<String, String>
    protected abstract fun getHttpClient(): HttpClient

    override suspend fun loadPageRaw(index: Int): ByteArray? = withContext(Dispatchers.IO) {
        if (index < 0 || index >= pageCountField) return@withContext null

        pageCache.get(index)?.let { return@withContext it }

        try {
            val url = getDownloadUrl(index) ?: return@withContext null
            val headers = getAuthHeaders(url)
            val client = getHttpClient()

            val size = FolderByteSource.probeSize(url, client, headers)

            val bytes = if (size > 0 && size <= MAX_RANGE_FILE_SIZE) {
                downloadViaRange(url, size, client, headers)
            } else {
                downloadWhole(url, client, headers)
            }

            pageCache.put(index, bytes)
            bytes
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("BaseFolderReader", "loadPageRaw($index) failed: ${t.message}")
            null
        }
    }

    private suspend fun downloadViaRange(
        url: String,
        size: Long,
        client: HttpClient,
        headers: Map<String, String>,
    ): ByteArray {
        val source = FolderByteSource(url, size, client, headers)
        val buffered = ReadAheadArchiveByteSource(source)
        val cached = BlockCacheArchiveByteSource(buffered, size)

        try {
            val buf = ByteArray(size.toInt())
            var read = 0
            while (read < size) {
                val n = cached.readAt(read.toLong(), buf, read, minOf((size - read).toInt(), 65536))
                if (n <= 0) break
                read += n
            }
            return if (read == size.toInt()) buf else buf.copyOf(read)
        } finally {
            cached.close()
        }
    }

    private suspend fun downloadWhole(
        url: String,
        client: HttpClient,
        headers: Map<String, String>,
    ): ByteArray {
        val hdrs = headers
        val response = client.get(url) {
            hdrs.forEach { (k, v) -> header(k, v) }
        }
        if (response.status.value !in 200..299) {
            throw java.io.IOException("下载失败，HTTP ${response.status.value}")
        }
        return response.body()
    }

    override suspend fun loadBitmap(index: Int): Bitmap? {
        val raw = loadPageRaw(index) ?: return null
        return DownsampleFallback.decode(raw)
    }

    override fun onFocus(index: Int) {}

    override suspend fun close() {}

    companion object {
        private const val MAX_RANGE_FILE_SIZE = 20L * 1024 * 1024
    }
}