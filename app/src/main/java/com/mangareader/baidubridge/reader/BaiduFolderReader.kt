package com.mangareader.baidubridge.reader

import com.hippo.ehviewer.library.IMAGE_EXTENSIONS
import com.hippo.ehviewer.library.naturalCompare
import com.mangareader.baidubridge.BaiduConstants
import com.mangareader.baidubridge.api.BaiduApiClient
import com.mangareader.baidubridge.api.BaiduHttpClient
import com.mangareader.cloudcommon.folder.BaseFolderReader
import com.mangareader.cloudcommon.folder.FolderIndexCache
import io.ktor.client.HttpClient

class BaiduFolderReader(
    private val folderPath: String,
    private val apiClient: BaiduApiClient,
) : BaseFolderReader() {

    private var imageEntries: List<com.mangareader.baidubridge.api.BaiduFileEntry> = emptyList()

    suspend fun load() {
        val cacheKey = "baidu:${folderPath}"
        val cached: List<com.mangareader.baidubridge.api.BaiduFileEntry>? = FolderIndexCache.get(cacheKey)
        if (cached != null) {
            imageEntries = cached
                .filter { it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
                .sortedWith { a, b -> naturalCompare(a.name, b.name) }
            pageCountField = imageEntries.size
            return
        }
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
        FolderIndexCache.put(cacheKey, allItems)
    }

    override suspend fun getDownloadUrl(index: Int): String? {
        if (index !in imageEntries.indices) return null
        val entry = imageEntries[index]
        val downloadInfo = apiClient.dlink(entry.path)
        return downloadInfo.url
    }

    override fun getAuthHeaders(url: String): Map<String, String> {
        return mapOf("User-Agent" to BaiduConstants.BAIDU_UA)
    }

    override fun getHttpClient(): HttpClient = BaiduHttpClient.client()
}