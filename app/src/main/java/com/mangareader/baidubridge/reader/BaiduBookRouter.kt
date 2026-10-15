package com.mangareader.baidubridge.reader

import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.gallery.PageLoader
import com.hippo.ehviewer.gallery.PasswdProvider
import com.hippo.ehviewer.gallery.useArchivePageLoader
import com.hippo.ehviewer.gallery.useDocumentExtractPageLoader
import com.hippo.ehviewer.gallery.usePdfiumPageLoader
import com.hippo.ehviewer.gallery.useSolidExtractPageLoader
import com.hippo.ehviewer.gallery.useStreamArchivePageLoader
import com.hippo.ehviewer.gallery.useTarChunkPageLoader
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.RemoteRangeNotSupportedException
import com.hippo.ehviewer.library.isDocumentFileName
import com.hippo.ehviewer.library.isEpubFileName
import com.hippo.ehviewer.library.isMobiFileName
import com.hippo.ehviewer.library.isPdfFileName
import com.hippo.ehviewer.library.isSolidArchiveFileName
import com.hippo.ehviewer.library.isTarArchiveFileName
import com.mangareader.baidubridge.BaiduBridges
import com.mangareader.baidubridge.BaiduConstants
import com.mangareader.baidubridge.api.BaiduApiClient
import com.mangareader.baidubridge.api.BaiduAuthException
import com.mangareader.baidubridge.api.BaiduDownloadInfo
import com.mangareader.baidubridge.api.BaiduHttpClient
import com.mangareader.baidubridge.auth.BaiduLoginFlow
import com.mangareader.baidubridge.auth.BaiduSessionStore
import com.mangareader.cloudcommon.transport.SolidFallbackException
import com.hippo.ehviewer.library.ZipAsDirListing
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.utils.io.readAvailable
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath

object BaiduBookRouter {

    sealed interface OpenResult {
        class Supported(
            val pageLoader: PageLoader?,
            val source: ArchiveByteSource?,
            val supply: com.mangareader.native.RemotePageSupply,
        ) : OpenResult

        class Failed(val message: String) : OpenResult
    }

    suspend fun openBaiduBook(
        filePath: String,
        startPage: Int = 0,
    ): OpenResult {
        var lastResult: OpenResult = OpenResult.Failed("打开失败，请重试")
        for (attempt in 0..2) {
            lastResult = openBaiduBookInternal(filePath, startPage)
            if (lastResult is OpenResult.Supported) return lastResult
            val msg = (lastResult as? OpenResult.Failed)?.message ?: ""
            if (msg.contains("登录已失效") || msg.contains("登录已过期") || msg.contains("无效")) return lastResult
        }
        return OpenResult.Failed("打开失败，请重试")
    }

