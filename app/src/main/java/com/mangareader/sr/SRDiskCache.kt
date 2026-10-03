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
 * 超分磁盘缓存。三层用途：
 *  1. 实时超分产物（cacheDir 根，容量默认 1000MB  LRU 驱逐）——key 含模型/倍率/降噪/黑白配置（见 SuperResolutionManager.buildCacheKey，设置变了自动不串图）
 *  2. 批量离线产物（batch/<书目安全名>/<章节key>/pageNNN.webp + metadata.json，不设上限，用户手动清）
 *  3. ★ 页级持久产物（artifacts/<bookId>/<章节key>/<缓存键>.webp，2026-10-03）：
 *     实时增强成功即永久落盘，任何作用域下最高优先显示（"只要有过且产物在就优先"）；
 *     缓存键自带完整指纹（档位/降噪/黑白/引擎）——改设置自动失效走新键，同键覆盖；
 *     总量受 artifactCacheLimitMb 上限控制，超限自动清最旧（用户拍板三件套：上限+清最旧+单书清理）。
 * API 30+ 用 WEBP_LOSSY，低版本自动 JPEG（系统限制，功能不受影响）。
 */
class SRDiskCache(
    private val cacheDir: File,
    /** 实时缓存上限提供者（字节）；默认 1000MB。来自用户设置（SrSettings.realtimeCacheLimitMb） */
    private val realtimeLimitBytes: () -> Long = { 1000L * 1024 * 1024 },
    /** 页级持久产物上限提供者（字节）；默认 1000MB。来自 SrSettings.artifactCacheLimitMb */
    private val artifactLimitBytes: () -> Long = { 1000L * 1024 * 1024 },
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
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 95, out)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
            }
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                // rename 失败兜底直写
                FileOutputStream(file).use { out ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 95, out)
                    } else {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
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

    /** 按章节 key 找批量产物（不依赖书名目录，O(1) 命中常见情况）。
     *  ★ 只读查询不建目录（2026-10-02）：不走 batchChapterDir 的 mkdirs，查询路径不得写文件系统 */
    fun getBatchPage(bookTitle: String, chapterKey: String, pageIndex: Int): Bitmap? {
        val dir = File(batchDir, "${DiskUtil.buildValidFilename(bookTitle)}/$chapterKey")
        return readBatchPageFile(dir, pageIndex)
    }

    /** 只判断产物文件是否存在（不解码整图）。批量断点续做跳页用它：
     *  getBatchPage 每次跳过都要解码整页，续做几百页时会白白解码几百张大图（慢且吃内存） */
    fun hasBatchPage(bookTitle: String, chapterKey: String, pageIndex: Int): Boolean {
        // 不走 batchChapterDir：它会 mkdirs，只读判断不该产生空目录
        val dir = File(batchDir, "${DiskUtil.buildValidFilename(bookTitle)}/$chapterKey")
        if (!dir.isDirectory) return false
        val stem = pageIndex.toString().padStart(3, '0')
        return File(dir, "$stem.webp").exists() || File(dir, "$stem.jpg").exists()
    }

    /** ★ 清除一本书的全部批量产物（2026-10-02，书架"清除增强产物"用）：
     *  chapterKey 自带 bookId 前缀（b<id>_），按前缀匹配无需枚举书的全部章节路径 */
    fun removeBatchBook(bookId: Long) {
        val prefix = "b${bookId}_"
        if (!batchDir.exists()) return
        try {
            batchDir.listFiles()?.forEach { bookDir ->
                bookDir.listFiles()?.forEach { chDir ->
                    if (chDir.isDirectory && chDir.name.startsWith(prefix)) chDir.deleteRecursively()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "removeBatchBook $bookId failed", e)
        }
    }

    /** 从 0 开始连续存在产物的页数（批量重跑/续做的进度基线） */
    fun countBatchDonePrefix(bookTitle: String, chapterKey: String, total: Int): Int {
        var n = 0
        while (n < total && hasBatchPage(bookTitle, chapterKey, n)) n++
        return n
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

    // ════ 页级持久产物（2026-10-03）：实时增强结果永久落盘 ════
    // 布局：artifacts/<bookId>/<章节key安全名>/<实时缓存键>.webp|jpg
    //  - 缓存键自带完整指纹（档位/降噪/黑白/引擎+页号），改设置自动走新键，同键覆盖
    //  - bookId 一级目录使"单书清理"为 O(1) 树删
    private val artifactDir: File
        get() = File(cacheDir, "artifacts").also { it.mkdirs() }

    private fun artifactChapterDir(bookId: Long, chapterKey: String): File =
        File(artifactDir, "$bookId/${DiskUtil.buildValidFilename(chapterKey)}")

    // ★ 布局 v2（2026-10-03）：artifacts/<bookId>/<章节key>/<指纹目录>/pNNN.webp
    //   指纹必须来自 **设置**（模型/降噪/黑白/引擎）而非 manager 运行态——
    //   冷启动先于引擎装载时 manager 的模型是 unknown，用 buildCacheKey 会在重开书时
    //   算出不同键 → 产物永远 miss → "退出重进不显示增强图"。
    private fun artifactPageFile(bookId: Long, chapterKey: String, fp: String, pageIndex: Int): File {
        val ext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "webp" else "jpg"
        return File(
            File(artifactChapterDir(bookId, chapterKey), DiskUtil.buildValidFilename(fp)),
            "${pageIndex.toString().padStart(4, '0')}.$ext",
        )
    }

    /** 实时增强成功后调用（须在 IO 线程）：落盘 + 超限自动清最旧 */
    fun putArtifact(bookId: Long, chapterKey: String, fp: String, pageIndex: Int, bitmap: Bitmap) {
        if (bookId < 0) return
        try {
            val file = artifactPageFile(bookId, chapterKey, fp, pageIndex)
            file.parentFile?.mkdirs()
            writeBitmapAtomic(file, bitmap)
            Log.i(TAG, "pc w ok p$pageIndex fp=$fp")
            evictArtifactsIfNeeded()
        } catch (e: Exception) {
            Log.e(TAG, "pc w fail p$pageIndex fp=$fp", e)
        }
    }

    /** 只判断产物文件是否存在（O(1) stat，不解码）——显示链 Fast 路径守卫用 */
    fun hasArtifact(bookId: Long, chapterKey: String, fp: String, pageIndex: Int): Boolean =
        bookId >= 0 && artifactPageFile(bookId, chapterKey, fp, pageIndex).exists()

    /** 本章是否已有任何页级产物（O(1) 目录 stat）——pollForResult 守卫用 */
    fun hasArtifactChapter(bookId: Long, chapterKey: String): Boolean =
        bookId >= 0 && artifactChapterDir(bookId, chapterKey).isDirectory

    fun getArtifact(bookId: Long, chapterKey: String, fp: String, pageIndex: Int): Bitmap? {
        val file = artifactPageFile(bookId, chapterKey, fp, pageIndex)
        if (!file.exists()) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "read artifact failed p$pageIndex", e)
            null
        }
    }

    /** 全库产物用量（张数, 字节）——面板产物管理显示用，IO 线程调用 */
    fun artifactUsage(): Pair<Int, Long> {
        if (!artifactDir.exists()) return 0 to 0L
        var count = 0
        var bytes = 0L
        artifactDir.walkTopDown().filter { it.isFile }.forEach {
            count++
            bytes += it.length()
        }
        return count to bytes
    }

    /** 单书清理（三件套之三）：删除 artifacts/<bookId> 整树 */
    fun removeArtifacts(bookId: Long) {
        if (bookId < 0) return
        try {
            File(artifactDir, bookId.toString()).deleteRecursively()
        } catch (e: Exception) {
            Log.e(TAG, "removeArtifacts $bookId failed", e)
        }
    }

    /** 三件套之二：总字节超上限时按 mtime 从旧到新删，直到回落上限内（IO 线程调用） */
    private fun evictArtifactsIfNeeded() {
        val limit = try {
            artifactLimitBytes().coerceAtLeast(16L * 1024 * 1024)
        } catch (_: Throwable) {
            return
        }
        var files = artifactDir.walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        if (total <= limit) return
        files = files.sortedBy { it.lastModified() }
        for (f in files) {
            if (total <= limit) break
            val len = f.length()
            if (f.delete()) total -= len
        }
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

    fun putBatchMetadata(bookTitle: String, chapterKey: String, pageCount: Int, bookId: Long, path: String, modelKey: String = "") {
        val dir = batchChapterDir(bookTitle, chapterKey)
        val json = org.json.JSONObject().apply {
            put("bookId", bookId)
            put("bookTitle", bookTitle)
            put("path", path)
            put("pageCount", pageCount)
            put("completedAt", System.currentTimeMillis())
            put("modelKey", modelKey)
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
        val modelKey: String = "",
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
                        modelKey = json.optString("modelKey"),
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "parse batch metadata failed: ${file.path}", e)
            }
        }
        return result
    }

    /** 移除章节产物；书目录空了顺带移除 */
    fun removeBatchChapter(bookTitle: String, chapterKey: String) {
        val dir = batchChapterDir(bookTitle, chapterKey)
        if (dir.exists()) dir.deleteRecursively()
        val bookDir = dir.parentFile
        if (bookDir != null && bookDir.exists() && bookDir.list()?.isEmpty() == true) {
            bookDir.delete()
        }
    }

    /** 章节起跑时写入模型指纹（区分"断点续做"与"换模型后须重算"） */
    fun putBatchChapterModelStamp(bookTitle: String, chapterKey: String, modelKey: String) {
        try {
            File(batchChapterDir(bookTitle, chapterKey), ".model").writeText(modelKey)
        } catch (_: Exception) {
        }
    }

    /** 进行中章节的模型指纹（.model 文件；完成章节读 metadata.json） */
    fun getBatchChapterModelStamp(bookTitle: String, chapterKey: String): String? = try {
        val f = File(batchChapterDir(bookTitle, chapterKey), ".model")
        if (f.isFile) f.readText().trim().takeIf { it.isNotEmpty() } else null
    } catch (_: Exception) {
        null
    }

    /** 已完成章节元数据里记录的模型（旧产物无此字段返回 null） */
    fun getBatchChapterModelKey(bookTitle: String, chapterKey: String): String? = try {
        val f = File(batchChapterDir(bookTitle, chapterKey), "metadata.json")
        if (f.isFile) org.json.JSONObject(f.readText()).optString("modelKey").takeIf { it.isNotEmpty() } else null
    } catch (_: Exception) {
        null
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
