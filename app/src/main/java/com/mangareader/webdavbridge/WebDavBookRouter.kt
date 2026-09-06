package com.mangareader.webdavbridge

import com.hippo.ehviewer.gallery.useArchivePageLoader
import com.hippo.ehviewer.gallery.useDocumentExtractPageLoader
import com.hippo.ehviewer.gallery.PageLoader
import com.hippo.ehviewer.gallery.PasswdProvider
import com.hippo.ehviewer.gallery.useSolidExtractPageLoader
import com.hippo.ehviewer.gallery.useStreamArchivePageLoader
import com.hippo.ehviewer.gallery.useTarChunkPageLoader
import com.hippo.ehviewer.gallery.useWebDavFolderPageLoader
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.library.IMAGE_EXTENSIONS
import com.hippo.ehviewer.library.RemoteArchiveOpen
import com.hippo.ehviewer.library.RemoteRangeNotSupportedException
import com.hippo.ehviewer.library.isDocumentFileName
import com.hippo.ehviewer.library.isEpubFileName
import com.hippo.ehviewer.library.isSolidArchiveFileName
import com.hippo.ehviewer.library.isTarArchiveFileName
import com.hippo.ehviewer.webdav.WebDavArchiveByteSource
import com.hippo.ehviewer.webdav.WebDavGateway
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath

/**
 * WebDAV 阅读入口路由（design.md 2.2.2 WebDavBookRouter / spec 5.9）。
 * 与 SmbBookRouter 同构，差异：**无 Blocked**（无 API 守卫，API 26+ 全可用）；
 * 新增 **RANGE 不支持降级**（RemoteRangeNotSupportedException → 整档下载 → 本地打开）。
 */
object WebDavBookRouter {

    sealed interface OpenResult {
        class Supported(
            val pageLoader: PageLoader,
            val source: ArchiveByteSource?,
            val supply: WebDavPageAdapter,
        ) : OpenResult

        /** 需全量下载格式（mobi/azw3）：UI 显示占位 + "此格式需要完整下载" + 下载按钮 */
        object Unsupported : OpenResult

        class Failed(val message: String) : OpenResult
    }