    private suspend fun openBaiduBookInternal(
        filePath: String,
        startPage: Int = 0,
    ): OpenResult = withContext(Dispatchers.IO) {
        val ref0 = BaiduPaths.parsePath(filePath) ?: return@withContext OpenResult.Failed("无效的百度路径")
        val zipSplit = ZipAsDirListing.splitZipBrowsePath(ref0.name)
        val ref = if (zipSplit != null && zipSplit.second.isNotEmpty()) {
            val effectiveName = zipSplit.first
            ref0.copy(name = effectiveName, ext = effectiveName.substringAfterLast('.', "").lowercase())
        } else {
            ref0
        }
        val sessionStore = BaiduSessionStore(BaiduBridges.context)
        val loginFlow = BaiduLoginFlow(sessionStore)
        val apiClient = BaiduApiClient(sessionStore = sessionStore, loginFlow = loginFlow)

        if (ref.ext.isEmpty()) {
            return@withContext try {
                val folderReader = BaiduFolderReader(ref.path, apiClient)
                folderReader.load()
                if (folderReader.pageCount == 0) {
                    OpenResult.Failed("此文件夹中没有图片")
                } else {
                    OpenResult.Supported(null, null, folderReader)
                }
            } catch (e: BaiduAuthException) {
                OpenResult.Failed("登录已失效，请重新授权")
            } catch (t: Throwable) {
                OpenResult.Failed(humanize(t))
            }
        }

        val downloadInfo = try {
            cachedDlink(apiClient, ref.path)
        } catch (e: BaiduAuthException) {
            return@withContext OpenResult.Failed("登录已失效，请重新授权")
        } catch (t: Throwable) {
            return@withContext OpenResult.Failed(humanize(t))
        }

        val baiduPath = ref.path
        val urlProvider: suspend () -> BaiduDownloadInfo = { cachedDlink(apiClient, baiduPath) }

        val byteSource = BaiduArchiveByteSource(
            initialUrl = downloadInfo.url,
            urlProvider = urlProvider,
            knownSize = downloadInfo.size,
        )

        val adapter = BaiduPageAdapter()
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        sessionScope.launch {
            try {
                dispatchLoaderSession(ref, byteSource, startPage) { loader, source ->
                    adapter.attach(loader, source, sizeSettled = ref.ext.lowercase() in SETTLED_COUNT_EXTS)
                    adapter.awaitClose()
                }
            } catch (e: RemoteRangeNotSupportedException) {
                if (isDocumentFileName(ref.name)) throw e
                android.util.Log.i(TAG, "RANGE unsupported → whole-download fallback: ${ref.name}")
                adapter.resetForRetry()
                val localPath = downloadWholeFile(downloadInfo, ref, urlProvider)
                    ?: throw IllegalStateException("不支持断点续传，且整档下载未完成")
                useArchivePageLoader(
                    localPath.toPath(),
                    info = null,
                    startPage = startPage,
                    passwdProvider = { throw IllegalStateException("该归档需要密码（当前未配置归档密码）") },
                ) { loader ->
                    adapter.attach(loader, null, sizeSettled = true)
                    adapter.awaitClose()
                }
            } catch (e: SolidFallbackException) {
                if (isDocumentFileName(ref.name)) throw e
                android.util.Log.i(TAG, "solid failed → whole-download fallback: ${ref.name}")
                adapter.resetForRetry()
                val localPath = downloadWholeFile(downloadInfo, ref, urlProvider)
                    ?: throw IllegalStateException("不支持断点续传，且整档下载未完成")
                useArchivePageLoader(
                    localPath.toPath(),
                    info = null,
                    startPage = startPage,
                    passwdProvider = { throw IllegalStateException("该归档需要密码（当前未配置归档密码）") },
                ) { loader ->
                    adapter.attach(loader, null, sizeSettled = true)
                    adapter.awaitClose()
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
            OpenResult.Failed("连接超时，请稍后重试")
        } catch (t: Throwable) {
            adapter.close()
            OpenResult.Failed(humanize(t))
        }
    }

    private suspend fun <T> dispatchLoaderSession(
        ref: BaiduPathInfo,
        byteSource: BaiduArchiveByteSource,
        startPage: Int,
        passwdProvider: PasswdProvider? = null,
        block: suspend (loader: PageLoader, byteSource: ArchiveByteSource?) -> T,
    ) {
        val cacheKey = "baidu:${ref.path}:${ref.name}"
        val titleHint = ref.name
        val fileName = ref.name

        val effectivePasswdProvider: PasswdProvider = passwdProvider ?: {
            throw IllegalStateException("该归档需要密码（当前未配置归档密码）")
        }

        val routeTag = when {
            isDocumentFileName(fileName) -> "document"
            isSolidArchiveFileName(fileName) -> "solid"
            isTarArchiveFileName(fileName) -> "tar"
            else -> "archive"
        }
        android.util.Log.d(TAG, "dispatch route=$routeTag file=$fileName")

        if (isDocumentFileName(fileName)) {
            if (isPdfFileName(fileName) && !Settings.webDavPdfFastMode.value) {
                usePdfiumPageLoader(
                    source = byteSource,
                    cacheKey = cacheKey,
                    titleHint = titleHint,
                    info = null,
                    startPage = startPage,
                    remoteSize = com.hippo.ehviewer.gallery.boundedRemoteSizeProbe(byteSource),
                ) { loader -> block(loader, byteSource) }
            } else {
                val documentFormat = when {
                    isPdfFileName(fileName) -> "pdf"
                    isEpubFileName(fileName) -> "epub"
                    isMobiFileName(fileName) -> "mobi"
                    else -> "pdf"
                }
                useDocumentExtractPageLoader(
                    source = byteSource,
                    cacheKey = cacheKey,
                    titleHint = titleHint,
                    formatHint = documentFormat,
                    info = null,
                    startPage = startPage,
                    remoteSize = com.hippo.ehviewer.gallery.boundedRemoteSizeProbe(byteSource),
                    progressivePdf = documentFormat == "pdf",
                ) { loader -> block(loader, byteSource) }
            }
        } else if (isSolidArchiveFileName(fileName)) {
            try {
                useSolidExtractPageLoader(
                    source = byteSource,
                    cacheKey = cacheKey,
                    titleHint = titleHint,
                    info = null,
                    startPage = startPage,
                    remoteSize = com.hippo.ehviewer.gallery.boundedRemoteSizeProbe(byteSource),
                    passwdProvider = effectivePasswdProvider,
                ) { loader -> block(loader, byteSource) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "solid open failed: ${t.message}")
                throw SolidFallbackException(t)
            }
        } else if (isTarArchiveFileName(fileName)) {
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
                passwdProvider = effectivePasswdProvider,
            ) { loader -> block(loader, byteSource) }
        }
    }

    private suspend fun downloadWholeFile(
        initialInfo: BaiduDownloadInfo,
        ref: BaiduPathInfo,
        urlProvider: suspend () -> BaiduDownloadInfo,
    ): String? = withContext(Dispatchers.IO) {
        val cacheDir = java.io.File(BaiduBridges.context.cacheDir, "${BaiduConstants.CACHE_DIR}/files")
        cacheDir.mkdirs()
        val ext = if (ref.ext.isNotEmpty()) ref.ext else "bin"
        val safeName = ref.path.replace("/", "_").replace("\\", "_")
        val localFile = java.io.File(cacheDir, "$safeName.$ext")
        if (localFile.exists() && localFile.length() > 0) return@withContext localFile.absolutePath
        try {
            downloadTo(initialInfo.url, localFile)
            localFile.absolutePath
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "whole download failed, trying refresh: ${t.message}")
            try {
                val fresh = urlProvider()
                downloadTo(fresh.url, localFile)
                localFile.absolutePath
            } catch (t2: Throwable) {
                android.util.Log.w(TAG, "whole download failed after refresh: ${t2.message}")
                null
            }
        }
    }

