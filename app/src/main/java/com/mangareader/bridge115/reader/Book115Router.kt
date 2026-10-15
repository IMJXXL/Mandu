package com.mangareader.bridge115.reader

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
import com.mangareader.bridge115.Bridges115
import com.mangareader.bridge115.Constants115
import com.mangareader.bridge115.api.Api115Client
import com.mangareader.bridge115.api.Auth115Exception
import com.mangareader.bridge115.api.DownloadInfo115
import com.mangareader.bridge115.api.HttpClient115
import com.mangareader.bridge115.api.RateLimit115Exception
import com.mangareader.bridge115.api.ApiChanged115Exception
import com.mangareader.bridge115.api.WafBlockedException
import com.mangareader.bridge115.api.Web115Client
import com.mangareader.bridge115.auth.Login115Flow
import com.mangareader.bridge115.auth.SessionStore115
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

object Book115Router {

    sealed interface OpenResult {
        class Supported(
            val pageLoader: PageLoader?,
            val source: ArchiveByteSource?,
            val supply: com.mangareader.native.RemotePageSupply,
        ) : OpenResult

        class Failed(val message: String) : OpenResult
    }

    suspend fun open115Book(
        filePath: String,
        startPage: Int = 0,
    ): OpenResult = withContext(Dispatchers.IO) {
        val ref = Paths115.parsePath(filePath) ?: return@withContext OpenResult.Failed("无效的115路径")
        val sessionStore = SessionStore115(Bridges115.context)
        val loginFlow = Login115Flow(sessionStore)

        val ua = if (ref.mode == Constants115.MODE_OFFICIAL) Constants115.UA_OFFICIAL else Constants115.UA_WEB

        if (ref.ext.isEmpty()) {
            return@withContext try {
                val folderReader = if (ref.mode == Constants115.MODE_OFFICIAL) {
                    val apiClient = Api115Client(sessionStore = sessionStore, loginFlow = loginFlow)
                    FolderReader115(ref.pickCode, ref.mode, apiClient = apiClient)
                } else {
                    val webClient = Web115Client(sessionStore = sessionStore)
                    FolderReader115(ref.pickCode, ref.mode, webClient = webClient)
                }
                folderReader.load()
                if (folderReader.pageCount == 0) {
                    OpenResult.Failed("此文件夹中没有图片")
                } else {
                    OpenResult.Supported(null, null, folderReader)
                }
            } catch (e: Auth115Exception) {
                OpenResult.Failed("登录已失效，请重新授权")
            } catch (t: Throwable) {
                OpenResult.Failed(humanize(t))
            }
        }

        val downloadInfo = try {
            fetchDownloadUrl(ref, sessionStore, loginFlow)
        } catch (e: Auth115Exception) {
            return@withContext OpenResult.Failed("登录已失效，请重新授权")
        } catch (t: Throwable) {
            return@withContext OpenResult.Failed(humanize(t))
        }

        val pickCode = ref.pickCode
        val mode = ref.mode
        val urlProvider: suspend () -> DownloadInfo115 = {
            fetchDownloadUrl(PathInfo115(mode, pickCode, ref.name, ref.ext), sessionStore, loginFlow)
        }

        val byteSource = Archive115ByteSource(
            initialUrl = downloadInfo.url,
            urlProvider = urlProvider,
            knownSize = downloadInfo.size,
            ua = ua,
        )

        val adapter = PageAdapter115()
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
                val localPath = downloadWholeFile(downloadInfo, ref, urlProvider, ua)
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

    private suspend fun fetchDownloadUrl(
        ref: PathInfo115,
        sessionStore: SessionStore115,
        loginFlow: Login115Flow,
    ): DownloadInfo115 {
        return if (ref.mode == Constants115.MODE_OFFICIAL) {
            val apiClient = Api115Client(sessionStore = sessionStore, loginFlow = loginFlow)
            apiClient.downurl(ref.pickCode)
        } else {
            val webClient = Web115Client(sessionStore = sessionStore)
            webClient.downurl(ref.pickCode)
        }
    }

    private suspend fun <T> dispatchLoaderSession(
        ref: PathInfo115,
        byteSource: Archive115ByteSource,
        startPage: Int,
        passwdProvider: PasswdProvider? = null,
        block: suspend (loader: PageLoader, byteSource: ArchiveByteSource?) -> T,
    ) {
        val cacheKey = "115:${ref.mode}:${ref.pickCode}:${ref.name}"
        val titleHint = ref.name
        val fileName = ref.name

        val effectivePasswdProvider: PasswdProvider = passwdProvider ?: {
            throw IllegalStateException("该归档需要密码（当前未配置归档密码）")
        }

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
                throw t
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
        initialInfo: DownloadInfo115,
        ref: PathInfo115,
        urlProvider: suspend () -> DownloadInfo115,
        ua: String,
    ): String? = withContext(Dispatchers.IO) {
        val cacheDir = java.io.File(Bridges115.context.cacheDir, "${Constants115.CACHE_DIR}/files")
        cacheDir.mkdirs()
        val ext = if (ref.ext.isNotEmpty()) ref.ext else "bin"
        val safeName = ref.pickCode.replace("/", "_").replace("\\", "_")
        val localFile = java.io.File(cacheDir, "$safeName.$ext")
        if (localFile.exists() && initialInfo.size > 0 && localFile.length() == initialInfo.size) return@withContext localFile.absolutePath
        if (localFile.exists() && initialInfo.size <= 0 && localFile.length() > 0) return@withContext localFile.absolutePath
        try {
            downloadTo(initialInfo.url, localFile, ua)
            localFile.absolutePath
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "whole download failed, trying refresh: ${t.message}")
            try {
                val fresh = urlProvider()
                downloadTo(fresh.url, localFile, ua)
                localFile.absolutePath
            } catch (t2: Throwable) {
                android.util.Log.w(TAG, "whole download failed after refresh: ${t2.message}")
                null
            }
        }
    }

    private suspend fun downloadTo(url: String, target: java.io.File, ua: String) {
        val client = HttpClient115.client()

        val response = client.get(url) {
            header("User-Agent", ua)
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
        if (t is Auth115Exception) return "登录已失效，请重新授权"
        if (t is RateLimit115Exception) return "访问被限制，请稍后再试"
        if (t is ApiChanged115Exception) return "服务接口可能已变更"
        if (t is WafBlockedException) return "触发风控，请稍后再试"
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
    private const val TAG = "Book115Router"
    private val SETTLED_COUNT_EXTS = setOf("zip", "cbz", "epub", "pdf", "mobi", "azw", "azw3")
}