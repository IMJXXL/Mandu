package com.mangareader.smbbridge

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
 * SMB 阅读会话 + 页面供给（design.md 2.2.2 SmbPageAdapter）。
 *
 * 生命周期与参考项目 PageLoader 的作用域契约（useXxxPageLoader 的 autoCloseScope）对接：
 * [SmbBookRouter] 在会话协程中启动 loader，loader 就绪后调用 [attach]，随后挂起在
 * [awaitClose] 直到 [close] 被调用——block 返回触发 autoCloseScope 关闭 source，资源归位。
 *
 * [loadPageRaw] 通过 `loader.navigate(ReaderNavigation)` 驱动参考项目的 demand/extract
 * 机制，等页面落盘（ArchiveStreamPageCache / ramPages）后读取原始字节；实现绝不抛出
 * （阅读器 loadPageAsync 无 catch，只有 finally），任何异常一律返回 null 按加载失败占位。
 */
class SmbPageAdapter(
    /** design.md 签名保留；漫读侧经由 RemotePageSupply 钩子接入，视图引用可不传 */
    @Suppress("unused") private val readerView: android.view.View? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : RemotePageSupply {

    private var loaderReady = CompletableDeferred<PageLoader>()
    private val closeSignal = CompletableDeferred<Unit>()
    private val closed = AtomicBoolean(false)

    @Volatile
    private var sourceRef: com.hippo.ehviewer.library.ArchiveByteSource? = null

    /** 会话字节源（归档/文档类；文件夹书为 null）。Supported 结果透传给调用方。 */
    val source: com.hippo.ehviewer.library.ArchiveByteSource? get() = sourceRef

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

    /** loader 就绪后由会话 block 调用；等待页数稳定（TAR 渐进索引会增长）再放行 */
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

    /** 等待 loader 就绪（供 Router 取 PageLoader 返回 Supported） */
    suspend fun awaitReady(): PageLoader = loaderReady.await()

    /** 会话 block 的挂起点：close() 触发后返回，autoCloseScope 随之关闭 source */
    suspend fun awaitClose() = closeSignal.await()

    /** 会话失败（连接/认证/打开失败）：让 awaitReady 以异常结束 */
    fun failAsync(t: Throwable) {
        if (!loaderReady.isCompleted) loaderReady.completeExceptionally(t)
        closeSignal.complete(Unit)
    }

    /** 会话重开前重置就绪信号（预留） */
    fun resetForRetry() {
        if (!closed.get()) loaderReady = CompletableDeferred()
    }

    override suspend fun loadPageRaw(index: Int): ByteArray? = withContext(Dispatchers.IO) {
        focusIndex = index
        try {
            val loader = loaderReady.await()
            driveAndAwaitExtract(loader, index) ?: return@withContext null
            runCatching { loader.navigate(ReaderNavigation(index, index..index, NavigationKind.Settled)) }
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
        while (maxEdge / (sample * 2) >= screenMax * 2) sample *= 2
        opts.inSampleSize = sample
        return BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
    }

    override suspend fun close() {
        if (closed.getAndSet(true)) return
        closeSignal.complete(Unit)
        // 给 autoCloseScope 的正常收尾一个窗口，再兜底取消整个会话作用域（install 清理在取消时同样执行）
        delay(150)
        scope.cancel()
    }

    /** 驱动参考项目 demand 机制并等待该页提取完成（openSource 不再抛"未提取"）；
     *  ★ 返回 null = 被新焦点页取代（让位，不占用 loader 资源） */
    private suspend fun driveAndAwaitExtract(loader: PageLoader, index: Int): Unit? {
        val deadline = System.currentTimeMillis() + PAGE_WAIT_TIMEOUT_MS
        var lastNavigate = 0L
        while (System.currentTimeMillis() < deadline) {
            if (focusIndex != index) return null // ★ 让位：新焦点页已接管 loader
            if (isPageAvailable(loader, index)) return Unit
            val now = System.currentTimeMillis()
            if (now - lastNavigate > 1500) {
                lastNavigate = now
                if (focusIndex != index) return null
                runCatching { loader.navigate(ReaderNavigation(index, index..index, NavigationKind.Settled)) }
            }
            delay(80)
        }
        android.util.Log.w(TAG, "page $index extract timeout (${loader.javaClass.simpleName})")
        return Unit
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
        private const val TAG = "SmbPageAdapter"
        private const val PAGE_WAIT_TIMEOUT_MS = 30_000L
        private const val PAGE_SIZE_STABILIZE_TIMEOUT_MS = 8_000L
    }
}
