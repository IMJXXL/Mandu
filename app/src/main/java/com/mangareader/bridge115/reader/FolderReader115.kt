package com.mangareader.bridge115.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hippo.ehviewer.library.IMAGE_EXTENSIONS
import com.hippo.ehviewer.library.naturalCompare
import com.mangareader.bridge115.Constants115
import com.mangareader.bridge115.api.Api115Client
import com.mangareader.bridge115.api.DownloadInfo115
import com.mangareader.bridge115.api.FileEntry115
import com.mangareader.bridge115.api.HttpClient115
import com.mangareader.bridge115.api.Web115Client
import com.mangareader.native.RemotePageSupply
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FolderReader115(
    private val cid: String,
    private val mode: String,
    private val apiClient: Api115Client? = null,
    private val webClient: Web115Client? = null,
) : RemotePageSupply {

    private var imageEntries: List<FileEntry115> = emptyList()

    @Volatile
    private var pageCountField: Int = 0

    override val pageCount: Int get() = pageCountField

    override val onPageCountGrow: MutableList<(newCount: Int) -> Unit> = java.util.concurrent.CopyOnWriteArrayList()

    suspend fun load() {
        val allItems = mutableListOf<FileEntry115>()
        if (mode == Constants115.MODE_OFFICIAL) {
            val client = apiClient ?: throw IllegalStateException("未初始化官方客户端")
            val entries = client.list(cid)
            allItems.addAll(entries.filter { !it.isDir })
        } else {
            val client = webClient ?: throw IllegalStateException("未初始化网页客户端")
            val entries = client.list(cid)
            allItems.addAll(entries.filter { !it.isDir })
        }
        imageEntries = allItems
            .filter { it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
            .sortedWith { a, b -> naturalCompare(a.name, b.name) }
        pageCountField = imageEntries.size
    }

    private suspend fun getDownloadInfo(entry: FileEntry115): DownloadInfo115 {
        return if (mode == Constants115.MODE_OFFICIAL) {
            apiClient?.downurl(entry.pickCode) ?: throw IllegalStateException("未初始化官方客户端")
        } else {
            webClient?.downurl(entry.pickCode) ?: throw IllegalStateException("未初始化网页客户端")
        }
    }

    private val ua: String = if (mode == Constants115.MODE_OFFICIAL) Constants115.UA_OFFICIAL else Constants115.UA_WEB

    override suspend fun loadPageRaw(index: Int): ByteArray? = withContext(Dispatchers.IO) {
        if (index !in imageEntries.indices) return@withContext null
        try {
            val entry = imageEntries[index]
            val downloadInfo = getDownloadInfo(entry)
            val client = HttpClient115.client()
            val response = client.get(downloadInfo.url) {
                header("User-Agent", ua)
            }
            response.body<ByteArray>()
        } catch (t: Throwable) {
            android.util.Log.w("FolderReader115", "loadPageRaw($index) failed: ${t.message}")
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