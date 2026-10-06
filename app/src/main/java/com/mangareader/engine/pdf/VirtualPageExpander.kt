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

    // ★ P5（2026-10-04）：顺流介质（solid archive）——单 fd 顺流遍历，乱序随机访问要反复重绕
    private val SOLID_SOURCES = setOf("rar", "7z", "tar")

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
        wideSplitEnabled: Boolean = false,
        splitLeftFirst: Boolean = true,
        tallRatioThreshold: Float = PdfTallSplitCalculator.TALL_RATIO_THRESHOLD,
    ): List<ArchiveEngine.PageMeta> {
        if ((!tallSplitEnabled || !isWebtoonMode) && !wideSplitEnabled) return pages
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
        // ★ 并行预探（仅 allowProbe=true 的入口路径）：几百页未探测章的串行头部探测
        //   最坏数十秒，全程挡在"正在打开…"。预探只提前发起 IO，结果仍走下方 pending 合并
        //   落盘——输出与串行版本完全一致。
        //   ★ P5（2026-10-04）探测调度按介质分流：rar/7z/tar 是顺流介质（fd 单锁），乱序并发
        //   探测会反复重绕流——最坏 O(N²) 数据流过。改单线程按页序探测：loadPageBounds 底层
        //   loadArchiveEntryViaFdCache 每次顺流顺带收集 target..+2 进 adjacentPrereadCache，
        //   升序提交时邻页直接命中，消除乱序重绕（实际流量取决于 native 续读实现，但恒不劣于
        //   旧乱序并发）。zip/dir 头部读取是随机访问
        //  （ZipFile 按名直取/文件头 64KB），保持并行池。
        val preProbed = HashMap<String, IntArray?>()
        if (allowProbe) {
            val stamp0 = sizeStamp(pages)
            val store0 = engine.loadPageSizeCache(treePath, stamp0)
            storeStamp = stamp0
            storeSizes = store0
            val needProbe = pages.filter { it.segmentIndex <= 0 && it.source in SPLIT_SOURCES && it.source != "pdf" }
                .distinctBy { pageSizeKey(it) }
                .filter { (store0 ?: emptyMap())[pageSizeKey(it)] == null }
            fun probeOne(meta: ArchiveEngine.PageMeta) {
                val b = try { engine.loadPageBounds(meta, treePath) } catch (_: Exception) { null }
                synchronized(preProbed) {
                    preProbed[pageSizeKey(meta)] = b?.let { intArrayOf(it.width, it.height) }
                }
            }
            // 排序键 ext→localIdx：多子包书各子包 localIdx 皆从 0 起，仅按 localIdx 会把子包页
            //   交错（0,0,1,1…）打散顺流预读；按 ext 分组后包内升序（当前子包过滤只放行
            //   zip/epub/pdf/mobi、solid 罕见多包——防御性，防未来放行即踩）
            val solid = needProbe.filter { it.source in SOLID_SOURCES }.sortedWith(compareBy({ it.ext }, { it.localIdx }))
            val random = needProbe.filter { it.source !in SOLID_SOURCES }
            solid.forEach { probeOne(it) }
            if (random.size > 1) {
                val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(4, random.size))
                try {
                    val futures = random.map { meta -> pool.submit { probeOne(meta) } }
                    futures.forEach { it.get() }
                } catch (_: Exception) {
                } finally {
                    pool.shutdown()
                }
            } else {
                random.forEach { probeOne(it) }
            }
        }
        for ((origIdx, meta) in pages.withIndex()) {
            // ★ splitSide 页（宽图二分半页）透传且永不二次处理——半页免竖切（横屏条漫要求）、
            //   免重复二分，originalPageIdx 已是物理页不可再盖
            if (meta.segmentIndex > 0 || meta.splitSide != 0 || meta.source !in SPLIT_SOURCES) {
                // 透传页统一盖 originalPageIdx（段页保持原值）——Reader 侧切模式映射页号用；
                // 对 WebtoonReaderView 的同组段页判隙（segmentIndex>0 双条件）无影响
                result.add(when {
                    meta.segmentIndex > 0 || meta.splitSide != 0 -> meta
                    else -> meta.copy(originalPageIdx = origIdx)
                })
                continue
            }
            var size: IntArray? = null
            var fromStore = false
            if (meta.source == "pdf") {
                size = try { engine.loadPageSize(meta, treePath) } catch (_: Exception) { null }
            } else {
                val key = pageSizeKey(meta)
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
                result.add(meta.copy(originalPageIdx = origIdx))
                continue
            }
            val splitH = PdfTallSplitCalculator.splitHeightPdf(screenMax, screenWidth, pw)
            // ★ 竖切仅条漫族（isWebtoonMode）参与：翻页视图不渲染段页，高图在翻页族走透传/宽图分支
            val tall = isWebtoonMode && PdfTallSplitCalculator.isTallPage(pw, ph, splitH, tallRatioThreshold)
            if (!tall) {
                // ★ 宽图二分（2026-10-02）：跨页拼合图（宽>高×1.2，与 ArchiveEngine.applySplitSide
                //   裁半守卫同源——展开的页加载时必被裁半）拆成左右两个半页位（splitSide 1/2），
                //   条漫/翻页统一按独立页消费。竖切优先：高图不进此分支，两分支天然互斥。
                if (wideSplitEnabled && pw > ph * 1.2f) {
                    result.add(meta.copy(splitSide = if (splitLeftFirst) 1 else 2, originalPageIdx = origIdx))
                    result.add(meta.copy(splitSide = if (splitLeftFirst) 2 else 1, originalPageIdx = origIdx))
                    continue
                }
                if (!fromStore) pending[pageSizeKey(meta)] = intArrayOf(pw.toInt(), ph.toInt())
                result.add(meta.copy(originalPageIdx = origIdx))
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

    /** ★ 远程书渐进尺寸合并落盘（2026-10-06 条漫宽图二分工单）：远程页 loadPageBounds 被
     *  requireNotRemote 拒收 → expand 探测全失败 → 宽图二分永不生效。解法：解码时顺带记录
     *  真实尺寸（零额外流量），节流后用【原始表】stamp 合并写尺寸缓存——下次进书 expand
     *  命中缓存，宽图/长图全量生效。pages 必须是展开前的原始表（stamp 才与 expand 读取一致；
     *  页键 pageSizeKey 不掺 splitSide/segment，展开段 meta 可直接取键）。 */
    fun mergeProbedSizes(
        engine: ArchiveEngine,
        treePath: String,
        pages: List<ArchiveEngine.PageMeta>,
        sizes: Map<String, IntArray>,
    ) {
        if (sizes.isEmpty()) return
        try {
            val stamp = sizeStamp(pages)
            val merged = HashMap<String, IntArray>(engine.loadPageSizeCache(treePath, stamp) ?: emptyMap())
            merged.putAll(sizes)
            engine.savePageSizeCache(treePath, stamp, merged)
        } catch (_: Exception) {}
    }

    /** 尺寸表条目键。localIdx|source 在 dir 源（ext=文档ID、localIdx 恒 0）与 d:/z: 多子包书
     *  （各子包 localIdx 皆从 0 起）上跨页/跨包碰撞——一页尺寸被整本共用、竖切/宽图判定全错；
     *  掺 ext（各源 PageMeta 第三参：dir/子包=文档ID，整档书=档自身 docId 恒定不碍事）消歧。
     *  expand 与 Reader.autoWebtoon 两侧必须共用本函数，保证同一张表读写同键 */
    internal fun pageSizeKey(m: ArchiveEngine.PageMeta): String = "${m.localIdx}|${m.source}|${m.ext}"

    /** 尺寸表失效戳：页数 + ext/source 序列摘要。加删页/换类型即失效；同内容替换由渲染器侧尺寸复核兜底 */
    // ★ P6（2026-10-04）：internal 供 Reader.autoWebtoon 复用——autoWebtoon 探测与 expand
    //   共享同一张尺寸表必须用同一个 stamp（页数+ext 序列指纹）
    internal fun sizeStamp(pages: List<ArchiveEngine.PageMeta>): String {
        // v2：键格式掺 ext 后旧表条目永不命中，整体升版作废重探，防新旧键混存只增不减
        val sb = StringBuilder("v2|").append(pages.size).append(':')
        for (m in pages) sb.append(m.ext.length).append('.').append(m.source).append(';')
        val body = sb.toString()
        return try {
            java.security.MessageDigest.getInstance("MD5").digest(body.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(16)
        } catch (_: Exception) { body.hashCode().toString() }
    }
}
