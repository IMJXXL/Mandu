package com.mangareader.native

/**
 * ★ 双页合并显式跨页表（2026-10-05 重做，取代 2s/2s+1 序号算术）
 *
 * 旧行为把页两两硬拼（0-1、2-3…），横宽跨页图（宽>高）被挤在半屏、超长条图被缩成指甲盖，
 * 且双页+拆分模式下宽图落在"右位"时拆分分支不生效——用户视角"合并逻辑不够好"。
 * 本表在进书/切模式/尺寸扫描回填时构建一次，全部翻页映射（adapter 位次、进度、点击翻页）
 * 从表取值，不再内联公式。
 *
 * 配对规则（对齐 mihon 系 fork 的成熟取舍，Koharia 阈值版放宽）：
 *  - 宽 > 高（横宽跨页图）→ 单独占一位（它本身就是跨页，合并必裁）
 *  - 宽/高 < 0.5（超长条图）→ 单独占一位（并排渲染会缩到不可读；整本长图在进书时已被
 *    autoWebtoon 切条漫，这里兜单页混排场景）
 *  - 两页宽高比差 > 1.3 → 不配（一正常页配一近方形/瘦长页并排观感差）
 *  - 尺寸未知（远程书/头部解析失败）→ 容忍配对，行为与旧算术一致（Koharia UNKNOWN 同策略）
 *
 * ★ v1.384 Komiho 跟进（宽图二分上移到表层，引擎层展开退役）：
 *  - wideSplit 开启时横宽页（w>h×1.2，dims 已知）占两个连续 slot（左半位+右半位，两个 slot
 *    指向同一物理页，slotHalf 1/2 区分；rtl 时右半位在前=右开本读序），渲染层按 slotHalf
 *    定位半页视口，位图共享不裁拷贝；
 *  - dims 未知的页恒 1 slot（整页显示）——远程书/探测失败书页表身份 1:1 不漂移（rar 工单
 *    "页数 98≠49 根源"根治），渐进尺寸到达后翻案重排（该页闪一帧变双 slot）；
 *  - 拆分序=Komiho onPageSplit 插入位置语义：LTR 左先、RTL 右先。
 *
 * 纯 Kotlin 无 Android 依赖，配对判定有单测（SpreadTableTest）。
 */
