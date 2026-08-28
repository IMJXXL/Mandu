package com.mangareader.sr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 超分磁盘缓存。两层用途：
 *  1. 实时超分产物（cacheDir 根，容量 100MB  LRU 驱逐）——key 含模型/倍率/降噪/黑白配置（见 SuperResolutionManager.buildCacheKey，设置变了自动不串图）
 *  2. 批量离线产物（batch/<书目安全名>/<章节key>/pageNNN.webp + metadata.json，不设上限，用户手动清）
 * API 30+ 用 WEBP_LOSSY，低版本自动 JPEG（系统限制，功能不受影响）。
 */
class SRDiskCache(
    private val cacheDir: File,
    /** 实时缓存上限提供者（字节）；默认 100MB。来自用户设置（SrSettings.realtimeCacheLimitMb） */
    private val realtimeLimitBytes: () -> Long = { 100L * 1024 * 1024 },
) {
    private val evictScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        if (!cacheDir.exists()) cacheDir.mkdirs()
    }

    fun get(key: String): Bitmap? {
        val file = getFile(key)
        if (!file.exists()) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "read disk cache failed: $key", e)
            null
        }
    }

    fun put(key: String, bitmap: Bitmap) {
        val file = getFile(key)
        file.parentFile?.mkdirs()
        writeBitmapAtomic(file, bitmap)
        evictIfNeeded()
    }

    fun remove(key: String) {
        getFile(key).delete()
    }

    fun contains(key: String): Boolean = getFile(key).exists()

    internal fun getFile(key: String): File {
        val safeKey = key.replace("[^a-zA-Z0-9_/.-]".toRegex(), "_")
        if (safeKey.contains('/')) {
            return File(cacheDir, safeKey)
        }
        val ext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "webp" else "jpg"
        return File(cacheDir, "$safeKey.$ext")
    }

    private fun writeBitmapAtomic(file: File, bitmap: Bitmap) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, out)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
            }
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                // rename 失败兜底直写
                FileOutputStream(file).use { out ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, out)
                    } else {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                }
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "write failed: ${file.name}", e)
            tmp.delete()
        }
    }

    // ── 批量产物 ──

    private val batchDir: File get() = File(cacheDir, "batch").also { it.mkdirs() }

    private fun batchChapterDir(bookTitle: String, chapterKey: String): File {
        val safeTitle = DiskUtil.buildValidFilename(bookTitle)
        return File(batchDir, "$safeTitle/$chapterKey").also { it.mkdirs() }
    }

    fun putBatchPage(bookTitle: String, chapterKey: String, pageIndex: Int, bitmap: Bitmap) {
        val dir = batchChapterDir(bookTitle, chapterKey)
        val ext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "webp" else "jpg"
        val file = File(dir, "${pageIndex.toString().padStart(3, '0')}.$ext")
        writeBitmapAtomic(file, bitmap)
    }

    /** 按章节 key 找批量产物（不依赖书名目录，O(1) 命中常见情况） */
    fun getBatchPage(bookTitle: String, chapterKey: String, pageIndex: Int): Bitmap? {
        val dir = batchChapterDir(bookTitle, chapterKey)
        return readBatchPageFile(dir, pageIndex)
    }

    fun getBatchPageAnyBook(chapterKey: String, pageIndex: Int): Bitmap? {
        if (!batchDir.exists()) return null
        batchDir.walkTopDown().filter { it.isDirectory && it.name == chapterKey }.forEach { dir ->
            val bitmap = readBatchPageFile(dir, pageIndex)
            if (bitmap != null) return bitmap
        }
        return null
    }

    fun hasBatchChapter(chapterKey: String): Boolean {
        if (!batchDir.exists()) return false
        return batchDir.walkTopDown().any { it.isDirectory && it.name == chapterKey }
    }

    /** 快速直取判定（每页调用路径用）：按书名/章节精确目录存在性，O(1) 文件 stat */
    fun hasBatchChapter(bookTitle: String, chapterKey: String): Boolean {
        return File(batchDir, "${DiskUtil.buildValidFilename(bookTitle)}/$chapterKey").isDirectory
    }

    private fun readBatchPageFile(dir: File, pageIndex: Int): Bitmap? {
        val ext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "webp" else "jpg"
        val file = File(dir, "${pageIndex.toString().padStart(3, '0')}.$ext")
        if (!file.exists()) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "read batch page failed: $file", e)
            null
        }
    }

    fun putBatchMetadata(bookTitle: String, chapterKey: String, pageCount: Int, bookId: Long, path: String) {
        val dir = batchChapterDir(bookTitle, chapterKey)
        val json = org.json.JSONObject().apply {
            put("bookId", bookId)
            put("bookTitle", bookTitle)
            put("path", path)
            put("pageCount", pageCount)
            put("completedAt", System.currentTimeMillis())
        }.toString()
        try {
            File(dir, "metadata.json").writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "write batch metadata failed: $chapterKey", e)
        }
    }

    data class BatchCompletedChapter(
        val chapterKey: String,
        val bookId: Long,
        val bookTitle: String,
        val path: String,
        val pageCount: Int,
        val completedAt: Long,
    )

    fun getCompletedBatchChapters(): List<BatchCompletedChapter> {
        val result = mutableListOf<BatchCompletedChapter>()
        if (!batchDir.exists()) return result
        batchDir.walkTopDown().filter { it.isFile && it.name == "metadata.json" }.forEach { file ->
            try {
                val json = org.json.JSONObject(file.readText())
                result.add(
                    BatchCompletedChapter(
                        chapterKey = file.parentFile?.name ?: return@forEach,
                        bookId = json.optLong("bookId"),
                        bookTitle = json.optString("bookTitle"),
                        path = json.optString("path"),
                        pageCount = json.optInt("pageCount"),
                        completedAt = json.optLong("completedAt"),
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "parse batch metadata failed: ${file.path}", e)
            }
        }
        return result
    }

    fun removeBatchChapter(bookTitle: String, chapterKey: String) {
        val dir = batchChapterDir(bookTitle, chapterKey)
        if (dir.exists()) dir.deleteRecursively()
        val bookDir = dir.parentFile
        if (bookDir != null && bookDir.exists() && bookDir.list()?.isEmpty() == true) {
            bookDir.delete()
        }
    }

    fun clearBatch() {
        if (batchDir.exists()) batchDir.deleteRecursively()
    }

    /** 只清实时缓存（cacheDir 根部的 page_* 文件），绝不动 batch/ 产物 */
    fun clearTransient() {
        cacheDir.listFiles()?.forEach { file ->
            if (file.isFile && !file.name.endsWith(".json")) file.delete()
        }
    }

    /** 用量拆分：实时缓存 / 批量离线产物 各一份 */
    fun getRealtimeUsage(): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        cacheDir.listFiles()?.forEach { file ->
            if (file.isFile && !file.name.endsWith(".json")) {
                count++
                bytes += file.length()
            }
        }
        return count to bytes
    }

    fun getBatchUsage(): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        if (batchDir.exists()) {
            batchDir.walkTopDown().forEach { file ->
                if (file.isFile && file.name != "metadata.json") {
                    count++
                    bytes += file.length()
                }
            }
        }
        return count to bytes
    }

    fun getUsage(): Pair<Int, Long> {
        val (c1, b1) = getRealtimeUsage()
        val (c2, b2) = getBatchUsage()
        return (c1 + c2) to (b1 + b2)
    }

    fun clearAll() {
        cacheDir.listFiles()?.forEach { if (it.isDirectory) it.deleteRecursively() else it.delete() }
    }

    private fun evictIfNeeded() {
        evictScope.launch {
            val limit = try { realtimeLimitBytes().coerceAtLeast(16L * 1024 * 1024) } catch (_: Exception) { 100L * 1024 * 1024 }
            val files = cacheDir.listFiles()?.filter {
                it.isFile && it.name != "batch" && !it.name.endsWith(".json")
            }?.toMutableList() ?: return@launch
            var totalSize = files.sumOf { it.length() }
            if (totalSize <= limit) return@launch
            files.sortByDescending { it.lastModified() }
            var index = files.size - 1
            while (totalSize > limit && index >= 0) {
                totalSize -= files[index].length()
                files[index].delete()
                index--
            }
        }
    }

    companion object {
        private const val TAG = "SrDiskCache"
    }
}

private object DiskUtil {
    fun buildValidFilename(name: String): String {
        return name.trim()
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), "_")
            .take(120)
    }
}
