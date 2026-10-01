package com.mangareader.engine.pdf

import android.util.Log
import com.mangareader.viewer.ArchiveEngine

/**
 * 虚拟页展开器：把 tall 页展开成 N 个虚拟段页（webtoon 列表项）。
 *
 * ★ 长图分割扩展（2026-09-25）：从 PDF 专属放开到图片族（dir/zip/rar/7z/tar），几何
 *   统一走 PdfTallSplitCalculator（A1 均分语义）。mobi/azw3 家族排除——其解码分发
 *   （loadMobiPageAsync）不理解 segmentIndex，混入会重复显示整页。
 *
 * 尺寸解析三级：PDF → pdfGetPageSize（专用分支，快）；图片族 → 尺寸表缓存（内存/磁盘）
 * →（allowProbe=true 时）loadPageBounds 头部探测并写回缓存。
 * allowProbe=false 的调用点（WebtoonReaderView.setup 重入）只读不探，绝不发起 IO——
 * 主线程零探测卡顿；探过的结果已在缓存里，展开结果与 Reader 入口天然一致。
 */
object VirtualPageExpander {
    private const val TAG = "PdfTallSplit"

    private val SPLIT_SOURCES = setOf("pdf", "dir", "zip", "rar", "7z", "tar")

    /** 最近一次 expand 统计（诊断取证）：入口页数/输出页数/探测失败整页透传数 */
    @Volatile
    var lastStats: String = ""
        private set

    fun expand(
        pages: List<ArchiveEngine.PageMeta>,
        tallSplitEnabled: Boolean,
        isWebtoonMode: Boolean,
        screenMax: Int,
        screenWidth: Int,
        engine: ArchiveEngine,
        treePath: String,
        allowProbe: Boolean = false,
    ): List<ArchiveEngine.PageMeta> {
        if (!tallSplitEnabled || !isWebtoonMode) return pages
        var storeStamp: String? = null
        var storeSizes: Map<String, IntArray>? = null
        val pending = HashMap<String, IntArray>()
        // 图片页尺寸表惰性加载：纯 PDF 书不触缓存
        fun sizesForImages(): Map<String, IntArray>? {
            if (storeSizes == null) {
                storeStamp = sizeStamp(pages)
                storeSizes = engine.loadPageSizeCache(treePath, storeStamp!!)
            }
            return storeSizes
        }
        val result = mutableListOf<ArchiveEngine.PageMeta>()
        var unprobedCount = 0
        // ★ 并行预探（并发 4，仅 allowProbe=true 的入口路径）：几百页未探测章的串行头部探测
        //   最坏数十秒（rar/7z 每页全量解压 ×2 次重试 + 失败页 sleep 120ms），全程挡在"正在打开…"。
        //   预探只提前发起 IO，结果仍走下方 pending 合并落盘——输出与串行版本完全一致
        val preProbed = HashMap<String, IntArray?>()
        if (allowProbe) {
            val stamp0 = sizeStamp(pages)
            val store0 = engine.loadPageSizeCache(treePath, stamp0)
            storeStamp = stamp0
            storeSizes = store0
            val needProbe = pages.filter { it.segmentIndex <= 0 && it.source in SPLIT_SOURCES && it.source != "pdf" }
                .distinctBy { "${it.localIdx}|${it.source}" }
                .filter { (store0 ?: emptyMap())["${it.localIdx}|${it.source}"] == null }
            if (needProbe.size > 1) {
                val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(4, needProbe.size))
                try {
                    val futures = needProbe.map { meta ->
                        pool.submit {
                            val b = try { engine.loadPageBounds(meta, treePath) } catch (_: Exception) { null }
                            synchronized(preProbed) {
                                preProbed["${meta.localIdx}|${meta.source}"] = b?.let { intArrayOf(it.width, it.height) }
                            }
                        }
                    }
                    futures.forEach { it.get() }
                } catch (_: Exception) {
                } finally {
                    pool.shutdown()
                }
            }
        }
        for ((origIdx, meta) in pages.withIndex()) {
            if (meta.segmentIndex > 0 || meta.source !in SPLIT_SOURCES) {
                result.add(meta)
                continue
            }
            var size: IntArray? = null
            var fromStore = false
            if (meta.source == "pdf") {
                size = try { engine.loadPageSize(meta, treePath) } catch (_: Exception) { null }
            } else {
                val key = "${meta.localIdx}|${meta.source}"
                val cached = sizesForImages()?.get(key)
                when {
                    cached != null -> { size = cached; fromStore = true }
                    allowProbe -> {
                        // 预探结果优先；预探未跑（≤1 页缺尺寸）才就地探测
                        val pre = synchronized(preProbed) { preProbed[key] }
                        val b: IntArray? = pre ?: (try {
                            engine.loadPageBounds(meta, treePath)?.let { intArrayOf(it.width, it.height) }
                        } catch (_: Exception) { null })
                        if (b != null) {
                            size = b
                            pending[key] = size
                        }
                    }
                    // else：setup 重入只读不探；未知页透传（Reader 入口已探过并落缓存）
                }
            }
            val pw = size?.get(0)?.toFloat() ?: 0f
            val ph = size?.get(1)?.toFloat() ?: 0f
            if (pw <= 0f || ph <= 0f) {
                // 动图（0,0）/探测失败/未知 → 原样透传，永不阻塞
                if (meta.source in SPLIT_SOURCES) unprobedCount++
                result.add(meta)
                continue
            }
            val splitH = PdfTallSplitCalculator.splitHeightPdf(screenMax, screenWidth, pw)
            if (!PdfTallSplitCalculator.isTallPage(pw, ph, splitH)) {
                if (!fromStore) pending["${meta.localIdx}|${meta.source}"] = intArrayOf(pw.toInt(), ph.toInt())
                result.add(meta)
                continue
            }
            val n = PdfTallSplitCalculator.segmentCount(ph, splitH)
            Log.d(TAG, "expand idx=$origIdx src=${meta.source} h/w=${ph / pw} N=$n splitH=$splitH")
            for (seg in 1..n) {
                result.add(meta.copy(segmentIndex = seg, segmentTotal = n, originalPageIdx = origIdx))
            }
        }
        // 探测成果落盘：新探到的尺寸（含动图标记、非长图页）合并进既有表，下次零探测
        if (allowProbe && pending.isNotEmpty()) {
            val merged = HashMap<String, IntArray>(storeSizes ?: emptyMap())
            merged.putAll(pending)
            engine.savePageSizeCache(treePath, storeStamp ?: sizeStamp(pages), merged)
        }
        lastStats = "in=${pages.size} out=${result.size} unprobed=$unprobedCount"
        return result
    }

    /** 尺寸表失效戳：页数 + ext/source 序列摘要。加删页/换类型即失效；同内容替换由渲染器侧尺寸复核兜底 */
    private fun sizeStamp(pages: List<ArchiveEngine.PageMeta>): String {
        val sb = StringBuilder(pages.size).append(':')
        for (m in pages) sb.append(m.ext.length).append('.').append(m.source).append(';')
        val body = sb.toString()
        return try {
            java.security.MessageDigest.getInstance("MD5").digest(body.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(16)
        } catch (_: Exception) { body.hashCode().toString() }
    }
}
