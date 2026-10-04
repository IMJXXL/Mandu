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
    // ★ 固定四边裁剪（仅本书功能 2026-10-04）：条漫分割段图（segmentTotal>1）只裁左右
    @Volatile var cropL: Float = 0f
    @Volatile var cropT: Float = 0f
    @Volatile var cropR: Float = 0f
    @Volatile var cropB: Float = 0f
    /** ★ 墨水屏灰阶抖动参数（解码管线参数，单/双页 curl 共用本 loader；变更经 applyEinkDitherNow 即时生效） */
    var einkDither: Boolean = false
    var einkDitherAlgorithm: Int = 0
    var einkDitherLevels: Int = 16
    var einkEndpointPassthrough: Int = 0

    private val inflightLoads = ConcurrentHashMap<Int, Long>()
    private val loadJobs = ConcurrentHashMap<Int, Job>()
    // ★ 2 许可（2026-10-04 双页 curl 翻快了下一屏没就位）：原 1=全部预载严格串行，
    //   下一跨页要排在上一跨页后面逐页等，快翻时目标页永远赶不上翻页动画
    private val preloadSemaphore = Semaphore(2)
    /** idx → 已烘焙的源位图 identity：源没换不重烘（清缓存换纹理时由 clearBakeTracking 复位） */
    private val bakedFrom = HashMap<Int, Int>()

    fun clearBakeTracking() {
        synchronized(bakedFrom) { bakedFrom.clear() }
    }

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
        synchronized(bakedFrom) { bakedFrom.clear() }
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
                    // ★ 烘焙一次化：cached 恒为烘焙副本 → srHit !== cached 恒真，无标记则每次落地
                    //   对整个预载窗口逐页整页 copy+MULTIPLY（主线程 50-75ms）。源实例没换就跳过重烘
                    val srcId = System.identityHashCode(srHit)
                    val already = synchronized(bakedFrom) { bakedFrom[idx] }
                    if (already != srcId) {
                        cache.put(idx, com.mangareader.filter.PaperPageTexture.apply(srHit))
                        synchronized(bakedFrom) { bakedFrom[idx] = srcId }
                        onLoaded(idx)
                    }
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

                    val cropActive = cropL > 0.001f || cropT > 0.001f || cropR > 0.001f || cropB > 0.001f
                    if (cropActive && bmp.width > 10 && bmp.height > 10) {
                        try {
                            val segTall = meta.segmentTotal > 1
                            bmp = com.mangareader.viewer.ImageTrimmer.applyFixedCrop(bmp, cropL, if (segTall) 0f else cropT, cropR, if (segTall) 0f else cropB)
                        } catch (_: Exception) {}
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
                        try { bmp = bmp.applyEinkDither(einkDitherAlgorithm, einkDitherLevels, einkEndpointPassthrough) } catch (_: Exception) {}
                    }
                    // ★ 纸张纹理烘焙：管线末端、入缓存前（纸随页翻动，替代旧屏幕覆盖层）
                    bmp = com.mangareader.filter.PaperPageTexture.apply(bmp)
                    synchronized(bakedFrom) { bakedFrom[idx] = System.identityHashCode(bmp) }
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
                        val cropActive = cropL > 0.001f || cropT > 0.001f || cropR > 0.001f || cropB > 0.001f
                        if (cropActive && bmp.width > 10 && bmp.height > 10) {
                            try {
                                val segTall = meta.segmentTotal > 1
                                bmp = com.mangareader.viewer.ImageTrimmer.applyFixedCrop(bmp, cropL, if (segTall) 0f else cropT, cropR, if (segTall) 0f else cropB)
                            } catch (_: Exception) {}
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
                        // ★ 墨水屏灰阶抖动（远程路径）：SR 之后、入缓存之前
                        if (einkDither && !bmp.isRecycled) {
                            try { bmp = bmp.applyEinkDither(einkDitherAlgorithm, einkDitherLevels, einkEndpointPassthrough) } catch (_: Exception) {}
                        }
                        // ★ 纸张纹理烘焙：管线末端、入缓存前
                        bmp = com.mangareader.filter.PaperPageTexture.apply(bmp)
                        synchronized(bakedFrom) { bakedFrom[idx] = System.identityHashCode(bmp) }
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