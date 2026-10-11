package com.hippo.ehviewer.gallery

import arrow.autoCloseScope
import com.ehviewer.core.model.GalleryInfo
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.DocumentExtractCache
import com.mangareader.data.ReaderConfig
import com.mangareader.native.NativeEngine
import com.mangareader.smbbridge.SmbAppContextBridge
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.tarsin.kt.install
import okio.Path

/**
 * PDF range reads block the calling thread inside pdfium's synchronous read
 * callback ([NativeEngine.PdfRangeReader] → [ArchiveByteSource.readAt] →
 * runBlocking). Running that on [Dispatchers.IO] parks shared workers one per
 * in-flight render; enough concurrent renders starve the pool that the HTTP
 * engine and DataStore need, deadlocking every reader. A small dedicated pool
 * caps the fan-out and keeps the parked threads private.
 */
@PublishedApi
internal val pdfiumDispatcher: CoroutineDispatcher by lazy {
    val counter = AtomicInteger()
    Executors.newFixedThreadPool(2) { r ->
        Thread(r, "pdfium-render-" + counter.incrementAndGet())
    }.asCoroutineDispatcher()
}

@PublishedApi
internal val pdfiumInteractiveDispatcher: CoroutineDispatcher by lazy {
    val counter = AtomicInteger()
    Executors.newFixedThreadPool(2) { r ->
        Thread(r, "pdfium-interactive-" + counter.incrementAndGet())
    }.asCoroutineDispatcher()
}

/**
 * Remote PDF reader via pdfium FPDF_LoadCustomDocument: range-reads the remote
 * file on demand (no full download), renders each page to a cached JPEG.
 */
