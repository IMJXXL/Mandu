package com.mangareader.webdavbridge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hippo.ehviewer.gallery.NavigationKind
import com.hippo.ehviewer.gallery.PageLoader
import com.hippo.ehviewer.gallery.ReaderNavigation
import com.hippo.ehviewer.image.ByteBufferSource
import com.hippo.ehviewer.image.PathSource
import com.mangareader.native.RemotePageSupply
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WebDAV 阅读会话 + 页面供给（design.md 2.2.2 WebDavPageAdapter）。
 * 与 SmbPageAdapter 同构（design 14.2：adapter 侧统一订阅需求/提取完成信号，无逻辑差异）。
 */
class WebDavPageAdapter(
    @Suppress("unused") private val readerView: android.view.View? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : RemotePageSupply {

    private var loaderReady = CompletableDeferred<PageLoader>()
    private val closeSignal = CompletableDeferred<Unit>()
    private val closed = AtomicBoolean(false)

    @Volatile
    private var sourceRef: com.hippo.ehviewer.library.ArchiveByteSource? = null

    @Volatile
    private var pageCountField: Int = 0

    /** ★ 焦点页（2026-09-09 跳页调度）：最近一次请求的 index；只有焦点页允许驱动 loader，
     *  非焦点页的等待协程让位返回 null——对齐参考项目 interactiveSlots 保留机制 */
    @Volatile
    private var focusIndex: Int = -1

    override val pageCount: Int get() = pageCountField

    override val onPageCountGrow: MutableList<(Int) -> Unit> = java.util.concurrent.CopyOnWriteArrayList()

    /** ★ 焦点切换：设焦点并立即 navigate 新锚点（取消旧焦点页的下载/提取需求） */
    override fun onFocus(index: Int) {
        focusIndex = index
        scope.launch {
            runCatching {
                val loader = loaderReady.await()
                loader.navigate(ReaderNavigation(index, index..index, NavigationKind.Settled))
            }
        }
    }

    private fun checkPageCountGrow(loader: PageLoader) {
        val s = loader.size
        if (s > pageCountField) {
            pageCountField = s
            onPageCountGrow.forEach { runCatching { it(s) } }
        }
    }

    val source: com.hippo.ehviewer.library.ArchiveByteSource? get() = sourceRef

    suspend fun attach(loader: PageLoader, source: com.hippo.ehviewer.library.ArchiveByteSource?) {
        sourceRef = source
        var last = loader.size
        var stable = 0
        val t0 = System.currentTimeMillis()
        while (stable < 2 && System.currentTimeMillis() - t0 < PAGE_SIZE_STABILIZE_TIMEOUT_MS) {
            delay(250)
            val s = loader.size
            if (s == last) stable++ else { last = s; stable = 0 }
        }
        pageCountField = last
        loaderReady.complete(loader)
        loader.onSizeGrown = { newSize ->
            if (newSize > pageCountField) {
                pageCountField = newSize
                onPageCountGrow.forEach { runCatching { it(newSize) } }
            }
        }
    }

    suspend fun awaitReady(): PageLoader = loaderReady.await()

    suspend fun awaitClose() = closeSignal.await()

    fun failAsync(t: Throwable) {
        if (!loaderReady.isCompleted) loaderReady.completeExceptionally(t)
        closeSignal.complete(Unit)
    }

    /** RANGE 降级重开前重置就绪信号（首个 loader 打开失败/中途换源时使用） */
    fun resetForRetry() {
        if (!closed.get()) loaderReady = CompletableDeferred()
    }

    override suspend fun loadPageRaw(index: Int): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val loader = loaderReady.await()
            driveAndAwaitExtract(loader, index)
            // ★ 只有焦点页驱动 loader navigate（非焦点页零干预，靠 navigate(焦点页) 的预取窗口覆盖）
            if (focusIndex == -1 || focusIndex == index) {
                runCatching { loader.navigate(ReaderNavigation(index, index..index, NavigationKind.Settled)) }
            }
            checkPageCountGrow(loader)
            readPageBytes(loader, index)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "loadPageRaw($index) failed: ${t.message}")
            null
        }
    }

    override suspend fun loadBitmap(index: Int): Bitmap? {
        val raw = loadPageRaw(index) ?: return null
        // 大图防护：先解 bounds，超过屏幕短边 2 倍则按比例采样（防远程 8K 大图 OOM）
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val opts = BitmapFactory.Options()
        var sample = 1
        val maxEdge = maxOf(bounds.outWidth, bounds.outHeight)
        val screenMax = readerView?.resources?.displayMetrics?.let { maxOf(it.widthPixels, it.heightPixels) } ?: 2560
        val prefs = readerView?.context?.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE)
        val tierName = if (prefs?.contains("downsampleTier") == true) prefs.getString("downsampleTier", "STANDARD") else if (prefs?.getBoolean("forceFullDecode", false) == true) "ORIGINAL" else "STANDARD"
        val forceFull = (tierName == "ORIGINAL") && prefs?.getBoolean("isEinkMode", false) == false
        if (!forceFull) {
            while (maxEdge / (sample * 2) >= screenMax * 2) sample *= 2
        }
        opts.inSampleSize = sample
        return BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
    }

    override suspend fun close() {
        if (closed.getAndSet(true)) return
        closeSignal.complete(Unit)
        delay(150)
        scope.cancel()
    }

    /** 驱动参考项目 demand 机制并等待该页提取完成（openSource 不再抛"未提取"）。
     *  ★ 2026-09-09 v3 调度：非焦点页不让位、不驱动 navigate——纯等待 loader 对焦点页
     *  navigate 的预取窗口把它带上（对齐参考项目 interactive slot + prefetch slot 分离）。 */
    private suspend fun driveAndAwaitExtract(loader: PageLoader, index: Int) {
        val deadline = System.currentTimeMillis() + PAGE_WAIT_TIMEOUT_MS
        var lastNavigate = 0L
        while (System.currentTimeMillis() < deadline) {
            if (isPageAvailable(loader, index)) return
            val now = System.currentTimeMillis()
            if (now - lastNavigate > 1500) {
                lastNavigate = now
                if (focusIndex == -1 || focusIndex == index) {
                    runCatching { loader.navigate(ReaderNavigation(index, index..index, NavigationKind.Settled)) }
                }
            }
            delay(80)
        }
        android.util.Log.w(TAG, "page $index extract timeout (${loader.javaClass.simpleName})")
    }

    private fun isPageAvailable(loader: PageLoader, index: Int): Boolean = try {
        loader.openSource(index).close()
        true
    } catch (_: Exception) {
        false
    }

    private fun readPageBytes(loader: PageLoader, index: Int): ByteArray? = try {
        val src = loader.openSource(index)
        try {
            when (src) {
                is PathSource -> java.io.File(src.source.toString()).takeIf { it.isFile }?.readBytes()
                is ByteBufferSource -> ByteArray(src.source.remaining()).also { src.source.get(it) }
                else -> null
            }
        } finally {
            runCatching { src.close() }
        }
    } catch (t: Throwable) {
        android.util.Log.w(TAG, "readPageBytes($index): ${t.message}")
        null
    }

    companion object {
        private const val TAG = "WebDavPageAdapter"
        private const val PAGE_WAIT_TIMEOUT_MS = 30_000L
        private const val PAGE_SIZE_STABILIZE_TIMEOUT_MS = 8_000L
    }
}
