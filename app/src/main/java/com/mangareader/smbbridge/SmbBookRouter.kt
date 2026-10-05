package com.mangareader.smbbridge

import com.hippo.ehviewer.gallery.useDocumentExtractPageLoader
import com.hippo.ehviewer.gallery.usePdfiumPageLoader
import com.hippo.ehviewer.gallery.useArchivePageLoader
import com.hippo.ehviewer.gallery.PageLoader
import com.hippo.ehviewer.gallery.PasswdProvider
import com.hippo.ehviewer.gallery.useSolidExtractPageLoader
import com.hippo.ehviewer.gallery.useSmbFolderPageLoader
import com.hippo.ehviewer.gallery.useStreamArchivePageLoader
import com.hippo.ehviewer.gallery.useTarChunkPageLoader
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.library.IMAGE_EXTENSIONS
import com.hippo.ehviewer.library.RemoteArchiveOpen
import com.hippo.ehviewer.library.isDocumentFileName
import com.hippo.ehviewer.library.isEpubFileName
import com.hippo.ehviewer.library.isImageFileName
import com.hippo.ehviewer.library.isMobiFileName
import com.hippo.ehviewer.library.isPdfFileName
import com.hippo.ehviewer.library.isSolidArchiveFileName
import com.hippo.ehviewer.library.isTarArchiveFileName
import com.hippo.ehviewer.library.isZipArchiveFileName
import com.hippo.ehviewer.library.naturalCompare
import com.hippo.ehviewer.library.ZipAsDirListing
import com.hippo.ehviewer.smb.SmbArchiveByteSource
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
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

/**
 * SMB 阅读入口路由（design.md 2.2.2 SmbBookRouter / spec 5.3）。
 *
 * Reader.kt 的 `smb:` 分流调用 [openSmbBook]；内部完成 API 守卫 → 格式识别 →
 * SmbArchiveByteSource → 按格式选择参考项目 PageLoader（对齐参考项目 ReaderScreen 分发）：
 * - zip/cbz → StreamArchivePageLoader（range stream）
 * - tar/cbt → TarChunkPageLoader（chunk readahead）
 * - pdf/epub → DocumentExtractPageLoader（document extract）
 * - 7z/rar/cbr → SolidExtractPageLoader（失败回退整档下载 + 本地 useArchivePageLoader）
 * - 含图片目录 → SmbFolderPageLoader
 * - mobi/azw/azw3 → DocumentExtractPageLoader（M2 记录级流式，不再 Unsupported）
 *
 * PageLoader 作用域契约：useXxxPageLoader 的 block 挂起在 [SmbPageAdapter.awaitClose]，
 * [SmbPageAdapter.close] 触发返回，autoCloseScope 随之关闭 source —— "调用方持有到 close"
 * 的语义与参考项目一致。
 */
object SmbBookRouter {

    sealed interface OpenResult {
        /** 可阅读：调用方持有 loader/source/supply，阅读结束调用 supply.close() */
        class Supported(
            val pageLoader: PageLoader,
            val source: ArchiveByteSource?,
            val supply: SmbPageAdapter,
        ) : OpenResult

        /** 不支持格式（mobi/azw3）：UI 显示占位 + 下载按钮 */
        object Unsupported : OpenResult

        /** 打开失败：UI 提示 message */
        class Failed(val message: String) : OpenResult
    }

