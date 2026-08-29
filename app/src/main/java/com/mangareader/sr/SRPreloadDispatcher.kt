package com.mangareader.sr

import android.graphics.Bitmap
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 预加载调度（修复自 mihon-super-resolution：其 onPreloadRequested 从未被赋值导致"预载空转"，
 * 这里由 SrPipeline 在构造后立刻赋值真实取料逻辑）。
 */
class SRPreloadDispatcher(
    private val manager: SuperResolutionManager,
    private val diskCache: SRDiskCache,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val preloadingPages = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var preloadWindow: Int = 3

    /** 真实取料回调：由 SrPipeline 注入，(chapterId, pageIndex) -> 读原图→超分→写缓存 */
    @Volatile
    var onPreloadRequested: ((chapterId: Long, pageIndex: Int) -> Unit)? = null

    fun onPageChanged(chapterId: Long, currentPageIndex: Int, totalPages: Int) {
        if (!manager.isReady) return
        val window = preloadWindow
        if (window <= 0) return

        val end = minOf(currentPageIndex + window, totalPages - 1)
        for (pageIndex in (currentPageIndex + 1)..end) {
            val cacheKey = buildCacheKey(chapterId, pageIndex)
            if (!preloadingPages.add(cacheKey)) continue

            if (diskCache.contains(cacheKey) || manager.getCachedResult(cacheKey) != null) {
                preloadingPages.remove(cacheKey)
                continue
            }

            scope.launch {
                try {
                    onPreloadRequested?.invoke(chapterId, pageIndex)
                } catch (e: Exception) {
                    Log.e(TAG, "preload failed ch$chapterId p$pageIndex", e)
                } finally {
                    preloadingPages.remove(cacheKey)
                }
            }
        }
    }

    fun getSrBitmap(chapterId: Long, pageIndex: Int): Bitmap? {
        val cacheKey = buildCacheKey(chapterId, pageIndex)
        return manager.getCachedResult(cacheKey) ?: diskCache.get(cacheKey)
    }

    fun putSrResult(chapterId: Long, pageIndex: Int, bitmap: Bitmap) {
        val cacheKey = buildCacheKey(chapterId, pageIndex)
        manager.putCachedResult(cacheKey, bitmap)
        // ★ F1.1：同步完成磁盘编码再返回（唯一调用方 SrPipeline.scheduleInternal 在 IO 协程），
        //   emitReady 交给视图时写盘已结束——堵死"异步编码期间位图被视图 recycle"的第三属主窗口
        if (!bitmap.isRecycled) diskCache.put(cacheKey, bitmap)
    }

    /** 只清"实时"产物：磁盘实时缓存 + 内存结果缓存；批量离线产物不受影响 */
    fun clearCache() {
        diskCache.clearTransient()
        manager.clearSrCache()
    }

    private fun buildCacheKey(chapterId: Long, pageIndex: Int): String =
        manager.buildCacheKey(chapterId, pageIndex)

    companion object {
        private const val TAG = "SrPreload"
    }
}
