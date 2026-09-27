package com.mangareader.native.curl

import android.content.Context
import android.graphics.Bitmap
import com.mangareader.eink.applyEinkDither
import com.mangareader.viewer.ArchiveEngine
import com.mangareader.native.RemotePageSupply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class CurlPageLoader(
    private val context: Context,
    private val cache: CurlBitmapCache,
    private val scope: CoroutineScope,
) {
    private var engine: ArchiveEngine? = null
    private var treePath: String = ""
    private var remoteSupply: RemotePageSupply? = null
    private var pageList: List<ArchiveEngine.PageMeta> = emptyList()
    private var currentPage: Int = 0

    var trimWhiteBorder: Boolean = false
    var trimThreshold: Int = 220
    var trimWhiteRatio: Float = 0.90f
    /** ★ 墨水屏灰阶抖动参数（解码管线参数，单/双页 curl 共用本 loader；变更经 applyEinkDitherNow 即时生效） */
    var einkDither: Boolean = false
    var einkDitherAlgorithm: Int = 0
    var einkDitherLevels: Int = 16

    private val inflightLoads = ConcurrentHashMap<Int, Long>()
    private val loadJobs = ConcurrentHashMap<Int, Job>()
    private val preloadSemaphore = Semaphore(1)

    fun bind(
        engine: ArchiveEngine,
        treePath: String,
        pages: List<ArchiveEngine.PageMeta>,
        remoteSupply: RemotePageSupply?,
    ) {
        this.engine = engine
        this.treePath = treePath
        this.pageList = pages
        this.remoteSupply = remoteSupply
    }

    fun setCurrentPage(idx: Int) {
        currentPage = idx
    }

    fun cancelAll() {
        loadJobs.values.forEach { it.cancel() }
        loadJobs.clear()
        inflightLoads.clear()
    }

    fun loadPageAsync(idx: Int, isPreload: Boolean = false, onLoaded: (Int) -> Unit = {}) {
        if (idx < 0 || idx >= pageList.size) return
        val cached = cache.get(idx)
        if (cached != null && !cached.isRecycled) {
            try {
                val srHit = com.mangareader.sr.SrPipeline.attachOrScheduleFast(idx, cached)
                if (srHit != null && !srHit.isRecycled && srHit !== cached) {
                    cache.put(idx, srHit)
                    onLoaded(idx)
                }
            } catch (_: Exception) {}
            return
        }
        val meta = pageList[idx]
        val nowTs = android.os.SystemClock.uptimeMillis()
        val pitTs = inflightLoads[idx]
        if (pitTs != null && nowTs - pitTs < 5000L) {
            if (remoteSupply != null && idx == currentPage) remoteSupply?.onFocus(idx)
            return
        }
        inflightLoads[idx] = nowTs
        if (remoteSupply != null && idx == currentPage) remoteSupply?.onFocus(idx)
        val job = scope.launch {
            var acquired = false
            if (isPreload) { preloadSemaphore.acquire(); acquired = true }
            try {
                val supply = remoteSupply

                if (supply == null) {
                    var bmp = withContext(Dispatchers.IO) { engine?.loadPage(meta, treePath) }
                    if (bmp == null || bmp.isRecycled) {
                        cache.remove(idx)
                        inflightLoads.remove(idx)
                        return@launch
                    }

                    if (trimWhiteBorder && bmp.width > 10 && bmp.height > 10) {
                        try {
                            bmp = com.mangareader.viewer.ImageTrimmer.ensurePixelReadable(bmp)
                            val trim = com.mangareader.viewer.ImageTrimmer.trimWhiteBorders(
                                bmp, threshold = trimThreshold, whiteRatio = trimWhiteRatio, factor = 0.15f
                            )
                            bmp = com.mangareader.viewer.ImageTrimmer.applyTrim(bmp, trim)
                        } catch (_: Exception) {}
                    }
                    try {
                        val srHit = com.mangareader.sr.SrPipeline.attachOrSchedule(idx, bmp)
                        if (srHit != null && !srHit.isRecycled) bmp = srHit
                    } catch (_: Exception) {}
                    // ★ 墨水屏灰阶抖动（本地路径）：SR 之后、入缓存之前
                    if (einkDither && !bmp.isRecycled) {
                        try { bmp = bmp.applyEinkDither(einkDitherAlgorithm, einkDitherLevels) } catch (_: Exception) {}
                    }
                    cache.put(idx, bmp)
                    withContext(Dispatchers.Main) { onLoaded(idx) }
                } else {
                    val raw = withContext(Dispatchers.IO) { supply.loadPageRaw(idx) }
                    if (raw == null) {
                        cache.remove(idx)
                        inflightLoads.remove(idx)
                        return@launch
                    }

                    val loader = coil3.SingletonImageLoader.get(context)
                    val result = withContext(Dispatchers.IO) {
                        loader.execute(coil3.request.ImageRequest.Builder(context).data(raw).build())
                    }
                    val image = result.image
                    if (image is coil3.BitmapImage) {
                        var bmp = image.bitmap
                        if (trimWhiteBorder && bmp.width > 10 && bmp.height > 10) {
                            try {
                                bmp = com.mangareader.viewer.ImageTrimmer.ensurePixelReadable(bmp)
                                val trim = com.mangareader.viewer.ImageTrimmer.trimWhiteBorders(
                                    bmp, threshold = trimThreshold, whiteRatio = trimWhiteRatio, factor = 0.15f
                                )
                                bmp = com.mangareader.viewer.ImageTrimmer.applyTrim(bmp, trim)
                            } catch (_: Exception) {}
                        }
                        try {
                            val srHit = com.mangareader.sr.SrPipeline.attachOrSchedule(idx, bmp)
                            if (srHit != null && !srHit.isRecycled) bmp = srHit
                        } catch (_: Exception) {}
                        // ★ 墨水屏灰阶抖动（远程路径）：SR 之后、入缓存之前
                        if (einkDither && !bmp.isRecycled) {
                            try { bmp = bmp.applyEinkDither(einkDitherAlgorithm, einkDitherLevels) } catch (_: Exception) {}
                        }
                        cache.put(idx, bmp)
                        withContext(Dispatchers.Main) { onLoaded(idx) }
                    }
                }
            } finally {
                if (acquired) preloadSemaphore.release()
                if (inflightLoads[idx] == nowTs) inflightLoads.remove(idx)
                loadJobs.remove(idx)
            }
        }
        loadJobs[idx] = job
    }

    fun prioritizeLoad(anchorIdx: Int) {
        val window = (anchorIdx - 8)..(anchorIdx + 8)
        loadJobs.entries.removeAll { (idx, job) ->
            if (!job.isActive) true
            else if (idx !in window) {
                job.cancel()
                inflightLoads.remove(idx)
                true
            } else false
        }
    }

    fun preloadAround(centerIdx: Int, totalPages: Int, onLoaded: (Int) -> Unit = {}) {
        prioritizeLoad(centerIdx)
        if (remoteSupply != null) remoteSupply?.onFocus(centerIdx)
        try { com.mangareader.sr.SrPipeline.onPageChanged(centerIdx, totalPages) } catch (_: Exception) {}
        cache.protectThreeLayers(centerIdx)
        loadPageAsync(centerIdx, isPreload = false, onLoaded = onLoaded)
        if (centerIdx + 1 < totalPages) loadPageAsync(centerIdx + 1, isPreload = true, onLoaded = onLoaded)
        if (centerIdx - 1 >= 0) loadPageAsync(centerIdx - 1, isPreload = true, onLoaded = onLoaded)
        if (centerIdx + 2 < totalPages) loadPageAsync(centerIdx + 2, isPreload = true, onLoaded = onLoaded)
        if (centerIdx - 2 >= 0) loadPageAsync(centerIdx - 2, isPreload = true, onLoaded = onLoaded)
    }
}