suspend inline fun <T> usePdfiumPageLoader(
    source: ArchiveByteSource,
    cacheKey: String,
    titleHint: String,
    info: GalleryInfo? = null,
    startPage: Int = 0,
    remoteSize: Long = 0L,
    crossinline block: suspend (PageLoader) -> T,
): T = autoCloseScope {
    coroutineScope {
        // ★ 有界探测 + 半缓存离线续读（2026-10-11 审查）：pdfium 探测在最前且无离线路径，
        // 黑洞断网下整本打不开。size 未知时若磁盘有可用索引（渲染页落 DocumentExtractCache），
        // 引擎无关直出已缓存页，未缓存页按页失败；无缓存才干净失败。
        // ★ F4（报告 §六）：pin 前置到在线/离线分支之外——在线会话渲染页同样落
        //   DocumentExtractCache，会话期 trim 不能删正在看的页（与 Document 家族完全对齐）
        DocumentExtractCache.pin(cacheKey)
        install({ }, { _, _ -> DocumentExtractCache.unpin(cacheKey) })
        val size = remoteSize.takeIf { it > 0L } ?: com.hippo.ehviewer.gallery.boundedRemoteSizeProbe(source).coerceAtLeast(0L)
        if (size <= 0L) {
            val partialIdx = DocumentExtractCache.loadUsableIndex(cacheKey, remoteSize = 0L)
            val partialLoader = partialIdx?.let { idx ->
                val exts = idx.members.associate { it.i to it.ext }
                val total = idx.members.size
                val available = idx.members.filter { DocumentExtractCache.isPageCached(cacheKey, it.i, it.ext) }
                    .map { it.i }.toSet()
                if (total > 0 && available.isNotEmpty()) {
                    install(
                        object : PageLoader(
                            this,
                            info,
                            startPage.coerceIn(0, (total - 1).coerceAtLeast(0)),
                            total,
                            false,
                        ) {
                            override val title by lazy { info?.title ?: titleHint }
                            override fun getImageExtension(index: Int) = exts[index] ?: "jpg"
                            override fun savePage(index: Int, file: Path): Boolean = runCatching {
                                val ext = exts[index] ?: "jpg"
                                if (!DocumentExtractCache.isPageCached(cacheKey, index, ext)) return@runCatching false
                                val p = DocumentExtractCache.pagePath(cacheKey, index, ext)
                                File(p.toString()).copyTo(File(file.toString()), overwrite = true)
                                true
                            }.getOrDefault(false)
                            override fun openSource(index: Int): ImageSource {
                                val ext = exts[index] ?: "jpg"
                                val p = DocumentExtractCache.pagePath(cacheKey, index, ext)
                                return object : PathSource {
                                    override val source: Path = p
                                    override val type: String = ext
                                    override fun close() = Unit
                                }
                            }
                            override fun prefetchPages(pages: List<Int>, bounds: IntRange) = Unit
                            override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) {
                                if (index in available) notifySourceReady(index, orgImg)
                                else notifyPageFailed(index, "离线不可用（未缓存）")
                            }
                        },
                    )
                } else null
            }
            if (partialLoader != null) {
                DocumentExtractCache.touchAsync(cacheKey)
                return@coroutineScope block(partialLoader)
            }
            check(false) { "Cannot open PDF offline (no usable cache): $cacheKey" }
        }

        val pdfKey = "remote_pdf:$cacheKey"

        val reader = NativeEngine.PdfRangeReader { position, readSize ->
            val buf = ByteArray(readSize)
            val read = source.readAt(position, buf, 0, readSize)
            if (read > 0) {
                buf.copyOf(read)
            } else {
                android.util.Log.w("PdfiumOpen", "readAt failed cacheKey=$cacheKey pos=$position size=$readSize read=$read")
                null
            }
        }

        val opened = withContext(pdfiumDispatcher) {
            NativeEngine.pdfOpenCustom(pdfKey, size, reader)
        }
        if (!opened) {
            android.util.Log.e("PdfiumOpen", "pdfOpenCustom failed cacheKey=$cacheKey size=$size")
        }
        check(opened) { "pdfium failed to open PDF: $cacheKey" }

        install({ source }, { s, _ -> s.close() })
        install({ pdfKey }, { key, _ -> NativeEngine.pdfClose(key) })

        val pageCount = withContext(pdfiumDispatcher) { NativeEngine.pdfGetPageCount(pdfKey) }
        check(pageCount > 0) { "PDF has no pages: $cacheKey" }

        val ctx = SmbAppContextBridge.appCtx
        val config = ReaderConfig.load(ctx)
        val dpi = (config.pdfQuality * 72f).toInt().coerceIn(72, 360)
        val screenMax = maxOf(
            ctx.resources.displayMetrics.widthPixels,
            ctx.resources.displayMetrics.heightPixels,
        )

                val pagePaths = ConcurrentHashMap<Int, Path>()
                val renderJobs = ConcurrentHashMap<Int, Job>()
                val interactivePending = AtomicInteger(-1)
                val hostScope = this

        val loader = install(
            object : PageLoader(
                hostScope,
                info,
                startPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
                pageCount,
                false,
            ) {
                override val title by lazy { info?.title ?: titleHint }

                override fun getImageExtension(index: Int) = "jpg"

                override fun savePage(index: Int, file: Path): Boolean = runCatching {
                    val path = pagePaths[index]
                        ?: DocumentExtractCache.pagePath(cacheKey, index, "jpg")
                            .takeIf { DocumentExtractCache.isCachedFile(it) }
                        ?: return@runCatching false
                    pagePaths[index] = path
                    File(path.toString()).copyTo(File(file.toString()), overwrite = true)
                    true
                }.getOrDefault(false)

                override fun openSource(index: Int): ImageSource {
                    val path = pagePaths[index]
                        ?: DocumentExtractCache.pagePath(cacheKey, index, "jpg")
                            .takeIf { DocumentExtractCache.isCachedFile(it) }
                    checkNotNull(path) { "PDF page $index not rendered" }
                    pagePaths[index] = path
                    return object : PathSource {
                        override val source: Path = path
                        override val type: String = "jpg"
                        override fun close() = Unit
                    }
                }

                override fun prefetchPages(pages: List<Int>, bounds: IntRange) {
                    val ip = interactivePending.get()
                    if (ip >= 0 && pagePaths[ip] == null && renderJobs[ip]?.isActive == true) return
                    pages.firstOrNull { pagePaths[it] == null && !DocumentExtractCache.isPageCached(cacheKey, it, "jpg") }?.let {
                        ensureRender(it, interactive = false)
                    }
                }

                override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) {
                    interactivePending.set(index)
                    val iter = renderJobs.entries.iterator()
                    while (iter.hasNext()) {
                        val (jobIndex, job) = iter.next()
                        if (kotlin.math.abs(jobIndex - index) > 3) {
                            job.cancel()
                            iter.remove()
                        }
                    }
                    ensureRender(index, interactive = true) {
                        notifySourceReady(index, orgImg)
                    }
                }

                override fun close() {
                    renderJobs.values.toList().forEach { it.cancel() }
                    renderJobs.clear()
                    super.close()
                }

                private fun ensureRender(
                    index: Int,
                    interactive: Boolean,
                    onReady: () -> Unit = {},
                ) {
                    if (pagePaths[index] != null ||
                        DocumentExtractCache.isPageCached(cacheKey, index, "jpg")
                    ) {
                        pagePaths[index] = pagePaths[index]
                            ?: DocumentExtractCache.pagePath(cacheKey, index, "jpg")
                        onReady()
                        return
                    }
                    renderJobs[index]?.cancel()
                    val dispatcher = if (interactive) pdfiumInteractiveDispatcher else pdfiumDispatcher
                    renderJobs[index] = hostScope.launch(dispatcher) {
                        val renderStart = android.os.SystemClock.elapsedRealtime()
                        try {
                            val pageSize = NativeEngine.pdfGetPageSize(pdfKey, index)
                                ?: error("pdfium getPageSize failed for page $index")
                            val pageWidth = pageSize[0].toFloat()
                            val pageHeight = pageSize[1].toFloat()
                            var w = (dpi.toFloat() * pageWidth / 72f).toInt().coerceAtLeast(1)
                            var h = (dpi.toFloat() * pageHeight / 72f).toInt().coerceAtLeast(1)
                            if (w > screenMax || h > screenMax) {
                                val scale = screenMax.toFloat() / maxOf(w, h)
                                w = (w * scale).toInt().coerceAtLeast(1)
                                h = (h * scale).toInt().coerceAtLeast(1)
                            }
                            val bmp = NativeEngine.pdfRenderPageBitmap(pdfKey, index, w, h, dpi)
                                ?: error("pdfium render failed for page $index")
                            val bytes = ByteArrayOutputStream().use { baos ->
                                bmp.compress(
                                    android.graphics.Bitmap.CompressFormat.JPEG,
                                    95,
                                    baos,
                                )
                                baos.toByteArray()
                            }
                            val path = DocumentExtractCache.writePage(cacheKey, index, "jpg", bytes)
                            pagePaths[index] = path
                            android.util.Log.d(
                                "PdfRender",
                                "page=" + index + " done ms=" + (android.os.SystemClock.elapsedRealtime() - renderStart),
                            )
                            onReady()
                            if (interactive) interactivePending.compareAndSet(index, -1)
                        } catch (e: CancellationException) {
                            android.util.Log.d("PdfRender", "page=" + index + " cancelled")
                            throw e
                        } catch (t: Throwable) {
                            android.util.Log.w(
                                "PdfRender",
                                "page=" + index + " failed ms=" + (android.os.SystemClock.elapsedRealtime() - renderStart),
                                t,
                            )
                            if (interactive) notifyPageFailed(index, t.message)
                        }
                    }
                }
            },
        )

        block(loader)
    }
}