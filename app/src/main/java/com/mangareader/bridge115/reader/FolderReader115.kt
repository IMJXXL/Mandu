package com.mangareader.bridge115.reader

import com.hippo.ehviewer.library.IMAGE_EXTENSIONS
import com.hippo.ehviewer.library.naturalCompare
import com.mangareader.bridge115.Constants115
import com.mangareader.bridge115.api.Api115Client
import com.mangareader.bridge115.api.FileEntry115
import com.mangareader.bridge115.api.HttpClient115
import com.mangareader.bridge115.api.Web115Client
import com.mangareader.cloudcommon.folder.BaseFolderReader
import com.mangareader.cloudcommon.folder.FolderIndexCache
import io.ktor.client.HttpClient

class FolderReader115(
    private val cid: String,
    private val mode: String,
    private val apiClient: Api115Client? = null,
    private val webClient: Web115Client? = null,
) : BaseFolderReader() {

    private var imageEntries: List<FileEntry115> = emptyList()

    private val ua: String = if (mode == Constants115.MODE_OFFICIAL) Constants115.UA_OFFICIAL else Constants115.UA_WEB

    suspend fun load() {
        val cacheKey = "115:${mode}:${cid}"
        val cached: List<FileEntry115>? = FolderIndexCache.get(cacheKey)
        if (cached != null) {
            imageEntries = cached
                .filter { it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
                .sortedWith { a, b -> naturalCompare(a.name, b.name) }
            pageCountField = imageEntries.size
            return
        }
        val allItems = mutableListOf<FileEntry115>()
        if (mode == Constants115.MODE_OFFICIAL) {
            val client = apiClient ?: throw IllegalStateException("api client not initialized")
            val entries = client.list(cid)
            allItems.addAll(entries.filter { !it.isDir })
        } else {
            val client = webClient ?: throw IllegalStateException("web client not initialized")
            val entries = client.list(cid)
            allItems.addAll(entries.filter { !it.isDir })
        }
        imageEntries = allItems
            .filter { it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
            .sortedWith { a, b -> naturalCompare(a.name, b.name) }
        pageCountField = imageEntries.size
        FolderIndexCache.put(cacheKey, allItems)
    }

    private suspend fun getDownloadInfo(entry: FileEntry115): com.mangareader.bridge115.api.DownloadInfo115 {
        return if (mode == Constants115.MODE_OFFICIAL) {
            apiClient?.downurl(entry.pickCode) ?: throw IllegalStateException("api client not initialized")
        } else {
            webClient?.downurl(entry.pickCode) ?: throw IllegalStateException("web client not initialized")
        }
    }

    override suspend fun getDownloadUrl(index: Int): String? {
        if (index !in imageEntries.indices) return null
        val entry = imageEntries[index]
        val downloadInfo = getDownloadInfo(entry)
        return downloadInfo.url
    }

    override fun getAuthHeaders(url: String): Map<String, String> {
        return mapOf("User-Agent" to ua)
    }

    override fun getHttpClient(): HttpClient = HttpClient115.client()
}