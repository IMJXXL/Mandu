package com.mangareader.quarkbridge.browser

import com.mangareader.quarkbridge.api.QuarkApiClient
import com.mangareader.quarkbridge.api.QuarkApiException
import com.mangareader.quarkbridge.api.QuarkAuthException
import com.mangareader.quarkbridge.api.QuarkDirResult
import com.mangareader.quarkbridge.api.QuarkFileEntry
import com.mangareader.quarkbridge.data.QuarkCacheManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class QuarkDirResultWithCache(
    val result: QuarkDirResult,
    val fromCache: Boolean
)

class QuarkDirService(
    private val apiClient: QuarkApiClient,
    private val cache: QuarkCacheManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun listDir(
        pdirFid: String,
        page: Int = 1,
        forceRefresh: Boolean = false
    ): QuarkDirResultWithCache {
        if (!forceRefresh) {
            val cached = cache.getDirCache(pdirFid)
            if (cached != null) {
                scope.launch {
                    try {
                        val fresh = apiClient.listDir(pdirFid, page)
                        cache.saveDirCache(pdirFid, fresh.items)
                    } catch (e: Exception) {}
                }
                return QuarkDirResultWithCache(
                    QuarkDirResult(cached, false, cached.size),
                    fromCache = true
                )
            }
        }

        return try {
            val result = apiClient.listDir(pdirFid, page)
            cache.saveDirCache(pdirFid, result.items)
            QuarkDirResultWithCache(result, fromCache = false)
        } catch (e: QuarkAuthException) {
            throw e
        } catch (e: Exception) {
            val cached = cache.getDirCache(pdirFid)
            if (cached != null) {
                QuarkDirResultWithCache(
                    QuarkDirResult(cached, false, cached.size),
                    fromCache = true
                )
            } else {
                throw e
            }
        }
    }
}