class SpreadTable private constructor(
    /** 每个跨页的阅读序首页（物理页索引） */
    private val slotsFirst: IntArray,
    /** 每个跨页的阅读序次页（-1=该位单独显示一页） */
    private val slotsSecond: IntArray,
    /** 物理页 → 跨页序号（横宽双 slot 页指向其首个 slot，即阅读序先读的半页位） */
    private val pageToSlot: IntArray,
    private val pagesCount: Int,
    /** ★ v1.383 构建时智能判定是否真的生效（smart 且 dims 有效）——false=朴素产物
     *  （进书首帧 pageDims 异步空的两两硬拼，可能含横宽页被拼的错误位）。
     *  freeze 翻案只拆朴素旧表的错误位；smart 旧表的冻结位稳定（乱闪根修语义不变） */
    val builtSmart: Boolean,
    /** ★ v1.384 每 slot 的半页侧：0=整页 1=左半 2=右半（横宽页双 slot 为 1/2） */
    private val slotHalfArr: ByteArray,
) {
    val slotCount: Int get() = slotsFirst.size

    fun firstPage(spread: Int): Int = slotsFirst.getOrElse(spread) { 0 }

    fun secondPage(spread: Int): Int = slotsSecond.getOrElse(spread) { -1 }

    /** ★ v1.384 该 slot 显示页的哪一半：0=整页 1=左半 2=右半 */
    fun slotHalf(spread: Int): Int = slotHalfArr.getOrElse(spread) { 0 }.toInt()

    fun pageToSpread(page: Int): Int =
        if (page in 0 until pagesCount) pageToSlot[page] else 0

    /** 表是否已含某页的映射（pageToSpread 对越界页返回 0 的兜底会让调用方误判，供守卫用） */
    fun containsPage(page: Int): Boolean = page in 0 until pagesCount

    /** 映射逐位相同（供尺寸回填时判断"表没变则免重绑"，常规书籍扫描确认后映射多与朴素配对一致） */
    fun sameMapping(other: SpreadTable): Boolean {
        if (this === other) return true
        if (slotCount != other.slotCount || pagesCount != other.pagesCount) return false
        for (s in 0 until slotCount) {
            if (slotsFirst[s] != other.slotsFirst[s] || slotsSecond[s] != other.slotsSecond[s]) return false
            if (slotHalfArr[s] != other.slotHalfArr[s]) return false
        }
        return true
    }

    companion object {
        /**
         * 构建跨页表。
         * @param double false=单页模式：单位表，每页独占一位（slotCount==totalPages）；
         *               true=双页布局：!smart 时全配对（与旧 2s/2s+1 算术逐位一致），
         *               smart 时按尺寸智能配对。
         * @param startOffset 起始页豁免数（仅 double 时生效）：前 startOffset 页各占一位
         *                    单独显示，第 startOffset+1 页（0 基）起两两合并。
         *                    0=全合并（旧"双页"）；1=跳过首页（旧"双页跳过首页"）；
         *                    上限 9（"从第 10 页开始双页"，UI 滑条 2..10）。
         * @param soloPages 用户标记的页豁免（仅 double 时生效）：命中页强制单独占一位，
         *                  其余页按顺序重新两两配对（无空洞）。键=当前章物理页号，
         *                  存储按章隔离（page_solo_flags 表），不跨书/跨章。
         * @param dims 2*totalPages，[2i]=宽 [2i+1]=高，0=未知
         * @param prev 旧表（配合 freezeThroughPage 做稳定重排）
         * @param freezeThroughPage 冻结边界：该页号（含）之前的所有位逐位照抄 prev——
         *                  渐进尺寸到达引发的重新配对只发生在用户未看到的后方位，
         *                  当前正在看的位构图绝不中途改变（"打架乱闪"根修：
         *                  试探合并→伙伴解码→拆开 的翻转只允许发生在屏外）
         * @param wideSplit ★ v1.384 宽图二分（Komiho dualPageSplit 跟进）：true 时横宽页
         *                  （dims 已知 w>h×1.2）占两个连续 slot（半页位），dims 未知页 1 slot
         *                  整页；单页/双页布局均可开（双页布局下横宽页本来就该整页 solo，
         *                  二分后按半页翻=阅读器翻页族的"每半页一翻"体验）
         * @param rtl 半页读序：true=右半位在前（右开本，Komiho RTL 插入位置语义）
         */
        fun build(
            totalPages: Int,
            double: Boolean = true,
            startOffset: Int = 0,
            smart: Boolean = false,
            dims: IntArray? = null,
            soloPages: Set<Int> = emptySet(),
            prev: SpreadTable? = null,
            freezeThroughPage: Int = -1,
            wideSplit: Boolean = false,
            rtl: Boolean = false,
        ): SpreadTable {
            val n = totalPages.coerceAtLeast(0)
            if (!double && !wideSplit) {
                // 单位表：单页模式每页一位（页号==跨页号，second 恒 -1）
                return SpreadTable(IntArray(n) { it }, IntArray(n) { -1 }, IntArray(n) { it }, n, false, ByteArray(n))
            }
            // ★ v1.384：smart 是否实际生效——dims 缺失时 smart 退化为朴素两两硬拼
            val hadDims = dims != null && dims.size >= n * 2 && dims.any { it > 0 }
            val builtSmart = smart && hadDims
            // 预算上限：wideSplit 时横宽页占 2 位，上限放宽到 2n
            val cap = if (wideSplit) n * 2 else n
            val first = IntArray(cap)
            val second = IntArray(cap)
            val half = ByteArray(cap)
            val p2s = IntArray(n)
            var s = 0
            var i = 0
            val canFreeze = prev != null && freezeThroughPage >= 0 && prev.pagesCount == n
            // 起始页豁免（startOffset）：前 N 页各自单独显示（与旧 shift 门控语义一致，N=1 即跳过首页）
            val head = startOffset.coerceIn(0, n)
            while (i < head) {
                first[s] = i; second[s] = -1; half[s] = 0; p2s[i] = s
                s++; i++
            }
            while (i < n) {
                // ★ 冻结前缀（2026-10-06 乱闪根修）：边界（含）前的位照抄旧表——顺序行走从位首
                //   整位复制，边界所在位及其前所有位与旧表逐位一致，pageToSpread 不变
                if (canFreeze && i <= freezeThroughPage) {
                    val ps = prev!!.pageToSpread(i)
                    val pf = prev.firstPage(ps)
                    val psec = prev.secondPage(ps)
                    // ★ v1.384 修正：双 slot 页（横宽页占两位）的 pageToSpread 恒指首位——
                    //   i 走到第二位（i > pf）时必须取相邻位（ps+1）的 half，否则两位都照抄
                    //   成先读半页（半页序列 1,1,3,3…），右半永远不出现
                    val phalf = (if (i > pf) prev.slotHalf(ps + 1) else prev.slotHalf(ps)).toByte()
                    // ★ v1.383（用户工单"一张错全错"）：翻案只针对朴素旧表——进书首帧
                    //   pageDims 异步空、首表朴素硬拼（横宽页被并排），全量尺寸到达时
                    //   freeze 照抄旧位会把错误配对固化。朴素旧表（builtSmart=false）的
                    //   冻结位含横宽/长条页 → 拆开重排修正（该位闪一帧）；smart 旧表冻结
                    //   位稳定（乱闪根修不变）。修正位消耗页数变化致边界错位（pf != i）
                    //   时落穿正常配对，防跳页
                    // ★ v1.384 翻案扩展：冻结位若是"未拆的横宽整页"（half=0 但 wide 判定
                    //   成立）→ 落穿重排成双 slot（渐进尺寸到达后整页位升级半页位）
                    val frozenInvalid = (!prev.builtSmart && smart && dims != null && psec >= 0 &&
                        (!pairable(pf, dims) || !pairable(psec, dims))) ||
                        (wideSplit && phalf == 0.toByte() && psec < 0 && isWidePage(pf, dims))
                    if (!frozenInvalid) {
                        if (psec >= 0 && pf == i) {
                            // 配对位照抄
                            first[s] = pf; second[s] = psec; half[s] = phalf
                            p2s[pf] = s; p2s[psec] = s
                            s++; i += 2
                            continue
                        }
                        if (psec < 0 && pf == i) {
                            // 单页位照抄（整页，或双 slot 的先读半页位）。
                            // ★ v1.384 修正：双 slot 页（half!=0）必须同轮补出第二个半页位——
                            //   i 按页号步进一页一轮，否则第二半页位丢失、后续页前移顶掉
                            first[s] = pf; second[s] = -1; half[s] = phalf
                            p2s[pf] = s
                            s++; i += 1
                            if (phalf != 0.toByte()) {
                                first[s] = pf; second[s] = -1; half[s] = prev.slotHalf(ps + 1).toByte()
                                s++
                            }
                            continue
                        }
                        // 其余（翻案/边界错位）落穿正常配对逻辑，防跳页
                    }
                }
                // ★ v1.384 宽图二分（Komiho 跟进）：横宽页占两个连续半页 slot
                if (wideSplit && isWidePage(i, dims)) {
                    val firstHalf: Byte = if (rtl) 2 else 1
                    val secondHalf: Byte = if (rtl) 1 else 2
                    first[s] = i; second[s] = -1; half[s] = firstHalf; p2s[i] = s
                    s++
                    first[s] = i; second[s] = -1; half[s] = secondHalf
                    s++
                    i += 1
                    continue
                }
                // ★ 用户页豁免（单本单页，不能影响别人）：命中页强制 solo，其余页顺序重新配对
                if (i in soloPages) {
                    first[s] = i; second[s] = -1
                    half[s] = 0
                    p2s[i] = s
                    s++; i += 1
                    continue
                }
                // 非智能=全配对（与旧 2s/2s+1 算术逐位一致）；智能=按尺寸判定；
                // ★ v1.384 单页+wideSplit：不配对（横宽页双 slot、其余单 slot，见上）
                val canPairNext = if (double) {
                    if (smart) {
                        if (wideSplit && !hasKnownDims(i, dims)) false
                        else (i + 1 < n && canPair(i, i + 1, dims))
                    } else (i + 1 < n)
                } else false
                if (canPairNext) {
                    first[s] = i; second[s] = i + 1
                    half[s] = 0
                    p2s[i] = s; p2s[i + 1] = s
                    s++; i += 2
                } else {
                    first[s] = i; second[s] = -1
                    half[s] = 0
                    p2s[i] = s
                    s++; i += 1
                }
            }
            return SpreadTable(first.copyOf(s), second.copyOf(s), p2s, n, builtSmart, half.copyOf(s))
        }

        /** dims 约定：2*totalPages，[2i]=宽 [2i+1]=高，0=未知 */
        private fun pairable(idx: Int, dims: IntArray?): Boolean {
            if (dims == null || idx * 2 + 1 >= dims.size) return true
            val w = dims[idx * 2]
            val h = dims[idx * 2 + 1]
            if (w <= 0 || h <= 0) return true
            if (w > h) return false        // 横宽跨页图
            if (w * 2 < h) return false    // 长条图（宽高比 < 0.5）
            return true
        }

        /** ★ v1.384 横宽判定（宽图二分）：w>h×1.2（与 ArchiveEngine 裁半守卫/引擎展开同源阈值） */
        private fun isWidePage(idx: Int, dims: IntArray?): Boolean {
            if (dims == null || idx * 2 + 1 >= dims.size) return false
            val w = dims[idx * 2]
            val h = dims[idx * 2 + 1]
            return w > 0 && h > 0 && w > h * 1.2f
        }

        private fun hasKnownDims(idx: Int, dims: IntArray?): Boolean {
            if (dims == null || idx * 2 + 1 >= dims.size) return false
            return dims[idx * 2] > 0 && dims[idx * 2 + 1] > 0
        }

        private fun canPair(a: Int, b: Int, dims: IntArray?): Boolean {
            if (!pairable(a, dims) || !pairable(b, dims)) return false
            // 宽高比差守卫：两页形状悬殊不并排（Koharia 1.25 放宽到 1.3 减少误拆）
            if (dims != null) {
                val aw = dims.getOrNull(a * 2) ?: 0
                val ah = dims.getOrNull(a * 2 + 1) ?: 0
                val bw = dims.getOrNull(b * 2) ?: 0
                val bh = dims.getOrNull(b * 2 + 1) ?: 0
                if (aw > 0 && ah > 0 && bw > 0 && bh > 0 && aw <= ah && bw <= bh) {
                    val aa = aw.toFloat() / ah
                    val ba = bw.toFloat() / bh
                    if (maxOf(aa, ba) / minOf(aa, ba) > 1.3f) return false
                }
            }
            return true
        }
    }
}