    suspend fun openSmbBook(
        filePath: String,
        startPage: Int = 0,
        passwdProvider: PasswdProvider? = null,
    ): OpenResult = withContext(Dispatchers.IO) {
        val ref = SmbPaths.parseBookPath(filePath) ?: return@withContext OpenResult.Failed("无效的 SMB 路径")
        if (SmbUnsupportedFormatHandler.isUnsupported(ref.ext)) return@withContext OpenResult.Unsupported
        val source = SmbRepository.load(ref.serverId)
            ?: return@withContext OpenResult.Failed("SMB 服务器配置不存在（可能已被删除）")
        val password = try { SmbPasswordStore.get(source.id) } catch (_: Throwable) { "" }

        val adapter = SmbPageAdapter()
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        sessionScope.launch {
            try {
                dispatchLoaderSession(source, password, ref, startPage, passwdProvider) { loader, byteSource ->
                    // ★ P2：zip 族/epub/pdf 开包即定页数，跳过 500ms 稳定采样；tar/rar/7z 等渐进索引格式保留
                    adapter.attach(loader, byteSource, sizeSettled = ref.ext.lowercase() in SETTLED_COUNT_EXTS)
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
            OpenResult.Failed("无法连接服务器：打开超时")
        } catch (t: Throwable) {
            adapter.close()
            OpenResult.Failed(humanize(t))
        }
    }

    /**
     * 临时会话（封面提取等一次性用途）：打开 loader → 执行 block → 自动关闭。
     * 与阅读会话完全同构的分发，保证封面/阅读行为一致。
     */
    suspend fun <T> openTransient(ref: SmbBookRef, block: suspend (PageLoader, SmbPageAdapter) -> T): T =
        withContext(Dispatchers.IO) {
            val source = SmbRepository.load(ref.serverId) ?: error("SMB 服务器配置不存在")
            val password = try { SmbPasswordStore.get(source.id) } catch (_: Throwable) { "" }
            val adapter = SmbPageAdapter()
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

    /**
     * 按格式分发（对齐参考项目 ReaderScreen 的网络书加载选择）。
     * block 收到就绪的 PageLoader 与（归档/文档类）字节源；文件夹书 byteSource 为 null。
     * block 返回/抛出后 autoCloseScope 负责关闭字节源与会话内资源。
     */
    private suspend fun <T> dispatchLoaderSession(
        source: com.ehviewer.core.database.model.SmbSourceEntity,
        password: String,
        ref: SmbBookRef,
        startPage: Int,
        passwdProvider: PasswdProvider? = null,
        block: suspend (loader: PageLoader, byteSource: ArchiveByteSource?) -> T,
    ) {
        // gateway remote：fixed-share 源用去 share 段的 rel；server-root 源用完整路径（首段即 share）
        val remoteRaw = if (source.share.isBlank()) ref.fullRel else ref.rel
        // zip-as-dir 路径（`dir/file.zip/inner/page.webp`）→ 用 zip 文件路径打开归档，不是成员路径
        val zipSplit = ZipAsDirListing.splitZipBrowsePath(remoteRaw)
        val remote = if (zipSplit != null && zipSplit.second.isNotEmpty()) zipSplit.first else remoteRaw
        android.util.Log.d("SmbRoute", "dispatch ref=$ref ext=${ref.ext} remoteRaw=$remoteRaw zipSplit=$zipSplit -> remote=$remote")
        val cacheKey = "smb:${source.id}:$remote"
        val titleHint = remote.substringAfterLast('/').substringBefore(':').ifEmpty { source.displayName }

        // 含图片目录（文件夹书）：无扩展名 → 现场列目录取图片文件名
        android.util.Log.d("SmbRoute", "branch: extEmpty=${ref.ext.isEmpty()} remote=$remote")
        // ★ 修复循环进入：路径即使被存成文件夹书形态，只要指向的是 zip/cbz 档案文件，
        //   必须按 zip 档案流式打开（否则列目录会把同一个 zip 再列出来，无限循环）
        val remoteIsZip = isZipArchiveFileName(remote)
        if (ref.ext.isEmpty() && !remote.contains(':') && !remoteIsZip) {
            val entries = SmbGateway.listDirectory(source, password, remote)
            val imageNames = entries
                .filter { it !is BrowseEntryRemote.Directory && it !is BrowseEntryRemote.FolderGallery }
                .filter { it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
                .map { it.name }
            useSmbFolderPageLoader(
                source = source,
                remoteDir = remote,
                imageFileNames = imageNames,
                info = null,
                startPage = startPage,
            ) { loader -> block(loader, null) }
            return
        }

        val byteSource = SmbArchiveByteSource(
            source,
            password,
            remote,
            // 归档：顺序窗口饱和流量；PDF/EPUB 稀疏对象读，关顺序管线
            preferSequential = !isDocumentFileName(remote),
            pipeline = !isDocumentFileName(remote),
        )
        val effectivePasswdProvider: PasswdProvider = passwdProvider ?: {
            throw IllegalStateException("该归档需要密码（当前未配置归档密码）")
        }
        if (isDocumentFileName(remote)) {
            if (isPdfFileName(remote)) {
                usePdfiumPageLoader(
                    source = byteSource,
                    cacheKey = cacheKey,
                    titleHint = titleHint,
                    info = null,
                    startPage = startPage,
                    remoteSize = runCatching { byteSource.size }.getOrDefault(0L),
                ) { loader -> block(loader, byteSource) }
            } else {
                // ★ 2026-10-05 M2：mobi 系走记录级流式文档管线（不再落 ZIP 流解析必失败）
                val documentFormat = when {
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
                // 打开失败（如特殊 7z）：回退整档下载 + 本地 useArchivePageLoader（spec 5.3.1 规则 4）
                android.util.Log.w(TAG, "solid open failed, fallback to whole download", t)
                runCatching { byteSource.close() }
                val local = RemoteArchiveOpen.ensureSmbArchive(
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
                val dirEntries = SmbGateway.listDirectory(source, password, parentDir)
                dirEntries.filterIsInstance<BrowseEntryRemote.RegularFile>()
                    .map { it.name }
                    .filter { isImageFileName(it) }
                    .sortedWith { a, b -> naturalCompare(a, b) }
            }.getOrDefault(listOf(fileName))
            val imageIndex = allImageNames.indexOf(fileName).coerceAtLeast(0)
            useSmbFolderPageLoader(
                source = source,
                remoteDir = parentDir,
                imageFileNames = allImageNames,
                info = null,
                startPage = imageIndex,
            ) { loader -> block(loader, null) }
        } else {
            android.util.Log.i("SmbBookRouter", "stream archive path: remote=$remote ext=${ref.ext} size=${runCatching { byteSource.size }.getOrDefault(-1L)}")
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

    /** spec 5.3.1 异常映射：smbj 状态/常见异常 → 人话 */
    internal fun humanize(t: Throwable): String {
        if (t is TimeoutCancellationException) return "无法连接服务器：连接超时"
        val msg = t.message ?: ""
        return when {
            msg.contains("需要密码") -> "该归档需要密码"
            msg.contains("LOGON_FAILURE", ignoreCase = true) || msg.contains("Logon failure", ignoreCase = true) ->
                "认证失败：用户名或密码错误"
            msg.contains("BAD_NETWORK_NAME", ignoreCase = true) ->
                "共享不存在：请检查共享名配置"
            msg.contains("NOT_FOUND", ignoreCase = true) || msg.contains("No such file", ignoreCase = true) ->
                "文件不存在：可能已被移动或删除"
            else -> msg.take(160).ifEmpty { "无法连接服务器，请检查网络和配置" }
        }
    }

    private const val OPEN_TIMEOUT_MS = 15_000L
    private const val TAG = "SmbBookRouter"
    /** ★ P2（2026-10-04）：开包即定页数的扩展名——attach 跳过 500ms 页数稳定采样 */
    private val SETTLED_COUNT_EXTS = setOf("zip", "cbz", "epub", "pdf", "mobi", "azw", "azw3")
}
