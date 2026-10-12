package com.mangareader.komgabridge.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.mangareader.komgabridge.KomgaConstants
import com.mangareader.komgabridge.api.KomgaApiClient
import com.mangareader.komgabridge.api.model.PageDto
import com.mangareader.native.RemotePageSupply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

class KomgaPageAdapter(
    private val pages: List<PageDto>,
    private val apiClient: KomgaApiClient,
    private val komgaBookId: String,
    private val isPdf: Boolean = false,
    private val progressWriter: KomgaProgressWriter? = null,
) : RemotePageSupply {

    override val pageCount: Int = pages.size

    override val onPageCountGrow: MutableList<(Int) -> Unit> = CopyOnWriteArrayList()

    override suspend fun loadPageRaw(index: Int): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val pageNumber = index + 1
            val bytes = apiClient.downloadBytes(apiClient.pageImageUrl(komgaBookId, pageNumber, isPdf))
            progressWriter?.onPageChanged(index)
            bytes
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(KomgaConstants.LOG_TAG, "loadPageRaw($index) failed${if (isPdf) " [PDF]" else ""}: ${t.message}")
            null
        }
    }

    override suspend fun loadBitmap(index: Int): Bitmap? = withContext(Dispatchers.IO) {
        val raw = loadPageRaw(index) ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val opts = BitmapFactory.Options()
        var sample = 1
        val prefs = com.mangareader.komgabridge.KomgaBridges.context.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE)
        val tierName = if (prefs.contains("downsampleTier")) prefs.getString("downsampleTier", "STANDARD") else if (prefs.getBoolean("forceFullDecode", false)) "ORIGINAL" else "STANDARD"
        // ★ 动图页跳过降采样：不省解码成本只毁首帧画质（条漫动图页为静帧显示）；OOM 降级兜底
        val forceFull = ((tierName == "ORIGINAL") && !prefs.getBoolean("isEinkMode", false)) ||
            com.mangareader.viewer.ArchiveEngine.isAnimatedBytes(raw)
        if (!forceFull) {
            val maxEdge = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxEdge / (sample * 2) >= 2560 * 2) sample *= 2
        }
        opts.inSampleSize = sample
        return@withContext try {
            BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
        } catch (e: OutOfMemoryError) {
            System.gc()
            try { BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = 2 }) } catch (_: Exception) { null }
        }
    }

    override fun onFocus(index: Int) {}

    override suspend fun close() {
        progressWriter?.flush()
        apiClient.close()
    }
}