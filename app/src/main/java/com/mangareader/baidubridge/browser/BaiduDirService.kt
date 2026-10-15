package com.mangareader.baidubridge.browser

import com.mangareader.baidubridge.api.BaiduApiClient
import com.mangareader.baidubridge.api.BaiduAuthException
import com.mangareader.baidubridge.api.BaiduFileEntry
import com.mangareader.baidubridge.data.BaiduCacheManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class BaiduDirResultWithCache(
    val items: List<BaiduFileEntry>,
    val fromCache: Boolean
)

class BaiduDirService(
    private val apiClient: BaiduApiClient,
    private val cache: BaiduCacheManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun listDir(
        dir: String,
        forceRefresh: Boolean = false
    ): BaiduDirResultWithCache {
        if (!forceRefresh) {
            val cached = cache.getDirCache(dir)
            if (cached != null) {
                scope.launch {
                    try {
                        val fresh = apiClient.listDir(dir)
                        cache.saveDirCache(dir, fresh)
                    } catch (e: Exception) {}
                }
                return BaiduDirResultWithCache(cached, fromCache = true)
            }
        }

        return try {
            val result = apiClient.listDir(dir)
            cache.saveDirCache(dir, result)
            BaiduDirResultWithCache(result, fromCache = false)
        } catch (e: BaiduAuthException) {
            throw e
        } catch (e: Exception) {
            val cached = cache.getDirCache(dir)
            if (cached != null) {
                BaiduDirResultWithCache(cached, fromCache = true)
            } else {
                throw e
            }
        }
    }
}