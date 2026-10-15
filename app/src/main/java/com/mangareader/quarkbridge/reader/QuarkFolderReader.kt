package com.mangareader.quarkbridge.reader

import com.hippo.ehviewer.library.IMAGE_EXTENSIONS
import com.hippo.ehviewer.library.naturalCompare
import com.mangareader.cloudcommon.folder.BaseFolderReader
import com.mangareader.cloudcommon.folder.FolderIndexCache
import com.mangareader.quarkbridge.QuarkConstants
import com.mangareader.quarkbridge.api.QuarkApiClient
import com.mangareader.quarkbridge.api.QuarkHttpClient
import io.ktor.client.HttpClient

class QuarkFolderReader(
    private val folderFid: String,
    private val apiClient: QuarkApiClient,
) : BaseFolderReader() {

    private var imageEntries: List<com.mangareader.quarkbridge.api.QuarkFileEntry> = emptyList()

    private var currentCookie: String = ""

    suspend fun load() {
        val cacheKey = "quark:${folderFid}"
        val cached: List<com.mangareader.quarkbridge.api.QuarkFileEntry>? = FolderIndexCache.get(cacheKey)
        if (cached != null) {
            imageEntries = cached
                .filter { it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
                .sortedWith { a, b -> naturalCompare(a.name, b.name) }
            pageCountField = imageEntries.size
            return
        }
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
        FolderIndexCache.put(cacheKey, allItems)
    }

    override suspend fun getDownloadUrl(index: Int): String? {
        if (index !in imageEntries.indices) return null
        val entry = imageEntries[index]
        val downloadInfo = apiClient.getDownloadUrl(entry.fid)
        currentCookie = downloadInfo.tempCookie
        return downloadInfo.url
    }

    override fun getAuthHeaders(url: String): Map<String, String> {
        val headers = mutableMapOf("User-Agent" to QuarkConstants.QUARK_UA)
        if (currentCookie.isNotEmpty()) headers["Cookie"] = currentCookie
        return headers
    }

    override fun getHttpClient(): HttpClient = QuarkHttpClient.client()
}