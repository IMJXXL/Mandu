package com.mangareader.quarkbridge.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hippo.ehviewer.gallery.NavigationKind
import com.hippo.ehviewer.gallery.PageLoader
import com.hippo.ehviewer.gallery.ReaderNavigation
import com.hippo.ehviewer.image.ByteBufferSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.library.ArchiveByteSource
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

class QuarkPageAdapter(
    @Suppress("unused") private val readerView: android.view.View? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : RemotePageSupply {

    private var loaderReady = CompletableDeferred<PageLoader>()
    private val closeSignal = CompletableDeferred<Unit>()
    private val closed = AtomicBoolean(false)

    @Volatile
    private var sourceRef: ArchiveByteSource? = null

    @Volatile
    private var pageCountField: Int = 0

    @Volatile
    private var focusIndex: Int = -1

    override val pageCount: Int get() = pageCountField

    override val onPageCountGrow: MutableList<(Int) -> Unit> = java.util.concurrent.CopyOnWriteArrayList()

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

    val source: ArchiveByteSource? get() = sourceRef

    suspend fun attach(loader: PageLoader, source: ArchiveByteSource?, sizeSettled: Boolean = false) {
        sourceRef = source
        var last = loader.size
        if (!sizeSettled) {
            var stable = 0
            val t0 = System.currentTimeMillis()
            while (stable < 2 && System.currentTimeMillis() - t0 < PAGE_SIZE_STABILIZE_TIMEOUT_MS) {
                delay(250)
                val s = loader.size
                if (s == last) stable++ else { last = s; stable = 0 }
            }
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

    fun resetForRetry() {
        if (!closed.get()) loaderReady = CompletableDeferred()
    }

    override suspend fun loadPageRaw(index: Int): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val loader = loaderReady.await()
            readPageBytes(loader, index)?.let { bytes ->
                if (focusIndex == -1 || focusIndex == index) {
                    runCatching { loader.navigate(ReaderNavigation(index, index..index, NavigationKind.Settled)) }
                }
                checkPageCountGrow(loader)
                return@withContext bytes
            }
            driveAndAwaitExtract(loader, index)
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
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val opts = BitmapFactory.Options()
        var sample = 1
        val maxEdge = maxOf(bounds.outWidth, bounds.outHeight)
        val screenMax = readerView?.resources?.displayMetrics?.let { maxOf(it.widthPixels, it.heightPixels) } ?: 2560
        val prefs = readerView?.context?.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE)
        val tierName = if (prefs?.contains("downsampleTier") == true) prefs.getString("downsampleTier", "STANDARD") else if (prefs?.getBoolean("forceFullDecode", false) == true) "ORIGINAL" else "STANDARD"
        val forceFull = ((tierName == "ORIGINAL") && prefs?.getBoolean("isEinkMode", false) == false) ||
            com.mangareader.viewer.ArchiveEngine.isAnimatedBytes(raw)
        if (!forceFull) {
            while (maxEdge / (sample * 2) >= screenMax * 2) sample *= 2
        }
        opts.inSampleSize = sample
        return try {
            BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
        } catch (e: OutOfMemoryError) {
            System.gc()
            try { BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = 2 }) } catch (_: Exception) { null }
        }
    }

    override suspend fun close() {
        if (closed.getAndSet(true)) return
        closeSignal.complete(Unit)
        delay(150)
        scope.cancel()
    }

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
        private const val TAG = "QuarkPageAdapter"
        private const val PAGE_WAIT_TIMEOUT_MS = 30_000L
        private const val PAGE_SIZE_STABILIZE_TIMEOUT_MS = 8_000L
    }
}