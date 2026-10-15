package com.mangareader.baidubridge.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hippo.ehviewer.library.IMAGE_EXTENSIONS
import com.hippo.ehviewer.library.naturalCompare
import com.mangareader.baidubridge.BaiduConstants
import com.mangareader.baidubridge.api.BaiduApiClient
import com.mangareader.baidubridge.api.BaiduHttpClient
import com.mangareader.native.RemotePageSupply
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BaiduFolderReader(
    private val folderPath: String,
    private val apiClient: BaiduApiClient,
) : RemotePageSupply {

    private var imageEntries: List<com.mangareader.baidubridge.api.BaiduFileEntry> = emptyList()

    @Volatile
    private var pageCountField: Int = 0

    override val pageCount: Int get() = pageCountField

    override val onPageCountGrow: MutableList<(newCount: Int) -> Unit> = java.util.concurrent.CopyOnWriteArrayList()

    suspend fun load() {
        val allItems = mutableListOf<com.mangareader.baidubridge.api.BaiduFileEntry>()
        var start = 0
        while (true) {
            val entries = apiClient.listDir(folderPath, start)
            if (entries.isEmpty()) break
            allItems.addAll(entries.filter { !it.isDir })
            start += BaiduConstants.PAGE_SIZE
        }
        imageEntries = allItems
            .filter { it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
            .sortedWith { a, b -> naturalCompare(a.name, b.name) }
        pageCountField = imageEntries.size
    }

    override suspend fun loadPageRaw(index: Int): ByteArray? = withContext(Dispatchers.IO) {
        if (index !in imageEntries.indices) return@withContext null
        try {
            val entry = imageEntries[index]
            val downloadInfo = apiClient.dlink(entry.path)
            val client = BaiduHttpClient.client()
            val response = client.get(downloadInfo.url) {
                header("User-Agent", BaiduConstants.BAIDU_UA)
            }
            response.body<ByteArray>()
        } catch (t: Throwable) {
            android.util.Log.w("BaiduFolderReader", "loadPageRaw($index) failed: ${t.message}")
            null
        }
    }

    override suspend fun loadBitmap(index: Int): Bitmap? {
        val raw = loadPageRaw(index) ?: return null
        return try {
            BitmapFactory.decodeByteArray(raw, 0, raw.size)
        } catch (e: OutOfMemoryError) {
            System.gc()
            try {
                BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = 2 })
            } catch (_: Exception) { null }
        }
    }

    override fun onFocus(index: Int) {}

    override suspend fun close() {}
}