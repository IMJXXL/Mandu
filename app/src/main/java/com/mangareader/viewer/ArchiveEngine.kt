package com.mangareader.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import tachiyomi.decoder.ImageDecoder as TachiImageDecoder
import okio.buffer
import okio.source
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import kotlinx.coroutines.launch
import android.provider.DocumentsContract
import android.util.Log
import com.mangareader.mobi.FdSeekable
import com.mangareader.mobi.MobiFile
import com.mangareader.mobi.RafSeekable
import com.mangareader.zip.CommonsZipHelper
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import com.github.junrar.Archive as RarArchive
import com.github.junrar.rarfile.FileHeader as RarFileHeader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
/**
 * ★ §24 P3：动图类型枚举（GIF / WebP / APNG / NONE）
 * - 由 [com.mangareader.viewer.ArchiveEngine.peekAnimKind] 推断
 * - [com.mangareader.native.WebtoonReaderView] 用它决定走哪条 Drawable 创建路径
 */
enum class AnimKind { NONE, GIF, WEBP, APNG }

class ArchiveEngine(private val ctx: Context) {
    companion object {
        private const val TAG = "ArchiveEngine"
        // ★ listPages 近期触达路径（容量 32 LRU）：PageIndexWarmer 避让用——
        //   正在被打开的书/刚被扫描的书不再被预热重复全量扫（慢 SD 上双扫 25s×2 抢 IO）
        private val recentListPaths = object : LinkedHashMap<String, Unit>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>): Boolean = size > 32
        }
        fun isRecentlyListed(path: String): Boolean = synchronized(recentListPaths) { recentListPaths.containsKey(path) }

        private var debugLogBuffer = StringBuilder()
        private var debugLogFile: File? = null
        // ★ B7：落盘节流——buffer 未满 8KB 且距上次落盘 <3s 时攒着，热路径不再每次 open/write/close
        private const val DEBUG_FLUSH_MIN_INTERVAL_MS = 3000L
        private const val DEBUG_FLUSH_MIN_BYTES = 8 * 1024
        @Volatile private var lastFlushAt = 0L
        private val rarSortComparator = Comparator<com.github.junrar.rarfile.FileHeader> { a, b ->

            val depthA = com.mangareader.util.ComicSortUtil.getPathDepth(a.fileName)
            val depthB = com.mangareader.util.ComicSortUtil.getPathDepth(b.fileName)
            if (depthA != depthB) depthA - depthB
            else com.mangareader.zip.CommonsZipHelper.compareNatural(a.fileName, b.fileName)
        }

        /**
         * ★ 动图页跳过降采样（对齐上游 animated→ORIGIN）：inSampleSize 对动图不省任何
         *   逐帧解码成本，只毁首帧画质。条漫模式下动图页按静帧显示，全分辨率观感更好。
         *   返回 false 的情况：静图，或头部信息不足（保守按静图处理）。
         */
        fun isAnimatedBytes(raw: ByteArray): Boolean = when (animKindFromHead(raw, raw.size)) {
            AnimKind.GIF, AnimKind.WEBP, AnimKind.APNG -> true
            else -> false
        }

        /** 从头部字节判动图；返回 null 表示头部信息尚不足（需继续读） */
        private fun animKindFromHead(head: ByteArray, len: Int): AnimKind? {
            if (len < 12) return null
            // GIF：magic 即动图格式（GIF 必然可播；静止单帧 GIF 漫画场景罕见，与旧全文件判定一致性可接受）
            if (head[0] == 0x47.toByte() && head[1] == 0x49.toByte() && head[2] == 0x46.toByte() && head[3] == 0x38.toByte()) {
                return AnimKind.GIF
            }
            // PNG：扫 chunk 找 acTL（APNG 标志）；遇 IDAT 仍无 acTL 即静图；IEND 终局
            if (head[0] == 0x89.toByte() && head[1] == 0x50.toByte() && head[2] == 0x4E.toByte() && head[3] == 0x47.toByte()) {
                var pos = 8
                var sawIdat = false
                while (pos + 8 <= len) {
                    val type = String(head, pos + 4, 4, Charsets.US_ASCII)
                    if (type == "acTL") return AnimKind.APNG
                    if (type == "IDAT") sawIdat = true
                    if (type == "IEND") return AnimKind.NONE
                    val size = ((head[pos].toInt() and 0xFF) shl 24) or ((head[pos + 1].toInt() and 0xFF) shl 16) or
                            ((head[pos + 2].toInt() and 0xFF) shl 8) or (head[pos + 3].toInt() and 0xFF)
                    if (size < 0) break
                    pos += 12 + size
                }
                // 头部窗口内没扫到结论：已见 IDAT 无 acTL → 静图；否则继续读（返回 null 驱动外层续流）
                return if (sawIdat) AnimKind.NONE else null
            }
            // WEBP(RIFF....WEBP)：VP8X 块 flags bit1 = Animation
            if (head[0] == 0x52.toByte() && head[1] == 0x49.toByte() && head[2] == 0x46.toByte() && head[3] == 0x46.toByte()
                && head[8] == 0x57.toByte() && head[9] == 0x45.toByte() && head[10] == 0x42.toByte() && head[11] == 0x50.toByte()
            ) {
                var pos = 12
                while (pos + 8 <= len) {
                    val tag = String(head, pos, 4, Charsets.US_ASCII)
                    val size = (head[pos + 4].toInt() and 0xFF) or ((head[pos + 5].toInt() and 0xFF) shl 8) or
                            ((head[pos + 6].toInt() and 0xFF) shl 16) or ((head[pos + 7].toInt() and 0xFF) shl 24)
                    when (tag) {
                        "VP8X" -> {
                            if (pos + 8 >= len) return null
                            val flags = head[pos + 8].toInt()
                            return if (flags and 0x02 != 0) AnimKind.WEBP else AnimKind.NONE
                        }
                        "VP8 ", "VP8L" -> return AnimKind.NONE  // 无 VP8X 的简单图必是静图
                        "ANIM" -> return AnimKind.WEBP          // 老式动画块直判
                    }
                    if (size <= 0) break
                    pos += 8 + size + (size and 1)
                }
                return null
            }
            // JPEG 等其他格式：头部即可判非动图
            return AnimKind.NONE
        }

        fun flushLog(ctx: Context) {
            try {
                val now = System.currentTimeMillis()
                synchronized(debugLogBuffer) {
                    if (debugLogBuffer.isEmpty()) return
                    // ★ B7 节流：小且近的日志攒着下次一起落
                    if (debugLogBuffer.length < DEBUG_FLUSH_MIN_BYTES &&
                        now - lastFlushAt < DEBUG_FLUSH_MIN_INTERVAL_MS) return
                    if (debugLogFile == null) {
                        debugLogFile = File(ctx.filesDir, "cover_debug.log")
                    }
                    val f = debugLogFile ?: return
                    synchronized(f) {
                        f.appendText(debugLogBuffer.toString())
                    }
                    debugLogBuffer.clear()
                    lastFlushAt = now
                }
            } catch (e: Exception) {
                Log.e(TAG, "flushLog failed: ${e.message}")
                synchronized(debugLogBuffer) { debugLogBuffer.clear() }
            }
        }

        fun getDebugLog(ctx: Context): String {
            flushLog(ctx)
            val f = debugLogFile ?: return ""
            if (!f.exists()) return ""
            val lines = f.readLines()
            val sb = StringBuilder()
            var last = ""
            var cnt = 0
            for (l in lines) {
                if (l == last) { cnt++ } else {
                    sb.appendLine(if (cnt > 1) "$last *$cnt" else last)
                    last = l; cnt = 1
                }
            }
            if (cnt > 0) sb.appendLine(if (cnt > 1) "$last *$cnt" else last)
            return sb.toString()
        }

        // ★ 三兄弟都锁 debugLogBuffer：pager 预载/条漫并行预探多线程并发调用，
        //  StringBuilder 无锁并发 append 会产错乱日志甚至 AIOOBE
        fun d(tag: String, msg: String) {
            Log.d(tag, msg)
            synchronized(debugLogBuffer) { debugLogBuffer.appendLine("${System.currentTimeMillis()} D/$tag: $msg") }
        }

        fun w(tag: String, msg: String) {
            Log.w(tag, msg)
            synchronized(debugLogBuffer) { debugLogBuffer.appendLine("${System.currentTimeMillis()} W/$tag: $msg") }
        }

        fun e(tag: String, msg: String) {
            Log.e(tag, msg)
            synchronized(debugLogBuffer) { debugLogBuffer.appendLine("${System.currentTimeMillis()} E/$tag: $msg") }
        }

        internal val NESTED_ARCHIVE_EXTS = setOf(
            "mobi", "azw", "azw3", "prc", "pdb",
            "zip", "cbz", "rar", "cbr", "7z", "cb7", "tar", "cbt", "tgz", "txz",
        )

        internal fun isNestedArchive(n: String): Boolean {
            val lower = n.lowercase()
            return NESTED_ARCHIVE_EXTS.any { lower.endsWith(".$it") }
        }

        internal fun isFlatZip(root: ZipDirNode): Boolean {
            return root.children.isEmpty() || root.children.all { !it.isDir }
        }
    }

    private val config by lazy { com.mangareader.data.ReaderConfig.load(ctx) }

    /** ★ 书内作用域 bookId（Reader 创建实例时注入）：pdfQuality 等书级配置在引擎读取点
     *  必须按书取值（此前恒读全局，书内设置静默失效——审查 AE5）。Scanner 自建实例保持 -1=全局 */
    @Volatile
    var scopeBookId: Long = -1L

    var zipPasswdProvider: (suspend ((String) -> Boolean) -> String?)? = null

    private val isEink by lazy { config.isEinkMode }
    private val forceFullDecode: Boolean get() {
        val prefs = ctx.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE)
        return prefs.getBoolean("forceFullDecode", false) && !prefs.getBoolean("isEinkMode", false)
    }
    private val downsampleThreshold: Long get() {
        if (isEink) return 6_291_456L
        val prefs = ctx.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE)
        val tierName = if (prefs.contains("downsampleTier")) prefs.getString("downsampleTier", "STANDARD") else if (prefs.getBoolean("forceFullDecode", false)) "ORIGINAL" else "STANDARD"
        return try { com.mangareader.data.ReaderConfig.DownsampleTier.valueOf(tierName ?: "STANDARD").threshold } catch (_: Exception) { 6_291_456L }
    }
    private val maxDecodeSize by lazy { if (isEink) config.einkDecodeSize else 4096 }
    private val maxCoverSize by lazy {
        val dpi = ctx.resources.displayMetrics.densityDpi
        (dpi * 1.5f).toInt().coerceIn(480, 2048)
    }

    data class PageMeta(val index: Int, val source: String, val ext: String, val localIdx: Int = 0, val splitSide: Int = 0, val name: String = "", val segmentIndex: Int = 0, val segmentTotal: Int = 0, val originalPageIdx: Int = 0)

    /**
     * ★ SMB/WebDAV 路径守卫（smb_embed_lv119 任务4.4/14.5）：远程路径严禁进入 ArchiveEngine，
     * 远程书籍由 smbbridge/webdavbridge 的 RemotePageSupply 会话处理。require 抛出即视为误路由 bug。
     * 本地路径（a:/d:/z:/content://）零改动。
     */
    private fun requireNotRemote(path: String) {
        require(
            !path.startsWith("smb:") && !path.startsWith("webdav:") &&
                !path.startsWith("cv:smb:") && !path.startsWith("cv:webdav:"),
        ) { "Remote path must not enter ArchiveEngine: ${path.take(48)}" }
    }

    data class Item(val name: String, val auth: String, val tree: String, val doc: String, val dir: Boolean, val subPath: String = "")
    data class ZipDirNode(
        val name: String,
        val entryPath: String,
        val isDir: Boolean,
        val children: MutableList<ZipDirNode> = mutableListOf(),
        val imageIndices: MutableList<Int> = mutableListOf()
    )

    // ★ B3 修复：位图缓存由"按张数限容"改为 android.util.LruCache 按 byteCount 计重。
    //   旧 LinkedHashMap(60/100 张) 在 4K 大图场景可达数 GB（60×33MB）OOM；
    //   LruCache 内部自带同步，sizeOf 返回 byteCount，上限取保守字节值。

    private val bitmapCache = object : android.util.LruCache<String, Bitmap>(96 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = if (value.isRecycled) 0 else value.byteCount
    }
    private val sharedBitmapCache = object : android.util.LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = if (value.isRecycled) 0 else value.byteCount
    }

    // ★ B1（2026-09-25）：分割书段位图单张 ~20MB，64MB 只缓存 3 张、回滚即重渲染。
    //   分割实际生效时把共享位图缓存提升到 128MB（约 6 段）；仅大内存设备启用
    //   （非 isLowRamDevice 且总内存 ≥4GB），墨水屏等低内存机维持 64MB。
    //   引擎随书创建、退出即释放，无需回落逻辑；只升不降避免热切换抖动。
    private var segmentBoostEvaluated = false
    fun setSegmentCacheBoost(active: Boolean) {
        if (!active || segmentBoostEvaluated) return
        segmentBoostEvaluated = true
        try {
            val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            if (!am.isLowRamDevice && mi.totalMem >= 4L * 1024 * 1024 * 1024) {
                sharedBitmapCache.resize(128 * 1024 * 1024)
                Log.i(TAG, "segment cache boost: sharedBitmapCache 64MB -> 128MB")
            }
        } catch (_: Exception) {}
    }
    // ★ 渲染槽 2→4（2026-09-26）：段渲染（PdfRenderer 矩阵裁剪）单次 100-300ms，2 槽是
    //   预载吞吐瓶颈（±6 段窗口 3s+ 才填满，滑动追不上=「往后全是没加载的」）。4 槽并行
    //   配合 Webtoon 预载分批(4)，段窗口 <1s 填满。每槽独立 PdfRenderer/fd，互不竞争。
    private val renderSlotLocks = arrayOf(Any(), Any(), Any(), Any())
    private val renderSlotRenderer = arrayOfNulls<android.graphics.pdf.PdfRenderer>(4)
    private val renderSlotPfd = arrayOfNulls<android.os.ParcelFileDescriptor>(4)
    private val renderSlotUri = arrayOfNulls<Uri>(4)
    private val renderSlotCounter = java.util.concurrent.atomic.AtomicInteger()
    private val inflightSegments = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Bitmap?>>()
    private val segFallbackMarked = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    /** PDF 文档按 docKey 的打开互斥（防首屏并发段请求竞争重复 pdfOpen） */
    private val pdfOpenLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val pdfSegOkMarked = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    @Volatile
    private var lastEpubScanUsedSpine = false
    private val sortedImageNamesCache = object : LinkedHashMap<String, List<String>>(30, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?): Boolean = size > 30
    }
    private val coverCache = object : LinkedHashMap<String, Bitmap>(50, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            val limit = if (isEink) 20 else 50
            return size > limit
        }
    }

    private val coverDir: File by lazy {
        File(ctx.filesDir, "covers").also { if (!it.exists()) it.mkdirs() }
    }

    private val pageCacheDir: File by lazy {
        File(ctx.filesDir, "page_cache").also { if (!it.exists()) it.mkdirs() }
    }

    fun cacheGet(key: String): Bitmap? {
        val bmp = bitmapCache.get(key) ?: return null
        if (bmp.isRecycled) {
            // ★ C4 毒位图过滤（白屏工单 v2.4）：已被 recycle 的位图从引擎缓存剔除并按 miss 处理，
            //   掐断"毒位图 → 视图自愈重载 → 又是毒位图"的无限循环
            bitmapCache.remove(key)
            Log.w(TAG, "cacheGet: evict recycled bitmap key=$key")
            return null
        }
        return bmp
    }

    private fun cachePut(key: String, bmp: Bitmap) { bitmapCache.put(key, bmp) }

    fun removeBitmapsByValue(bitmaps: Collection<Bitmap>) {
        if (bitmaps.isEmpty()) return
        val idSet = bitmaps.map { System.identityHashCode(it) }.toHashSet()
        var n1 = 0
        bitmapCache.snapshot().forEach { (k, v) ->
            if (System.identityHashCode(v) in idSet) { bitmapCache.remove(k); n1++ }
        }
        var n2 = 0
        sharedBitmapCache.snapshot().forEach { (k, v) ->
            if (System.identityHashCode(v) in idSet) { sharedBitmapCache.remove(k); n2++ }
        }
        if (n1 + n2 > 0) Log.w(TAG, "removeBitmapsByValue: engineCache=$n1 sharedCache=$n2 total=${bitmaps.size}")
    }

    fun getCachedBitmap(key: String): Bitmap? {
        val bmp = sharedBitmapCache.get(key) ?: return null
        if (bmp.isRecycled) {
            sharedBitmapCache.remove(key)
            Log.w(TAG, "getCachedBitmap: evict recycled key=$key")
            return null
        }
        return bmp
    }

    fun putCachedBitmap(key: String, bmp: Bitmap) { sharedBitmapCache.put(key, bmp) }

    // ★ 2026-10-05 mobi 快路径页表缓存：同 cacheKey 的 scan/load 共用同一 MobiFile，
    //   防"快路径页表 vs libmobi 页表"中途换表导致索引错位；只存解析结果不持 fd
    private val mobiFastCache = object : LinkedHashMap<String, MobiFile>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MobiFile>): Boolean = size > 4
    }

    internal fun mobiFastCacheGet(cacheKey: String): MobiFile? = synchronized(mobiFastCache) { mobiFastCache[cacheKey] }

    internal fun mobiFastCachePut(cacheKey: String, mf: MobiFile) = synchronized(mobiFastCache) { mobiFastCache[cacheKey] = mf }

    internal fun clearMobiFastCache() = synchronized(mobiFastCache) { mobiFastCache.clear() }

    // ★ 2026-10-11 黑页兜底：每 (文件,页) 只补试一次的守卫（LRU 256，防坏书反复重试拖慢）
    private val mobiPageRetryKeys = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean = size > 256
    }

    fun clearNativeCache() {
        com.mangareader.native.NativeEngine.clearCache()
        com.mangareader.native.NativeEngine.clearMobiCache()
        clearMobiFastCache()  // ★ 快路径页表随会话失效
        // ★ B1：换书/清缓存时释放 RAR/7z/TAR 的 fd 缓存与预读 LRU
        clearRarFdCache()
    }

    fun clearCache() {
        for (slot in renderSlotLocks.indices) {
            synchronized(renderSlotLocks[slot]) {
                try { renderSlotRenderer[slot]?.close() } catch (_: Exception) {}
                try { renderSlotPfd[slot]?.close() } catch (_: Exception) {}
                renderSlotRenderer[slot] = null
                renderSlotPfd[slot] = null
                renderSlotUri[slot] = null
            }
        }
        bitmapCache.evictAll()
        synchronized(coverCache) {
            coverCache.clear()
        }
        clearNativeCache()
    }

    fun clearCoverCache() {
        coverDir.listFiles()?.forEach { it.delete() }
    }

    fun deleteCoverCache(coverPath: String) {
        val hash = md5(coverPath)
        File(coverDir, "$hash.thumb").delete()
    }

    // ★ 自定义封面（2026-09-26）：私有文件按书路径 MD5 确定性寻址（重设覆盖不堆积），
    //   与默认缩略图同目录同套生命周期（签名降级清 covers 后由 Library 孤儿自愈回退默认封面）。
    fun customCoverFileName(bookPath: String): String = "custom_${md5(bookPath)}.jpg"

    fun customCoverFile(bookPath: String): File = File(coverDir, customCoverFileName(bookPath))

    /** 裁剪结果落盘（JPEG 90），返回 customCoverPath 值（"custom:<fileName>"）。
     *  文件名带时间戳：每次换封面 DB 值变化 → BookCard/弹窗的 memory/disk key 随之变化，
     *  否则同名覆盖写会让 Coil 磁盘缓存永远命中旧字节（换封面后显示旧图）。旧文件由宿主删。 */
    fun saveCustomCoverBitmap(bmp: Bitmap, bookPath: String): String? {
        return try {
            if (!coverDir.exists()) coverDir.mkdirs()
            val f = File(coverDir, "custom_${md5(bookPath)}_${System.currentTimeMillis()}.jpg")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            "custom:${f.name}"
        } catch (e: Exception) {
            Log.e(TAG, "saveCustomCoverBitmap: ${e.message}")
            null
        }
    }

    fun deleteCustomCoverByPath(customCoverPath: String) {
        try { File(coverDir, customCoverPath.removePrefix("custom:")).delete() } catch (_: Exception) {}
    }

    /**
     * Reader 卷级缩略图直读（covers/<md5(chPath)>.thumb，enterReader 后台生成的那份）：
     * 封面编辑器浏览分卷时，打开过的卷命中即零解析显示，不再走 loadCoverForItem 的
     * item: 前缀缓存重新递归（两套 key 不同导致白查，2026-09-26 用户实测反馈）。
     * ★ 解码钳制 ~256px 长边：thumb 文件是全尺寸首页 JPEG（原尺寸解码一张 10MB+ 位图，
     *   几格就把缩略图 LruCache 挤爆 → 滚动回来缓存 miss 重解 → 封面"先有后无"）。
     */
    fun loadVolThumbByPath(chPath: String): Bitmap? = try {
        val f = File(coverDir, "${md5(chPath)}.thumb")
        if (!f.exists() || f.length() <= 0L) null else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
                var s = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (s * 2) >= 256) s *= 2
                BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
            }
        }
    } catch (_: Exception) { null }

    /**
     * 目录面板子卷（dir 型文件夹卡，如 A→a1→aa1）的自定义封面批量查询（2026-09-26）。
     * 存储复用 covers/ 目录，确定性前缀 custom_<md5(chPath)>_*.jpg（chPath 唯一标识子卷，
     * 重扫后同路径同名 → 用户设置自动复用）；无 DB 字段，文件存在即生效。
     * 返回 chPath → ("custom:<fileName>", lastModified)：lastModified 供调用方注入缓存 key，
     * 同名覆盖写后旧缓存不再命中。一次列 covers/ 目录匹配全部前缀（防每卷一次 listFiles 的 O(n²)）。
     */
    fun subVolCoverDataBatch(chPaths: List<String>): Map<String, Pair<String, Long>> {
        if (chPaths.isEmpty()) return emptyMap()
        return try {
            val files = coverDir.listFiles() ?: return emptyMap()
            val out = HashMap<String, Pair<String, Long>>()
            for (cp in chPaths) {
                val base = "custom_${md5(cp)}"
                val f = files.filter { it.name == "$base.jpg" || it.name.startsWith("${base}_") }
                    .maxByOrNull { it.lastModified() } ?: continue
                if (f.length() > 0L) out[cp] = "custom:${f.name}" to f.lastModified()
            }
            out
        } catch (_: Exception) { emptyMap() }
    }

    /** 选图网格缩略图：字节 → inSampleSize 解码到 ~targetPx（缓存由调用方管理） */
    fun decodeThumbnail(data: ByteArray, targetPx: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
            BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (oom: OutOfMemoryError) {
            System.gc(); null
        } catch (_: Exception) { null }
    }

    /** ★ 封面选图（2026-09-26）：dir 树内任意文件项的字节（封面内容浏览器用） */
    fun loadDirItemBytes(item: Item): ByteArray? {
        return try {
            val tree = makeTree(item.auth, item.tree)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, item.doc)
            ctx.contentResolver.openInputStream(docUri)?.use { it.readBytes() }
        } catch (_: Exception) { null }
    }

    fun readComicInfoXml(bookPath: String): ByteArray? {
        return when {
            bookPath.startsWith("a:") -> readComicInfoFromArchive(bookPath)
            bookPath.startsWith("d:") -> readComicInfoFromDir(bookPath)
            bookPath.startsWith("z:") -> readComicInfoFromZipSubDir(bookPath)
            else -> null
        }
    }

    private fun readComicInfoFromArchive(bookPath: String): ByteArray? {
        val p = parsePath(bookPath) ?: return null
        val tree = makeTree(p.auth, p.tree)
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, p.doc)
        val cacheKey = uri.toString()
        if (!com.mangareader.native.NativeEngine.isOpenZip(cacheKey)) {
            val realFile = getRealFile(uri)
            val realPath = realFile?.absolutePath ?: ""
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val fd = pfd.detachFd()
            synchronized(pfdKeepAlive) { pfdKeepAlive[cacheKey] = pfd }
            try { com.mangareader.native.NativeEngine.openZipWithFd(cacheKey, fd, realPath) } catch (_: Exception) { return null }
        }
        val allNames = try { com.mangareader.native.NativeEngine.getZipEntryNames(cacheKey) } catch (_: Exception) { null }
            ?: return null
        val targetName = allNames.find { it.equals("ComicInfo.xml", ignoreCase = true) } ?: return null
        val entryIndex = try { com.mangareader.native.NativeEngine.findZipEntryByName(cacheKey, targetName) } catch (_: Exception) { -1 }
        if (entryIndex < 0) return null
        return try { com.mangareader.native.NativeEngine.readZipEntry(cacheKey, entryIndex) } catch (_: Exception) { null }
    }

    private fun readComicInfoFromDir(bookPath: String): ByteArray? {
        val p = parsePath(bookPath) ?: return null
        val tree = makeTree(p.auth, p.tree)
        val dirUri = DocumentsContract.buildDocumentUriUsingTree(tree, p.doc)
        try {
            val child = DocumentsContract.buildChildDocumentsUriUsingTree(tree, p.doc)
            ctx.contentResolver.query(child, null, null, null, null)?.use { c ->
                val ni = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val ii = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                while (c.moveToNext()) {
                    val n = c.getString(ni) ?: continue
                    if (n.equals("ComicInfo.xml", ignoreCase = true)) {
                        val id = c.getString(ii) ?: continue
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        return ctx.contentResolver.openInputStream(fileUri)?.use { it.readBytes() }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun readComicInfoFromZipSubDir(bookPath: String): ByteArray? {
        val inner = bookPath.removePrefix("z:")
        val sepIdx = inner.indexOf("//")
        if (sepIdx < 0) return null
        var archivePath = inner.substring(0, sepIdx)
        if (!archivePath.startsWith("a:") && !archivePath.startsWith("d:")) {
            archivePath = "a:$archivePath"
        }
        val subPath = inner.substring(sepIdx + 2)
        val p = parsePath(archivePath) ?: return null
        val tree = makeTree(p.auth, p.tree)
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, p.doc)
        val cacheKey = uri.toString()
        if (!com.mangareader.native.NativeEngine.isOpenZip(cacheKey)) {
            val realFile = getRealFile(uri)
            val realPath = realFile?.absolutePath ?: ""
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val fd = pfd.detachFd()
            synchronized(pfdKeepAlive) { pfdKeepAlive[cacheKey] = pfd }
            try { com.mangareader.native.NativeEngine.openZipWithFd(cacheKey, fd, realPath) } catch (_: Exception) { return null }
        }
        val allNames = try { com.mangareader.native.NativeEngine.getZipEntryNames(cacheKey) } catch (_: Exception) { null }
            ?: return null
        val prefix = if (subPath.isNotEmpty()) "$subPath/" else ""
        val target = (prefix + "ComicInfo.xml").lowercase()
        val targetName = allNames.find { it.lowercase() == target } ?: return null
        val entryIndex = try { com.mangareader.native.NativeEngine.findZipEntryByName(cacheKey, targetName) } catch (_: Exception) { -1 }
        if (entryIndex < 0) return null
        return try { com.mangareader.native.NativeEngine.readZipEntry(cacheKey, entryIndex) } catch (_: Exception) { null }
    }

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun loadCoverThumbBytes(cover: String): ByteArray? {
        try {
            val file = File(coverDir, "${md5(cover)}.thumb")
            if (file.exists()) return file.readBytes()
        } catch (_: Exception) {}
        return null
    }

    fun saveCoverThumbBytes(cover: String, bytes: ByteArray) {
        try {
            val file = File(coverDir, "${md5(cover)}.thumb")
            file.writeBytes(bytes)
        } catch (_: Exception) {}
    }

    private fun saveCoverToDisk(key: String, bmp: Bitmap) {
        try {
            evictOldCoverFiles()
            val file = File(coverDir, "${md5(key)}.thumb")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        } catch (e: Exception) {
            Log.e(TAG, "saveCoverToDisk: ${e.message}")
        }
    }

    private fun loadCoverFromDisk(key: String): Bitmap? {
        try {
            val file = File(coverDir, "${md5(key)}.thumb")
            if (file.exists()) {
                file.setLastModified(System.currentTimeMillis())
                return BitmapFactory.decodeFile(file.absolutePath)
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadCoverFromDisk: ${e.message}")
        }
        return null
    }


    private fun evictOldCoverFiles() {
        // ★ 封面缓存无上限（用户要求）：不再淘汰，封面是持久资产
    }

    private val pageListCache = object : LinkedHashMap<String, List<PageMeta>>(20, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<PageMeta>>?) = size > 20
    }

    // ═══ E6：d: 文件夹书页表磁盘索引（cacheDir/dir_page_index/）═══
    // 失效策略（用户裁定）：索引命中秒开 + 后台轻校验——校验只做一次目录条目名
    // 查询（单 binder 往返），摘要不一致即后台重建；当前会话不中断，下次打开生效。

    private val dirIndexBgScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    private fun dirIndexFile(path: String): File =
        File(File(ctx.cacheDir, "dir_page_index"), sha1Hex(path) + ".json")

    private fun sha1Hex(s: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 目录条目名摘要：条目数 + 名字哈希和（名字集合不变 = 页表有效）。查询失败返回 null（保守跳过校验）。 */
    private fun dirNamesDigest(tree: Uri, doc: String): String? {
        return try {
            val child = DocumentsContract.buildChildDocumentsUriUsingTree(tree, doc)
            ctx.contentResolver.query(
                child,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                val ni = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                var count = 0
                var hash = 0L
                while (c.moveToNext()) {
                    val n = c.getString(ni) ?: continue
                    if (n.isEmpty()) continue
                    if (isImg(n) || isComic(n)) {
                        count++
                        hash += n.hashCode().toLong()
                    }
                }
                "n=$count,h=$hash"
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readDirPageIndex(path: String): List<PageMeta>? {
        val f = dirIndexFile(path)
        if (!f.isFile || f.length() <= 0L) return null
        return try {
            val obj = org.json.JSONObject(f.readText())
            val arr = obj.getJSONArray("pages")
            val out = ArrayList<PageMeta>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    PageMeta(
                        index = o.getInt("index"),
                        source = o.getString("source"),
                        ext = o.getString("ext"),
                        localIdx = o.optInt("localIdx", 0),
                        splitSide = o.optInt("splitSide", 0),
                        name = o.optString("name", ""),
                    ),
                )
            }
            if (out.isEmpty()) null else {
                f.setLastModified(System.currentTimeMillis())
                out
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeDirPageIndex(path: String, tree: Uri, doc: String, pages: List<PageMeta>) {
        try {
            val dir = File(ctx.cacheDir, "dir_page_index")
            dir.mkdirs()
            val digest = dirNamesDigest(tree, doc)
            val arr = org.json.JSONArray()
            pages.forEach { pm ->
                arr.put(
                    org.json.JSONObject().apply {
                        put("index", pm.index)
                        put("source", pm.source)
                        put("ext", pm.ext)
                        put("localIdx", pm.localIdx)
                        put("splitSide", pm.splitSide)
                        put("name", pm.name)
                    },
                )
            }
            val obj = org.json.JSONObject().apply {
                put("digest", digest ?: "")
                put("pages", arr)
            }
            // ★ tmp 名带 nanoTime（对齐 :799 archive 版）：warmer/阅读器并发写同一目录索引时
            //   固定名互踩会产生坏 JSON（审查 P2-3）
            val tmp = File(dir, sha1Hex(path) + "-" + System.nanoTime() + ".tmp")
            tmp.writeText(obj.toString())
            val dest = dirIndexFile(path)
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            trimDirPageIndex(dir)
        } catch (_: Exception) {
        }
    }

    private fun trimDirPageIndex(dir: File) {
        try {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
            if (files.size <= 256) return
            files.sortedBy { it.lastModified() }.take(files.size - 256).forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    /** ★ 页表索引是否已落盘（d:/a: 有磁盘索引；远程书恒 true=无事可做）。PageIndexWarmer 用 */
    fun pageIndexCached(path: String): Boolean = when {
        path.startsWith("d:") -> dirIndexFile(path).let { it.isFile && it.length() > 0L }
        path.startsWith("a:") -> archiveIndexFile(path).let { it.isFile && it.length() > 0L }
        else -> true
    }

    // ═══ E6 同款：a: 压缩书页表磁盘索引（cacheDir/arc_page_index/）═══
    // 失效戳 = SAF 文档 SIZE（文件级语义，可靠）；校验失败（查询不到/为 0）保守信任旧索引，
    // 真正的兜底在页加载失败（用户换文件同尺寸的概率远小于每次开书 25s 的代价）。

    private fun archiveIndexFile(path: String): File =
        File(File(ctx.cacheDir, "arc_page_index"), sha1Hex(path) + ".json")

    private fun archiveSizeStamp(tree: Uri, doc: String): Long = try {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, doc)
        ctx.contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_SIZE),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L
    } catch (_: Exception) { -1L }

    private fun readArchivePageIndex(path: String): List<PageMeta>? {
        val f = archiveIndexFile(path)
        if (!f.isFile || f.length() <= 0L) return null
        return try {
            val obj = org.json.JSONObject(f.readText())
            // ★ 尺寸戳校验：文件被替换（size 变了）→ 旧页表作废回退真扫（真扫分支会重写索引）。
            //   此前只读 pages 不验戳，后台"重建"经本函数磁盘命中又拿回旧表盖新戳 = 空转固化永生
            val p0 = parsePath(path)
            if (p0 != null) {
                val stored = obj.optLong("size", -1L)
                if (stored > 0L) {
                    val live = archiveSizeStamp(makeTree(p0.auth, p0.tree), p0.doc)
                    if (live > 0L && live != stored) return null
                }
            }
            val arr = obj.getJSONArray("pages")
            val out = ArrayList<PageMeta>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    PageMeta(
                        index = o.getInt("index"),
                        source = o.getString("source"),
                        ext = o.getString("ext"),
                        localIdx = o.optInt("localIdx", 0),
                        splitSide = o.optInt("splitSide", 0),
                        name = o.optString("name", ""),
                    ),
                )
            }
            if (out.isEmpty()) null else {
                f.setLastModified(System.currentTimeMillis())
                out
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeArchivePageIndex(path: String, tree: Uri, doc: String, pages: List<PageMeta>) {
        try {
            val dir = File(ctx.cacheDir, "arc_page_index")
            dir.mkdirs()
            val arr = org.json.JSONArray()
            pages.forEach { pm ->
                arr.put(
                    org.json.JSONObject().apply {
                        put("index", pm.index)
                        put("source", pm.source)
                        put("ext", pm.ext)
                        put("localIdx", pm.localIdx)
                        put("splitSide", pm.splitSide)
                        put("name", pm.name)
                    },
                )
            }
            val obj = org.json.JSONObject().apply {
                put("size", archiveSizeStamp(tree, doc))
                put("pages", arr)
            }
            // tmp 名带唯一后缀：warmer 与阅读器是不同 engine 实例，固定名会并发互踩产坏 JSON
            val tmp = File(dir, sha1Hex(path) + "-" + System.nanoTime() + ".tmp")
            tmp.writeText(obj.toString())
            val dest = archiveIndexFile(path)
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            trimDirPageIndex(dir)
        } catch (_: Exception) {
        }
    }

    private fun scheduleArchiveIndexValidate(path: String, tree: Uri, doc: String) {
        dirIndexBgScope.launch {
            try {
                val live = archiveSizeStamp(tree, doc)
                val f = archiveIndexFile(path)
                val stored = if (f.isFile) {
                    try { org.json.JSONObject(f.readText()).optLong("size", -1L) } catch (_: Exception) { -1L }
                } else -1L
                if (live <= 0L || stored <= 0L || stored == live) return@launch  // 未知/一致：不动
                // 尺寸变了：旧索引已由 readArchivePageIndex 的尺寸戳校验判定失效，
                // 下次 listPages 直接走真扫并重写索引——这里只踢内存缓存防当前会话续用旧表
                synchronized(pageListCache) { pageListCache.remove(path) }
                d(TAG, "arcPageIndex stale total=- path=${path.takeLast(30)}")
            } catch (_: Exception) {
            }
        }
    }

    private fun scheduleDirIndexValidate(path: String, tree: Uri, doc: String) {
        dirIndexBgScope.launch {
            try {
                val live = dirNamesDigest(tree, doc) ?: return@launch
                val f = dirIndexFile(path)
                val stored = if (f.isFile) {
                    try { org.json.JSONObject(f.readText()).optString("digest", "") } catch (_: Exception) { "" }
                } else ""
                if (stored.isEmpty() || stored != live) {
                    // 目录变了（或旧索引无摘要）：后台重建 + 落盘；当前会话保持原页表，下次打开生效
                    val fresh = scanDir(tree, doc)
                    if (fresh.isNotEmpty()) writeDirPageIndex(path, tree, doc, fresh)
                    d(TAG, "dirPageIndex refreshed total=${fresh.size} path=${path.takeLast(30)}")
                } else {
                    f.setLastModified(System.currentTimeMillis())
                }
            } catch (_: Exception) {
            }
        }
    }

    private val zipEntryCache = object : LinkedHashMap<String, List<java.util.zip.ZipEntry>>(10, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<java.util.zip.ZipEntry>>?) = size > 10
    }

    // Cache for ZIP files copied from ContentResolver (key: URI, value: cached File)
    private val zipFileCache = object : LinkedHashMap<String, File>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, File>?) = size > 4
    }

    private fun getSortedZipEntries(zf: java.util.zip.ZipFile, cacheKey: String): List<java.util.zip.ZipEntry> {
        synchronized(zipEntryCache) { zipEntryCache[cacheKey]?.let { return it } }
        val entries = zf.entries().asSequence()
            .filter { !it.isDirectory && isImg(it.name) }
            .sortedWith(compareBy<java.util.zip.ZipEntry> { it.name.count { c -> c == '/' || c == '\\' } }
                .thenComparing { a, b -> com.mangareader.zip.CommonsZipHelper.compareNatural(a.name, b.name) })
            .toList()
        synchronized(zipEntryCache) { zipEntryCache[cacheKey] = entries }
        return entries
    }

    /** ★ 2026-10-12 开卷失败原因提示（真机复现问题2/3）：scan7z/scanEpub 空结果时写原因，
     *  Reader 的「文件暂时找不到」弹窗读到则用其替换误导文案；listPages 入口清空防串书 */
    @Volatile
    var lastOpenHint: String? = null

    fun listPages(path: String): List<PageMeta> {
        requireNotRemote(path)
        lastOpenHint = null
        synchronized(recentListPaths) { recentListPaths[path] = Unit }
        synchronized(pageListCache) {
            val cached = pageListCache[path]
            if (cached != null) {
                d(TAG, "listPages CACHED path=${path.takeLast(30)} total=${cached.size}")
                return cached
            }
        }
        // ★ E6：d: 文件夹书磁盘索引——命中秒开，后台轻校验兜住"目录变了但索引用旧"的窗口。
        //   SAF 的 lastModified 不可靠（常见返回 0），失效判定用"目录条目名摘要"而不是 mtime。
        if (path.startsWith("d:")) {
            val p0 = parsePath(path)
            if (p0 != null) {
                val hit = readDirPageIndex(path)
                if (hit != null) {
                    synchronized(pageListCache) { pageListCache[path] = hit }
                    scheduleDirIndexValidate(path, makeTree(p0.auth, p0.tree), p0.doc)
                    d(TAG, "listPages DIR-INDEX hit total=${hit.size} path=${path.takeLast(30)}")
                    return hit
                }
            }
        }
        // ★ E6 同款（2026-09-30 Boyue+SD 实锤 25s 开书）：a: 压缩书（mobi/epub/zip…）页表磁盘索引。
        //   慢 SD 上 scanMobi/scanEpub 每次开书都要经 SAF 流重读整个索引——命中后秒开。
        //   失效戳用文档 SIZE（文件级可靠），后台校验发现尺寸变了就重建，当前会话不中断。
        if (path.startsWith("a:")) {
            val p0 = parsePath(path)
            if (p0 != null) {
                val hit = readArchivePageIndex(path)
                if (hit != null) {
                    synchronized(pageListCache) { pageListCache[path] = hit }
                    scheduleArchiveIndexValidate(path, makeTree(p0.auth, p0.tree), p0.doc)
                    d(TAG, "listPages ARC-INDEX hit total=${hit.size} path=${path.takeLast(30)}")
                    return hit
                }
            }
        }
        return try {
            val result = when {
                path.startsWith("z:") -> {
                    val inner = path.removePrefix("z:")
                    val sepIdx = inner.indexOf("//")
                    if (sepIdx < 0) emptyList()
                    else {
                        var archivePath = inner.substring(0, sepIdx)
                        if (!archivePath.startsWith("a:") && !archivePath.startsWith("d:")) {
                            archivePath = "a:$archivePath"
                        }
                        val subPath = inner.substring(sepIdx + 2)
                        if (subPath.isNotEmpty() && !subPath.endsWith('/') &&
                            isNestedArchive(subPath.substringAfterLast('/'))) {
                            val nestedFile = extractNestedArchive(archivePath, subPath)
                            if (nestedFile != null) {
                                val nestedExt = subPath.substringAfterLast('.').lowercase()
                                listNestedArchivePages(nestedFile, nestedExt)
                            } else emptyList()
                        } else {
                            listZipSubDirPages(archivePath, subPath)
                        }
                    }
                }
                else -> {
                    val p = parsePath(path)
                    if (p == null) emptyList()
                    else {
                        val tree = makeTree(p.auth, p.tree)
                        when {
                            p.tag == "d" -> scanDir(tree, p.doc)
                            p.ext == "epub" -> scanEpub(tree, p.doc)
                            p.ext == "mobi" || p.ext == "azw" || p.ext == "azw3" || p.ext == "pdb" || p.ext == "prc" -> scanMobi(tree, p.doc)
                            p.ext == "cbr" || p.ext == "rar" -> scanRar(tree, p.doc)
                            p.ext == "cb7" || p.ext == "7z" -> scan7z(tree, p.doc)
                            else -> scanFile(tree, p.doc, p.ext, "")
                        }
                    }
                }
            }
            synchronized(pageListCache) { pageListCache[path] = result }
            if (path.startsWith("d:")) {
                val p0 = parsePath(path)
                if (p0 != null) writeDirPageIndex(path, makeTree(p0.auth, p0.tree), p0.doc, result)
            }
            if (path.startsWith("a:") && result.isNotEmpty()) {
                val p0 = parsePath(path)
                if (p0 != null && (p0.ext != "epub" || lastEpubScanUsedSpine)) {
                    writeArchivePageIndex(path, makeTree(p0.auth, p0.tree), p0.doc, result)
                }
            }
            d(TAG, "listPages path=${path.takeLast(40)} total=${result.size} first5=${result.take(5).map { it.index }}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "listPages: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * ★ §24 P3b：返回某一页的**原始字节**（不解码为 Bitmap）——给 APNG4Android 的 FrameAnimationDrawable 用
     * 决策 2：这是新增方法，不动现有 `loadPage` 的 6 处 IO 分支逻辑——只是把相同的 source 分发再写一遍，
     *   但直接返回 ByteArray，跳过 `decodeBitmap + cache` 两步。
     *
     * - 不复用 `loadPage` 的 bitmap cache（动图走 Drawable 路径自带解码 + 帧缓存，复用它反而内存翻倍）
     * - 不应用 splitSide（动图一般不会是 split 图）
     * - 不应用签名随机黑页机制（动图也要播，签名未验证时返回正常字节而非 null）
     *
     * 6 处 source 分支与 loadPage 完全对应：zip(z:) / zip(a:) / rar / 7z / tar / mobi 系 / dir / pdf
     */
    fun loadPageRaw(meta: PageMeta, treeStr: String): ByteArray? {
        requireNotRemote(treeStr)
        if (treeStr.startsWith("z:")) {
            val inner = treeStr.removePrefix("z:")
            val sepIdx = inner.indexOf("//")
            if (sepIdx >= 0) {
                var archPath = inner.substring(0, sepIdx)
                if (!archPath.startsWith("a:") && !archPath.startsWith("d:")) archPath = "a:$archPath"
                val subPath = inner.substring(sepIdx + 2)
                if (subPath.isNotEmpty() && !subPath.endsWith('/') && isNestedArchive(subPath.substringAfterLast('/'))) {
                    val nestedFile = extractNestedArchive(archPath, subPath)
                    if (nestedFile != null) {
                        val nestedExt = subPath.substringAfterLast('.').lowercase()
                        return readNestedPageRaw(nestedFile, nestedExt, meta.localIdx)
                    }
                    return null
                }
            }
        }
        // 一、ZIP + treeStr z:→ NativeEngine.readZipEntry
        if (treeStr.isNotEmpty() && meta.source == "zip") {

            if (treeStr.startsWith("z:")) {
                try {
                    val inner = treeStr.removePrefix("z:")
                    val sepIdx = inner.indexOf("//")
                    if (sepIdx >= 0) {
                        var archivePath = inner.substring(0, sepIdx)
                        if (!archivePath.startsWith("a:")) archivePath = "a:$archivePath"
                        val subPath = inner.substring(sepIdx + 2)
                        val result = getZipAllNames(archivePath) ?: return null
                        val (cacheKey, allNames) = result
                        val root = zipTree(cacheKey) { buildZipTree(allNames.toList()) }
                        var current = root
                        val pathParts = subPath.split('/').filter { it.isNotEmpty() }
                        for (part in pathParts) {
                            val child = current.children.find { it.name == part && it.isDir } ?: return null
                            current = child
                        }
                        val imgLocalIdx = meta.localIdx
                        if (imgLocalIdx in current.imageIndices.indices) {
                            val rawEntryIdx = current.imageIndices[imgLocalIdx]
                            return com.mangareader.native.NativeEngine.readZipEntry(cacheKey, rawEntryIdx)
                        }
                    }
                } catch (_: Exception) { return null }
                return null
            }
            // 二、ZIP + 普通 a: 路径 → loadFromZip
            try {
                val p = parsePath(treeStr) ?: return null
                val tree = makeTree(p.auth, p.tree)
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, p.doc)
                return loadFromZip(uri, meta.localIdx, treeStr)
            } catch (_: Exception) { return null }
        }
        // 三、RAR/7Z/TAR → loadFrom{Rar/7z/Tar}
        if (treeStr.isNotEmpty() && meta.source in listOf("rar", "7z", "tar")) {
            try {
                val p = parsePath(treeStr) ?: return null
                val tree = makeTree(p.auth, p.tree)
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, p.doc)
                return when (meta.source) {
                    "rar" -> loadFromRar(uri, meta.localIdx)
                    "7z" -> loadFrom7z(uri, meta.localIdx)
                    "tar" -> loadFromTar(uri, meta.localIdx)
                    else -> null
                }
            } catch (_: Exception) { return null }
        }
        // 四、MOBI/AZW/PDB/PRC → NativeEngine.readMobiPage + fallback loadMobiPage
        if (treeStr.isNotEmpty() && meta.source in listOf("mobi", "azw", "azw3", "pdb", "prc")) {
            try {
                val p = parsePath(treeStr) ?: return null
                val tree = makeTree(p.auth, p.tree)
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, p.doc)
                val cacheKey = uri.toString()
                // ★ 2026-10-11 用户拍板：本地 mobi 不走自研流式快路径（用户实报个别页黑，
                //   原有 libmobi 链"非常好"）——恢复原链：libmobi 读页 → Java 两遍扫描兜底。
                //   自研 MobiFile 保留给远程 DocumentExtract 链（MobiEngine）。
                ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val fd = pfd.detachFd()
                    try { com.mangareader.native.NativeEngine.openMobiWithFd(cacheKey, fd) } catch (_: Exception) {}
                    val rawData = com.mangareader.native.NativeEngine.readMobiPage(cacheKey, meta.localIdx)
                    if (rawData != null) return rawData
                }
                return loadMobiPage(uri, meta.localIdx)  // fallback Java 两遍扫描
            } catch (_: Exception) { return null }
        }
        // 五、DIR → loadDirPage 包 ByteArray 的 java fallback
        if (meta.source == "dir") {
            try {
                val p = parsePath(treeStr) ?: return null
                val tree = makeTree(p.auth, p.tree)
                return loadDirPageRaw(tree, meta, treeStr)
            } catch (_: Exception) { return null }
        }
        // 六、PDF → loadPdfPageRaw（如有）；当前未实现 PDF 的 raw 路径，返回 null 走 loadPage 兜底
        if (meta.source == "pdf") {
            return null  // ★ 动图 PDF 极少；不做（如果需要可加 loadPdfPageRaw）
        }
        return null
    }

    /**
     * ★ P2 优化：autoWebtoon 判定专用——只读页尺寸不解帧。
     *   比 loadPage 节省 ~95% 时间（不解码 RGB，只扫文件头读宽高）。
     *   比 notes 第 303 行被否版多 PDF 分路——loadPageRaw 对 pdf 硬返回 null，
     *   本方法把 PDF 单独走 NativeEngine.pdfGetPageSize（已有 API，纯 metadata 读取不解帧）
     *   修正原方案被否的"PDF 条漫书判不中"缺口。
     *   ★ 返回 IntArray[width, height] 或 null；调用方 ratio = w.toFloat()/h
     *   ★ 不缓存 Bitmap（没解出来）；不读 SignatureVerifier.isVerified 守卫
     *     （autoWebtoon 判定只是读 size 决策 mode，不显示给用户，跳签名门不破坏杀进程机制——
     *      原 loadPage 的签名门是"未验证时随机返回 null 导致黑页"，本方法不显示不需此门）
     */
    fun loadPageSize(meta: PageMeta, treeStr: String): IntArray? {
        requireNotRemote(treeStr)
        // ★ PDF 分路：NativeEngine.pdfGetPageSize 返回 IntArray[width, height]，纯 metadata 不 render
        //   ★★★ 审查修正 1：docKey 必须用 docUri.toString()，不带 "pdf:" 前缀。
        //     理由：CPP 句柄表（pdfium_wrapper.cpp:132 `handles.find(key)`）用 key 直接查表。
        //     loadPdfPage（行 1939）用 `docKey = uri.toString()` 不带前缀。
        //     本方法必须与 loadPdfPage 用相同 docKey 才能复用同一 PDF 句柄；
        //     若带 "pdf:" 前缀会创建独立条目 → 句柄泄漏 + 表膨胀 + 后续 loadPdfPage 仍重新 open。
        //     （"pdf:" 前缀只在 sharedKey = "pdf:$uri|$pageIndex" 做 bitmap 缓存键用，与 docKey 不同用途）
        if (meta.source == "pdf") {
            return try {
                val p = parsePath(treeStr) ?: return null
                val tree = makeTree(p.auth, p.tree)
                val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, meta.ext)
                val docKey = docUri.toString()  // ★ 不带 "pdf:" 前缀，与 loadPdfPage 对齐
                // ★ 审查修正 2：pfd 处理范式对齐 loadPdfPage（行 1943-1953）：
                //   detachFd() 后 pfd 不再持有 fd，但用 try/finally { pfd.close() } 兜底（detachFd 后 close 是 no-op 不抛）
                //   比 .use { } 更明确语义、与原版范式一致；避免 detachFd 边界行为差异
                if (!com.mangareader.native.NativeEngine.pdfIsOpen(docKey)) {
                    val pfd = ctx.contentResolver.openFileDescriptor(docUri, "r")
                    if (pfd != null) {
                        try {
                            val fd = pfd.detachFd()
                            val ok = try {
                                com.mangareader.native.NativeEngine.pdfOpen(docKey, fd)
                            } catch (e: Exception) {
                                Log.e(TAG, "loadPageSize pdfOpen failed: ${e.message}", e)
                                false
                            }
                            if (!ok) {
                                // ★ A2：pdfOpen 返回 false/抛异常 = native 未接管 fd，必须手动关闭防泄漏
                                try { android.os.ParcelFileDescriptor.adoptFd(fd).close() } catch (_: Exception) {}
                            }
                        } finally { try { pfd.close() } catch (_: Exception) {} }
                    }
                }
                com.mangareader.native.NativeEngine.pdfGetPageSize(docKey, meta.localIdx)
            } catch (_: Exception) { null }
        }
        // 其他 source：loadPageRaw 拿字节 + inJustDecodeBounds 扫头部
        //   ★ readZipEntry（native_engine.cpp:195-204）对 DEFLATE 压缩已 inflate 返回解压后字节；
        //     loadDirPageRaw（行 496）读完整文件字节；loadFromRar/7z/tar 同理都是解压后字节。
        //   ★ inJustDecodeBounds 对 JPEG/PNG/WebP/GIF/APNG 头部都能读 outWidth/outHeight 不解帧。
        val raw = try { loadPageRaw(meta, treeStr) } catch (_: Exception) { null } ?: return null
        if (raw.isEmpty()) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
        return if (opts.outWidth > 0 && opts.outHeight > 0) intArrayOf(opts.outWidth, opts.outHeight) else null
    }

    // ★ 长图分割 §2 探测层（2026-09-25）：只读头部 64KB 判定尺寸/动图，压缩包不解压全量。
    //   与 loadPageSize 的区别：①返回含动图标记（动图页强制不拆，w/h 归零）②zip 有 realFile+spine
    //   名单时走 java ZipFile 按【条目名】前缀读（spine 即 listPages 的排序权威，按名取不复制排序
    //   逻辑）③rar/7z/tar 与无 spine/realFile 的 zip 回退 loadPageRaw 全量字节 bounds（一次性成本，
    //   配合 PageSizeCacheStore 尺寸表二开免探）。
    data class PageBounds(val width: Int, val height: Int, val animated: Boolean)

    fun loadPageBounds(meta: PageMeta, treeStr: String): PageBounds? {
        requireNotRemote(treeStr)
        if (meta.source == "pdf") return null  // PDF 走 loadPageSize 的 pdfGetPageSize 分支，无需头部探测
        // ★ 失败重试一次：首次冷缓存 zip/SAF 流抖动会让头部探测偶发失败 → 该页被 expand 整页
        //   透传，页列表混杂段页与整页（观感=第一页清晰后面乱且糊，第二次进入才正常）
        repeat(2) { attempt ->
            val head: ByteArray? = try { loadPageHeadBytes(meta, treeStr) } catch (_: Exception) { null }
            if (head != null && head.isNotEmpty()) {
                if (isAnimatedBytes(head)) return PageBounds(0, 0, true)
                try {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(head, 0, head.size, opts)
                    if (opts.outWidth > 0 && opts.outHeight > 0) return PageBounds(opts.outWidth, opts.outHeight, false)
                } catch (_: Exception) {}
            }
            if (attempt == 0) try { Thread.sleep(120) } catch (_: InterruptedException) { return null }
        }
        return null
    }

    /** ★ 长图分割 §2：尺寸表缓存入口（PageSizeCacheStore 包装，供 VirtualPageExpander 使用） */
    fun loadPageSizeCache(bookPath: String, stamp: String): Map<String, IntArray>? =
        com.mangareader.data.PageSizeCacheStore.load(ctx, bookPath, stamp)

    fun savePageSizeCache(bookPath: String, stamp: String, sizes: Map<String, IntArray>) =
        com.mangareader.data.PageSizeCacheStore.save(ctx, bookPath, stamp, sizes)

    private fun readUpTo(input: java.io.InputStream, maxLen: Int): ByteArray {
        val buf = java.io.ByteArrayOutputStream(maxLen)
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (total < maxLen) {
            val r = input.read(chunk, 0, minOf(chunk.size, maxLen - total))
            if (r < 0) break
            buf.write(chunk, 0, r)
            total += r
        }
        return buf.toByteArray()
    }

    private fun loadPageHeadBytes(meta: PageMeta, treeStr: String): ByteArray? {
        val maxLen = 64 * 1024
        // 一、dir：真实文件 FileInputStream（零解压），SAF 流兜底
        if (meta.source == "dir") {
            getRealFileForPage(treeStr, meta)?.let { f ->
                if (f.exists() && f.length() > 0) return java.io.FileInputStream(f).use { readUpTo(it, maxLen) }
            }
            val p = parsePath(treeStr) ?: return null
            val tree = makeTree(p.auth, p.tree)
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, meta.ext)
            return ctx.contentResolver.openInputStream(uri)?.use { readUpTo(it, maxLen) }
        }
        // 二、zip（非 z: 树）：realFile + spine 名单 → java ZipFile 按条目名前缀读
        if (meta.source == "zip" && !treeStr.startsWith("z:")) {
            try {
                val p = parsePath(treeStr)
                if (p != null) {
                    val tree = makeTree(p.auth, p.tree)
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, p.doc)
                    val cacheKey = uri.toString()
                    val spine = synchronized(sortedImageNamesCache) { sortedImageNamesCache[cacheKey] }
                    if (spine != null && meta.localIdx in spine.indices) {
                        val realFile = getRealFile(uri)
                        if (realFile != null) {
                            java.util.zip.ZipFile(realFile).use { zf ->
                                val name = spine[meta.localIdx]
                                val entry = zf.getEntry(name) ?: zf.getEntry(name.substringAfterLast("/"))
                                if (entry != null) return zf.getInputStream(entry).use { readUpTo(it, maxLen) }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
            // 快路径不可用（无 spine/realFile/z: 树）→ 落到下方 loadPageRaw 全量
        }
        // 三、压缩包族兜底（rar/7z/tar/z:树/无快路径 zip）：loadPageRaw 全量字节（一次性，尺寸表缓存消化）
        return loadPageRaw(meta, treeStr)
    }

    /** §24 P3 dir 分支：loadDirPage 返回 Bitmap，这里补一个返回 ByteArray 的同款函数（直接读图字节） */
    private fun loadDirPageRaw(tree: Uri, meta: PageMeta, treeStr: String): ByteArray? {
        // ★ 真实路径快速路径：FileInputStream 跳过 SAF Binder IPC
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
            && android.os.Environment.isExternalStorageManager()) {
            try {
                val docUri = if (meta.ext.isNotEmpty()) {
                    DocumentsContract.buildDocumentUriUsingTree(tree, meta.ext)
                } else {
                    DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                }
                val realFile = getRealFile(docUri)
                if (realFile != null && realFile.exists() && realFile.length() > 0) {
                    return realFile.readBytes()
                }
            } catch (_: Exception) {}
        }
        // 复用 loadDirPage 的 URI 构造，但直接读文件字节而非 decode
        try {
            val docUri = if (meta.ext.isNotEmpty()) {
                DocumentsContract.buildDocumentUriUsingTree(tree, meta.ext)
            } else {
                DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            }
            ctx.contentResolver.openInputStream(docUri)?.use { return it.readBytes() }
        } catch (_: Exception) {}
        return null
    }

    /**
     * ★ §24 P3 二代：读唯一一次全文件 → 判 magic 头 → 三个 Parser 共用同一 ByteBufferReader。
     *   旧版本 peekHeadBytes + isAnimatedGif/isApng/isAWebP 各读一遍（共 2 次全读）→ 大卷 IO 翻倍。
     *   注意：仍要读全文件——APNG 需遍历整文件找 acTL chunk（§25 B2 修订）。
     *   bind 时单页调，不会卷级联阻塞翻页 IO（旧版本是整章串行预读）。
     */
    /** ★ 动图打开提速（2026-08-27）：进卷采样判定专用——只读文件头部而非全文件。
     *   GIF 头部 magic 即可判；动态 WEBP 的 VP8X Animation bit 在文件头 ~30 字节内；
     *   APNG 的 acTL 在 IDAT 前（通常 <64KB）。头部窗口内判不出再按格式语义收场。
     *   zip/rar/7z 源的 native 接口按 entry 全量读，头部截读需 JNI 改造（待 ANIMT 实测确认后再立项）；
     *   本期仅 dir 源走流式头读，其余源回退全文件读。 */
    @Volatile var lastPeekBytes: Int = 0
        private set

    suspend fun peekAnimKindFast(treeStr: String, meta: PageMeta): AnimKind {
        // dir 源：流式读头部（不落全文件）
        if (meta.source == "dir") {
            try {
                val p = parsePath(treeStr) ?: return peekAnimKindFallback(treeStr, meta)
                val tree = makeTree(p.auth, p.tree)
                val docUri = if (meta.ext.isNotEmpty()) {
                    DocumentsContract.buildDocumentUriUsingTree(tree, meta.ext)
                } else {
                    DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                }
                val head = ByteArray(64 * 1024)
                var off = 0
                // ★ 真实路径快速路径：FileInputStream 跳过 SAF Binder IPC（1068 页 × 200ms → ~0）
                val stream = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
                    && android.os.Environment.isExternalStorageManager()) {
                    val rf = getRealFile(docUri)
                    if (rf != null && rf.exists() && rf.length() > 0) java.io.FileInputStream(rf) else null
                } else null
                val ins = stream ?: ctx.contentResolver.openInputStream(docUri)
                ins?.use { s ->
                    while (off < head.size) {
                        val n = s.read(head, off, head.size - off)
                        if (n <= 0) break
                        off += n
                        val k = animKindFromHead(head, off)
                        if (k != null) { lastPeekBytes = off; return k }
                    }
                }
                lastPeekBytes = off
                return AnimKind.NONE
            } catch (_: Exception) {
                return peekAnimKindFallback(treeStr, meta)
            }
        }
        // 其他源：暂走全文件读（与旧 peekAnimKind 同速），ANIMT 实测后再决定是否做 native 头部截读
        return peekAnimKindFallback(treeStr, meta)
    }

    private suspend fun peekAnimKindFallback(treeStr: String, meta: PageMeta): AnimKind {
        // ★ 提速（2026-10-03"打开 GIF 几十秒"专项）：先试 64KB 头部截读——GIF 魔数 6B、
        //   动图 WebP VP8X ~30B 内可判，APNG acTL 通常 <64KB；判不出才全量读。
        //   压缩包内大 GIF 免掉一次纯为嗅探的全量解压（此前 zip/rar/7z 每页全读一遍）。
        val head = try { loadPageHeadBytes(meta, treeStr) } catch (_: Exception) { null }
        if (head != null && head.isNotEmpty()) {
            animKindFromHead(head, head.size)?.let {
                lastPeekBytes = head.size
                return it
            }
        }
        val raw = try { loadPageRaw(meta, treeStr) } catch (_: Exception) { null } ?: run { lastPeekBytes = 0; return AnimKind.NONE }
        lastPeekBytes = raw.size
        return peekAnimKindFromRaw(raw)
    }

    /**
     * ★ v1.13 动图提速：从已读字节判定动图类型（配合 AnimPager rawCache 单次读取复用，
     *   消除 peek/load 各自全文件读的重复 IO）。逻辑与 peekAnimKind 一致。
     */
    fun peekAnimKindFromRaw(raw: ByteArray): AnimKind {
        return animKindFromHead(raw, raw.size) ?: AnimKind.NONE
    }

    suspend fun peekAnimKind(treeStr: String, meta: PageMeta): AnimKind {
        val raw = try { loadPageRaw(meta, treeStr) } catch (_: Exception) { null } ?: return AnimKind.NONE
        return animKindFromHead(raw, raw.size) ?: AnimKind.NONE
    }



    fun loadPage(meta: PageMeta, treeStr: String): Bitmap? {
        requireNotRemote(treeStr)
        // 签名校验：未验证时随机返回 null 导致黑页
        if (!com.mangareader.SignatureVerifier.isVerified && meta.index % 3 == 0) return null
        if (treeStr.startsWith("z:")) {
            val inner = treeStr.removePrefix("z:")
            val sepIdx = inner.indexOf("//")
            if (sepIdx >= 0) {
                var archPath = inner.substring(0, sepIdx)
                if (!archPath.startsWith("a:") && !archPath.startsWith("d:")) archPath = "a:$archPath"
                val subPath = inner.substring(sepIdx + 2)
                if (subPath.isNotEmpty() && !subPath.endsWith('/') && isNestedArchive(subPath.substringAfterLast('/'))) {
                    val key = "$treeStr|${meta.index}|${meta.splitSide}"
                    cacheGet(key)?.let { return it }
                    val nestedFile = extractNestedArchive(archPath, subPath)
                    if (nestedFile != null) {
                        val nestedExt = subPath.substringAfterLast('.').lowercase()
                        val rawData = readNestedPageRaw(nestedFile, nestedExt, meta.localIdx)
                        if (rawData != null) {
                            val half = applySplitSide(decodeBitmap(rawData), meta)
                            if (half != null) { cachePut(key, half); return half }
                        }
                    }
                    return null
                }
            }
        }
        // For ZIP/EPUB: go straight to loadFromZip (libzip fd-based, skip getNativeArchive)
        if (treeStr.isNotEmpty() && meta.source == "zip") {
            // z: path — ZIP internal directory
            if (treeStr.startsWith("z:")) {
                try {
                    val inner = treeStr.removePrefix("z:")
                    val sepIdx = inner.indexOf("//")
                    if (sepIdx >= 0) {
                        var archivePath = inner.substring(0, sepIdx)
                        if (!archivePath.startsWith("a:")) archivePath = "a:$archivePath"
                        val subPath = inner.substring(sepIdx + 2)
                        android.util.Log.d(TAG, "loadPage z: archivePath=$archivePath subPath=$subPath idx=${meta.localIdx}")
                        val result = getZipAllNames(archivePath) ?: return null
                        val (cacheKey, allNames) = result
                        val root = zipTree(cacheKey) { buildZipTree(allNames.toList()) }
                        var current = root
                        val pathParts = subPath.split('/').filter { it.isNotEmpty() }
                        for (part in pathParts) {
                            val child = current.children.find { it.name == part && it.isDir }
                            if (child != null) current = child
                            else { android.util.Log.e(TAG, "loadPage z: node not found: $part"); return null }
                        }
                        android.util.Log.d(TAG, "loadPage z: imageIndices.size=${current.imageIndices.size}")
                        val imgLocalIdx = meta.localIdx
                        if (imgLocalIdx in current.imageIndices.indices) {
                            val rawEntryIdx = current.imageIndices[imgLocalIdx]
                            android.util.Log.d(TAG, "loadPage z: rawEntryIdx=$rawEntryIdx cacheKey=${cacheKey.takeLast(30)}")
                            // ★ B3 统一 key：四分支一律 "treeStr|index|splitSide"（旧 z:/rar/7z/tar 缺 splitSide 段）
                            val key = "$treeStr|${meta.index}|${meta.splitSide}"
                            cacheGet(key)?.let { return it }
                            val rawData = com.mangareader.native.NativeEngine.readZipEntry(cacheKey, rawEntryIdx)
                            android.util.Log.d(TAG, "loadPage z: readZipEntry returned ${rawData?.size ?: "null"} bytes")
                            if (rawData != null) {
                                val half = applySplitSide(decodeBitmap(rawData), meta)
                                if (half != null) { cachePut(key, half); return half }
                            }
                        } else {
                            android.util.Log.e(TAG, "loadPage z: imgLocalIdx $imgLocalIdx out of range (0..${current.imageIndices.indices.last})")
                        }
                    }
                } catch (e: Exception) { android.util.Log.e(TAG, "loadPage z: exception: ${e.message}") }
                return null
            }
            try {
                val p = parsePath(treeStr)
                if (p != null) {
                    val tree = makeTree(p.auth, p.tree)
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, p.doc)
                    // ★ B3 统一 key
                    val key = "$treeStr|${meta.index}|${meta.splitSide}"
                    cacheGet(key)?.let { return it }
                    val rawData = loadFromZip(uri, meta.localIdx, treeStr)
                    if (rawData != null) {
                        val half = applySplitSide(decodeBitmap(rawData), meta)
                        if (half != null) { cachePut(key, half); return half }
                    }
                }
            } catch (_: Exception) {}
            return null
        }
        // For RAR/7Z/TAR: use loadFromRar/loadFrom7z/loadFromTar (libarchive)
        if (treeStr.isNotEmpty() && meta.source in listOf("rar", "7z", "tar")) {
            try {
                val p = parsePath(treeStr)
                if (p != null) {
                    val tree = makeTree(p.auth, p.tree)
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, p.doc)
                    // ★ B3 统一 key
                    val key = "$treeStr|${meta.index}|${meta.splitSide}"
                    cacheGet(key)?.let { return it }
                    val rawData = when (meta.source) {
                        "rar" -> loadFromRar(uri, meta.localIdx)
                        "7z" -> loadFrom7z(uri, meta.localIdx)
                        "tar" -> loadFromTar(uri, meta.localIdx)
                        else -> null
                    }
                    if (rawData != null) {
                        val half = applySplitSide(decodeBitmap(rawData), meta)
                        if (half != null) { cachePut(key, half); return half }
                    }
                }
            } catch (_: Exception) {}
            return null
        }
        // MOBI/AZW/PDB/PRC: libmobi (fd-based, cached handle)
        if (treeStr.isNotEmpty() && meta.source in listOf("mobi", "azw", "azw3", "pdb", "prc")) {
            try {
                val p = parsePath(treeStr) ?: return null
                val tree = makeTree(p.auth, p.tree)
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, p.doc)
                val cacheKey = uri.toString()
                Log.d(TAG, "MOBI loadPage: idx=${meta.localIdx} cacheKey=${cacheKey.takeLast(30)}")
                // ★ 2026-10-11 用户拍板：本地 mobi 不走自研流式快路径（见 loadPageRaw 同款注释），
                //   恢复原链：libmobi 整档装载读页 → Java 两遍扫描兜底
                // get fd from URI, pass to libmobi
                ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val fd = pfd.detachFd()
                    Log.d(TAG, "MOBI loadPage: fd=$fd, calling openMobiWithFd")
                    try {
                        com.mangareader.native.NativeEngine.openMobiWithFd(cacheKey, fd)
                    } catch (_: Exception) {}
                    val rawData = com.mangareader.native.NativeEngine.readMobiPage(cacheKey, meta.localIdx)
                    Log.d(TAG, "MOBI loadPage: readMobiPage returned ${rawData?.size ?: "null"} bytes")
                    if (rawData != null) {
                        val bmp = decodeBitmap(rawData)
                        if (bmp != null) return bmp
                    }
                }
                // fallback: Java两遍扫描
                Log.d(TAG, "MOBI loadPage: falling back to Java loadMobiPage")
                val bmp = loadMobiPage(uri, meta.localIdx)?.let { decodeBitmap(it) }
                if (bmp != null) return bmp
            } catch (e: Exception) { Log.e(TAG, "MOBI error: ${e.message}") }
            // ★ 2026-10-11 黑页兜底（用户实报"个别页黑、重进才好"=SD/SAF 瞬态读失败特征，
            //   Boyue+外置SD 同病已实锤过）：流式/libmobi/Java 三段全 null 时延迟 250ms
            //   整链补试一次（重进 loadPage 自身即整链重跑）；每 (文件,页) 只补一次，
            //   真坏页不至被 rebind 反复重试拖慢。取证短码区别"三段全 null"。
            runCatching { com.mangareader.DiagTracer.markStatic("mobi: page fail idx=${meta.localIdx} r=${mobiPageRetryKeys.size}") }
            if (mobiPageRetryKeys.put("$treeStr#${meta.localIdx}", true) == null) {
                runCatching { Thread.sleep(250) }
                runCatching { return loadPage(meta, treeStr) }
            }
            return null
        }
        // PDF/dir/其他: Java路径（保留Java缓存）
        val key = "$treeStr|${meta.index}|${meta.splitSide}"
        cacheGet(key)?.let { return it }
        return try {
            val p = parsePath(treeStr) ?: return null
            val tree = makeTree(p.auth, p.tree)
            val bmp = when (meta.source) {
                "dir" -> loadDirPage(tree, meta, treeStr)
                "pdf" -> {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, meta.ext)
                    loadPdfPage(uri, meta.localIdx)
                }
                else -> null
            }
            val result = applySplitSide(bmp, meta)
            result?.let { cachePut(key, it) }
            result
        } catch (e: Exception) { Log.e(TAG, "loadPage: ${e.message}", e); null }
    }

    /** ★ 宽图二分（splitSide）：跨页拼合图按左右半裁切（splitSide=1 左半 / 2 右半）。
     *  1.2 宽高比守卫与展开器判定同源——展开的页必然裁，未展开页零影响。
     *  裁半在 cachePut 之前：半图入缓存，两个半页各自缓存条目互不覆盖。 */
    private fun applySplitSide(bmp: Bitmap?, meta: PageMeta): Bitmap? {
        if (bmp == null || bmp.isRecycled) return bmp
        if (meta.splitSide == 0 || bmp.width <= bmp.height * 1.2f) return bmp
        return try {
            val halfWidth = bmp.width / 2
            val startX = if (meta.splitSide == 1) 0 else halfWidth
            Bitmap.createBitmap(bmp, startX, 0, halfWidth, bmp.height)
        } catch (e: Exception) {
            Log.e(TAG, "applySplitSide: ${e.message}")
            bmp
        }
    }

    private fun calculateSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sampleSize = 1
        while (width / sampleSize > maxDim || height / sampleSize > maxDim) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun loadDirPage(tree: Uri, meta: PageMeta, treeStr: String): Bitmap? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, meta.ext)
        d(TAG, "dir loadPage idx=${meta.index} docId=${meta.ext.substringAfterLast("/")} uri=${uri}")
        val screenMax = (maxOf(ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels) * 1.2f).toInt()
        val hasManageStorage = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && android.os.Environment.isExternalStorageManager()
        if (hasManageStorage) {
            val realFile = getRealFile(uri)
            if (realFile != null && realFile.exists() && realFile.length() > 0) {
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(realFile.absolutePath, boundsOpts)
                // ★ 动图首帧同样按 screenMax 采样（2026-10-03"打开 GIF 几十秒"专项）：
                //   长条 GIF 全尺寸首帧可达 200MB+ 位图；该帧只是动图 drawable 的临时占位
                val sampleSize = if (downsampleThreshold >= Long.MAX_VALUE || boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) {
                    1
                } else {
                    calculateSampleSize(boundsOpts.outWidth, boundsOpts.outHeight, screenMax)
                }
                return try {
                    BitmapFactory.decodeFile(realFile.absolutePath, BitmapFactory.Options().apply { inSampleSize = sampleSize })
                } catch (e: OutOfMemoryError) {
                    System.gc()
                    try { BitmapFactory.decodeFile(realFile.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 }) } catch (_: Exception) { null }
                }
            }
        }
        // fallback: SAF URI
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try { ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) } } catch (_: Exception) {}
        val sampleSize = if (downsampleThreshold >= Long.MAX_VALUE || boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) {
            1
        } else {
            calculateSampleSize(boundsOpts.outWidth, boundsOpts.outHeight, screenMax)
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
        } catch (e: OutOfMemoryError) {
            System.gc()
            try {
                ctx.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
                }
            } catch (_: Exception) { null }
        } catch (_: Exception) { null }
    }

    /** 读流头部字节（动图嗅探用；读不满返回实际长度） */
    private fun readHeadBytes(input: java.io.InputStream, max: Int): ByteArray {
        return try {
            val buf = ByteArray(max)
            var off = 0
            while (off < max) {
                val n = input.read(buf, off, max - off)
                if (n <= 0) break
                off += n
            }
            if (off == buf.size) buf else buf.copyOf(off)
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    fun getPageFileUri(meta: PageMeta, treeStr: String): String? {
        if (meta.source != "dir") return null
        return try {
            val p = parsePath(treeStr) ?: return null
            val tree = makeTree(p.auth, p.tree)
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, meta.ext)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && android.os.Environment.isExternalStorageManager()) {
                val realFile = getRealFile(uri)
                if (realFile != null && realFile.exists() && realFile.length() > 0) {
                    return "file://${realFile.absolutePath}"
                }
            }
            uri.toString()
        } catch (_: Exception) { null }
    }

    /** ★ 获取页面对应的真实 File（用于 mmap 替代 readBytes 降 Java 堆） */
    fun getRealFileForPage(treeStr: String, meta: PageMeta): java.io.File? {
        if (meta.source != "dir") return null
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        if (!android.os.Environment.isExternalStorageManager()) return null
        return try {
            val p = parsePath(treeStr) ?: return null
            val tree = makeTree(p.auth, p.tree)
            val docUri = if (meta.ext.isNotEmpty()) {
                DocumentsContract.buildDocumentUriUsingTree(tree, meta.ext)
            } else {
                DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            }
            val f = getRealFile(docUri)
            if (f != null && f.exists() && f.length() > 0) f else null
        } catch (_: Exception) { null }
    }

    fun decodeBitmap(data: ByteArray): Bitmap? {
        return try {
            // ★ F3.2 大图解码闸：解码前先探尺寸，解码内存超 24MB(6M px) 逐级 inSampleSize。
            //   8K 图全尺寸解码可达 256MB 瞬时 OOM；6M px ≈ 3 倍屏幕像素，fit-width 观感无损。
            //   bounds 探测不消费数据（ByteArray 可重复解码）。
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            var sample = 1
            // ★ 动图首帧也降采样（2026-10-03"打开 GIF 几十秒"专项）：此前动图页强制 sample=1，
            //   长条 GIF 第一帧=整屏逻辑尺寸（1080×50000 → 216MB 位图），解一次几十秒+GC 风暴。
            //   该帧只是动图 drawable 就位前的临时占位（已知动图页在阅读器侧直接跳过静解码），
            //   按 screenMax 采样不影响动图播放画质；普通尺寸 GIF sample=1 无变化。
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                val px = bounds.outWidth.toLong() * bounds.outHeight.toLong()
                while (px / (sample.toLong() * sample) > downsampleThreshold) sample *= 2
            }
            if (sample > 1) {
                BitmapFactory.decodeByteArray(
                    data, 0, data.size,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            } else {
                BitmapFactory.decodeByteArray(data, 0, data.size)
            }.also { bmp ->
                if (bmp == null) {
                    val magic = data.take(4).joinToString("") { "%02X".format(it) }
                    e(TAG, "decodeBitmap NULL bytes=${data.size} bounds=${bounds.outWidth}x${bounds.outHeight} sample=$sample magic=$magic")
                }
            } ?: decodeWithImageDecoder(data)

        } catch (e: OutOfMemoryError) {
            e(TAG, "decodeBitmap OOM bytes=${data.size} err=${e.message}")
            System.gc()
            try {
                BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = 2 })
            } catch (e2: Throwable) {
                e(TAG, "decodeBitmap OOM retry FAIL ${e2::class.java.simpleName}: ${e2.message}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "decode: ${e.message}")
            try { BitmapFactory.decodeByteArray(data, 0, data.size) } catch (_: Exception) { null } ?: decodeWithImageDecoder(data)
        }
    }

    private fun decodeWithImageDecoder(data: ByteArray): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            val source = ImageDecoder.createSource(data)
            ImageDecoder.decodeBitmap(source)
        } catch (e: Throwable) {
            e(TAG, "ImageDecoder fallback FAIL bytes=${data.size} err=${e::class.java.simpleName}: ${e.message}")
            decodeWithLibAvif(data)
        }
    }

    private fun decodeWithLibAvif(data: ByteArray): Bitmap? {
        if (data.size < 12) return null
        val isAvif = data[4] == 'f'.code.toByte() && data[5] == 't'.code.toByte() &&
            data[6] == 'y'.code.toByte() && data[7] == 'p'.code.toByte() &&
            (data[8] == 'a'.code.toByte() && data[9] == 'v'.code.toByte())
        if (!isAvif) return null
        return try {
            val stream = java.io.ByteArrayInputStream(data)
            val decoder = TachiImageDecoder.newInstance(stream) ?: run {
                e(TAG, "decodeWithLibAvif: newInstance returned null bytes=${data.size}")
                return null
            }
            val bmp = try { decoder.decode() } finally { decoder.recycle() }
            if (bmp != null) android.util.Log.i(TAG, "decodeWithLibAvif OK bytes=${data.size} ${bmp.width}x${bmp.height}")
            else e(TAG, "decodeWithLibAvif: decode returned null bytes=${data.size}")
            bmp
        } catch (e: Throwable) {
            e(TAG, "decodeWithLibAvif FAIL bytes=${data.size} err=${e::class.java.simpleName}: ${e.message}")
            null
        }
    }

    private fun decodeCoverBitmap(data: ByteArray): Bitmap? {
        if (data.isEmpty()) return null
        return try {
            com.mangareader.native.NativeEngine.decodeRaw(data, maxCoverSize, maxCoverSize)
                ?: BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }.let { opts ->
                    var s = 1
                    while (opts.outWidth / s > maxCoverSize || opts.outHeight / s > maxCoverSize) s *= 2
                    BitmapFactory.Options().apply { inSampleSize = s }
                })
                ?: decodeWithImageDecoder(data) ?: decodeWithLibAvif(data)
        } catch (e: OutOfMemoryError) {
            System.gc()
            try {
                BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = 4 })
            } catch (_: Exception) { null }
        } catch (e: Exception) {
            Log.e(TAG, "decodeCover: ${e.message}")
            null
        }
    }

    fun children(path: String): List<Item> {
        requireNotRemote(path)
        return try {
            if (path.startsWith("z:")) {
                android.util.Log.d(TAG, "children z: path=$path")
                val inner = path.removePrefix("z:")
                val sepIdx = inner.indexOf("//")
                android.util.Log.d(TAG, "children sepIdx=$sepIdx inner=$inner")
                if (sepIdx < 0) return emptyList()
                var archivePath = inner.substring(0, sepIdx)
                if (!archivePath.startsWith("a:") && !archivePath.startsWith("d:")) {
                    archivePath = "a:$archivePath"
                }
                val subPath = inner.substring(sepIdx + 2)
                android.util.Log.d(TAG, "children archivePath=$archivePath subPath=$subPath")
                val result = listZipDirs(archivePath, subPath.ifEmpty { null })
                android.util.Log.d(TAG, "children result.size=${result.size}")
                result
            } else if (path.startsWith("a:") && isNestedArchive(path.substringAfterLast("|"))) {
                android.util.Log.d(TAG, "children a:container path=$path")
                listContainerRootItems(path)
            } else {
                val p = parsePath(path) ?: return emptyList()
                val tree = makeTree(p.auth, p.tree)
                listDir(tree, p.doc, p.auth, p.tree)
            }
        } catch (e: Exception) {
            Log.e(TAG, "children: ${e.message}", e)
            emptyList()
        }
    }

    private fun listContainerRootItems(path: String): List<Item> {
        val p = parsePath(path) ?: return emptyList()
        if (p.tag != "a") return emptyList()
        val tree = makeTree(p.auth, p.tree)
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, p.doc)
        val realFile = getRealFile(uri)
        if (realFile == null || !realFile.exists()) return emptyList()
        val ext = realFile.name.substringAfterLast('.').lowercase()
        val names = mutableListOf<String>()
        val dirNames = mutableListOf<String>()
        when (ext) {
            "7z", "cb7" -> {
                try {
                    SevenZFile(realFile).use { sz ->
                        var entry = sz.nextEntry
                        while (entry != null) {
                            if (!entry.name.contains('/')) {
                                if (entry.isDirectory) {
                                    val dn = entry.name.trimEnd('/')
                                    if (dn.isNotEmpty()) dirNames.add(dn)
                                } else names.add(entry.name)
                            }
                            entry = sz.nextEntry
                        }
                    }
                } catch (e: Exception) { Log.e(TAG, "listContainerRootItems 7z: ${e.message}") }
            }
            "zip", "cbz" -> {
                return listZipDirs(path, null).filter { it.subPath.isNotEmpty() }
            }
            "rar", "cbr", "tar", "cbt", "tgz", "txz" -> {
                try {
                    val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
                    pfd.use {
                        val readHandle = me.zhanghai.android.libarchive.Archive.readNew()
                        try {
                            me.zhanghai.android.libarchive.Archive.setCharset(readHandle, "UTF-8".toByteArray())
                            me.zhanghai.android.libarchive.Archive.readSupportFilterAll(readHandle)
                            me.zhanghai.android.libarchive.Archive.readSupportFormatAll(readHandle)
                            me.zhanghai.android.libarchive.Archive.readOpenFd(readHandle, it.fd, 10240L)
                            var header = me.zhanghai.android.libarchive.Archive.readNextHeader(readHandle)
                            while (header != 0L) {
                                val nameBytes = me.zhanghai.android.libarchive.ArchiveEntry.pathname(header)
                                val name = nameBytes?.let { String(it) }
                                val fileType = me.zhanghai.android.libarchive.ArchiveEntry.filetype(header)
                                val isDir = fileType == me.zhanghai.android.libarchive.ArchiveEntry.AE_IFDIR
                                if (name != null && !name.contains('/')) {
                                    if (isDir) {
                                        val dn = name.trimEnd('/')
                                        if (dn.isNotEmpty()) dirNames.add(dn)
                                    } else names.add(name)
                                }
                                header = me.zhanghai.android.libarchive.Archive.readNextHeader(readHandle)
                            }
                            me.zhanghai.android.libarchive.Archive.free(readHandle)
                        } catch (e: Exception) {
                            try { me.zhanghai.android.libarchive.Archive.free(readHandle) } catch (_: Exception) {}
                            Log.e(TAG, "listContainerRootItems libarchive: ${e.message}")
                        }
                    }
                } catch (e: Exception) { Log.e(TAG, "listContainerRootItems fd: ${e.message}") }
            }
        }
        val items = mutableListOf<Item>()
        for (dn in dirNames.sortedWith { a, b -> com.mangareader.zip.CommonsZipHelper.compareNatural(a, b) }) {
            items.add(Item(dn, p.auth, p.tree, p.doc, true, "$dn/"))
        }
        for (name in names.filter { isNestedArchive(it) }.sortedWith { a, b -> com.mangareader.zip.CommonsZipHelper.compareNatural(a, b) }) {
            items.add(Item(name, p.auth, p.tree, p.doc, false, name))
        }
        android.util.Log.d(TAG, "listContainerRootItems ext=$ext total=${items.size}")
        return items
    }

    fun collectAllImages(path: String): List<PageMeta> {
        val allPages = mutableListOf<PageMeta>()
        try {
            val p = parsePath(path) ?: return emptyList()
            val tree = makeTree(p.auth, p.tree)
            collectImagesRecursive(tree, p.doc, p.auth, p.tree, allPages)
            allPages.sortBy { it.ext }
            Log.d(TAG, "collectAllImages: found ${allPages.size} total images")
        } catch (e: Exception) {
            Log.e(TAG, "collectAllImages: ${e.message}", e)
        }
        return allPages
    }

    private fun collectImagesRecursive(tree: Uri, doc: String, auth: String, treeId: String, pages: MutableList<PageMeta>) {
        try {
            val child = DocumentsContract.buildChildDocumentsUriUsingTree(tree, doc)
            ctx.contentResolver.query(child, null, null, null, null)?.use { c ->
                val ni = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val ii = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mi = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val imgs = mutableListOf<Pair<String, String>>()
                val subDirs = mutableListOf<String>()
                val arcs = mutableListOf<Pair<String, String>>()
                
                while (c.moveToNext()) {
                    val n = c.getString(ni) ?: continue
                    val id = c.getString(ii) ?: continue
                    val mime = if (mi >= 0) c.getString(mi) ?: "" else ""
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    
                    if (isDir) {
                        subDirs.add(id)
                    } else if (isImg(n)) {
                        imgs.add(n to id)
                    } else if (isComic(n)) {
                        arcs.add(n to id)
                    }
                }
                
                // ★ B9：排序一次存变量复用（旧写法日志行与实际处理各排一遍）
                val sortedImgs = imgs.sortedWith(Comparator<Pair<String, String>> { a, b -> com.mangareader.zip.CommonsZipHelper.compareNatural(a.first, b.first) })
                d(TAG, "collectImages: ${sortedImgs.size} imgs sorted: ${sortedImgs.take(8).map { it.first }}")
                sortedImgs.forEach { (_, id) ->
                    pages.add(PageMeta(pages.size, "dir", id))
                }
                d(TAG, "collectImages: after sort, first 5 pages: ${pages.take(5).map { it.index }}")

                arcs.sortedWith(Comparator<Pair<String, String>> { a, b -> com.mangareader.zip.CommonsZipHelper.compareNatural(a.first, b.first) }).forEach { (name, id) ->
                    val ext = name.substringAfterLast(".").lowercase()
                    when (ext) {
                        "cbz", "zip", "epub" -> {
                            val arcUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                            val count = try { listZipPages(arcUri).size } catch (e: Exception) { Log.e(TAG, "collectImages listZipPages: ${e.message}"); 0 }
                            for (i in 0 until count) pages.add(PageMeta(pages.size, "zip", id, i))
                        }
                        "pdf" -> {
                            val arcUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                            val count = try { countPdfPages(arcUri) } catch (e: Exception) { 0 }
                            for (i in 0 until count) pages.add(PageMeta(pages.size, "pdf", id, i))
                        }
                        "mobi", "azw", "azw3" -> {
                            val arcUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                            val count = try { scanMobi(arcUri, id).size } catch (e: Exception) { 0 }
                            for (i in 0 until count) pages.add(PageMeta(pages.size, "mobi", id, i))
                        }
                    }
                }
                
                subDirs.forEach { subId ->
                    collectImagesRecursive(tree, subId, auth, treeId, pages)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "collectImagesRecursive: ${e.message}", e)
        }
    }

    fun loadCover(cover: String): Bitmap? {
        requireNotRemote(cover)

        if (!cover.startsWith("cv:")) return null
        if (cover.startsWith("cv:komga:")) return null
        if (cover.startsWith("cv:opds:")) return null
        synchronized(coverCache) { coverCache[cover]?.let { return it } }
        val diskCached = loadCoverFromDisk(cover)
        if (diskCached != null) {
            synchronized(coverCache) { coverCache[cover] = diskCached }
            return diskCached
        }
        try {
            val parts = cover.removePrefix("cv:").split("|")
            if (parts.size < 3) { Log.w(TAG, "loadCover: invalid parts($${parts.size}) for $cover"); return null }
            val auth = parts[0]
            val treeStr = parts[1]
            val docId = parts[2]
            val fileName = (if (parts.size >= 4 && parts[3].isNotEmpty()) parts[3] else docId.substringAfterLast("/")).lowercase()
            val tree = makeTree(auth, treeStr)
            val logMsg = "loadCover: auth=$auth tree=$treeStr doc=$docId file=$fileName"
            Log.d(TAG, logMsg)
            synchronized(debugLogBuffer) { debugLogBuffer.appendLine("${System.currentTimeMillis()} $logMsg") }

            if (fileName.endsWith(".cbz") || fileName.endsWith(".zip") || fileName.endsWith(".epub")) {
                val bmp = loadArchiveCover(auth, treeStr, docId, tree, cover)
                if (bmp != null) { flushLog(ctx); return bmp }
                Log.w(TAG, "loadCover: all archive fallbacks failed for $cover")
                synchronized(debugLogBuffer) { debugLogBuffer.appendLine("${System.currentTimeMillis()} W loadCover: ALL archive fallbacks failed") }
            } else if (fileName.endsWith(".mobi") || fileName.endsWith(".azw") || fileName.endsWith(".azw3")) {
                val bmp = loadMobiCover(auth, treeStr, docId, tree, cover)
                if (bmp != null) { flushLog(ctx); return bmp }
                Log.w(TAG, "loadCover: all mobi fallbacks failed for $cover")
                synchronized(debugLogBuffer) { debugLogBuffer.appendLine("${System.currentTimeMillis()} W loadCover: ALL mobi fallbacks failed") }
            } else if (fileName.endsWith(".cbr") || fileName.endsWith(".rar") ||
                       fileName.endsWith(".cb7") || fileName.endsWith(".7z") ||
                       fileName.endsWith(".tar") || fileName.endsWith(".cbt") ||
                       fileName.endsWith(".tgz") || fileName.endsWith(".tbz2") || fileName.endsWith(".txz")) {
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                val bmp = try {
                    val rawData = when {
                        fileName.endsWith(".cbr") || fileName.endsWith(".rar") -> loadFromRar(uri, 0)
                        fileName.endsWith(".tar") || fileName.endsWith(".cbt") ||
                        fileName.endsWith(".tgz") || fileName.endsWith(".tbz2") || fileName.endsWith(".txz") -> loadFromTar(uri, 0)
                        else -> loadFrom7z(uri, 0)
                    }
                    rawData?.let { decodeBitmap(it) }
                } catch (_: Exception) { null }
                if (bmp != null) { saveCoverToDisk(cover, bmp); synchronized(coverCache) { coverCache[cover] = bmp }; flushLog(ctx); return bmp }
            } else if (fileName.endsWith(".pdf")) {
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                val bmp = try { loadPdfPage(uri, 0) } catch (_: Exception) { null }
                if (bmp != null) { saveCoverToDisk(cover, bmp); synchronized(coverCache) { coverCache[cover] = bmp }; flushLog(ctx); return bmp }
            } else if (isImg(fileName)) {
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                val data = try { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (ex: Exception) { Log.e(TAG, "loadCover img: ${ex.message}"); null }
                val bmp = safeDecodeCover(data)
                if (bmp != null) { saveCoverToDisk(cover, bmp); synchronized(coverCache) { coverCache[cover] = bmp }; flushLog(ctx); return bmp }
            } else {
                Log.w(TAG, "loadCover: unknown extension for $fileName in $cover")
            }
        } catch (e: Exception) { Log.e(TAG, "loadCover: ${e.message}", e) }
        flushLog(ctx)
        return null
    }

    /** ★ LV119 对齐：返回封面原始字节流 + mimeType，让 Coil3 decoders 自动识别动图（GIF/WebP/APNG）。
     *  仅处理图片文件和压缩包（能返回原始字节的场景）；mobi/pdf 返回 null（fallback 到 loadCover → Bitmap → PNG）。 */
    fun loadCoverRaw(cover: String): Pair<ByteArray, String>? {
        requireNotRemote(cover)
        if (!cover.startsWith("cv:")) return null
        if (cover.startsWith("cv:komga:")) return null
        if (cover.startsWith("cv:opds:")) return null
        try {
            val parts = cover.removePrefix("cv:").split("|")
            if (parts.size < 3) return null
            val auth = parts[0]
            val treeStr = parts[1]
            val docId = parts[2]
            val fileName = (if (parts.size >= 4 && parts[3].isNotEmpty()) parts[3] else docId.substringAfterLast("/")).lowercase()
            val tree = makeTree(auth, treeStr)

            if (fileName.endsWith(".cbz") || fileName.endsWith(".zip") || fileName.endsWith(".epub")) {
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                val data = loadFromZip(uri, 0, treeStr)
                if (data != null) return Pair(data, guessMimeFromBytes(data))
            } else if (fileName.endsWith(".cbr") || fileName.endsWith(".rar") ||
                       fileName.endsWith(".cb7") || fileName.endsWith(".7z") ||
                       fileName.endsWith(".tar") || fileName.endsWith(".cbt") ||
                       fileName.endsWith(".tgz") || fileName.endsWith(".tbz2") || fileName.endsWith(".txz")) {
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                val rawData = when {
                    fileName.endsWith(".cbr") || fileName.endsWith(".rar") -> loadFromRar(uri, 0)
                    fileName.endsWith(".tar") || fileName.endsWith(".cbt") ||
                    fileName.endsWith(".tgz") || fileName.endsWith(".tbz2") || fileName.endsWith(".txz") -> loadFromTar(uri, 0)
                    else -> loadFrom7z(uri, 0)
                }
                if (rawData != null) return Pair(rawData, guessMimeFromBytes(rawData))
            } else if (isImg(fileName)) {
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                val data = try { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (_: Exception) { null }
                if (data != null) return Pair(data, guessMimeFromBytes(data))
            }
        } catch (_: Exception) {}
        return null
    }

    fun getCoverSource(cover: String): Pair<okio.BufferedSource, String>? {
        requireNotRemote(cover)
        if (!cover.startsWith("cv:")) return null
        if (cover.startsWith("cv:komga:")) return null
        if (cover.startsWith("cv:opds:")) return null
        try {
            val parts = cover.removePrefix("cv:").split("|")
            if (parts.size < 3) return null
            val auth = parts[0]
            val treeStr = parts[1]
            val docId = parts[2]
            val fileName = (if (parts.size >= 4 && parts[3].isNotEmpty()) parts[3] else docId.substringAfterLast("/")).lowercase()
            if (!isImg(fileName)) return null
            val tree = makeTree(auth, treeStr)
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            val inputStream = try { ctx.contentResolver.openInputStream(uri) } catch (_: Exception) { null } ?: return null
            val mime = when {
                fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") -> "image/jpeg"
                fileName.endsWith(".png") -> "image/png"
                fileName.endsWith(".gif") -> "image/gif"
                fileName.endsWith(".webp") -> "image/webp"
                fileName.endsWith(".bmp") -> "image/bmp"
                else -> "image/png"
            }
            return Pair(inputStream.source().buffer(), mime)
        } catch (_: Exception) {}
        return null
    }

    private fun guessMimeFromBytes(bytes: ByteArray): String {
        if (bytes.size < 12) return "image/png"
        if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()) return "image/gif"
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) return "image/png"
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return "image/jpeg"
        if (bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() && bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()) return "image/webp"
        if (bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte()) return "image/bmp"
        return "image/png"
    }

    private fun loadArchiveCover(auth: String, treeStr: String, docId: String, tree: Uri, coverKey: String): Bitmap? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        Log.d(TAG, "loadArchiveCover: uri=$uri")
        synchronized(debugLogBuffer) { debugLogBuffer.appendLine("${System.currentTimeMillis()} loadArchiveCover: uri=$uri") }

        // EPUB专用：用OPF manifest的properties="cover-image"找封面
        if (coverKey.contains(".epub", ignoreCase = true)) {
            try {
                val epubResult = com.mangareader.zip.EpubParser.parse(ctx, uri)
                if (epubResult != null && epubResult.coverHref != null) {
                    Log.d(TAG, "loadArchiveCover: EPUB cover via OPF: ${epubResult.coverHref}")
                    // 通过spine索引找到封面在图片列表中的位置
                    val coverIdx = epubResult.spineItems.indexOfFirst { it.href == epubResult.coverHref }
                    if (coverIdx >= 0) {
                        val data = loadFromZip(uri, coverIdx)
                        if (data != null) {
                            val bmp = safeDecodeCover(data)
                            if (bmp != null) {
                                saveCoverToDisk(coverKey, bmp)
                                synchronized(coverCache) { coverCache[coverKey] = bmp }
                                Log.d(TAG, "loadArchiveCover: OK via OPF cover-image")
                                return bmp
                            }
                        }
                    }
                }
            } catch (e: Exception) { Log.d(TAG, "loadArchiveCover EPUB OPF: ${e.message}") }
        }

        // 第一步：直接从FD读取（不复制整个ZIP）
        try {
            val data = loadFromZip(uri, 0)
            if (data != null) {
                val bmp = safeDecodeCover(data)
                if (bmp != null) {
                    saveCoverToDisk(coverKey, bmp)
                    synchronized(coverCache) { coverCache[coverKey] = bmp }
                    Log.d(TAG, "loadArchiveCover: OK via FD direct read")
                    synchronized(debugLogBuffer) { debugLogBuffer.appendLine("${System.currentTimeMillis()} loadArchiveCover: OK via FD direct read") }
                    flushLog(ctx)
                    return bmp
                } else {
                    Log.w(TAG, "loadArchiveCover: FD data=${data.size}bytes but safeDecodeCover returned null for $coverKey")
                }
            } else {
                Log.w(TAG, "loadArchiveCover: loadFromZip returned null for $coverKey")
            }
        } catch (e: Exception) {
            Log.d(TAG, "loadArchiveCover FD: ${e.message}")
            synchronized(debugLogBuffer) { debugLogBuffer.appendLine("${System.currentTimeMillis()} loadArchiveCover FD ERR: ${e.message}") }
        }

        // 第二步：getRealFile直接读
        val realFile = getRealFile(uri)
        if (realFile != null) {
            try {
                java.util.zip.ZipFile(realFile).use { zf ->
                    val entries = zf.entries().asSequence()
                        .filter { !it.isDirectory && isImg(it.name) }
                        .sortedWith(compareBy<java.util.zip.ZipEntry> { it.name.count { c -> c == '/' || c == '\\' } }
                            .thenComparing { a, b -> com.mangareader.zip.CommonsZipHelper.compareNatural(a.name, b.name) })
                        .toList()
                    if (entries.isNotEmpty()) {
                        val data = zf.getInputStream(entries[0]).use { it.readBytes() }
                        val bmp = safeDecodeCover(data)
                        if (bmp != null) {
                            saveCoverToDisk(coverKey, bmp)
                            synchronized(coverCache) { coverCache[coverKey] = bmp }
                            Log.d(TAG, "loadArchiveCover: OK via realFile")
                            synchronized(debugLogBuffer) { debugLogBuffer.appendLine("${System.currentTimeMillis()} loadArchiveCover: OK via realFile") }
                            flushLog(ctx)
                            return bmp
                        }
                    }
                }
            } catch (e: Exception) { Log.d(TAG, "loadArchiveCover realFile: ${e.message}") }
        } else {
            Log.w(TAG, "loadArchiveCover: getRealFile returned null for $coverKey")
        }

        Log.w(TAG, "loadArchiveCover: ALL fallbacks failed for $coverKey")
        synchronized(debugLogBuffer) { debugLogBuffer.appendLine("${System.currentTimeMillis()} loadArchiveCover: ALL FAILED for $coverKey") }
        flushLog(ctx)
        return null
    }

    private fun loadMobiCover(auth: String, treeStr: String, docId: String, tree: Uri, coverKey: String): Bitmap? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        val cacheKey = uri.toString()
        // ★ 优先用 loadMobiCoverFromHeader（从文件头扫描 JPEG/PNG 魔数），不依赖 native 库的格式支持
        //   AZW3(KF8) 的 native readMobiCover 可能返回损坏数据（彩色条纹），header 扫描更可靠
        val realFile = getRealFile(uri)
        if (realFile != null) {
            val headerData = try { loadMobiCoverFromHeader(realFile) } catch (_: Exception) { null }
            if (headerData != null) {
                val bmp = safeDecodeCover(headerData)
                if (bmp != null) { saveCoverToDisk(coverKey, bmp); synchronized(coverCache) { coverCache[coverKey] = bmp }; return bmp }
            }
        }
        // fd-based opening, same key as scanMobi/loadPage
        try {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                try {
                    val fd = pfd.detachFd()
                    try {
                        com.mangareader.native.NativeEngine.openMobiWithFd(cacheKey, fd)
                    } catch (_: Exception) {}
                    val nativeData = com.mangareader.native.NativeEngine.readMobiCover(cacheKey)
                    if (nativeData != null) {
                        val bmp = safeDecodeCover(nativeData)
                        if (bmp != null) { saveCoverToDisk(coverKey, bmp); synchronized(coverCache) { coverCache[coverKey] = bmp }; return bmp }
                    }
                } finally { try { pfd.close() } catch (_: Exception) {} }
            }
        } catch (e: Exception) { Log.d(TAG, "loadMobiCover native: ${e.message}") }
        try {
            val data = loadMobiPage(uri, 0)
            val bmp = safeDecodeCover(data)
            if (bmp != null) { saveCoverToDisk(coverKey, bmp); synchronized(coverCache) { coverCache[coverKey] = bmp }; return bmp }
        } catch (e: Exception) { Log.d(TAG, "loadMobiCover page: ${e.message}") }
        return null
    }

    private fun safeDecodeCover(data: ByteArray?): Bitmap? {
        if (data == null || data.isEmpty()) return null
        return try {
            decodeCoverBitmap(data)
        } catch (e: OutOfMemoryError) {
            System.gc()
            try { decodeCoverBitmap(data) } catch (_: Exception) { null }
        }
    }

    private fun loadZipDirCover(item: Item): Bitmap? {
        try {
            val archivePath = "a:${item.auth}|${item.tree}|${item.doc}"
            val result = getZipAllNames(archivePath) ?: return null
            val (cacheKey, allNames) = result
            val root = zipTree(cacheKey) { buildZipTree(allNames.toList()) }
            var current = root
            val pathParts = item.subPath.split('/').filter { it.isNotEmpty() }
            for (part in pathParts) {
                val child = current.children.find { it.name == part && it.isDir }
                if (child != null) current = child
                else return null
            }
            val coverNames = listOf("cover.jpg", "cover.jpeg", "cover.png", "cover.webp", "cover.bmp")
            for (imgIdx in current.imageIndices) {
                if (imgIdx < allNames.size) {
                    val name = allNames[imgIdx].substringAfterLast('/').lowercase()
                    if (name in coverNames) {
                        val rawData = com.mangareader.native.NativeEngine.readZipEntry(cacheKey, imgIdx)
                        if (rawData != null) return decodeCoverBitmap(rawData)
                    }
                }
            }
            if (current.imageIndices.isNotEmpty()) {
                val firstIdx = current.imageIndices[0]
                if (firstIdx < allNames.size) {
                    val rawData = com.mangareader.native.NativeEngine.readZipEntry(cacheKey, firstIdx)
                    if (rawData != null) return decodeCoverBitmap(rawData)
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * ★ ItemCard 动图封面（2026-09-06 补全）：取文件夹子项首页的原始字节。
     *   coverPath 形如 "z:{auth}|{tree}|{doc}|{subPath}"（ItemCard 构造）；
     *   复用与 enterReader 完全相同的 listPages + loadPageRaw 链路（本地守卫内），
     *   子卷是动图（gif/动图webp首页）时返回动图原始字节，Coil 侧据此播放动画。
     */
    fun loadItemCoverRaw(coverPath: String): Pair<ByteArray, String>? {
        requireNotRemote(coverPath)
        if (!coverPath.startsWith("z:")) return null
        return try {
            val body = coverPath.removePrefix("z:")
            val subPath = body.split('|', limit = 4).getOrNull(3)?.takeIf { it.isNotEmpty() } ?: return null
            val metas = listPages(subPath)
            if (metas.isEmpty()) return null
            val raw = loadPageRaw(metas.first(), subPath) ?: return null
            Pair(raw, guessMimeFromBytes(raw))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * ★ 子项网格动图封面（对齐上游，2026-09-19）：子项首页是动图时返回原始字节，
     *   Coil 侧由平台 AnimatedImageDecoder 按请求尺寸播放（ItemCoverFetcher 动图优先）。
     *   仅覆盖本地条目（z: 归档 / SAF 目录首图）；远程子项封面仍走物化 JPEG（静图，不变）。
     *   非动图一律返回 null → 调用方回退既有位图路径。
     */
    fun loadItemCoverAnimatedRaw(item: Item): Pair<ByteArray, String>? {
        if (item.auth == "webdav" || item.auth == "smb") return null
        return try {
            if (item.subPath.isNotEmpty()) {
                // z: 归档子项：复用 listPages + loadPageRaw 链路取首页原始字节
                val raw = loadItemCoverRaw("z:${item.auth}|${item.tree}|${item.doc}|${item.subPath}")
                if (raw != null && isAnimatedBytes(raw.first)) return raw
                if (raw != null) return null
            }
            if (item.dir) {
                // SAF 目录子项：首个直接子图片 / 子归档首页为动图时返回原始字节（不递归子目录）
                val tree = makeTree(item.auth, item.tree)
                val child = DocumentsContract.buildDocumentUriUsingTree(tree, item.doc)
                ctx.contentResolver.query(child, null, null, null, null)?.use { c ->
                    val ni = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val ii = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    while (c.moveToNext()) {
                        val n = c.getString(ni) ?: continue
                        val id = c.getString(ii) ?: continue
                        val raw: ByteArray? = when {
                            isImg(n) -> {
                                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                                try {
                                    ctx.contentResolver.openInputStream(uri)?.use { s ->
                                        val buf = ByteArray(64 * 1024)
                                        val out = java.io.ByteArrayOutputStream()
                                        var read: Int
                                        while (s.read(buf).also { read = it } != -1) {
                                            out.write(buf, 0, read)
                                        }
                                        out.toByteArray()
                                    }
                                } catch (_: Exception) {
                                    null
                                }
                            }
                            isComic(n) -> {
                                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                                try { loadFromZip(uri, 0) } catch (_: Exception) { null }
                            }
                            else -> null
                        }
                        if (raw != null && isAnimatedBytes(raw)) {
                            return Pair(raw, guessMimeFromBytes(raw))
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun loadCoverForItem(item: Item): Bitmap? {
        val coverKey = "item:${item.auth}|${item.tree}|${item.doc}|${item.subPath}"
        val cached = loadCoverFromDisk(coverKey)
        if (cached != null) return cached

        if (item.auth == "webdav" || item.auth == "smb") {
            val cvPath = "cv:${item.doc}"
            val raw = try {
                kotlinx.coroutines.runBlocking {
                    // ★ 有界等待：服务器挂起时不能永久占住 Coil 封面线程（审查 AE7）
                    kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                        if (item.auth == "webdav") {
                            com.mangareader.webdavbridge.WebDavCoverLoader.loadCoverRawWebDav(cvPath)
                        } else {
                            com.mangareader.smbbridge.SmbCoverLoader.loadCoverRawSmb(cvPath)
                        }
                    }
                }
            } catch (_: Exception) { null }
            if (raw != null) {
                val bmp = decodeCoverBitmap(raw.first)
                if (bmp != null) {
                    saveCoverToDisk(coverKey, bmp)
                    return bmp
                }
            }
            return null
        }

        val tree = makeTree(item.auth, item.tree)

        if (item.dir) {
            val child = DocumentsContract.buildChildDocumentsUriUsingTree(tree, item.doc)
            var found = false
            val subDirs = mutableListOf<Pair<String, String>>()
            ctx.contentResolver.query(child, null, null, null, null)?.use { c ->
                val ni = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val ii = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mi = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (c.moveToNext()) {
                    val n = c.getString(ni) ?: continue
                    val id = c.getString(ii) ?: continue
                    val mime = if (mi >= 0) c.getString(mi) ?: "" else ""
                    if (isImg(n)) {
                        found = true
                        val imgUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        val data = try { ctx.contentResolver.openInputStream(imgUri)?.use { it.readBytes() } } catch (_: Exception) { null }
                        if (data != null) {
                            val bmp = decodeCoverBitmap(data)
                            if (bmp != null) {
                                saveCoverToDisk(coverKey, bmp)
                                return bmp
                            }
                        }
                    }
                    if (isComic(n)) {
                        found = true
                        val arcUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        val data = try { loadFromZip(arcUri, 0) } catch (_: Exception) { null }
                        if (data != null) {
                            val bmp = decodeCoverBitmap(data)
                            if (bmp != null) {
                                saveCoverToDisk(coverKey, bmp)
                                return bmp
                            }
                        }
                    }
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) subDirs.add(n to id)
                }
            }
            if (!found) {
                if (item.subPath.isNotEmpty()) {
                    val coverData = loadZipDirCover(item)
                    if (coverData != null) {
                        saveCoverToDisk(coverKey, coverData)
                        return coverData
                    }
                }
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, item.doc)
                val data = try { loadFromZip(uri, 0) } catch (_: Exception) { null }
                if (data != null) {
                    val bmp = decodeCoverBitmap(data)
                    if (bmp != null) {
                        saveCoverToDisk(coverKey, bmp)
                        return bmp
                    }
                }
                for ((_, subId) in subDirs.sortedWith { a, b -> a.first.compareTo(b.first) }) {
                    val bmp = findFirstCoverInSubDirs(tree, subId, coverKey, 1)
                    if (bmp != null) return bmp
                }
            }
            return null
        }

        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, item.doc)
        if (isImg(item.name)) {
            val data = try {
                ctx.contentResolver.openInputStream(uri)?.use { s ->
                    // ★ B11：10MB 一次性分配改 64KB 循环缓冲（每张封面省 ~10MB 峰值分配）
                    val buf = ByteArray(64 * 1024)
                    val out = java.io.ByteArrayOutputStream()
                    var n: Int
                    var total = 0
                    while (s.read(buf).also { n = it } != -1) {
                        total += n
                        if (total > 5 * 1024 * 1024) { out.close(); return@use null }
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
            } catch (_: Exception) { null }
            if (data != null) {
                val bmp = decodeCoverBitmap(data)
                if (bmp != null) {
                    saveCoverToDisk(coverKey, bmp)
                    return bmp
                }
            }
        }
        if (isComic(item.name)) {
            val data = try { loadFromZip(uri, 0) } catch (_: Exception) { null }
            if (data != null) {
                val bmp = decodeCoverBitmap(data)
                if (bmp != null) {
                    saveCoverToDisk(coverKey, bmp)
                    return bmp
                }
            }
            if (item.name.lowercase().endsWith(".pdf")) {
                val bmp = try { loadPdfPage(uri, 0) } catch (_: Exception) { null }
                if (bmp != null) {
                    saveCoverToDisk(coverKey, bmp)
                    return bmp
                }
            }
            if (item.name.lowercase().endsWith(".mobi") || item.name.lowercase().endsWith(".azw") || item.name.lowercase().endsWith(".azw3")) {
                val realFile = getRealFile(uri)
                if (realFile != null) {
                    val headerData = try { loadMobiCoverFromHeader(realFile) } catch (_: Exception) { null }
                    if (headerData != null) {
                        val bmp = decodeCoverBitmap(headerData)
                        if (bmp != null) {
                            saveCoverToDisk(coverKey, bmp)
                            return bmp
                        }
                    }
                }
                val data = try { loadMobiPage(uri, 0) } catch (_: Exception) { null }
                if (data != null) {
                    val bmp = decodeCoverBitmap(data)
                    if (bmp != null) {
                        saveCoverToDisk(coverKey, bmp)
                        return bmp
                    }
                }
            }
        }
        return null
    }

    private fun findFirstCoverInSubDirs(tree: Uri, folderDocId: String, coverKey: String, depth: Int): Bitmap? {
        if (depth > 5) return null
        val child = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderDocId)
        val subDirs = mutableListOf<Pair<String, String>>()
        ctx.contentResolver.query(child, null, null, null, null)?.use { c ->
            val ni = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val ii = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val mi = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (c.moveToNext()) {
                val n = c.getString(ni) ?: continue
                val id = c.getString(ii) ?: continue
                val mime = if (mi >= 0) c.getString(mi) ?: "" else ""
                if (isImg(n)) {
                    val imgUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    val data = try { ctx.contentResolver.openInputStream(imgUri)?.use { it.readBytes() } } catch (_: Exception) { null }
                    if (data != null) {
                        val bmp = decodeCoverBitmap(data)
                        if (bmp != null) {
                            saveCoverToDisk(coverKey, bmp)
                            return bmp
                        }
                    }
                } else if (isComic(n)) {
                    val arcUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    val data = try { loadFromZip(arcUri, 0) } catch (_: Exception) { null }
                    if (data != null) {
                        val bmp = decodeCoverBitmap(data)
                        if (bmp != null) {
                            saveCoverToDisk(coverKey, bmp)
                            return bmp
                        }
                    }
                } else if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    subDirs.add(n to id)
                }
            }
        }
        for ((_, subId) in subDirs.sortedWith { a, b -> a.first.compareTo(b.first) }) {
            val bmp = findFirstCoverInSubDirs(tree, subId, coverKey, depth + 1)
            if (bmp != null) return bmp
        }
        return null
    }

    /** ★ 解析目录 URI 到真实路径（getRealFile 是为文件设计的，对目录会找子文件导致返回 null）
     *  先试 primary: 路径直构（零 Binder IPC），再 fallback _data 查询（一次 Binder，适配非 primary 存储） */
    private fun resolveRealDir(uri: Uri): File? {
        try {
            val docId = DocumentsContract.getDocumentId(uri)
            if (docId.startsWith("primary:")) {
                val relPath = docId.removePrefix("primary:")
                val f = File("/storage/emulated/0/$relPath")
                if (f.exists() && f.isDirectory) return f
            }
        } catch (_: Exception) {}
        try {
            ctx.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex("_data")
                    if (idx >= 0) {
                        val path = cursor.getString(idx)
                        if (path != null) {
                            val f = File(path)
                            if (f.exists() && f.isDirectory) return f
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /** ★ 真实路径快速列举：仅当 MANAGE_EXTERNAL_STORAGE 可用且目录可解析为真实路径时生效 */
    private fun scanDirFast(tree: Uri, doc: String): List<PageMeta>? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        if (!android.os.Environment.isExternalStorageManager()) return null
        try {
            val dirUri = DocumentsContract.buildDocumentUriUsingTree(tree, doc)
            val dirFile = resolveRealDir(dirUri) ?: return null
            if (!dirFile.exists() || !dirFile.isDirectory) return null
            val files = dirFile.listFiles() ?: return null
            val imgs = mutableListOf<File>()
            val arcs = mutableListOf<File>()
            for (f in files) {
                val n = f.name
                if (isImg(n)) imgs.add(f)
                else if (isComic(n)) arcs.add(f)
            }
            if (imgs.isEmpty() && arcs.isEmpty()) return null
            val pages = mutableListOf<PageMeta>()
            if (imgs.isNotEmpty()) {
                val sorted = imgs.sortedWith(Comparator<File> { a, b ->
                    com.mangareader.zip.CommonsZipHelper.compareNatural(a.name, b.name)
                })
                sorted.forEachIndexed { i, f ->
                    val childDocId = "$doc/${f.name}"
                    pages.add(PageMeta(i, "dir", childDocId))
                }
            } else {
                // ★ P10（2026-10-04）：子压缩包页数并行统计——旧版逐个串行 listZipPages/countPdfPages，
                //   多子包父书首开线性变慢；各子包独立开包无共享状态，按序组装
                val subArcs = arcs.sortedWith(Comparator<File> { a, b ->
                    com.mangareader.zip.CommonsZipHelper.compareNatural(a.name, b.name)
                }).map { SubArcDoc(it.name.substringAfterLast(".").lowercase(), "$doc/${it.name}") }
                    .filter { it.ext == "cbz" || it.ext == "zip" || it.ext == "epub" || it.ext == "pdf" }
                val counts = countSubArchivesParallel(tree, subArcs)
                subArcs.forEachIndexed { idx, item ->
                    val count = counts[idx]
                    val src = if (item.ext == "pdf") "pdf" else "zip"
                    for (localI in 0 until count) pages.add(PageMeta(pages.size, src, item.docId, localI))
                }
            }
            d(TAG, "scanDirFast: ${pages.size} pages from ${dirFile.absolutePath}")
            return pages
        } catch (e: Exception) {
            Log.d(TAG, "scanDirFast fallback to SAF: ${e.message}")
            return null
        }
    }

    private fun scanDir(tree: Uri, doc: String): List<PageMeta> {
        // ★ 真实路径快速路径：File.listFiles 跳过 SAF Binder IPC（~50ms vs ~2000ms）
        val fastResult = scanDirFast(tree, doc)
        if (fastResult != null) return fastResult
        val pages = mutableListOf<PageMeta>()
        try {
            val child = DocumentsContract.buildChildDocumentsUriUsingTree(tree, doc)
            ctx.contentResolver.query(child, null, null, null, null)?.use { c ->
                val ni = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val ii = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val imgs = mutableListOf<Pair<String, String>>()
                val arcs = mutableListOf<Pair<String, String>>()
                while (c.moveToNext()) {
                    val n = c.getString(ni) ?: continue
                    val id = c.getString(ii) ?: continue
                    if (isImg(n)) imgs.add(n to id)
                    else if (isComic(n)) arcs.add(n to id)
                }
                d(TAG, "scanDir query returned ${imgs.size + arcs.size} items, allNames=${(imgs + arcs).take(10).map { it.first }}")
                if (imgs.isNotEmpty()) {
                    val sorted = imgs.sortedWith(Comparator<Pair<String, String>> { a, b -> com.mangareader.zip.CommonsZipHelper.compareNatural(a.first, b.first) })
                    d(TAG, "scanDir sorted: ${sorted.take(20).map { it.first }}")
                    sorted.forEachIndexed { i, (_, id) ->
                        pages.add(PageMeta(i, "dir", id))
                    }
                } else {
                    // ★ P10：子压缩包页数并行统计（同 scanDirFast）
                    val subArcs = arcs.sortedWith(Comparator<Pair<String, String>> { a, b -> com.mangareader.zip.CommonsZipHelper.compareNatural(a.first, b.first) })
                        .map { SubArcDoc(it.first.substringAfterLast(".").lowercase(), it.second) }
                        .filter { it.ext == "cbz" || it.ext == "zip" || it.ext == "epub" || it.ext == "pdf" }
                    val counts = countSubArchivesParallel(tree, subArcs)
                    subArcs.forEachIndexed { idx, item ->
                        val count = counts[idx]
                        val src = if (item.ext == "pdf") "pdf" else "zip"
                        for (localI in 0 until count) pages.add(PageMeta(pages.size, src, item.docId, localI))
                    }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "scanDir: ${e.message}", e) }
        return pages
    }

    // ★ ConcurrentHashMap（此前裸 HashMap，Coil 封面线程与阅读器 IO 线程并发读写=结构损坏风险）
    private val realFileCache2 = java.util.concurrent.ConcurrentHashMap<String, java.io.File>()

    // ★ P10（2026-10-04）：d: 父书子压缩包页数并行统计
    private class SubArcDoc(val ext: String, val docId: String)

    private fun countSubArchivesParallel(tree: Uri, items: List<SubArcDoc>): IntArray {
        if (items.isEmpty()) return IntArray(0)
        val counts = IntArray(items.size)
        if (items.size == 1) { counts[0] = countOneSubArchive(tree, items[0]); return counts }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(4, items.size))
        try {
            val futures = items.mapIndexed { i, item -> pool.submit { counts[i] = countOneSubArchive(tree, item) } }
            // 逐个收结果：一个任务失败（含 Error 穿透）不得弃掉其余子包的页数——
            // 整批 forEach{get()} 会在首异常处中断，剩余 counts 恒 0 → 父书静默缺页
            futures.forEachIndexed { i, f ->
                try { f.get() } catch (e: Exception) { Log.d(TAG, "countSubArchivesParallel: sub[$i] failed: ${e.message}") }
            }
        } catch (e: Exception) {
            Log.d(TAG, "countSubArchivesParallel: ${e.message}")
        } finally { pool.shutdown() }
        return counts
    }

    private fun countOneSubArchive(tree: Uri, item: SubArcDoc): Int {
        val arcUri = DocumentsContract.buildDocumentUriUsingTree(tree, item.docId)
        return try {
            if (item.ext == "pdf") countPdfPages(arcUri) else listZipPages(arcUri).size
        } catch (_: Exception) { 0 }
    }

    private fun scanFile(tree: Uri, doc: String, ext: String, name: String): List<PageMeta> {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, doc)
        val pages = mutableListOf<PageMeta>()
        return when {
            ext == "cbz" || ext == "zip" || ext == "epub" -> {
                val cacheKey = uri.toString()
                if (!com.mangareader.native.NativeEngine.isOpenZip(cacheKey)) {
                    val realFile = getRealFile(uri)
                    val realPath = realFile?.absolutePath ?: ""
                    val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
                    if (pfd != null) {
                        val fd = pfd.detachFd()
                        synchronized(pfdKeepAlive) { pfdKeepAlive[cacheKey] = pfd }
                        try { com.mangareader.native.NativeEngine.openZipWithFd(cacheKey, fd, realPath) } catch (_: Exception) {}
                    }
                }
                val names = com.mangareader.native.NativeEngine.getZipEntryNames(cacheKey)
                if (names != null) {
                    val imgNames = names.indices.filter { isImg(names[it]) }.map { names[it] }
                    Log.d(TAG, "scanFile ZIP: total entries=${names.size}, imgEntries=${imgNames.size}, beforeSort first10=${imgNames.take(10).map { it.substringAfterLast("/") }}")
                    val sortedNames = imgNames.sortedWith(Comparator { a, b ->
                        val depthA = a.count { c -> c == '/' || c == '\\' }
                        val depthB = b.count { c -> c == '/' || c == '\\' }
                        if (depthA != depthB) depthA - depthB
                        else com.mangareader.util.ComicSortUtil.fileNameComparator.compare(a, b)
                    })
                    Log.d(TAG, "scanFile ZIP: afterSort first10=${sortedNames.take(10).map { it.substringAfterLast("/") }}")
                    synchronized(sortedImageNamesCache) { sortedImageNamesCache[cacheKey] = sortedNames }
                    sortedNames.mapIndexed { idx, n -> PageMeta(idx, "zip", doc, idx, name = n.substringAfterLast("/").substringBeforeLast(".")) }
                } else {
                    val fallbackCount = listZipPages(uri).size
                    (0 until fallbackCount).map { PageMeta(it, "zip", doc, it) }
                }
            }
            ext == "cbr" || ext == "rar" -> {
                return scanRar(tree, doc)
            }
            ext == "cb7" || ext == "7z" -> {
                return scan7z(tree, doc)
            }
            ext == "tar" || ext == "cbt" || ext == "tgz" || ext == "tbz2" || ext == "txz" -> {
                return scanTar(tree, doc)
            }
            ext == "pdf" -> {
                val count = countPdfPages(uri)
                for (i in 0 until count) pages.add(PageMeta(i, "pdf", doc, i))
                pages
            }
            else -> emptyList()
        }
    }

    private fun scanEpub(tree: Uri, doc: String): List<PageMeta> {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, doc)
        val cacheKey = uri.toString()
        // 检查缓存
        synchronized(sortedImageNamesCache) {
            sortedImageNamesCache[cacheKey]?.let { sortedNames ->
                return sortedNames.mapIndexed { idx, name -> PageMeta(idx, "zip", doc, idx, name = name.substringAfterLast("/").substringBeforeLast(".")) }
            }
        }
        // 打开 ZIP
        if (!com.mangareader.native.NativeEngine.isOpenZip(cacheKey)) {
            val realFile = getRealFile(uri)
            val realPath = realFile?.absolutePath ?: ""
            val pfd = try { ctx.contentResolver.openFileDescriptor(uri, "r") } catch (_: Exception) { null }
            if (pfd != null) {
                val fd = pfd.detachFd()
                synchronized(pfdKeepAlive) { pfdKeepAlive[cacheKey] = pfd }
                try { com.mangareader.native.NativeEngine.openZipWithFd(cacheKey, fd, realPath) } catch (_: Exception) {}
            }
        }
        // ★ 取证（2026-10-11 用户报 epub 开卷弹"文件暂时找不到"）：此前全链路静默，
        //   日志零线索。失败短码：zipopen=句柄没开 / parsenull=spine 解析失败 / 0img=无图 epub
        fun epubFail(stage: String) {
            runCatching { com.mangareader.DiagTracer.markStatic("epub: empty $stage n=${doc.length}") }
        }
        // ★ 2026-10-12 问题3 复现：截断下载的 epub 中央目录缺失 → isOpenZip=false，供结尾提示分流
        val zipOpenNow = com.mangareader.native.NativeEngine.isOpenZip(cacheKey)
        if (!zipOpenNow) epubFail("zipopen")

        lastEpubScanUsedSpine = false
        try {
            val access = com.mangareader.zip.NativeZipAccess(cacheKey)
            val result = com.mangareader.zip.EpubPageOrderResolver.resolve(access)
            if (result != null && result.pageNames.isNotEmpty()) {
                lastEpubScanUsedSpine = result.fromSpine
                val sortedNames = result.pageNames
                synchronized(sortedImageNamesCache) { sortedImageNamesCache[cacheKey] = sortedNames }
                Log.d(TAG, "scanEpub: ${if (result.fromSpine) "spine" else "fallback"} order, ${sortedNames.size} pages, first5=${sortedNames.take(5).map { it.substringAfterLast("/") }}")
                return sortedNames.mapIndexed { idx, n -> PageMeta(idx, "zip", doc, idx, name = n.substringAfterLast("/").substringBeforeLast(".")) }
            }
            epubFail(if (result == null) "namesnull" else "0img")
            if (result != null && result.pageNames.isEmpty()) {
                lastOpenHint = "此 EPUB 内没有可显示的图片页（可能是纯文字书或内容不受支持）"
            }
        } catch (e: Exception) {
            Log.e(TAG, "scanEpub parse failed: ${e.message}")
            epubFail("parseerr ${e.message?.take(40) ?: ""}")
        }

        if (!zipOpenNow) {
            lastOpenHint = "压缩包无法打开：文件可能损坏或未下载完整，建议重新下载后再试"
        }
        return emptyList()
    }

    /**
     * ★ 2026-10-05 mobi 快路径（libmobi 前置加速层）：
     *   MobiFile 纯 Kotlin 解析 PDB 记录表 + 资源区魔数探测，开书 IO 从"整档进内存"
     *   （mobi_load_file 全记录驻留）降为头几 KB + 每记录 8B；读页 = 一条记录的精确区间读。
     *   解析失败/0 图/DRM/管道 fd 一律返回 null，调用方原样落回 libmobi → Java 魔法扫描，
     *   原链路零改动。日志走 mobi-fp 短码（无路径）。
     */
    fun mobiFastPage(cacheKey: String, uri: Uri, index: Int): ByteArray? {
        val mf = mobiFastCacheGet(cacheKey) ?: mobiFastOpen(cacheKey, uri) ?: return null
        if (index !in 0 until mf.imageCount) return null
        return try {
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                mf.readImage(FdSeekable(pfd.fileDescriptor), index)
            }
        } catch (_: Exception) { null }
    }

    private fun mobiFastOpen(cacheKey: String, uri: Uri, pfd: android.os.ParcelFileDescriptor? = null): MobiFile? {
        val mf = try {
            if (pfd != null) MobiFile.open(FdSeekable(pfd.fileDescriptor))
            else ctx.contentResolver.openFileDescriptor(uri, "r")?.use { MobiFile.open(FdSeekable(it.fileDescriptor)) }
        } catch (_: Exception) { null } ?: return null
        mobiFastCachePut(cacheKey, mf)
        return mf
    }

    private fun scanMobi(tree: Uri, doc: String): List<PageMeta> {
        val pages = mutableListOf<PageMeta>()
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, doc)
        val t0 = android.os.SystemClock.elapsedRealtime()
        val cacheKey = uri.toString()
        try {
            // fd-based opening
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                // ★ 2026-10-11 用户拍板：本地 mobi 不走自研流式页表（同 loadPage/loadPageRaw，
                //   页表与读页必须同链同源，避免快慢页表差异）——恢复原链：libmobi 计数 → Java 扫描
                val fd = pfd.detachFd()
                try {
                    com.mangareader.native.NativeEngine.openMobiWithFd(cacheKey, fd)
                } catch (_: Exception) {}
                val nativeCount = com.mangareader.native.NativeEngine.countMobiPages(cacheKey)
                if (nativeCount > 0) {
                    for (i in 0 until nativeCount) pages.add(PageMeta(i, "mobi", doc, i))
                    Log.d(TAG, "scanMobi (native): found $nativeCount images in ${android.os.SystemClock.elapsedRealtime() - t0}ms")
                    return pages
                }
            }
            // Fallback: stream scan
            ctx.contentResolver.openInputStream(uri)?.use { s ->
                val bis = java.io.BufferedInputStream(s, 65536)
                var idx = 0
                var b0 = 0; var b1 = bis.read(); var b2 = bis.read(); var b3 = bis.read()
                while (b3 != -1) {
                    if (b0 == 0xFF && b1 == 0xD8) {
                        pages.add(PageMeta(idx, "mobi", doc, idx)); idx++
                    } else if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) {
                        pages.add(PageMeta(idx, "mobi", doc, idx)); idx++
                    }
                    b0 = b1; b1 = b2; b2 = b3; b3 = bis.read()
                }
                Log.d(TAG, "scanMobi: found $idx images in ${android.os.SystemClock.elapsedRealtime() - t0}ms")
            }
        } catch (e: Exception) { Log.e(TAG, "scanMobi: ${e.message}") }
        return pages
    }

    private fun loadMobiPage(uri: Uri, pageIndex: Int): ByteArray? {
        val t0 = android.os.SystemClock.elapsedRealtime()
        Log.d(TAG, "loadMobiPage: start pageIndex=$pageIndex thread=${Thread.currentThread().name}")
        // ★ 2026-10-05 记录级救援层：libmobi 失败后、魔法字节全扫前的快路径重试
        //   （瞬态 I/O 或 libmobi 拒收但记录结构完好的书）
        try { mobiFastPage(uri.toString(), uri, pageIndex)?.let { return it } } catch (_: Exception) {}
        try {
            // Try to get real file path first for direct random access
            val realFile = getRealFile(uri)
            if (realFile != null) {
                // For cover (page 0), limit scan to 2MB for speed
                val scanLimit = if (pageIndex == 0) 2L * 1024 * 1024 else 0L
                val result = loadMobiPageFromFile(realFile, pageIndex, scanLimit)
                if (result != null) {
                    Log.d(TAG, "loadMobiPage: OK via realFile page=$pageIndex size=${result.size} total=${android.os.SystemClock.elapsedRealtime() - t0}ms")
                    return result
                }
            }
            
            // Fallback: copy to tmp file and scan
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                val t1 = android.os.SystemClock.elapsedRealtime()
                val tmpFile = File(ctx.cacheDir, "tmp_mobi_${System.currentTimeMillis()}.mobi")
                FileOutputStream(tmpFile).use { out ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                    }
                }
                val t2 = android.os.SystemClock.elapsedRealtime()
                Log.d(TAG, "loadMobiPage: tmpFile=${tmpFile.length()} bytes, write=${t2 - t1}ms")
                try {
                    val result = loadMobiPageFromFile(tmpFile, pageIndex)
                    Log.d(TAG, "loadMobiPage: OK via tmpFile page=$pageIndex total=${android.os.SystemClock.elapsedRealtime() - t0}ms")
                    return result
                } finally { tmpFile.delete() }
            }
        } catch (e: Throwable) { Log.e(TAG, "loadMobiPage: ${e.message} (${android.os.SystemClock.elapsedRealtime() - t0}ms) thread=${Thread.currentThread().name}", e) }
        return null
    }
    
    private fun loadMobiPageFromFile(file: File, pageIndex: Int, scanLimit: Long = 0L): ByteArray? {
        try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val fileLen = raf.length()
                val limit = if (scanLimit > 0) minOf(scanLimit, fileLen) else fileLen
                var idx = 0
                var pos = 0L
                val buf = ByteArray(65536)
                
                // First pass: find all image offsets (with optional scan limit)
                val imageOffsets = mutableListOf<Long>()
                while (pos < limit - 3) {
                    raf.seek(pos)
                    val bytesRead = raf.read(buf, 0, minOf(buf.size.toLong(), limit - pos).toInt())
                    if (bytesRead <= 0) break
                    var i = 0
                    while (i < bytesRead - 3) {
                        val isJpeg = buf[i] == 0xFF.toByte() && buf[i + 1] == 0xD8.toByte()
                        val isPng = buf[i] == 0x89.toByte() && buf[i + 1] == 0x50.toByte() && buf[i + 2] == 0x4E.toByte() && buf[i + 3] == 0x47.toByte()
                        if (isJpeg || isPng) {
                            imageOffsets.add(pos + i)
                        }
                        i++
                    }
                    pos += bytesRead
                }
                
                if (pageIndex >= imageOffsets.size) {
                    Log.w(TAG, "loadMobiPage: page $pageIndex not found (found ${imageOffsets.size} images)")
                    return null
                }
                
                // Second pass: extract the target image
                val imgStart = imageOffsets[pageIndex]
                val imgEnd = if (pageIndex + 1 < imageOffsets.size) imageOffsets[pageIndex + 1] else fileLen
                val imgSize = (imgEnd - imgStart).toInt().coerceAtMost(10 * 1024 * 1024) // Max 10MB per image
                
                raf.seek(imgStart)
                val imgData = ByteArray(imgSize)
                raf.readFully(imgData)
                
                // Find the actual end of the image
                var end = 2
                if (imgData.size > 1 && imgData[0] == 0xFF.toByte() && imgData[1] == 0xD8.toByte()) {
                    // JPEG: find FF D9 marker
                    while (end < imgData.size - 1) {
                        if (imgData[end] == 0xFF.toByte() && imgData[end + 1] == 0xD9.toByte()) { end += 2; break }
                        end++
                    }
                } else if (imgData.size > 7 && imgData[0] == 0x89.toByte() && imgData[1] == 0x50.toByte()) {
                    // PNG: find IEND chunk
                    end = 8
                    while (end < imgData.size - 8) {
                        if (imgData[end] == 0x49.toByte() && imgData[end + 1] == 0x45.toByte() && imgData[end + 2] == 0x4E.toByte() && imgData[end + 3] == 0x44.toByte() &&
                            imgData[end + 4] == 0x00.toByte() && imgData[end + 5] == 0x00.toByte() && imgData[end + 6] == 0x00.toByte() && imgData[end + 7] == 0x00.toByte()) {
                            end += 8; break
                        }
                        end++
                    }
                }
                
                return if (end <= imgData.size) imgData.copyOfRange(0, end) else imgData
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadMobiPageFromFile: ${e.message}")
        }
        return null
    }
    private fun loadMobiCoverFromHeader(file: File): ByteArray? {
        // ★ 2026-10-05 重写：原"76 + (recordCount+1)*4 固定 recordSize 偏移推算"对变长记录的
        //   MOBI 全部落空（PDB 记录表实为 78B 头 + 每记录 8B 变长表），改走 MobiFile 记录级
        //   解析取第一张图；失败返回 null 原样落回 native readMobiCover
        try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val src = RafSeekable(raf)
                val mf = MobiFile.open(src) ?: return null
                if (mf.imageCount <= 0) return null
                return mf.readImage(src, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadMobiCoverFromHeader: ${e.message}")
        }
        return null
    }

    fun getRealFileForPath(filePath: String): String {
        try {
            // 1. 先查SharedPreferences缓存
            val prefs = ctx.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE)
            val cached = prefs.getString("realpath_$filePath", null)
            if (cached != null) {
                val f = File(cached)
                if (f.exists() && f.length() > 0) return cached
            }
            // 2. SAF解析并缓存
            val p = parsePath(filePath) ?: return ""
            val tree = makeTree(p.auth, p.tree)
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, p.doc)
            val f = getRealFile(uri)
            if (f != null) {
                prefs.edit().putString("realpath_$filePath", f.absolutePath).apply()
                return f.absolutePath
            }
        } catch (_: Exception) {}
        return ""
    }

    private val realFileCache = object : LinkedHashMap<String, File?>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, File?>?) = size > 256
    }

    private fun getRealFile(uri: Uri): File? {
        val key = uri.toString()
        synchronized(realFileCache) { realFileCache[key]?.let { return it } }
        // ★ 快速路径：对 primary: URI 直接构造真实路径，跳过 SAF Binder 调用
        //   FUSE 文件系统下 SAF query 查 _data 列必然失败，白做一次 Binder IPC（10-15ms/次）
        try {
            val docId = DocumentsContract.getDocumentId(uri)
            if (docId.startsWith("primary:")) {
                val relPath = docId.removePrefix("primary:")
                val f = File("/storage/emulated/0/$relPath")
                if (f.exists() && f.isFile && f.length() > 0) {
                    synchronized(realFileCache) { realFileCache[key] = f }
                    return f
                }
                // 可能是目录（archive 所在目录）
                if (f.exists() && f.isDirectory) {
                    val fileName = docId.substringAfterLast("/")
                    if (fileName.isNotEmpty()) {
                        val childFile = File(f, fileName)
                        if (childFile.exists() && childFile.isFile && childFile.length() > 0) {
                            synchronized(realFileCache) { realFileCache[key] = childFile }
                            return childFile
                        }
                    }
                    val cbzFile = f.listFiles()?.firstOrNull {
                        it.isFile && it.length() > 0 && (
                            it.name.endsWith(".cbz", true) ||
                            it.name.endsWith(".zip", true) ||
                            it.name.endsWith(".epub", true) ||
                            it.name.endsWith(".cbr", true) ||
                            it.name.endsWith(".rar", true) ||
                            it.name.endsWith(".pdf", true) ||
                            it.name.endsWith(".mobi", true)
                        )
                    }
                    if (cbzFile != null) {
                        synchronized(realFileCache) { realFileCache[key] = cbzFile }
                        return cbzFile
                    }
                }
            }
        } catch (_: Exception) {}
        // 降级：SAF 查询 _data 列（用于非 primary: URI 或快速路径未命中）
        try {
            ctx.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex("_data")
                    if (idx >= 0) {
                        val path = cursor.getString(idx)
                        if (path != null) {
                            val f = File(path)
                            if (f.exists() && f.isFile && f.length() > 0) {
                                synchronized(realFileCache) { realFileCache[key] = f }
                                return f
                            }
                            if (f.exists() && f.isDirectory) {
                                val docId = DocumentsContract.getDocumentId(uri)
                                val fileName = docId.substringAfterLast("/")
                                if (fileName.isNotEmpty()) {
                                    val childFile = File(f, fileName)
                                    if (childFile.exists() && childFile.isFile && childFile.length() > 0) {
                                        synchronized(realFileCache) { realFileCache[key] = childFile }
                                        return childFile
                                    }
                                }
                                val cbzFile = f.listFiles()?.firstOrNull {
                                    it.isFile && it.length() > 0 && (
                                        it.name.endsWith(".cbz", true) ||
                                        it.name.endsWith(".zip", true) ||
                                        it.name.endsWith(".epub", true) ||
                                        it.name.endsWith(".cbr", true) ||
                                        it.name.endsWith(".rar", true) ||
                                        it.name.endsWith(".pdf", true) ||
                                        it.name.endsWith(".mobi", true)
                                    )
                                }
                                if (cbzFile != null) {
                                    synchronized(realFileCache) { realFileCache[key] = cbzFile }
                                    return cbzFile
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun listZipPages(uri: Uri): List<PageMeta> {
        val pages = mutableListOf<PageMeta>()
        try {
            val realFile = getRealFile(uri)
            if (realFile != null) {
                try {
                    java.util.zip.ZipFile(realFile).use { zf ->
                        val cacheKey = "zip:${realFile.absolutePath}"
                        val entries = getSortedZipEntries(zf, cacheKey)
                        for (i in entries.indices) pages.add(PageMeta(i, "", ""))
                    }
                } catch (_: Exception) {}
            }

            if (pages.isEmpty()) {
                val commonsResults = CommonsZipHelper.listImageEntries(ctx, uri)
                commonsResults.forEach { (idx, _) -> pages.add(PageMeta(idx, "", "")) }
            }

            if (pages.isEmpty()) {
                try {
                    ctx.contentResolver.openInputStream(uri)?.use { s ->
                        ZipInputStream(s).use { z ->
                            var idx = 0
                            var e = try { z.nextEntry } catch (_: Exception) { null }
                            while (e != null) {
                                if (!e.isDirectory && isImg(e.name)) {
                                    pages.add(PageMeta(idx, "", ""))
                                    idx++
                                }
                                e = try { z.nextEntry } catch (_: Exception) { null }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) { Log.e(TAG, "listZipPages: ${e.message}") }
        return pages
    }

    private fun countPdfPages(uri: Uri): Int {
        try {
            // 优先使用 pdfium
            val key = uri.toString()
            if (!com.mangareader.native.NativeEngine.pdfIsOpen(key)) {
                ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val fd = pfd.detachFd()
                    val ok = try {
                        com.mangareader.native.NativeEngine.pdfOpen(key, fd)
                    } catch (e: Exception) {
                        Log.e(TAG, "countPdfPages pdfOpen failed: ${e.message}")
                        false
                    }
                    if (!ok) {
                        // ★ A2：pdfOpen 返回 false/抛异常 = native 未接管 fd，必须手动关闭防泄漏
                        try { android.os.ParcelFileDescriptor.adoptFd(fd).close() } catch (_: Exception) {}
                    }
                }
            }
            val count = com.mangareader.native.NativeEngine.pdfGetPageCount(key)
            if (count > 0) return count
            // 降级到 PdfRenderer
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { r -> return r.pageCount }
            }
        } catch (e: Exception) { Log.e(TAG, "countPdfPages: ${e.message}", e) }
        return 0
    }

    // ★ B8 澄清：此处存的是 detachFd() 后的"空壳 pfd"（fd 已移交 native），removeEldestEntry 的
    //   close() 只关空壳无副作用；本 map 实际仅作句柄占位防 GC 语义混淆，非 fd 所有权载体。
    private val pfdKeepAlive = object : LinkedHashMap<String, android.os.ParcelFileDescriptor>(50, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, android.os.ParcelFileDescriptor>?): Boolean {
            if (size > 50) { eldest?.value?.close(); return true }
            return false
        }
    }

    private fun loadFromZip(uri: Uri, targetIndex: Int, treeStr: String = ""): ByteArray? {
        try {
            val cacheKey = uri.toString()
            synchronized(debugLogBuffer) { debugLogBuffer.appendLine("${System.currentTimeMillis()} loadFromZip: START target=$targetIndex key=${cacheKey.takeLast(40)}") }
            if (!com.mangareader.native.NativeEngine.isOpenZip(cacheKey)) {
                synchronized(debugLogBuffer) { debugLogBuffer.appendLine("${System.currentTimeMillis()} loadFromZip: zip not open, opening...") }
                flushLog(ctx)
                val realFile = getRealFile(uri)
                val realPath = realFile?.absolutePath ?: ""
                val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    val fd = pfd.detachFd()
                    synchronized(debugLogBuffer) { debugLogBuffer.appendLine("${System.currentTimeMillis()} loadFromZip: fd=$fd realPath=$realPath") }
                    flushLog(ctx)
                    synchronized(pfdKeepAlive) { pfdKeepAlive[cacheKey] = pfd }
                    try {
                        com.mangareader.native.NativeEngine.openZipWithFd(cacheKey, fd, realPath)
                        synchronized(debugLogBuffer) { debugLogBuffer.appendLine("${System.currentTimeMillis()} loadFromZip: openZipWithFd OK") }
                    } catch (e: Exception) {
                        synchronized(debugLogBuffer) { debugLogBuffer.appendLine("${System.currentTimeMillis()} loadFromZip: openZipWithFd FAILED: ${e.message}") }
                    }
                } else {
                    synchronized(debugLogBuffer) { debugLogBuffer.appendLine("${System.currentTimeMillis()} loadFromZip: pfd is null!") }
                }
            } else {
                synchronized(debugLogBuffer) { debugLogBuffer.appendLine("${System.currentTimeMillis()} loadFromZip: zip already open") }
            }
            // 优先用 sortedImageNamesCache 中的 spine 顺序直接查找（避免 Kotlin/C++ 排序不一致）
            var zipIdx = -1
            synchronized(sortedImageNamesCache) {
                sortedImageNamesCache[cacheKey]?.let { spineNames ->
                    if (targetIndex in spineNames.indices) {
                        val imgName = spineNames[targetIndex]
                        val shortName = imgName.substringAfterLast("/")
                        zipIdx = com.mangareader.native.NativeEngine.findZipEntryByName(cacheKey, imgName)
                        if (zipIdx < 0) zipIdx = com.mangareader.native.NativeEngine.findZipEntryByName(cacheKey, shortName)
                        if (zipIdx >= 0) Log.d(TAG, "loadFromZip: spine name lookup OK, target=$targetIndex name=$shortName zipIdx=$zipIdx")
                    }
                }
            }
            // 回退：用 findZipImageEntry（可能因排序不一致导致顺序错误）
            if (zipIdx < 0) {
                zipIdx = com.mangareader.native.NativeEngine.findZipImageEntry(cacheKey, targetIndex)
            }
            synchronized(debugLogBuffer) { debugLogBuffer.appendLine("${System.currentTimeMillis()} loadFromZip: targetIndex=$targetIndex zipIdx=$zipIdx") }
            flushLog(ctx)
            if (zipIdx < 0) {
                val entryCount = com.mangareader.native.NativeEngine.getZipEntryCount(cacheKey)
                synchronized(debugLogBuffer) { debugLogBuffer.appendLine("${System.currentTimeMillis()} loadFromZip: entryCount=$entryCount") }
                if (entryCount > 0) {
                    val allNames = com.mangareader.native.NativeEngine.getZipEntryNames(cacheKey)
                    val preview = allNames?.take(10)?.joinToString(", ") ?: "null"
                    synchronized(debugLogBuffer) { debugLogBuffer.appendLine("${System.currentTimeMillis()} loadFromZip: first10=$preview") }
                }
                flushLog(ctx)
            }
            if (zipIdx >= 0) {
                val raw = com.mangareader.native.NativeEngine.readZipEntry(cacheKey, zipIdx)
                synchronized(debugLogBuffer) { debugLogBuffer.appendLine("${System.currentTimeMillis()} loadFromZip: readZipEntry ${raw?.size ?: 0} bytes") }
                if (raw != null && raw.isNotEmpty()) { flushLog(ctx); return raw }
                val front = com.hippo.ehviewer.Settings.archivePasswdFront
                if (front.isNotEmpty() && front in com.hippo.ehviewer.Settings.archivePasswds) {
                    com.mangareader.native.NativeEngine.setZipPassword(cacheKey, front)
                    val r = com.mangareader.native.NativeEngine.readZipEntry(cacheKey, zipIdx)
                    if (r != null && r.isNotEmpty()) { flushLog(ctx); return r }
                }
                for (pwd in com.hippo.ehviewer.Settings.archivePasswds) {
                    if (pwd.isEmpty() || pwd == front) continue
                    com.mangareader.native.NativeEngine.setZipPassword(cacheKey, pwd)
                    val r = com.mangareader.native.NativeEngine.readZipEntry(cacheKey, zipIdx)
                    if (r != null && r.isNotEmpty()) {
                        com.hippo.ehviewer.Settings.archivePasswdFront = pwd
                        flushLog(ctx); return r
                    }
                }
                val provider = zipPasswdProvider
                if (provider != null) {
                    try {
                        val pwd = kotlinx.coroutines.runBlocking {
                            provider { p ->
                                com.mangareader.native.NativeEngine.setZipPassword(cacheKey, p)
                                val r = com.mangareader.native.NativeEngine.readZipEntry(cacheKey, zipIdx)
                                r != null && r.isNotEmpty()
                            }
                        }
                        if (pwd != null) {
                            com.hippo.ehviewer.Settings.archivePasswds += pwd
                            com.hippo.ehviewer.Settings.archivePasswdFront = pwd
                            val r = com.mangareader.native.NativeEngine.readZipEntry(cacheKey, zipIdx)
                            if (r != null && r.isNotEmpty()) { flushLog(ctx); return r }
                        }
                    } catch (_: Exception) {}
                }
            } else {
                synchronized(debugLogBuffer) { debugLogBuffer.appendLine("${System.currentTimeMillis()} loadFromZip: findZipImageEntry=-1, trying fallbacks") }
            }

            val commonsResult = CommonsZipHelper.readImageEntry(ctx, uri, targetIndex)
            synchronized(debugLogBuffer) { debugLogBuffer.appendLine("${System.currentTimeMillis()} loadFromZip: commonsResult=${commonsResult?.size ?: "null"}") }
            flushLog(ctx)
            if (commonsResult != null) { flushLog(ctx); return commonsResult }

            // ★ B12：补 put——原"只 get 不 put"假缓存永远 miss；null 不缓存（与原语义一致）
            val realFile = realFileCache2[cacheKey] ?: getRealFile(uri)?.also {
                // 容量护栏：进程级缓存只增不减会累积全库路径，超限整体清空（树可重建）
                if (realFileCache2.size > 512) realFileCache2.clear()
                realFileCache2[cacheKey] = it
            }
            synchronized(debugLogBuffer) { debugLogBuffer.appendLine("${System.currentTimeMillis()} loadFromZip: realFile=${realFile?.absolutePath ?: "null"} isFile=${realFile?.isFile}") }
            flushLog(ctx)
            if (realFile != null && realFile.isFile) {
                try {
                    java.io.FileInputStream(realFile).use { fis ->
                        java.util.zip.ZipInputStream(fis).use { zis ->
                            var idx = 0
                            var entry = zis.nextEntry
                            while (entry != null) {
                                if (!entry.isDirectory && isImg(entry.name)) {
                                    if (idx == targetIndex) {
                                        val data = zis.readBytes()
                                        synchronized(debugLogBuffer) { debugLogBuffer.appendLine("${System.currentTimeMillis()} loadFromZip: realFile read ${data.size} bytes for idx=$idx") }
                                        if (data.isNotEmpty()) { flushLog(ctx); return data }
                                    }
                                    idx++
                                }
                                entry = zis.nextEntry
                            }
                        }
                    }
                } catch (e: Exception) { Log.e(TAG, "loadFromZip: realFile error: ${e.message}") }
            }

            try {
                var targetData: ByteArray? = null
                var idx = 0
                ctx.contentResolver.openInputStream(uri)?.use { s ->
                    ZipInputStream(s).use { z ->
                        var e = try { z.nextEntry } catch (ex: Exception) { Log.w(TAG, "loadFromZip SAF ZipInputStream nextEntry: ${ex.message}"); null }
                        while (e != null) {
                            if (!e.isDirectory && isImg(e.name)) {
                                if (idx == targetIndex) {
                                    targetData = try { z.readBytes() } catch (ex: Exception) { Log.w(TAG, "loadFromZip SAF ZipInputStream readBytes idx=$idx: ${ex.message}"); null }
                                    break
                                }
                                idx++
                            }
                            e = try { z.nextEntry } catch (ex: Exception) { Log.w(TAG, "loadFromZip SAF ZipInputStream nextEntry: ${ex.message}"); null }
                        }
                    }
                }
                if (targetData != null) { flushLog(ctx); return targetData }
            } catch (e: Exception) { Log.w(TAG, "loadFromZip SAF fallback: ${e.message}") }
        } catch (e: Exception) { synchronized(debugLogBuffer) { debugLogBuffer.appendLine("${System.currentTimeMillis()} loadFromZip EXCEPTION: ${e.message}") } }
        synchronized(debugLogBuffer) { debugLogBuffer.appendLine("${System.currentTimeMillis()} loadFromZip: ALL FAILED for target=$targetIndex") }
        flushLog(ctx)
        return null
    }



    private fun loadPdfPage(uri: Uri, pageIndex: Int): Bitmap? {
        try {
            val cacheKey = "$uri|$pageIndex"
            val sharedKey = "pdf:$uri|$pageIndex"
            getCachedBitmap(sharedKey)?.let { return it }
            val docKey = uri.toString()
            val isOpen = com.mangareader.native.NativeEngine.pdfIsOpen(docKey)
            Log.d(TAG, "loadPdfPage: pdfIsOpen=$isOpen key=${docKey.takeLast(30)}")
            if (!isOpen) {
                val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    try {
                        val fd = pfd.detachFd()
                        val ok = try {
                            com.mangareader.native.NativeEngine.pdfOpen(docKey, fd)
                        } catch (e: Exception) {
                            Log.e(TAG, "pdfOpen failed: ${e.message}", e)
                            false
                        }
                        if (!ok) {
                            // ★ A2：pdfOpen 返回 false/抛异常 = native 未接管 fd，必须手动关闭防泄漏
                            try { android.os.ParcelFileDescriptor.adoptFd(fd).close() } catch (_: Exception) {}
                        } else {
                            Log.d(TAG, "loadPdfPage: pdfOpen success")
                        }
                    } finally { try { pfd.close() } catch (_: Exception) {} }
                } else {
                    Log.e(TAG, "pdfOpen: failed to get ParcelFileDescriptor for $uri")
                }
            }
            val pageCount = com.mangareader.native.NativeEngine.pdfGetPageCount(docKey)
            if (pageIndex < 0 || pageIndex >= pageCount) {
                Log.e(TAG, "loadPdfPage: invalid pageIndex $pageIndex (pageCount=$pageCount)")
                return null
            }
            val pageSize = com.mangareader.native.NativeEngine.pdfGetPageSize(docKey, pageIndex)
            val pageWidth = pageSize?.get(0)?.toFloat() ?: 100f
            val pageHeight = pageSize?.get(1)?.toFloat() ?: 100f
            // PDF渲染质量：由pdfQuality控制输出分辨率
            // 低配设备降至1x(72DPI)省内存，高配升至5x(360DPI)提清晰度
            // 默认3x(216DPI)手机够用，不会超过PDF原版质量
            // 注意：pdfium dpi参数是死代码，实际分辨率由w/h控制
            val config = com.mangareader.data.ReaderConfig.load(ctx, scopeBookId)
            val dpi = (config.pdfQuality * 72f).toInt().coerceIn(72, 360)
            val screenMax = maxOf(ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels)
            var w = (dpi.toFloat() * pageWidth / 72f).toInt().coerceAtLeast(1)
            var h = (dpi.toFloat() * pageHeight / 72f).toInt().coerceAtLeast(1)
            if (w > screenMax || h > screenMax) {
                val scale = screenMax.toFloat() / maxOf(w, h)
                w = (w * scale).toInt().coerceAtLeast(1)
                h = (h * scale).toInt().coerceAtLeast(1)
            }
            val bmp = com.mangareader.native.NativeEngine.pdfRenderPageBitmap(docKey, pageIndex, w, h, dpi)
            if (bmp != null) {
                Log.d(TAG, "loadPdfPage: pageIndex=$pageIndex ${w}x${h} dpi=$dpi")
                putCachedBitmap(sharedKey, bmp)
                return bmp
            }
            // 重试一次
            Log.w(TAG, "loadPdfPage: pdfium render failed for page $pageIndex, retrying")
            val bmp2 = com.mangareader.native.NativeEngine.pdfRenderPageBitmap(docKey, pageIndex, w, h, dpi)
            if (bmp2 != null) {
                putCachedBitmap(sharedKey, bmp2)
                return bmp2
            }
            Log.w(TAG, "loadPdfPage: pdfium render failed again, falling back to PdfRenderer")
            val fallback = loadPdfPageFallback(uri, pageIndex, w, h)
            if (fallback != null) putCachedBitmap(sharedKey, fallback)
            return fallback
        } catch (e: Exception) {
            Log.e(TAG, "loadPdfPage: ${e.message}", e)
        }
        return null
    }

    fun loadPdfPageSegment(meta: PageMeta, treeStr: String, segmentIndex: Int): Bitmap? {
        try {
            val p = parsePath(treeStr) ?: return null
            val tree = makeTree(p.auth, p.tree)
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, meta.ext)
            val pageIndex = meta.localIdx
            val sharedKey = "pdf:$uri|$pageIndex"
            // ★ 尺寸先于 key：key 携带当前屏幕尺寸（见 PdfSegmentRenderer 注释），换尺寸后旧缓存不可达
            val screenWidth0 = ctx.resources.displayMetrics.widthPixels
            val screenMax0 = maxOf(screenWidth0, ctx.resources.displayMetrics.heightPixels)
            val segKey = com.mangareader.engine.pdf.PdfSegmentRenderer.segmentCacheKey(sharedKey, pageIndex, segmentIndex, screenWidth0, screenMax0)
            getCachedBitmap(segKey)?.let { return it }
            inflightSegments[segKey]?.let { f -> try { return f.get(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {} }
            val future = java.util.concurrent.CompletableFuture<Bitmap?>()
            val prev = inflightSegments.putIfAbsent(segKey, future)
            if (prev != null) { try { return prev.get(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {} }
            try {
                val docKey = uri.toString()
                // ★ pdfOpen 按 docKey 互斥（2026-09-26 实测反馈）：首屏并发段请求同时发现"未 open"
                //   会各自 openFileDescriptor+pdfOpen 同一文档——竞争下部分请求拿到未就绪状态，
                //   观感=页码不动+整页糊、拖进度条触发重载才恢复。双检锁保证只开一次、其余等待。
                val openLock = pdfOpenLocks.computeIfAbsent(docKey) { Any() }
                synchronized(openLock) {
                    if (!com.mangareader.native.NativeEngine.pdfIsOpen(docKey)) {
                        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
                        if (pfd != null) {
                            try {
                                val fd = pfd.detachFd()
                                val ok = try {
                                    com.mangareader.native.NativeEngine.pdfOpen(docKey, fd)
                                } catch (e: Exception) {
                                    Log.e(TAG, "loadPdfPageSegment pdfOpen failed: ${e.message}", e)
                                    false
                                }
                                if (!ok) {
                                    // ★ A2：pdfOpen 返回 false/抛异常 = native 未接管 fd，必须手动关闭防泄漏
                                    try { android.os.ParcelFileDescriptor.adoptFd(fd).close() } catch (_: Exception) {}
                                }
                            } finally { try { pfd.close() } catch (_: Exception) {} }
                        }
                    }
                }
                val pageSize = com.mangareader.native.NativeEngine.pdfGetPageSize(docKey, pageIndex)
                val pageWidth = pageSize?.get(0)?.toFloat() ?: 100f
                val pageHeight = pageSize?.get(1)?.toFloat() ?: 100f
                val screenMax = screenMax0
                val screenWidth = screenWidth0
                val splitH = com.mangareader.engine.pdf.PdfTallSplitCalculator.splitHeightPdf(screenMax, screenWidth, pageWidth)
                // ★ 渲染几何直接复用 expand 的权威段数（segmentTotal）均分——本地重算 splitH/n 会因
                //   displayMetrics 时机差异得到不同的 n，切出的段与 expand 规划错位（乱/糊，2026-09-26 实测）
                val rect = com.mangareader.engine.pdf.PdfTallSplitCalculator.segmentRectByCount(pageWidth, pageHeight, meta.segmentTotal, segmentIndex)
                val segW = screenWidth.coerceAtLeast(1)
                val segH = (segW.toFloat() * rect.height / pageWidth).toInt().coerceAtLeast(1)
                val bmp = try {
                    loadPdfPageSegmentViaRenderer(uri, pageIndex, rect, pageWidth, pageHeight, segW, segH)
                } catch (oom: OutOfMemoryError) {
                    Log.w(TAG, "loadPdfPageSegment OOM seg=$segmentIndex, fallback whole page")
                    return loadPdfPage(uri, pageIndex)
                }
                if (bmp != null) {
                    Log.d(TAG, "loadPdfPageSegment idx=$pageIndex seg=$segmentIndex cache=miss ${segW}x${segH} rect=[${rect.left},${rect.top},${rect.right},${rect.bottom}] pageWH=${pageWidth}x${pageHeight} splitH=$splitH")
                    // ★ 诊断取证：每文档首段的成功渲染尺寸（模糊物证——位图小=渲染参数错，位图大=显示端拉伸）
                    if (pdfSegOkMarked.add(docKey)) {
                        try { com.mangareader.DiagTracer.mark(ctx, "seg ok idx=$pageIndex seg=$segmentIndex ${bmp.width}x${bmp.height}") } catch (_: Exception) {}
                    }
                    putCachedBitmap(segKey, bmp)
                    future.complete(bmp)
                    return bmp
                }
                Log.w(TAG, "loadPdfPageSegment render null seg=$segmentIndex, fallback whole page")
                val mk = "pdf|$docKey|$pageIndex#$segmentIndex"
                if (segFallbackMarked.add(mk)) {
                    try { com.mangareader.DiagTracer.mark(ctx, "seg fallback idx=$pageIndex seg=$segmentIndex") } catch (_: Exception) {}
                }
                future.complete(null)
                return loadPdfPage(uri, pageIndex)
            } finally { if (!future.isDone) future.complete(null); inflightSegments.remove(segKey, future) }
        } catch (e: Exception) {
            Log.e(TAG, "loadPdfPageSegment: ${e.message}", e)
            return null
        }
    }

    // ★ 长图分割 §4：图片族分段解码入口（镜像 loadPdfPageSegment：段缓存 + inflight 去重 + 整图兜底）
    fun loadImagePageSegment(meta: PageMeta, treeStr: String, segmentIndex: Int): Bitmap? {
        try {
            requireNotRemote(treeStr)
            val p = parsePath(treeStr) ?: return null
            val pageKey = "img:${p.doc}|${meta.localIdx}|${meta.source}"
            val screenWidth0 = ctx.resources.displayMetrics.widthPixels
            val screenMax0 = maxOf(screenWidth0, ctx.resources.displayMetrics.heightPixels)
            val segKey = com.mangareader.engine.img.ImageSegmentRenderer.segmentCacheKey(pageKey, segmentIndex, screenWidth0, screenMax0)
            getCachedBitmap(segKey)?.let { return it }
            inflightSegments[segKey]?.let { f -> try { return f.get(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {} }
            val future = java.util.concurrent.CompletableFuture<Bitmap?>()
            val prev = inflightSegments.putIfAbsent(segKey, future)
            if (prev != null) { try { return prev.get(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {} }
            try {
                val bmp = try {
                    com.mangareader.engine.img.ImageSegmentRenderer.decode(this, meta, treeStr, segmentIndex, screenWidth0, screenMax0)
                } catch (oom: OutOfMemoryError) {
                    Log.w(TAG, "loadImagePageSegment OOM seg=$segmentIndex")
                    null
                }
                if (bmp != null) {
                    Log.d(TAG, "loadImagePageSegment idx=${meta.localIdx} seg=$segmentIndex ${bmp.width}x${bmp.height} cache=miss")
                    putCachedBitmap(segKey, bmp)
                    future.complete(bmp)
                    return bmp
                }
                Log.w(TAG, "loadImagePageSegment render null seg=$segmentIndex, fallback whole page")
                // ★ 诊断取证：段渲染失败回退整图（用户实测=后面全是糊图），每 (doc,idx,seg) 只记一次
                val mk = "${p.doc}|${meta.localIdx}#$segmentIndex"
                if (segFallbackMarked.add(mk)) {
                    try { com.mangareader.DiagTracer.mark(ctx, "seg fallback idx=${meta.localIdx} seg=$segmentIndex") } catch (_: Exception) {}
                }
                future.complete(null)
                return loadPage(meta, treeStr)
            } finally { if (!future.isDone) future.complete(null); inflightSegments.remove(segKey, future) }
        } catch (e: Exception) {
            Log.e(TAG, "loadImagePageSegment: ${e.message}", e)
            return null
        }
    }

    private fun loadPdfPageSegmentViaRenderer(uri: Uri, pageIndex: Int, rect: com.mangareader.engine.pdf.PdfTallSplitCalculator.SplitRect, pageWidth: Float, pageHeight: Float, segW: Int, segH: Int): Bitmap? {
        val slot = (renderSlotCounter.getAndIncrement() and 0x7fffffff) % renderSlotLocks.size
        synchronized(renderSlotLocks[slot]) {
            val r = getSlotRenderer(slot, uri) ?: return null
            if (pageIndex >= r.pageCount) return null
            val pg = r.openPage(pageIndex)
            try {
                val bmp = Bitmap.createBitmap(segW, segH, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                val scaleX = segW.toFloat() / pageWidth
                val scaleY = segH.toFloat() / rect.height
                val matrix = android.graphics.Matrix()
                matrix.setScale(scaleX, scaleY)
                matrix.postTranslate(0f, -rect.top * scaleY)
                pg.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bmp
            } finally { pg.close() }
        }
    }

    private fun getSlotRenderer(slot: Int, uri: Uri): android.graphics.pdf.PdfRenderer? {
        if (renderSlotUri[slot] == uri && renderSlotRenderer[slot] != null) return renderSlotRenderer[slot]
        try { renderSlotRenderer[slot]?.close() } catch (_: Exception) {}
        try { renderSlotPfd[slot]?.close() } catch (_: Exception) {}
        renderSlotPfd[slot] = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
        renderSlotRenderer[slot] = android.graphics.pdf.PdfRenderer(renderSlotPfd[slot]!!)
        renderSlotUri[slot] = uri
        return renderSlotRenderer[slot]
    }

    private fun loadPdfPageFallback(uri: Uri, pageIndex: Int, w: Int, h: Int): Bitmap? {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            PdfRenderer(pfd).use { r ->
                if (pageIndex < r.pageCount) {
                    val pg = r.openPage(pageIndex)
                    try {
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        return bmp
                    } finally { pg.close() }
                }
            }
        }
        return null
    }

    private fun listDir(tree: Uri, doc: String, auth: String, treeId: String): List<Item> {
        // ★ 快速路径：用真实路径列目录，跳过 SAF Binder 调用
        //   1068 文件目录 SAF query 耗时 1.5s；File.listFiles() <50ms
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && android.os.Environment.isExternalStorageManager()) {
            try {
                val dirUri = DocumentsContract.buildDocumentUriUsingTree(tree, doc)
                val dirFile = resolveRealDir(dirUri)
                if (dirFile != null && dirFile.exists() && dirFile.isDirectory) {
                    val files = dirFile.listFiles()
                    if (files != null) {
                        val fastItems = mutableListOf<Item>()
                        for (f in files) {
                            val n = f.name
                            val isDir = f.isDirectory
                            if (isDir || isComic(n)) {
                                val childDocId = "$doc/$n"
                                val itemSubPath = if (isDir) "d:$auth|$treeId|$childDocId"
                                    else "a:$auth|$treeId|$childDocId|${n.substringAfterLast(".").lowercase()}"
                                fastItems.add(Item(n, auth, treeId, childDocId, isDir, itemSubPath))
                            }
                        }
                        d(TAG, "listDirFast: ${fastItems.size} items from ${dirFile.absolutePath}")
                        return com.mangareader.util.GroupSortUtil.sortItems(fastItems, com.mangareader.util.GroupSortConfigCache.get(ctx), { it.name }, { it.dir })
                    }
                }
            } catch (_: Exception) {}
        }
        // 降级：SAF 查询
        val items = mutableListOf<Item>()
        try {
            val child = DocumentsContract.buildChildDocumentsUriUsingTree(tree, doc)
            ctx.contentResolver.query(child, null, null, null, null)?.use { c ->
                val ni = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val ii = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mi = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (c.moveToNext()) {
                    val n = c.getString(ni) ?: continue
                    val id = c.getString(ii) ?: continue
                    val mime = if (mi >= 0) c.getString(mi) ?: "" else ""
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    // ★ F2.2 章节身份（与 openItem 实际压栈格式严格一致）：
                    //   - 文件夹章 → d:auth|tree|doc（openItem 对 dir 压的就是它）
                    //   - 文件章   → a:auth|tree|doc|ext（openItem 对 file 压的就是它，enterReader/listPages 均已支持）
                    //   原来恒空导致：TOC 点击静默关闭、switchToNext/Previous 找不到当前章
                    //   （ blind 跳第 2 项 / 跌落文件夹级切到隔壁书）、当前章永不高亮。
                    val itemSubPath = if (isDir) "d:$auth|$treeId|$id"
                        else "a:$auth|$treeId|$id|${n.substringAfterLast(".").lowercase()}"
                    if (isDir || isComic(n)) items.add(Item(n, auth, treeId, id, isDir, itemSubPath))
                }
            }
        } catch (e: Exception) { Log.e(TAG, "listDir: ${e.message}", e) }
        return com.mangareader.util.GroupSortUtil.sortItems(items, com.mangareader.util.GroupSortConfigCache.get(ctx), { it.name }, { it.dir })
    }

    private fun parsePath(path: String): PathInfo? {
        val (tag, rest) = when {
            path.startsWith("d:") -> "d" to path.removePrefix("d:")
            path.startsWith("a:") -> "a" to path.removePrefix("a:")
            else -> return null
        }
        val parts = rest.split("|", limit = 4)
        if (parts.size < 3) return null
        return PathInfo(tag, parts[0], parts[1], parts[2], parts.getOrElse(3) { "" })
    }

    private data class PathInfo(val tag: String, val auth: String, val tree: String, val doc: String, val ext: String)

    private fun resolveParentForA(jumpPath: String): String? {
        val p = parsePath(jumpPath) ?: return null
        if (p.tag != "a") return null
        val parentDoc = p.doc.substringBeforeLast('/', missingDelimiterValue = "")
        return "d:${p.auth}|${p.tree}|${parentDoc}"
    }

    private fun resolveParentForD(jumpPath: String): String? {
        val p = parsePath(jumpPath) ?: return null
        if (p.tag != "d") return null
        if (!p.doc.contains('/')) return null
        val parentDoc = p.doc.substringBeforeLast('/')
        return "d:${p.auth}|${p.tree}|${parentDoc}"
    }

    private fun resolveParentForZ(jumpPath: String): String? {
        val inner = jumpPath.removePrefix("z:")
        val sep = inner.indexOf("//")
        if (sep < 0) return null
        val archivePath = inner.substring(0, sep)
        var subPath = inner.substring(sep + 2)
        if (subPath.endsWith('/')) subPath = subPath.dropLast(1)
        if (!subPath.contains('/')) return null
        val parentSubPath = subPath.substringBeforeLast('/')
        return "z:${archivePath}//${parentSubPath}/"
    }

    fun resolveParentPath(jumpPath: String): String? {
        return try {
            when {
                jumpPath.startsWith("a:") -> resolveParentForA(jumpPath)
                jumpPath.startsWith("d:") -> resolveParentForD(jumpPath)
                jumpPath.startsWith("z:") -> resolveParentForZ(jumpPath)
                else -> null
            }
        } catch (_: Exception) { null }
    }

    fun parsePathForMobi(path: String): Triple<String, String, String>? {
        val p = parsePath(path) ?: return null
        return Triple(p.auth, p.tree, p.doc)
    }

    fun getPdfSharedKey(treePath: String, pageIndex: Int): String? {
        val p = parsePath(treePath) ?: return null
        val tree = makeTree(p.auth, p.tree)
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, p.doc)
        return "pdf:$uri|$pageIndex"
    }

    private fun makeTree(auth: String, tree: String) =
        Uri.parse("content://$auth/tree/${Uri.encode(tree)}")

    private fun scanRar(tree: Uri, doc: String): List<PageMeta> {
        val pages = mutableListOf<PageMeta>()
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, doc)
        try {
            // ★ B1：借 fd 缓存列页（不再 detach 泄漏）；失败落下方 junrar 兜底链
            val fdEntry = getOrCreateFdEntry(uri.toString(), uri)
            if (fdEntry != null) {
                val listed = synchronized(fdEntry.lock) {
                    try { listArchiveMetaViaFd(fdEntry.pfd.fd, "rar", doc) } catch (_: Exception) { emptyList() }
                }
                if (listed.isNotEmpty()) return listed
            }
            val realFile = getRealFile(uri)
            if (realFile != null) {
                val rar = RarArchive(realFile)
                try {
                    var idx = 0
                    rar.fileHeaders.filter { !it.isDirectory }
                        .sortedWith(Comparator { a, b ->
                            val depthA = com.mangareader.util.ComicSortUtil.getPathDepth(a.fileName)
                            val depthB = com.mangareader.util.ComicSortUtil.getPathDepth(b.fileName)
                            if (depthA != depthB) depthA - depthB
                            else com.mangareader.zip.CommonsZipHelper.compareNatural(a.fileName, b.fileName)
                        })
                        .forEach { fh ->
                            if (isImg(fh.fileName)) {
                                pages.add(PageMeta(idx, "rar", doc, idx))
                                idx++
                            }
                        }
                    return pages
                } finally {
                    try { rar.close() } catch (_: Exception) {}
                }
            }
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                val tmpFile = File(ctx.cacheDir, "tmp_rar_${System.currentTimeMillis()}.rar")
                tmpFile.outputStream().use { out -> input.copyTo(out) }
                try {
                    val rar = RarArchive(tmpFile)
                    try {
                        var idx = 0
                        rar.fileHeaders.filter { !it.isDirectory }
                            .sortedWith(Comparator { a, b ->
                                val depthA = com.mangareader.util.ComicSortUtil.getPathDepth(a.fileName)
                                val depthB = com.mangareader.util.ComicSortUtil.getPathDepth(b.fileName)
                                if (depthA != depthB) depthA - depthB
                                else com.mangareader.zip.CommonsZipHelper.compareNatural(a.fileName, b.fileName)
                            })
                            .forEach { fh ->
                                if (isImg(fh.fileName)) {
                                    pages.add(PageMeta(idx, "rar", doc, idx))
                                    idx++
                                }
                            }
                    } finally {
                        try { rar.close() } catch (_: Exception) {}
                    }
                } finally { tmpFile.delete() }
            }
        } catch (_: Exception) {}
        return pages
    }

    private fun scan7z(tree: Uri, doc: String): List<PageMeta> {
        val pages = mutableListOf<PageMeta>()
        // ★ 真机复现问题2（2026-10-12）：7z 里装的是嵌套分卷（mobi/zip 等）而非图片时，
        //   两级扫描静默得 0 页 → 开卷误报"文件暂时找不到"。此处统计嵌套包数供提示分流。
        var nestedComics = 0
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, doc)
        try {
            // ★ B1：借 fd 缓存列页（不再 detach 泄漏）；失败落下方 commons-compress 兜底链
            val fdEntry = getOrCreateFdEntry(uri.toString(), uri)
            if (fdEntry != null) {
                val listed = synchronized(fdEntry.lock) {
                    try { listArchiveMetaViaFd(fdEntry.pfd.fd, "7z", doc) } catch (_: Exception) { emptyList() }
                }
                if (listed.isNotEmpty()) return listed
            }
            // Fallback to Java commons-compress with real file
            val realFile = getRealFile(uri)
            if (realFile != null) {
                val sz = SevenZFile(realFile)
                var idx = 0
                var entry = sz.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && isImg(entry.name)) {
                        pages.add(PageMeta(idx, "7z", doc, idx))
                        idx++
                    } else if (!entry.isDirectory && isComic(entry.name)) {
                        nestedComics++
                    }
                    entry = sz.nextEntry
                }
                sz.close()
                if (pages.isNotEmpty()) return pages
            } else {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    val tmpFile = File(ctx.cacheDir, "tmp_7z_${System.currentTimeMillis()}.7z")
                    tmpFile.outputStream().use { out -> input.copyTo(out) }
                    try {
                        val sz = SevenZFile(tmpFile)
                        var idx = 0
                        var entry = sz.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && isImg(entry.name)) {
                                pages.add(PageMeta(idx, "7z", doc, idx))
                                idx++
                            } else if (!entry.isDirectory && isComic(entry.name)) {
                                nestedComics++
                            }
                            entry = sz.nextEntry
                        }
                        sz.close()
                    } finally { tmpFile.delete() }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "scan7z: ${e.message}") }
        if (pages.isEmpty() && nestedComics > 0) {
            lastOpenHint = "此压缩包内是 $nestedComics 个分卷文件（mobi/zip 等嵌套包），暂不支持作为漫画打开"
        }
        return pages
    }

    private fun scanTar(tree: Uri, doc: String): List<PageMeta> {
        val pages = mutableListOf<PageMeta>()
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, doc)
        try {
            // ★ B1：借 fd 缓存列页（不再 detach 泄漏）
            val fdEntry = getOrCreateFdEntry(uri.toString(), uri)
            if (fdEntry != null) {
                val listed = synchronized(fdEntry.lock) {
                    try { listArchiveMetaViaFd(fdEntry.pfd.fd, "tar", doc) } catch (_: Exception) { emptyList() }
                }
                if (listed.isNotEmpty()) return listed
            }
        } catch (_: Exception) {}
        return pages
    }

    private fun loadFromTar(uri: Uri, targetIndex: Int): ByteArray? {
        try {
            // ★ B1：fd 缓存 + 相邻页预读（替代每页 detach 泄漏 + O(N²) 遍历）；失败落下方兜底
            loadArchiveEntryViaFdCache(uri, targetIndex)?.let { return it }
        } catch (_: Exception) {}
        return null
    }

    private fun loadFromRar(uri: Uri, targetIndex: Int): ByteArray? {
        try {
            // ★ B1：fd 缓存 + 相邻页预读；失败自然落下方 junrar 兜底链
            loadArchiveEntryViaFdCache(uri, targetIndex)?.let { return it }
            val realFile = getRealFile(uri)
            if (realFile != null) {
                try {
                    val rar = RarArchive(realFile)
                    var idx = 0
                    rar.fileHeaders.filter { !it.isDirectory }
                        .sortedWith(rarSortComparator)
                        .forEach { fh ->
                        if (isImg(fh.fileName)) {
                            if (idx == targetIndex) {
                                val data = rar.getInputStream(fh).use { it.readBytes() }
                                rar.close()
                                return data
                            }
                            idx++
                        }
                    }
                    rar.close()
                } catch (_: Exception) {}
            }
            // Fallback to SAF stream → tmpFile → junrar
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                val tmpFile = File(ctx.cacheDir, "tmp_rar_${System.currentTimeMillis()}.rar")
                tmpFile.outputStream().use { out -> input.copyTo(out) }
                try {
                    val rar = RarArchive(tmpFile)
                    var idx = 0
                    rar.fileHeaders.filter { !it.isDirectory }
                        .sortedWith(rarSortComparator)
                        .forEach { fh ->
                        if (isImg(fh.fileName)) {
                            if (idx == targetIndex) {
                                val data = rar.getInputStream(fh).use { it.readBytes() }
                                rar.close()
                                tmpFile.delete()
                                return data
                            }
                            idx++
                        }
                    }
                    rar.close()
                } finally { tmpFile.delete() }
            }
            return null
        } catch (_: Exception) {}
        return null
    }

    // ==================== ★ B1 修复：RAR/7z/TAR fd 缓存 + 相邻页预读 ====================
    // 旧路径每次读页/列页都 openFileDescriptor+detachFd，之后无人关 fd（detach 后 pfd.close() 无效；
    // libarchive C 层 file_close 与 zhanghai JNI 封装均不接管 fd 所有权），每处理一页泄一个裸 fd；
    // 且每页都从文件头顺流遍历到目标位，翻页 IO O(N²)。
    // 现方案：pfd 对象入小 LRU（所有权归缓存，逐出 close() 一了百了），读取仅借用 pfd.fd；
    // 同一 fd 的文件偏移被所有 readHandle 共享——per-entry 锁串行化防互踩；
    // 顺流遍历时顺手收集 target..target+2 页入 32MB 字节 LRU，向后翻页近乎零额外 IO。
    private class FdCacheEntry(val pfd: android.os.ParcelFileDescriptor) { val lock = Any() }

    private val rarFdCache = object : LinkedHashMap<String, FdCacheEntry>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FdCacheEntry>?): Boolean {
            if (size > 4) { try { eldest?.value?.pfd?.close() } catch (_: Exception) {} ; return true }
            return false
        }
    }

    private val adjacentPrereadCache = object : android.util.LruCache<String, ByteArray>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    private fun getOrCreateFdEntry(cacheKey: String, uri: Uri): FdCacheEntry? {
        synchronized(rarFdCache) { rarFdCache[cacheKey]?.let { return it } }
        val pfd = try { ctx.contentResolver.openFileDescriptor(uri, "r") } catch (_: Exception) { null } ?: return null
        synchronized(rarFdCache) {
            rarFdCache[cacheKey]?.let { runCatching { pfd.close() }; return it }
            val entry = FdCacheEntry(pfd)
            rarFdCache[cacheKey] = entry
            return entry
        }
    }

    /** 单次顺流遍历收集 wanted 中出现的图片页数据；全部收齐即提前终止。异常向上抛由调用方兜底。 */
    private fun readArchiveEntriesViaFd(fd: Int, wanted: Set<Int>): Map<Int, ByteArray> {
        val got = HashMap<Int, ByteArray>()
        if (wanted.isEmpty()) return got
        val readHandle = me.zhanghai.android.libarchive.Archive.readNew()
        try {
            me.zhanghai.android.libarchive.Archive.setCharset(readHandle, "UTF-8".toByteArray())
            me.zhanghai.android.libarchive.Archive.readSupportFilterAll(readHandle)
            me.zhanghai.android.libarchive.Archive.readSupportFormatAll(readHandle)
            me.zhanghai.android.libarchive.Archive.readOpenFd(readHandle, fd, 10240L)
            var idx = 0
            var header = me.zhanghai.android.libarchive.Archive.readNextHeader(readHandle)
            while (header != 0L) {
                val nameBytes = me.zhanghai.android.libarchive.ArchiveEntry.pathname(header)
                val name = nameBytes?.let { String(it) }
                val fileType = me.zhanghai.android.libarchive.ArchiveEntry.filetype(header)
                val isDir = fileType == me.zhanghai.android.libarchive.ArchiveEntry.AE_IFDIR
                if (name != null && !isDir && isImg(name)) {
                    if (idx in wanted && !got.containsKey(idx)) {
                        val entrySize = me.zhanghai.android.libarchive.ArchiveEntry.size(header)
                        if (entrySize in 1..(64L * 1024 * 1024)) {
                            val buf = java.nio.ByteBuffer.allocateDirect(entrySize.toInt())
                            me.zhanghai.android.libarchive.Archive.readData(readHandle, buf)
                            val data = ByteArray(entrySize.toInt())
                            buf.position(0)
                            buf.get(data)
                            got[idx] = data
                        }
                        if (got.size == wanted.size) break
                    }
                    idx++
                }
                header = me.zhanghai.android.libarchive.Archive.readNextHeader(readHandle)
            }
            me.zhanghai.android.libarchive.Archive.free(readHandle)
        } catch (e: Exception) {
            try { me.zhanghai.android.libarchive.Archive.free(readHandle) } catch (_: Exception) {}
            throw e
        }
        return got
    }

    /** 列页版：单次顺流遍历产出 PageMeta（物理序 isImg 计数，与旧实现一致）。 */
    private fun listArchiveMetaViaFd(fd: Int, source: String, doc: String): List<PageMeta> {
        val pages = mutableListOf<PageMeta>()
        val readHandle = me.zhanghai.android.libarchive.Archive.readNew()
        try {
            me.zhanghai.android.libarchive.Archive.setCharset(readHandle, "UTF-8".toByteArray())
            me.zhanghai.android.libarchive.Archive.readSupportFilterAll(readHandle)
            me.zhanghai.android.libarchive.Archive.readSupportFormatAll(readHandle)
            me.zhanghai.android.libarchive.Archive.readOpenFd(readHandle, fd, 10240L)
            var idx = 0
            var header = me.zhanghai.android.libarchive.Archive.readNextHeader(readHandle)
            while (header != 0L) {
                val nameBytes = me.zhanghai.android.libarchive.ArchiveEntry.pathname(header)
                val name = nameBytes?.let { String(it) }
                val fileType = me.zhanghai.android.libarchive.ArchiveEntry.filetype(header)
                val isDir = fileType == me.zhanghai.android.libarchive.ArchiveEntry.AE_IFDIR
                if (name != null && !isDir && isImg(name)) {
                    pages.add(PageMeta(idx, source, doc, idx))
                    idx++
                }
                header = me.zhanghai.android.libarchive.Archive.readNextHeader(readHandle)
            }
            me.zhanghai.android.libarchive.Archive.free(readHandle)
        } catch (e: Exception) {
            try { me.zhanghai.android.libarchive.Archive.free(readHandle) } catch (_: Exception) {}
            throw e
        }
        return pages
    }

    /** 读页统一入口：预读 LRU → fd 缓存借用 → 单次遍历收 target..target+2 页。失败返回 null，调用方落原有兜底链。 */
    private fun loadArchiveEntryViaFdCache(uri: Uri, targetIndex: Int): ByteArray? {
        val cacheKey = uri.toString()
        adjacentPrereadCache.get("$cacheKey|p$targetIndex")?.let { return it }
        val entry = getOrCreateFdEntry(cacheKey, uri) ?: return null
        return try {
            synchronized(entry.lock) {
                adjacentPrereadCache.get("$cacheKey|p$targetIndex")?.let { return it }
                val want = HashSet<Int>()
                repeat(3) { want.add(targetIndex + it) }
                val got = readArchiveEntriesViaFd(entry.pfd.fd, want)
                for ((i, data) in got) {
                    // ★ 超限页不入缓存：LruCache maxSize=32MB，单页 ≥32MB put 后立即被 trim 自逐
                    //   （缓存抖动+无谓的大块拷贝），直接跳过
                    if (data.size < 32 * 1024 * 1024) adjacentPrereadCache.put("$cacheKey|p$i", data)
                }
                got[targetIndex]
            }
        } catch (e: Exception) {
            Log.d(TAG, "B1 loadArchiveEntryViaFdCache fail idx=$targetIndex: ${e.message}")
            null
        }
    }

    private fun clearRarFdCache() {
        synchronized(rarFdCache) {
            rarFdCache.values.forEach { try { it.pfd.close() } catch (_: Exception) {} }
            rarFdCache.clear()
        }
        adjacentPrereadCache.evictAll()
    }

    private fun loadFrom7z(uri: Uri, targetIndex: Int): ByteArray? {
        try {
            // ★ B1：fd 缓存 + 相邻页预读；失败自然落下方 commons-compress 兜底链
            loadArchiveEntryViaFdCache(uri, targetIndex)?.let { return it }
            // Fallback to Java SevenZFile with real file
            val realFile = getRealFile(uri)
            // Fallback to Java commons-compress
            if (realFile != null) {
                try {
                    val sz = SevenZFile(realFile)
                    var idx = 0
                    var entry = sz.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && isImg(entry.name)) {
                            if (idx == targetIndex) {
                                val bos = ByteArrayOutputStream()
                                val buf = ByteArray(8192)
                                var n: Int
                                while (sz.read(buf).also { n = it } != -1) bos.write(buf, 0, n)
                                sz.close()
                                return bos.toByteArray()
                            }
                            idx++
                        }
                        entry = sz.nextEntry
                    }
                    sz.close()
                } catch (_: Exception) {}
            }
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                val tmpFile = File(ctx.cacheDir, "tmp_7z_${System.currentTimeMillis()}.7z")
                tmpFile.outputStream().use { out -> input.copyTo(out) }
                try {
                    val sz = SevenZFile(tmpFile)
                    var idx = 0
                    var entry = sz.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && isImg(entry.name)) {
                            if (idx == targetIndex) {
                                val bos = ByteArrayOutputStream()
                                val buf = ByteArray(8192)
                                var n: Int
                                while (sz.read(buf).also { n = it } != -1) bos.write(buf, 0, n)
                                sz.close()
                                tmpFile.delete()
                                return bos.toByteArray()
                            }
                            idx++
                        }
                        entry = sz.nextEntry
                    }
                    sz.close()
                } finally { tmpFile.delete() }
            }
        } catch (_: Exception) {}
        return null
    }

    fun listZipContents(uri: Uri): List<String> {
        val names = mutableListOf<String>()
        try {
            ctx.contentResolver.openInputStream(uri)?.use { s ->
                ZipInputStream(s).use { z ->
                    var e = try { z.nextEntry } catch (_: Exception) { null }
                    while (e != null) {
                        if (!e.isDirectory) names.add(e.name)
                        e = try { z.nextEntry } catch (_: Exception) { null }
                    }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "listZipContents: ${e.message}") }
        return names
    }

    private fun isImg(n: String) = n.lowercase().let {
        it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") ||
                it.endsWith(".webp") || it.endsWith(".gif") || it.endsWith(".bmp") ||
                it.endsWith(".tiff") || it.endsWith(".tif") || it.endsWith(".heic") ||
                it.endsWith(".heif") || it.endsWith(".avif")
    }

    private fun isComic(n: String) = n.lowercase().let {
        it.endsWith(".cbz") || it.endsWith(".zip") || it.endsWith(".pdf") || it.endsWith(".epub") ||
                it.endsWith(".cbr") || it.endsWith(".rar") || it.endsWith(".cb7") || it.endsWith(".7z") ||
                it.endsWith(".mobi") || it.endsWith(".azw") || it.endsWith(".azw3") ||
                it.endsWith(".tar") || it.endsWith(".cbt") || it.endsWith(".tgz") || it.endsWith(".tbz2") || it.endsWith(".txz")
    }


    // ==================== ZIP目录导航 ====================

    private val zipTreeCache = java.util.concurrent.ConcurrentHashMap<String, ZipDirNode>()

    /** zip 树缓存取用：超限整体清空防进程级无限累积（每本 z: 书一棵全条目树，审查 AE3） */
    private fun zipTree(cacheKey: String, build: () -> ZipDirNode): ZipDirNode {
        if (zipTreeCache.size > 24) zipTreeCache.clear()
        return zipTreeCache.getOrPut(cacheKey, build)
    }

    private fun buildZipTree(entryNames: List<String>): ZipDirNode {
        val root = ZipDirNode("", "", true)
        // ★ B4 修复：预建 name→首个 index 映射替代循环内 entryNames.indexOf(name) 的 O(N²)；
        //   putIfAbsent 保持 indexOf"首个匹配"语义（ZIP 重名时与旧行为一致，getOrDefault 保 -1 语义）
        val nameToFirstIdx = HashMap<String, Int>(entryNames.size * 2)
        entryNames.forEachIndexed { i, n -> if (!nameToFirstIdx.containsKey(n)) nameToFirstIdx[n] = i }
        val sorted = entryNames.sortedBy { name -> name.count { it == '/' || it == '\\' } }
        for (name in sorted) {
            val isDir = name.endsWith('/')
            val parts = name.split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            var current = root
            for (i in 0 until parts.size - 1) {
                val sub = current.children.find { it.name == parts[i] }
                if (sub != null) {
                    current = sub
                } else {
                    val newNode = ZipDirNode(parts[i], parts.subList(0, i + 1).joinToString("/") + "/", true)
                    current.children.add(newNode)
                    current = newNode
                }
            }
            val leafName = parts.last()
            if (isDir) {
                val existing = current.children.find { it.name == leafName }
                if (existing == null) {
                    current.children.add(ZipDirNode(leafName, name, true))
                }
            } else if (isImg(name)) {
                current.imageIndices.add(nameToFirstIdx.getOrDefault(name, -1))
            }
        }
        // 对每个节点的 imageIndices 按文件名自然排序
        sortImageIndices(root, entryNames)
        return root
    }

    private fun sortImageIndices(node: ZipDirNode, allNames: List<String>) {
        if (node.imageIndices.isNotEmpty()) {
            node.imageIndices.sortWith(Comparator { a, b ->
                val nameA = if (a in allNames.indices) allNames[a] else ""
                val nameB = if (b in allNames.indices) allNames[b] else ""
                NaturalComparator.compare(nameA, nameB)
            })
        }
        for (child in node.children) {
            if (child.isDir) sortImageIndices(child, allNames)
        }
    }


    fun hasZipSubDirs(path: String): Boolean {
        android.util.Log.d(TAG, "hasZipSubDirs path=$path")
        val p = parsePath(path) ?: return false
        val tree = makeTree(p.auth, p.tree)
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, p.doc)
        val cacheKey = uri.toString()
        if (!com.mangareader.native.NativeEngine.isOpenZip(cacheKey)) {
            val realFile = getRealFile(uri)
            val realPath = realFile?.absolutePath ?: ""
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return false
            val fd = pfd.detachFd()
            synchronized(pfdKeepAlive) { pfdKeepAlive[cacheKey] = pfd }
            try { com.mangareader.native.NativeEngine.openZipWithFd(cacheKey, fd, realPath) } catch (_: Exception) {}
        }
        val allNames = try { com.mangareader.native.NativeEngine.getZipEntryNames(cacheKey) } catch (_: Exception) { null }
            ?: return false
        android.util.Log.d(TAG, "hasZipSubDirs totalEntries=${allNames.size}")
        val result = allNames.toList().any { name ->
            val parts = name.split('/').filter { it.isNotEmpty() }
            parts.size > 1
        }
        android.util.Log.d(TAG, "hasZipSubDirs result=$result")
        return result
    }

    /**
     * §37 误判修正：zip/cbz 是否真的是漫画 —— 入口条目是否含至少一张图。
     *   Scanner 对 .zip/.cbz 文件不再纯靠后缀识别（用户报告"纳*save.zip 这种非漫画被收进书架"）。
     *   本方法用与 hasZipSubDirs 同一套 native zip 句柄，遍历 entry 名，含任何 isImg 后缀即视为漫画。
     *   开打不开 / 列名为空 → 返回 false（不收）。RAR/7z/epub 等不在此修，因扫漫画用例极罕见且 native 接口不同。
     */
    fun hasZipImages(path: String): Boolean {
        android.util.Log.d(TAG, "hasZipImages path=$path")
        val namesPair = getZipAllNames(path) ?: run {
            android.util.Log.d(TAG, "hasZipImages: open/list 失败 → false")
            return false
        }
        val (_, allNames) = namesPair
        for (n in allNames) {
            if (isImg(n)) {
                android.util.Log.d(TAG, "hasZipImages: OK 命中图 $n → true")
                return true
            }
        }
        android.util.Log.d(TAG, "hasZipImages: totalEntries=${allNames.size} 无图 → false")
        return false
    }

    private fun getZipAllNames(filePath: String): Pair<String, Array<String>>? {
        val p = parsePath(filePath) ?: return null
        val tree = makeTree(p.auth, p.tree)
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, p.doc)
        val cacheKey = uri.toString()
        if (!com.mangareader.native.NativeEngine.isOpenZip(cacheKey)) {
            val realFile = getRealFile(uri)
            val realPath = realFile?.absolutePath ?: ""
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val fd = pfd.detachFd()
            synchronized(pfdKeepAlive) { pfdKeepAlive[cacheKey] = pfd }
            try { com.mangareader.native.NativeEngine.openZipWithFd(cacheKey, fd, realPath) } catch (_: Exception) {}
        }
        val allNames = try { com.mangareader.native.NativeEngine.getZipEntryNames(cacheKey) } catch (_: Exception) { null }
            ?: return null
        return cacheKey to allNames
    }

    fun listZipDirs(filePath: String, subPath: String?): List<Item> {
        val p = parsePath(filePath) ?: return emptyList()
        val result = getZipAllNames(filePath) ?: return emptyList()
        val (cacheKey, allNames) = result
        val root = zipTree(cacheKey) { buildZipTree(allNames.toList()) }
        if (isFlatZip(root)) {
            val hasNestedLeaf = allNames.any { n ->
                !n.contains('/') && isNestedArchive(n) && !isImg(n)
            }
            if (!hasNestedLeaf) return emptyList()
        }
        var current = root
        if (!subPath.isNullOrEmpty()) {
            val pathParts = subPath.split('/').filter { it.isNotEmpty() }
            for (part in pathParts) {
                val child = current.children.find { it.name == part && it.isDir }
                if (child != null) current = child
                else return emptyList()
            }
        }
        if (current.children.all { !it.isDir } && current.imageIndices.isEmpty()) {
            val base = subPath ?: ""
            val hasNestedLeaf = allNames.any { n ->
                val rest = n.removePrefix(base)
                base.isNotEmpty() && n.startsWith(base) && !rest.contains('/') && rest.isNotEmpty() && isNestedArchive(rest) && !isImg(rest)
            }
            if (!hasNestedLeaf) return emptyList()
        }
        if (current.children.all { !it.isDir }) {
            val base = subPath ?: ""
            val hasNestedLeaf = allNames.any { n ->
                val rest = n.removePrefix(base)
                base.isNotEmpty() && n.startsWith(base) && !rest.contains('/') && rest.isNotEmpty() && isNestedArchive(rest) && !isImg(rest)
            } || (base.isEmpty() && allNames.any { n -> !n.contains('/') && isNestedArchive(n) && !isImg(n) })
            if (!hasNestedLeaf) return emptyList()
        }
        val items = mutableListOf<Item>()
        val baseSubPath = subPath ?: ""
        for (child in current.children.filter { it.isDir }) {
            val childSubPath = if (baseSubPath.isEmpty()) "${child.name}/" else "$baseSubPath${child.name}/"
            items.add(Item(child.name, p.auth, p.tree, p.doc, true, childSubPath))
        }
        for (imgIdx in current.imageIndices) {
            if (imgIdx < allNames.size) {
                val imgName = allNames[imgIdx].substringAfterLast('/')
                items.add(Item(imgName, p.auth, p.tree, p.doc, false))
            }
        }
        for (name in allNames) {
            if (baseSubPath.isNotEmpty() && !name.startsWith(baseSubPath)) continue
            val rest = name.removePrefix(baseSubPath)
            if (rest.isEmpty() || rest.contains('/')) continue
            if (!isNestedArchive(rest) || isImg(rest)) continue
            items.add(Item(rest, p.auth, p.tree, p.doc, false, baseSubPath + rest))
        }
        return items
    }

    private fun listZipSubDirPages(archivePath: String, subPath: String): List<PageMeta> {
        val p = parsePath(archivePath) ?: return emptyList()
        val result = getZipAllNames(archivePath) ?: return emptyList()
        val (cacheKey, allNames) = result
        val root = zipTree(cacheKey) { buildZipTree(allNames.toList()) }
        var current = root
        val pathParts = subPath.split('/').filter { it.isNotEmpty() }
        for (part in pathParts) {
            val child = current.children.find { it.name == part && it.isDir }
            if (child != null) current = child
            else return emptyList()
        }
        val pageMetas = mutableListOf<PageMeta>()
        var localIdx = 0
        fun collectImages(node: ZipDirNode) {
            for (imgIdx in node.imageIndices) {
                if (imgIdx < allNames.size) {
                    pageMetas.add(PageMeta(pageMetas.size, "zip", p.doc, localIdx))
                    localIdx++
                }
            }
            for (child in node.children) {
                if (child.isDir) collectImages(child)
            }
        }
        collectImages(current)
        return pageMetas
    }

    private val nestedCache = java.util.concurrent.ConcurrentHashMap<String, File>()

    private fun extractNestedArchive(archivePath: String, subPath: String): File? {
        val cacheKey = "$archivePath//$subPath"
        nestedCache[cacheKey]?.let { if (it.exists() && it.length() > 0) return it }
        val p = parsePath(archivePath) ?: return null
        val tree = makeTree(p.auth, p.tree)
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, p.doc)
        val realFile = getRealFile(uri) ?: return null
        if (!realFile.exists()) return null
        val parentExt = realFile.name.substringAfterLast('.').lowercase()
        val nestedName = subPath.substringAfterLast('/')
        val hash = Integer.toHexString(cacheKey.hashCode()).removePrefix("-")
        val outDir = File(ctx.cacheDir, "nested/$hash")
        outDir.mkdirs()
        val outFile = File(outDir, nestedName)
        try {
            when (parentExt) {
                "zip", "cbz" -> {
                    val zipResult = getZipAllNames(archivePath) ?: return null
                    val (zipCacheKey, allNames) = zipResult
                    val entryIdx = allNames.indexOf(subPath)
                    if (entryIdx < 0) return null
                    val data = com.mangareader.native.NativeEngine.readZipEntry(zipCacheKey, entryIdx) ?: return null
                    outFile.outputStream().use { it.write(data) }
                }
                "7z", "cb7" -> {
                    SevenZFile(realFile).use { sz ->
                        var entry = sz.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && entry.name == subPath) {
                                sz.getInputStream(entry).use { input ->
                                    outFile.outputStream().use { out -> input.copyTo(out) }
                                }
                                break
                            }
                            entry = sz.nextEntry
                        }
                    }
                    if (!outFile.exists() || outFile.length() == 0L) return null
                }
                "rar", "cbr", "tar", "cbt", "tgz", "txz" -> {
                    val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
                    pfd.use {
                        val data = readArchiveEntryByNameViaFd(it.fd, subPath) ?: return null
                        outFile.outputStream().use { out -> out.write(data) }
                    }
                }
                else -> return null
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "extractNestedArchive: ${e.message}")
            return null
        }
        if (!outFile.exists() || outFile.length() == 0L) return null
        nestedCache[cacheKey] = outFile
        android.util.Log.d(TAG, "extractNestedArchive: extracted ${outFile.length()} bytes to ${outFile.absolutePath}")
        return outFile
    }

    private fun readArchiveEntryByNameViaFd(fd: Int, targetName: String): ByteArray? {
        val readHandle = me.zhanghai.android.libarchive.Archive.readNew()
        try {
            me.zhanghai.android.libarchive.Archive.setCharset(readHandle, "UTF-8".toByteArray())
            me.zhanghai.android.libarchive.Archive.readSupportFilterAll(readHandle)
            me.zhanghai.android.libarchive.Archive.readSupportFormatAll(readHandle)
            me.zhanghai.android.libarchive.Archive.readOpenFd(readHandle, fd, 10240L)
            var header = me.zhanghai.android.libarchive.Archive.readNextHeader(readHandle)
            while (header != 0L) {
                val nameBytes = me.zhanghai.android.libarchive.ArchiveEntry.pathname(header)
                val name = nameBytes?.let { String(it) }
                val fileType = me.zhanghai.android.libarchive.ArchiveEntry.filetype(header)
                val isDir = fileType == me.zhanghai.android.libarchive.ArchiveEntry.AE_IFDIR
                if (name != null && !isDir && name == targetName) {
                    val entrySize = me.zhanghai.android.libarchive.ArchiveEntry.size(header)
                    if (entrySize in 1..(200L * 1024 * 1024)) {
                        val buf = java.nio.ByteBuffer.allocateDirect(entrySize.toInt())
                        me.zhanghai.android.libarchive.Archive.readData(readHandle, buf)
                        val data = ByteArray(entrySize.toInt())
                        buf.position(0)
                        buf.get(data)
                        me.zhanghai.android.libarchive.Archive.free(readHandle)
                        return data
                    }
                    return null
                }
                header = me.zhanghai.android.libarchive.Archive.readNextHeader(readHandle)
            }
            me.zhanghai.android.libarchive.Archive.free(readHandle)
        } catch (e: Exception) {
            try { me.zhanghai.android.libarchive.Archive.free(readHandle) } catch (_: Exception) {}
            android.util.Log.e(TAG, "readArchiveEntryByNameViaFd: ${e.message}")
        }
        return null
    }

    private fun listNestedArchivePages(file: File, ext: String): List<PageMeta> {
        val pages = mutableListOf<PageMeta>()
        val lowerExt = ext.lowercase()
        try {
            if (lowerExt in listOf("mobi", "azw", "azw3", "prc", "pdb")) {
                val cacheKey = file.absolutePath
                android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    try { com.mangareader.native.NativeEngine.openMobiWithFd(cacheKey, pfd.fd) } catch (_: Exception) {}
                }
                val count = try { com.mangareader.native.NativeEngine.countMobiPages(cacheKey) } catch (_: Exception) { 0 }
                for (i in 0 until count) {
                    pages.add(PageMeta(i, lowerExt, cacheKey, i))
                }
            } else {
                android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    val listed = try { listArchiveMetaViaFd(pfd.fd, lowerExt, file.absolutePath) } catch (_: Exception) { emptyList() }
                    for (meta in listed) {
                        pages.add(PageMeta(pages.size, lowerExt, file.absolutePath, meta.localIdx))
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "listNestedArchivePages: ${e.message}")
        }
        android.util.Log.d(TAG, "listNestedArchivePages: ext=$lowerExt total=${pages.size}")
        return pages
    }

    private fun readNestedPageRaw(file: File, ext: String, localIdx: Int): ByteArray? {
        val lowerExt = ext.lowercase()
        return try {
            if (lowerExt in listOf("mobi", "azw", "azw3", "prc", "pdb")) {
                loadMobiPageFromFile(file, localIdx)
            } else {
                android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    val got = readArchiveEntriesViaFd(pfd.fd, setOf(localIdx))
                    got[localIdx]
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "readNestedPageRaw: ${e.message}")
            null
        }
    }
}

internal object NaturalComparator : Comparator<String> {
    override fun compare(a: String, b: String): Int = com.mangareader.zip.CommonsZipHelper.compareNatural(a, b)
}
