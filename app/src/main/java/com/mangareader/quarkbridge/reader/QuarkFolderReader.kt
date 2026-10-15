package com.mangareader.quarkbridge.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hippo.ehviewer.library.IMAGE_EXTENSIONS
import com.hippo.ehviewer.library.naturalCompare
import com.mangareader.native.RemotePageSupply
import com.mangareader.quarkbridge.QuarkConstants
import com.mangareader.quarkbridge.api.QuarkApiClient
import com.mangareader.quarkbridge.api.QuarkHttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class QuarkFolderReader(
    private val folderFid: String,
    private val apiClient: QuarkApiClient,
) : RemotePageSupply {

    private var imageEntries: List<com.mangareader.quarkbridge.api.QuarkFileEntry> = emptyList()

    @Volatile
    private var pageCountField: Int = 0

    override val pageCount: Int get() = pageCountField

    override val onPageCountGrow: MutableList<(newCount: Int) -> Unit> = java.util.concurrent.CopyOnWriteArrayList()

    suspend fun load() {
        val allItems = mutableListOf<com.mangareader.quarkbridge.api.QuarkFileEntry>()
        var page = 1
        var hasMore = true
        while (hasMore) {
            val result = apiClient.listDir(folderFid, page)
            allItems.addAll(result.items.filter { !it.isDir })
            hasMore = result.hasMore
            page++
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
            val downloadInfo = apiClient.getDownloadUrl(entry.fid)
            val client = QuarkHttpClient.client()
            val response = client.get(downloadInfo.url) {
                header("User-Agent", QuarkConstants.QUARK_UA)
                if (downloadInfo.tempCookie.isNotEmpty()) header("Cookie", downloadInfo.tempCookie)
            }
            response.body<ByteArray>()
        } catch (t: Throwable) {
            android.util.Log.w("QuarkFolderReader", "loadPageRaw($index) failed: ${t.message}")
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