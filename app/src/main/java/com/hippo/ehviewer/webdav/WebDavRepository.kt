package com.hippo.ehviewer.webdav

import com.ehviewer.core.database.model.WebDavSourceEntity
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.NetworkFolderIndexCache
import com.hippo.ehviewer.library.localLibraryDb
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow

object WebDavRepository {
    private val dao get() = localLibraryDb.webDavSourceDao()

    fun sourcesFlow(): Flow<List<WebDavSourceEntity>> = dao.listFlow()

    suspend fun load(id: Long): WebDavSourceEntity? = dao.load(id)

    suspend fun add(
        displayName: String,
        baseUrl: String,
        pathPrefix: String,
        username: String,
        password: String,
    ): Long = withIOContext {
        val normalized = WebDavClient.normalizeBaseUrl(baseUrl)
        val id = dao.insert(
            WebDavSourceEntity(
                displayName = displayName.ifBlank {
                    runCatching { java.net.URI(normalized).host }.getOrNull().orEmpty().ifBlank { normalized }
                },
                baseUrl = normalized,
                pathPrefix = pathPrefix.trim().trim('/'),
                username = username,
                addedAt = Clock.System.now().toEpochMilliseconds(),
            ),
        )
        WebDavPasswordStore.set(id, password)
        id
    }

    suspend fun update(source: WebDavSourceEntity, password: String?) = withIOContext {
        val normalized = source.copy(
            baseUrl = WebDavClient.normalizeBaseUrl(source.baseUrl),
            pathPrefix = source.pathPrefix.trim().trim('/'),
        )
        dao.update(normalized)
        if (password != null) {
            WebDavPasswordStore.set(source.id, password)
        }
        BrowseSession.invalidateWebDavListing(source.id)
        BrowseSession.clearWebDavSegments(source.id)
        // 「不主动加载封面」开关可能变化：清会话级缓存让浏览/书架判断重读库（幂等，代价≈0）
        runCatching { com.mangareader.browse.RemoteBrowseCoverLoader.invalidateDavNoCoverCache() }
    }

    suspend fun delete(source: WebDavSourceEntity) = withIOContext {
        // ★ 源删除后失效「不主动加载封面」缓存（rowid 复用会指向旧布尔值）
        runCatching { com.mangareader.browse.RemoteBrowseCoverLoader.invalidateDavNoCoverCache() }
        BrowseSession.invalidateWebDavListing(source.id)
        BrowseSession.clearWebDavSegments(source.id)
        // ★ 页缓存/封面缩略图按源整目录清理（`<root>/<sourceId>/`）
        WebDavCache.deleteSourceCache(source.id)
        WebDavPasswordStore.remove(source.id)
        NetworkFolderIndexCache.deleteWebDav(source.id)
        dao.delete(source)
    }

    suspend fun markOk(id: Long) = withIOContext {
        val src = dao.load(id) ?: return@withIOContext
        dao.update(
            src.copy(
                lastOkAt = Clock.System.now().toEpochMilliseconds(),
                lastError = null,
            ),
        )
    }

    suspend fun markError(id: Long, message: String) = withIOContext {
        val src = dao.load(id) ?: return@withIOContext
        dao.update(src.copy(lastError = message.take(500)))
    }
}