    private suspend fun downloadTo(url: String, target: java.io.File) {
        val client = BaiduHttpClient.client()

        val response = client.get(url) {
            header("User-Agent", BaiduConstants.BAIDU_UA)
        }
        val channel = response.bodyAsChannel()
        java.io.FileOutputStream(target).use { out ->
            val buffer = ByteArray(64 * 1024)
            while (!channel.isClosedForRead) {
                val read = channel.readAvailable(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
            }
            out.flush()
        }
    }

    internal fun humanize(t: Throwable): String {
        if (t is TimeoutCancellationException) return "连接超时，请稍后重试"
        if (t is BaiduAuthException) return "登录已失效，请重新授权"
        if (t is com.mangareader.baidubridge.api.BaiduRateLimitException) return "访问被限制，请稍后再试"
        if (t is com.mangareader.baidubridge.api.BaiduApiChangedException) return "服务接口可能已变更"
        if (t is RemoteRangeNotSupportedException) return "此格式需要断点续传支持，暂无法流式打开"
        val msg = t.message ?: ""
        return when {
            msg.contains("需要密码") -> "该归档需要密码"
            msg.contains("登录已过期") -> msg
            msg.contains("超时") -> "连接超时，请稍后重试"
            else -> msg.take(160).ifEmpty { "打开失败，请稍后重试" }
        }
    }

    private const val OPEN_TIMEOUT_MS = 15_000L
    private const val TAG = "BaiduBookRouter"
    private val SETTLED_COUNT_EXTS = setOf("zip", "cbz", "epub", "pdf", "mobi", "azw", "azw3")
    private const val DLINK_CACHE_TTL_MS = 300_000L

    private data class DlinkCacheEntry(val info: BaiduDownloadInfo, val expiresAt: Long)

    private val dlinkCache = object : LinkedHashMap<String, DlinkCacheEntry>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DlinkCacheEntry>?): Boolean = size > 128
    }

    private suspend fun cachedDlink(apiClient: BaiduApiClient, path: String): BaiduDownloadInfo {
        val now = System.currentTimeMillis()
        synchronized(dlinkCache) {
            dlinkCache[path]?.let { entry ->
                if (now < entry.expiresAt) return entry.info
            }
        }
        val info = apiClient.dlink(path)
        synchronized(dlinkCache) {
            dlinkCache[path] = DlinkCacheEntry(info, now + DLINK_CACHE_TTL_MS)
        }
        return info
    }
}