    suspend fun openWebDavBook(filePath: String, startPage: Int = 0): OpenResult = withContext(Dispatchers.IO) {
        val ref = WebDavPaths.parseBookPath(filePath) ?: return@withContext OpenResult.Failed("无效的 WebDAV 路径")
        if (WebDavUnsupportedFormatHandler.isUnsupported(ref.ext)) return@withContext OpenResult.Unsupported
        val source = WebDavRepository.load(ref.serverId)
            ?: return@withContext OpenResult.Failed("WebDAV 服务器配置不存在（可能已被删除）")
        val password = try { WebDavPasswordStore.get(source.id) } catch (_: Throwable) { "" }

        val adapter = WebDavPageAdapter()
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        sessionScope.launch {
            try {
                try {
                    dispatchLoaderSession(source, password, ref, startPage) { loader, byteSource ->
                        adapter.attach(loader, byteSource)
                        adapter.awaitClose()
                    }
                } catch (e: RemoteRangeNotSupportedException) {
                    if (isDocumentFileName(ref.remotePath)) throw e // PDF/EPUB 不做整档降级（v1）
                    // ★ RANGE 不支持降级（spec 5.9.1 规则 8）：整档下载 → 本地 useArchivePageLoader 继续
                    android.util.Log.i(TAG, "RANGE unsupported → whole-download fallback: ${ref.remotePath}")
                    adapter.resetForRetry()
                    val localPath = WebDavRangeFallbackHandler.handleRangeNotSupported(source.id, ref.remotePath)
                        ?: throw IllegalStateException("服务器不支持断点续传，且整档下载未完成")
                    useArchivePageLoader(
                        localPath.toPath(),
                        info = null,
                        startPage = startPage,
                        passwdProvider = { throw IllegalStateException("该归档需要密码（当前未配置归档密码）") },
                    ) { loader ->
                        adapter.attach(loader, null)
                        adapter.awaitClose()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "session failed", t)
                adapter.failAsync(t)
            } finally {
                sessionScope.cancel()
            }
        }
        try {
            val loader = withTimeout(OPEN_TIMEOUT_MS) { adapter.awaitReady() }
            OpenResult.Supported(loader, adapter.source, adapter)
        } catch (e: TimeoutCancellationException) {
            adapter.close()
            OpenResult.Failed("无法连接服务器：打开超时")
        } catch (t: Throwable) {
            adapter.close()
            OpenResult.Failed(humanize(t))
        }
    }

    /** 临时会话（封面提取）：与阅读会话同构分发 */
    suspend fun <T> openTransient(ref: WebDavBookRef, block: suspend (PageLoader, WebDavPageAdapter) -> T): T =
        withContext(Dispatchers.IO) {
            val source = WebDavRepository.load(ref.serverId) ?: error("WebDAV 服务器配置不存在")
            val password = try { WebDavPasswordStore.get(source.id) } catch (_: Throwable) { "" }
            val adapter = WebDavPageAdapter()
            val result = CompletableDeferred<Result<T>>()
            val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val job = sessionScope.launch {
                try {
                    dispatchLoaderSession(source, password, ref, 0) { loader, byteSource ->
                        adapter.attach(loader, byteSource)
                        result.complete(runCatching { block(loader, adapter) })
                        adapter.awaitClose()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "transient session failed", t)
                    adapter.failAsync(t)
                    result.complete(Result.failure(t))
                } finally {
                    sessionScope.cancel()
                }
            }
            try {
                withTimeout(OPEN_TIMEOUT_MS) { adapter.awaitReady() }
                result.await().getOrThrow()
            } finally {
                adapter.close()
                job.join()
            }
        }

    private suspend fun <T> dispatchLoaderSession(
        source: com.ehviewer.core.database.model.WebDavSourceEntity,
        password: String,
        ref: WebDavBookRef,
        startPage: Int,
        block: suspend (loader: PageLoader, byteSource: ArchiveByteSource?) -> T,
    ) {
        val remote = ref.remotePath
        val cacheKey = "webdav:${source.id}:$remote"
        val titleHint = remote.substringAfterLast('/').substringBefore(':').ifEmpty { source.displayName }

        // 含图片目录（文件夹书，spec 5.9 → WebDavFolderPageLoader）
        if (ref.ext.isEmpty() && !remote.contains(':')) {
            val entries = WebDavGateway.listDirectory(source, password, remote)
            val imageNames = entries
                .filter { it !is BrowseEntryRemote.Directory && it !is BrowseEntryRemote.FolderGallery }
                .filter { it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
                .map { it.name }
            useWebDavFolderPageLoader(
                source = source,
                remoteDir = remote,
                imageFileNames = imageNames,
                info = null,
                startPage = startPage,
            ) { loader -> block(loader, null) }
            return
        }

        val byteSource = WebDavArchiveByteSource(
            source,
            password,
            remote,
            preferSequential = !isDocumentFileName(remote),
            pipeline = !isDocumentFileName(remote),
        )
        val passwdProvider: PasswdProvider = {
            throw IllegalStateException("该归档需要密码（当前未配置归档密码）")
        }
        if (isDocumentFileName(remote)) {
            val documentFormat = if (isEpubFileName(remote)) "epub" else "pdf"
            useDocumentExtractPageLoader(
                source = byteSource,
                cacheKey = cacheKey,
                titleHint = titleHint,
                formatHint = documentFormat,
                info = null,
                startPage = startPage,
                remoteSize = runCatching { byteSource.size }.getOrDefault(0L),
                progressivePdf = documentFormat == "pdf",
            ) { loader -> block(loader, byteSource) }
        } else if (isSolidArchiveFileName(remote)) {
            try {
                useSolidExtractPageLoader(
                    source = byteSource,
                    cacheKey = cacheKey,
                    titleHint = titleHint,
                    info = null,
                    startPage = startPage,
                    remoteSize = runCatching { byteSource.size }.getOrDefault(0L),
                    passwdProvider = passwdProvider,
                ) { loader -> block(loader, byteSource) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "solid open failed, fallback to whole download", t)
                runCatching { byteSource.close() }
                val local = RemoteArchiveOpen.ensureWebDavArchive(
                    source = source,
                    password = password,
                    remoteRelativeFile = remote,
                    allowLarge = true,
                )
                useArchivePageLoader(
                    local.path,
                    info = null,
                    startPage = startPage,
                    passwdProvider = passwdProvider,
                ) { loader -> block(loader, byteSource) }
            }
        } else if (isTarArchiveFileName(remote)) {
            useTarChunkPageLoader(
                source = byteSource,
                cacheKey = cacheKey,
                titleHint = titleHint,
                info = null,
                startPage = startPage,
            ) { loader -> block(loader, byteSource) }
        } else {
            useStreamArchivePageLoader(
                source = byteSource,
                cacheKey = cacheKey,
                titleHint = titleHint,
                info = null,
                startPage = startPage,
                passwdProvider = passwdProvider,
            ) { loader -> block(loader, byteSource) }
        }
    }

    /** spec 5.9.1 异常映射（HTTP 语义 → 人话） */
    internal fun humanize(t: Throwable): String {
        if (t is TimeoutCancellationException) return "无法连接服务器：连接超时"
        val msg = t.message ?: ""
        return when {
            msg.contains("需要密码") -> "该归档需要密码"
            msg.contains("401") || msg.contains("Unauthorized", ignoreCase = true) || msg.contains("403") ->
                "认证失败：用户名或密码错误"
            msg.contains("404") || msg.contains("Not Found", ignoreCase = true) ->
                "文件不存在：可能已被移动或删除"
            msg.contains("405") || msg.contains("Method Not Allowed", ignoreCase = true) ->
                "服务器不支持 WebDAV 操作（PROPFIND 被拒绝）"
            msg.contains("断点续传") -> msg.take(160)
            else -> msg.take(160).ifEmpty { "无法连接服务器，请检查网络和配置" }
        }
    }

    private const val OPEN_TIMEOUT_MS = 45_000L
    private const val TAG = "WebDavBookRouter"
}
