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
 * 纯 Kotlin 无 Android 依赖，配对判定有单测（SpreadTableTest）。
 */
class SpreadTable private constructor(
    /** 每个跨页的阅读序首页（物理页索引） */
    private val slotsFirst: IntArray,
    /** 每个跨页的阅读序次页（-1=该位单独显示一页） */
    private val slotsSecond: IntArray,
    /** 物理页 → 跨页序号 */
    private val pageToSlot: IntArray,
    private val pagesCount: Int,
) {
    val slotCount: Int get() = slotsFirst.size

    fun firstPage(spread: Int): Int = slotsFirst.getOrElse(spread) { 0 }

    fun secondPage(spread: Int): Int = slotsSecond.getOrElse(spread) { -1 }

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
         */
        fun build(
            totalPages: Int,
            double: Boolean = true,
            startOffset: Int = 0,
            smart: Boolean = false,
            dims: IntArray? = null,
            soloPages: Set<Int> = emptySet(),
        ): SpreadTable {
            val n = totalPages.coerceAtLeast(0)
            if (!double) {
                // 单位表：单页模式每页一位（页号==跨页号，second 恒 -1）
                return SpreadTable(IntArray(n) { it }, IntArray(n) { -1 }, IntArray(n) { it }, n)
            }
            // 预算上限：n 页至多 n 位（全部 solo）
            val first = IntArray(n)
            val second = IntArray(n)
            val p2s = IntArray(n)
            var s = 0
            var i = 0
            // 起始页豁免（startOffset）：前 N 页各自单独显示（与旧 shift 门控语义一致，N=1 即跳过首页）
            val head = startOffset.coerceIn(0, n)
            while (i < head) {
                first[s] = i; second[s] = -1; p2s[i] = s
                s++; i++
            }
            while (i < n) {
                // ★ 用户页豁免（单本单页，不能影响别人）：命中页强制 solo，其余页顺序重新配对
                if (i in soloPages) {
                    first[s] = i; second[s] = -1
                    p2s[i] = s
                    s++; i += 1
                    continue
                }
                // 非智能=全配对（与旧 2s/2s+1 算术逐位一致）；智能=按尺寸判定
                val canPairNext = if (smart) (i + 1 < n && canPair(i, i + 1, dims)) else (i + 1 < n)
                if (canPairNext) {
                    first[s] = i; second[s] = i + 1
                    p2s[i] = s; p2s[i + 1] = s
                    s++; i += 2
                } else {
                    first[s] = i; second[s] = -1
                    p2s[i] = s
                    s++; i += 1
                }
            }
            return SpreadTable(first.copyOf(s), second.copyOf(s), p2s, n)
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
