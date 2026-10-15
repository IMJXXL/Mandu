package com.mangareader.bridge115.browser

import com.mangareader.bridge115.Constants115
import com.mangareader.bridge115.api.Api115Client
import com.mangareader.bridge115.api.Auth115Exception
import com.mangareader.bridge115.api.FileEntry115
import com.mangareader.bridge115.api.Web115Client
import com.mangareader.bridge115.data.CacheManager115
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class DirResult115WithCache(
    val items: List<FileEntry115>,
    val fromCache: Boolean
)

class DirService115(
    private val apiClient: Api115Client? = null,
    private val webClient: Web115Client? = null,
    private val cache: CacheManager115
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun listDir(
        cid: String,
        mode: String,
        forceRefresh: Boolean = false
    ): DirResult115WithCache {
        if (!forceRefresh) {
            val cached = cache.getDirCache(cid)
            if (cached != null) {
                scope.launch {
                    try {
                        val fresh = fetch(cid, mode)
                        cache.saveDirCache(cid, fresh)
                    } catch (e: Exception) {}
                }
                return DirResult115WithCache(cached, fromCache = true)
            }
        }

        return try {
            val result = fetch(cid, mode)
            cache.saveDirCache(cid, result)
            DirResult115WithCache(result, fromCache = false)
        } catch (e: Auth115Exception) {
            throw e
        } catch (e: Exception) {
            val cached = cache.getDirCache(cid)
            if (cached != null) {
                DirResult115WithCache(cached, fromCache = true)
            } else {
                throw e
            }
        }
    }

    private suspend fun fetch(cid: String, mode: String): List<FileEntry115> {
        return if (mode == Constants115.MODE_OFFICIAL) {
            apiClient?.list(cid) ?: throw Auth115Exception("未初始化官方客户端")
        } else {
            webClient?.list(cid) ?: throw Auth115Exception("未初始化网页客户端")
        }
    }
}