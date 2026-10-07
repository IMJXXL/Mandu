package com.mangareader.webdavbridge

import com.hippo.ehviewer.gallery.useArchivePageLoader
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.gallery.useDocumentExtractPageLoader
import com.hippo.ehviewer.gallery.usePdfiumPageLoader
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
import com.hippo.ehviewer.library.isMobiFileName
import com.hippo.ehviewer.library.isPdfFileName
import com.hippo.ehviewer.library.isZipArchiveFileName
import com.hippo.ehviewer.library.isImageFileName
import com.hippo.ehviewer.library.isSolidArchiveFileName
import com.hippo.ehviewer.library.isTarArchiveFileName
import com.hippo.ehviewer.library.naturalCompare
import com.hippo.ehviewer.library.ZipAsDirListing
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

    private suspend fun openWebDavBookOnce(
        filePath: String,
        startPage: Int = 0,
        passwdProvider: PasswdProvider? = null,
    ): OpenResult = withContext(Dispatchers.IO) {
        val ref = WebDavPaths.parseBookPath(filePath) ?: return@withContext OpenResult.Failed("无效的 WebDAV 路径")
        if (WebDavUnsupportedFormatHandler.isUnsupported(ref.ext)) return@withContext OpenResult.Unsupported
        val source = WebDavRepository.load(ref.serverId)
            ?: return@withContext OpenResult.Failed("WebDAV 服务器配置不存在（可能已被删除）")
        val password = try { WebDavPasswordStore.get(source.id) } catch (_: Throwable) { "" }

        val effectivePasswdProvider: PasswdProvider = passwdProvider ?: {
            throw IllegalStateException("该归档需要密码（当前未配置归档密码）")
        }
        val adapter = WebDavPageAdapter()
        // ★ 远端开书计时（诊断日志取证：epub/zip 打开时快时慢）
        val openStartMs = android.os.SystemClock.uptimeMillis()
        runCatching { com.mangareader.DiagTracer.markStatic("remote: open ${ref.ext} begin") }
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        sessionScope.launch {
            try {
                try {
                    dispatchLoaderSession(source, password, ref, startPage, effectivePasswdProvider) { loader, byteSource ->
                        // ★ P2：zip 族/epub/pdf 开包即定页数，跳过 500ms 稳定采样；tar/rar/7z 等渐进索引格式保留
                        adapter.attach(loader, byteSource, sizeSettled = ref.ext.lowercase() in SETTLED_COUNT_EXTS)
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
                        passwdProvider = effectivePasswdProvider,
                    ) { loader ->
                        // 本地整档：页数打开即定，直接放行
                        adapter.attach(loader, null, sizeSettled = true)
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
            runCatching { com.mangareader.DiagTracer.markStatic("remote: open ok ${(android.os.SystemClock.uptimeMillis() - openStartMs)}ms") }
            OpenResult.Supported(loader, adapter.source, adapter)
        } catch (e: TimeoutCancellationException) {
            adapter.close()
            runCatching { com.mangareader.DiagTracer.markStatic("remote: open timeout ${(android.os.SystemClock.uptimeMillis() - openStartMs)}ms") }
            OpenResult.Failed("无法连接服务器：打开超时")
        } catch (t: Throwable) {
            adapter.close()
            runCatching { com.mangareader.DiagTracer.markStatic("remote: open fail ${(android.os.SystemClock.uptimeMillis() - openStartMs)}ms e=${t::class.java.simpleName}${t.message?.take(80)?.let { " msg=$it" } ?: ""}") }
            OpenResult.Failed(humanize(t))
        }
    }

    /**
     * ★ 开书失败自动重试一次（2026-09-30）：日志实锤"空闲35-46s后首开必失败1.9-3.2s、
     *   紧接重开即成"——服务端掐掉空闲 keep-alive 连接，首请求复用死 socket。
     *   失败后换新连接自动重试，用户无感；Unsupported 不重试。
     */
    suspend fun openWebDavBook(
        filePath: String,
        startPage: Int = 0,
        passwdProvider: PasswdProvider? = null,
    ): OpenResult {
        val first = openWebDavBookOnce(filePath, startPage, passwdProvider)
        if (first !is OpenResult.Failed) return first
        runCatching { com.mangareader.DiagTracer.markStatic("remote: open retry begin") }
        kotlinx.coroutines.delay(300L)
        val second = openWebDavBookOnce(filePath, startPage, passwdProvider)
        runCatching {
            when (second) {
                is OpenResult.Failed -> com.mangareader.DiagTracer.markStatic("remote: open retry fail")
                else -> com.mangareader.DiagTracer.markStatic("remote: open retry ok")
            }
        }
        return second
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
                        adapter.attach(loader, byteSource, sizeSettled = ref.ext.lowercase() in SETTLED_COUNT_EXTS)
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
        passwdProvider: PasswdProvider? = null,
        block: suspend (loader: PageLoader, byteSource: ArchiveByteSource?) -> T,
    ) {
        val remoteRaw = ref.remotePath
        // zip-as-dir 路径（`dir/file.zip/inner/page.webp`）→ 用 zip 文件路径打开归档
        val zipSplit = ZipAsDirListing.splitZipBrowsePath(remoteRaw)
        val remote = if (zipSplit != null && zipSplit.second.isNotEmpty()) zipSplit.first else remoteRaw
        val cacheKey = "webdav:${source.id}:$remote"
        val titleHint = remote.substringAfterLast('/').substringBefore(':').ifEmpty { source.displayName }

        // 含图片目录（文件夹书，spec 5.9 → WebDavFolderPageLoader）
        val remoteIsZip = isZipArchiveFileName(remote)
        if (ref.ext.isEmpty() && !remote.contains(':') && !remoteIsZip) {
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

        android.util.Log.i("WebDavRoute", "stream archive path: remote=$remote ext=${ref.ext}")
        val byteSource = WebDavArchiveByteSource(
            source,
            password,
            remote,
            preferSequential = !isDocumentFileName(remote),
            pipeline = !isDocumentFileName(remote),
        )
        val effectivePasswdProvider: PasswdProvider = passwdProvider ?: {
            throw IllegalStateException("该归档需要密码（当前未配置归档密码）")
        }
        if (isDocumentFileName(remote)) {
            if (isPdfFileName(remote) && !Settings.webDavPdfFastMode.value) {
                usePdfiumPageLoader(
                    source = byteSource,
                    cacheKey = cacheKey,
                    titleHint = titleHint,
                    info = null,
                    startPage = startPage,
                    remoteSize = runCatching { byteSource.size }.getOrDefault(0L),
                ) { loader -> block(loader, byteSource) }
            } else {
                // ★ 2026-10-05 M2：mobi 系走记录级流式文档管线（不再整档下载回退）
                val documentFormat = when {
                    isPdfFileName(remote) -> "pdf"
                    isEpubFileName(remote) -> "epub"
                    isMobiFileName(remote) -> "mobi"
                    else -> "pdf"
                }
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
            }
        } else if (isSolidArchiveFileName(remote)) {
            try {
                useSolidExtractPageLoader(
                    source = byteSource,
                    cacheKey = cacheKey,
                    titleHint = titleHint,
                    info = null,
                    startPage = startPage,
                    remoteSize = runCatching { byteSource.size }.getOrDefault(0L),
                    passwdProvider = effectivePasswdProvider,
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
                    passwdProvider = effectivePasswdProvider,
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
        } else if (isImageFileName(remote)) {
            runCatching { byteSource.close() }
            val parentDir = remote.substringBeforeLast('/', "")
            val fileName = remote.substringAfterLast('/')
            val allImageNames = runCatching {
                val dirEntries = WebDavGateway.listDirectory(source, password, parentDir)
                dirEntries.filterIsInstance<BrowseEntryRemote.RegularFile>()
                    .map { it.name }
                    .filter { isImageFileName(it) }
                    .sortedWith { a, b -> naturalCompare(a, b) }
            }.getOrDefault(listOf(fileName))
            val imageIndex = allImageNames.indexOf(fileName).coerceAtLeast(0)
            useWebDavFolderPageLoader(
                source = source,
                remoteDir = parentDir,
                imageFileNames = allImageNames,
                info = null,
                startPage = imageIndex,
            ) { loader -> block(loader, null) }
        } else {
            useStreamArchivePageLoader(
                source = byteSource,
                cacheKey = cacheKey,
                titleHint = titleHint,
                info = null,
                startPage = startPage,
                passwdProvider = effectivePasswdProvider,
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

    private const val OPEN_TIMEOUT_MS = 15_000L
    private const val TAG = "WebDavBookRouter"
    /** ★ P2（2026-10-04）：开包即定页数的扩展名——attach 跳过 500ms 页数稳定采样 */
    private val SETTLED_COUNT_EXTS = setOf("zip", "cbz", "epub", "pdf", "mobi", "azw", "azw3")
}
