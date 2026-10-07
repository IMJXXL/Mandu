package com.mangareader.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SpreadTable 配对算法单测（2026-10-05 双页合并重做）。
 * 回归守卫：smart=false 时映射必须与旧序号算术（SpreadMapping 2s/2s+1）逐位一致。
 */
class SpreadTableTest {

    private fun dimsOf(vararg wh: Pair<Int, Int>): IntArray {
        val d = IntArray(wh.size * 2)
        wh.forEachIndexed { i, (w, h) -> d[2 * i] = w; d[2 * i + 1] = h }
        return d
    }

    // ---- 回归守卫：非智能模式与旧算术一致 ----

    @Test
    fun naiveMatchesLegacyArithmetic() {
        for (n in 1..17) {
            val t = SpreadTable.build(n)
            assertEquals((n + 1) / 2, t.slotCount)
            for (s in 0 until t.slotCount) {
                assertEquals(2 * s, t.firstPage(s))
                val expectSecond = if (2 * s + 1 < n) 2 * s + 1 else -1
                assertEquals(expectSecond, t.secondPage(s))
            }
            for (p in 0 until n) assertEquals(p / 2, t.pageToSpread(p))
        }
    }

    @Test
    fun naiveShiftMatchesLegacyArithmetic() {
        for (n in 2..17) {
            val t = SpreadTable.build(n, startOffset = 1)
            assertEquals(1 + n / 2, t.slotCount)
            assertEquals(0, t.firstPage(0))
            assertEquals(-1, t.secondPage(0))
            for (s in 1 until t.slotCount) {
                assertEquals(2 * s - 1, t.firstPage(s))
                val expectSecond = if (2 * s < n) 2 * s else -1
                assertEquals(expectSecond, t.secondPage(s))
            }
            for (p in 0 until n) {
                assertEquals(if (p == 0) 0 else 1 + (p - 1) / 2, t.pageToSpread(p))
            }
        }
    }

    @Test
    fun singlePageWithShiftIsIdentity() {
        // 旧门控 shift && size>1：单页书 shift 不生效
        val t = SpreadTable.build(1, startOffset = 1, smart = true)
        assertEquals(1, t.slotCount)
        assertEquals(0, t.firstPage(0))
        assertEquals(-1, t.secondPage(0))
    }

    // ---- 智能配对：宽图/长图跳过 ----

    @Test
    fun widePageGoesSolo() {
        // [竖, 竖, 横宽跨页, 竖, 竖] → (0,1) (2,solo) (3,4)
        val d = dimsOf(800 to 1200, 800 to 1200, 2400 to 1200, 800 to 1200, 800 to 1200)
        val t = SpreadTable.build(5, smart = true, dims = d)
        assertEquals(3, t.slotCount)
        assertEquals(0, t.firstPage(0)); assertEquals(1, t.secondPage(0))
        assertEquals(2, t.firstPage(1)); assertEquals(-1, t.secondPage(1))
        assertEquals(3, t.firstPage(2)); assertEquals(4, t.secondPage(2))
    }

    @Test
    fun tallPageGoesSolo() {
        // 长条图 宽高比<0.5 不参与配对
        val d = dimsOf(800 to 1200, 400 to 1600, 800 to 1200)
        val t = SpreadTable.build(3, smart = true, dims = d)
        assertEquals(3, t.slotCount)
        assertEquals(0, t.firstPage(0)); assertEquals(-1, t.secondPage(0))
        assertEquals(1, t.firstPage(1)); assertEquals(-1, t.secondPage(1))
        assertEquals(2, t.firstPage(2)); assertEquals(-1, t.secondPage(2))
    }

    @Test
    fun aspectMismatchDoesNotPair() {
        // 0.67 与 0.9 比差 1.34 > 1.3 → 不配
        val d = dimsOf(800 to 1200, 900 to 1000)
        val t = SpreadTable.build(2, smart = true, dims = d)
        assertEquals(2, t.slotCount)
        assertEquals(-1, t.secondPage(0))
        assertEquals(-1, t.secondPage(1))
    }

    @Test
    fun nearSquarePagesPair() {
        // 0.9 与 0.95 比差 1.056 ≤ 1.3 且都 ≤ 高 → 可配
        val d = dimsOf(900 to 1000, 950 to 1000)
        val t = SpreadTable.build(2, smart = true, dims = d)
        assertEquals(1, t.slotCount)
        assertEquals(0, t.firstPage(0)); assertEquals(1, t.secondPage(0))
    }

    @Test
    fun unknownDimsFallbackToPairing() {
        // 尺寸全 0（远程书/解析失败）→ 与朴素配对一致
        val d = IntArray(6) // 全 0
        val t = SpreadTable.build(3, smart = true, dims = d)
        val naive = SpreadTable.build(3)
        assertTrue(t.sameMapping(naive))
    }

    @Test
    fun nullDimsEqualsNaive() {
        val t = SpreadTable.build(7, smart = true, dims = null)
        assertTrue(t.sameMapping(SpreadTable.build(7)))
    }

    // ---- shift + smart 组合 ----

    @Test
    fun shiftWithSmartSkipsWide() {
        // 首张单页 + [竖, 竖, 横, 竖] → 竖后邻横宽页不可配 → (0,solo) (1,solo) (2,solo) (3,solo)
        // （Koharia preservePairBoundaries 同语义：不配对不重排，各占一位）
        val d = dimsOf(800 to 1200, 800 to 1200, 2000 to 1000, 800 to 1200)
        val t = SpreadTable.build(4, startOffset = 1, smart = true, dims = d)
        assertEquals(4, t.slotCount)
        assertEquals(0, t.firstPage(0)); assertEquals(-1, t.secondPage(0))
        assertEquals(1, t.firstPage(1)); assertEquals(-1, t.secondPage(1))
        assertEquals(2, t.firstPage(2)); assertEquals(-1, t.secondPage(2))
        assertEquals(3, t.firstPage(3)); assertEquals(-1, t.secondPage(3))
    }

    // ---- 映射一致性 ----

    @Test
    fun pageToSpreadRoundtrip() {
        val d = dimsOf(800 to 1200, 2400 to 1200, 400 to 1600, 800 to 1200, 800 to 1200)
        val t = SpreadTable.build(5, smart = true, dims = d)
        for (p in 0 until 5) {
            val s = t.pageToSpread(p)
            assertTrue(p == t.firstPage(s) || p == t.secondPage(s))
        }
        // 越界页兜底 0
        assertEquals(0, t.pageToSpread(-1))
        assertEquals(0, t.pageToSpread(99))
    }

    @Test
    fun oddTailGoesSolo() {
        val d = dimsOf(800 to 1200, 800 to 1200, 800 to 1200)
        val t = SpreadTable.build(3, smart = true, dims = d)
        assertEquals(2, t.slotCount)
        assertEquals(2, t.firstPage(1)); assertEquals(-1, t.secondPage(1))
    }

    @Test
    fun emptyBookYieldsEmptyTable() {
        val t = SpreadTable.build(0, startOffset = 1, smart = true)
        assertEquals(0, t.slotCount)
    }

    @Test
    fun sameMappingDetectsChange() {
        val soloWide = SpreadTable.build(3, smart = true, dims = dimsOf(800 to 1200, 2400 to 1200, 800 to 1200))
        val allPair = SpreadTable.build(3, smart = false)
        assertFalse(soloWide.sameMapping(allPair))
        assertTrue(soloWide.sameMapping(SpreadTable.build(3, smart = true, dims = dimsOf(800 to 1200, 2400 to 1200, 800 to 1200))))
    }

    @Test
    fun containsPageGuardsBounds() {
        val t = SpreadTable.build(3)
        assertTrue(t.containsPage(2))
        assertFalse(t.containsPage(3))
        assertFalse(t.containsPage(-1))
    }

    @Test
    fun unitTableForSinglePageMode() {
        // 单页模式（double=false）：每页独占一位，映射恒等——getItemCount==页数的关键保证
        for (n in 1..9) {
            val t = SpreadTable.build(n, double = false, startOffset = 1, smart = true)
            assertEquals(n, t.slotCount)
            for (p in 0 until n) {
                assertEquals(p, t.pageToSpread(p))
                assertEquals(p, t.firstPage(p))
                assertEquals(-1, t.secondPage(p))
            }
        }
    }

    @Test
    fun startOffsetGeneralizesShiftTo10() {
        // ★ 2026-10-06 "从第几页开始双页"（2..10）：前 N-1 页 solo、第 N 页起两两合并。
        //   8 页 startOffset=3：页 0/1/2 solo、3+4、5+6、7 尾单
        val t = SpreadTable.build(8, startOffset = 3)
        assertEquals(6, t.slotCount)
        assertEquals(0, t.firstPage(0)); assertEquals(-1, t.secondPage(0))
        assertEquals(1, t.firstPage(1)); assertEquals(-1, t.secondPage(1))
        assertEquals(2, t.firstPage(2)); assertEquals(-1, t.secondPage(2))
        assertEquals(3, t.firstPage(3)); assertEquals(4, t.secondPage(3))
        assertEquals(5, t.firstPage(4)); assertEquals(6, t.secondPage(4))
        assertEquals(7, t.firstPage(5)); assertEquals(-1, t.secondPage(5))
        // 物理页 → 槽位往返一致（配对里的次页 firstPage≠p，命中任一半即归属正确）
        for (p in 0 until 8) {
            val slot = t.pageToSpread(p)
            assertTrue(t.firstPage(slot) == p || t.secondPage(slot) == p)
        }
    }

    @Test
    fun soloPagesForceStandaloneAndReflow() {
        // ★ 单本单页豁免：命中页强制 solo，其余页按顺序重新配对（无空洞）。
        //   6 页 soloPages={2}：0+1、2 solo、3+4、5 尾单
        val t = SpreadTable.build(6, soloPages = setOf(2))
        assertEquals(4, t.slotCount)
        assertEquals(0, t.firstPage(0)); assertEquals(1, t.secondPage(0))
        assertEquals(2, t.firstPage(1)); assertEquals(-1, t.secondPage(1))
        assertEquals(3, t.firstPage(2)); assertEquals(4, t.secondPage(2))
        assertEquals(5, t.firstPage(3)); assertEquals(-1, t.secondPage(3))
        // 多个豁免 + startOffset 叠加：8 页 startOffset=2（前 2 页 solo）、solo={4,5}：
        //   slot=0、1、2+3、4、5、6+7 —— 豁免页 4/5 抽走后 5/6 不跨豁免强行配对（顺序重排）
        val t2 = SpreadTable.build(8, startOffset = 2, soloPages = setOf(4, 5))
        assertEquals(6, t2.slotCount)
        assertEquals(0, t2.firstPage(0)); assertEquals(-1, t2.secondPage(0))
        assertEquals(1, t2.firstPage(1)); assertEquals(-1, t2.secondPage(1))
        assertEquals(2, t2.firstPage(2)); assertEquals(3, t2.secondPage(2))
        assertEquals(4, t2.firstPage(3)); assertEquals(-1, t2.secondPage(3))
        assertEquals(5, t2.firstPage(4)); assertEquals(-1, t2.secondPage(4))
        assertEquals(6, t2.firstPage(5)); assertEquals(7, t2.secondPage(5))
        // 单页模式豁免无意义：表仍为单位表
        val t3 = SpreadTable.build(4, double = false, soloPages = setOf(1, 2))
        assertEquals(4, t3.slotCount)
    }

    @Test
    fun freezeKeepsVisibleSlotsStable() {
        // ★ 冻结前缀（2026-10-06 乱闪根修）：渐进尺寸到达引发的重新配对，冻结边界（含）
        //   之前的位逐位照抄旧表，之后的位重新决策——用户正在看的位构图绝不中途改变。
        //   场景：6 页双页全配对 [0+1][2+3][4+5]；用户在第 2 页（slot 1=[2,3]）；
        //   新尺寸说 2 是横宽跨页该 solo（smart 下会拆），冻结边界=当前位末页 3：
        //   slot0/slot1 照抄，slot2 起重排。
        //   ★ v1.383：prev 必须是真 smart 表（带有效 dims，builtSmart=true）——朴素旧表的
        //   错误配对会被翻案修正（见 plainPrevFrozenErrorsGetCorrected），不再冻结固化
        val dims = dimsOf(
            800 to 1200, 800 to 1200,
            1600 to 800, 800 to 1200,
            800 to 1200, 800 to 1200,
        )
        val allPlainDims = dimsOf(
            800 to 1200, 800 to 1200,
            800 to 1200, 800 to 1200,
            800 to 1200, 800 to 1200,
        )
        val prev = SpreadTable.build(6, smart = true, dims = allPlainDims)
        assertEquals(3, prev.slotCount)
        assertTrue(prev.builtSmart)
        assertEquals(0, prev.firstPage(0)); assertEquals(1, prev.secondPage(0))
        assertEquals(2, prev.firstPage(1)); assertEquals(3, prev.secondPage(1))
        assertEquals(4, prev.firstPage(2)); assertEquals(5, prev.secondPage(2))
        // 无冻结：smart 会拆出 [2] solo → [0+1][2][3+4][5]（4 位）
        val fresh = SpreadTable.build(6, smart = true, dims = dims)
        assertEquals(4, fresh.slotCount)
        assertEquals(2, fresh.firstPage(1)); assertEquals(-1, fresh.secondPage(1))
        // 冻结到页 3（当前位 [2,3] 末页）：slot0/slot1 逐位照抄，slot2 起重排
        val frozen = SpreadTable.build(6, smart = true, dims = dims, prev = prev, freezeThroughPage = 3)
        assertEquals(prev.slotCount - 0, frozen.slotCount - 0)
        assertEquals(prev.firstPage(0), frozen.firstPage(0)); assertEquals(prev.secondPage(0), frozen.secondPage(0))
        assertEquals(prev.firstPage(1), frozen.firstPage(1)); assertEquals(prev.secondPage(1), frozen.secondPage(1))
        // 页 2/3 的归属不变（用户看到的构图稳定），pageToSpread 逐位一致
        assertEquals(prev.pageToSpread(2), frozen.pageToSpread(2))
        assertEquals(prev.pageToSpread(3), frozen.pageToSpread(3))
        // 冻结位之后的页重新决策：4+5 维持配对（形状相同可配）
        assertEquals(4, frozen.firstPage(2)); assertEquals(5, frozen.secondPage(2))
        // 冻结边界=0：仅 slot0 照抄，其余全新决策（页 2 拆出）
        val frozen0 = SpreadTable.build(6, smart = true, dims = dims, prev = prev, freezeThroughPage = 0)
        assertEquals(0, frozen0.firstPage(0)); assertEquals(1, frozen0.secondPage(0))
        assertEquals(2, frozen0.firstPage(1)); assertEquals(-1, frozen0.secondPage(1))
        assertEquals(3, frozen0.firstPage(2)); assertEquals(4, frozen0.secondPage(2))
        assertEquals(5, frozen0.firstPage(3)); assertEquals(-1, frozen0.secondPage(3))
        // 越界边界（≥总页数）：整表照抄
        val frozenAll = SpreadTable.build(6, smart = true, dims = dims, prev = prev, freezeThroughPage = 5)
        assertTrue(frozenAll.sameMapping(prev))
    }

    @Test
    fun plainPrevFrozenErrorsGetCorrected() {
        // ★ v1.383（用户工单"一张错全错"）：进书首帧 pageDims 异步空 → 朴素两两硬拼表
        //   （横宽页被错误并排）；全量尺寸到达的渐进回填 freeze 不得固化该错误。
        //   朴素旧表（builtSmart=false）的冻结位含横宽/长条页 → 翻案拆开重排修正。
        //   场景：6 页朴素表 [0+1][2+3][4+5]；用户在第 2 页；新 dims 说页 2 是横宽该 solo。
        val plainPrev = SpreadTable.build(6, smart = true) // 无 dims → builtSmart=false 朴素产物
        assertFalse(plainPrev.builtSmart)
        val dims = dimsOf(
            800 to 1200, 800 to 1200,
            1600 to 800, 800 to 1200,
            800 to 1200, 800 to 1200,
        )
        val corrected = SpreadTable.build(6, smart = true, dims = dims, prev = plainPrev, freezeThroughPage = 3)
        // 页 2 不再与 3 拼（错误配对被修正）：[0+1][2][3+4][5]
        assertEquals(2, corrected.firstPage(1)); assertEquals(-1, corrected.secondPage(1))
        assertEquals(-1, corrected.secondPage(corrected.pageToSpread(2)))
        // 冻结边界前的 slot0（正确配对）保持稳定
        assertEquals(0, corrected.firstPage(0)); assertEquals(1, corrected.secondPage(0))
        // 边界错位不跳页：修正位后顺序走位，每页都有归属
        assertEquals(0, corrected.pageToSpread(0)); assertEquals(0, corrected.pageToSpread(1))
        assertEquals(1, corrected.pageToSpread(2)); assertEquals(2, corrected.pageToSpread(3))
        assertEquals(2, corrected.pageToSpread(4)); assertEquals(3, corrected.pageToSpread(5))
    }

    @Test
    fun wideSplitDualSlotsFollowKomihoSemantics() {
        // ★ v1.384 Komiho 跟进：宽图二分在表层——横宽页（dims 已知 w>h×1.2）占两个连续
        //   half slot（LTR 左先/RTL 右先），dims 未知页 1 slot 整页（页表身份不漂移）。
        //   场景：3 页——页 0 横宽 2200x1700、页 1 竖页 800x1200、页 2 尺寸未知。
        val dims = dimsOf(
            2200 to 1700, 800 to 1200, 0 to 0,
        )
        // LTR：[0L][0R][1][2(整页,未知)] = 4 slot
        val ltr = SpreadTable.build(3, double = false, wideSplit = true, dims = dims, rtl = false)
        assertEquals(4, ltr.slotCount)
        assertEquals(0, ltr.firstPage(0)); assertEquals(-1, ltr.secondPage(0)); assertEquals(1, ltr.slotHalf(0))
        assertEquals(0, ltr.firstPage(1)); assertEquals(-1, ltr.secondPage(1)); assertEquals(2, ltr.slotHalf(1))
        assertEquals(1, ltr.firstPage(2)); assertEquals(0, ltr.slotHalf(2))
        assertEquals(2, ltr.firstPage(3)); assertEquals(0, ltr.slotHalf(3))
        // 页号→slot 恒指首个（先读的）半页位
        assertEquals(0, ltr.pageToSpread(0))
        // RTL：右半位在前 [0R][0L][1][2]
        val rtlt = SpreadTable.build(3, double = false, wideSplit = true, dims = dims, rtl = true)
        assertEquals(4, rtlt.slotCount)
        assertEquals(2, rtlt.slotHalf(0)); assertEquals(1, rtlt.slotHalf(1))
        // 全未知 dims：整表 1:1 单位（页表身份不漂移——rar 探测失败场景）
        val unknown = SpreadTable.build(3, double = false, wideSplit = true, dims = null, rtl = false)
        assertEquals(3, unknown.slotCount)
        assertEquals(0, unknown.slotHalf(0)); assertEquals(0, unknown.slotHalf(1)); assertEquals(0, unknown.slotHalf(2))
        // 双页+wideSplit：非横宽页仍两两配对（双页并排），横宽页双 slot
        val dual = SpreadTable.build(3, double = true, smart = false, wideSplit = true, dims = dims, rtl = false)
        // 页0 双slot、页1+2 配对（页2未知容忍配对）→ [0L][0R][1+2] = 3 slot
        assertEquals(3, dual.slotCount)
        assertEquals(1, dual.slotHalf(0)); assertEquals(2, dual.slotHalf(1))
        assertEquals(1, dual.firstPage(2)); assertEquals(2, dual.secondPage(2))
    }

    @Test
    fun wideSplitFreezeKeepsBothHalves() {
        // ★ v1.384 冻结×双 slot 组合：渐进重排照抄时，双 slot 的两个半页位 half 逐位保留。
        //   场景：3 页（0 横宽、1 竖、2 未知），先建全拆表，再以 freezeThroughPage=2 整表照抄
        val dims = dimsOf(
            2200 to 1700, 800 to 1200, 0 to 0,
        )
        val prev = SpreadTable.build(3, double = false, wideSplit = true, dims = dims, rtl = false)
        assertEquals(4, prev.slotCount)
        val frozen = SpreadTable.build(3, double = false, wideSplit = true, dims = dims,
            prev = prev, freezeThroughPage = 2, rtl = false)
        assertTrue(frozen.sameMapping(prev))
        // 渐进升级：旧表页 0 未拆（整页），新 dims 到达 → 翻案成双 slot，页 1/2 不动
        val plainPrev = SpreadTable.build(3, double = false, wideSplit = true, dims = null, rtl = false)
        assertEquals(3, plainPrev.slotCount)
        val upgraded = SpreadTable.build(3, double = false, wideSplit = true, dims = dims,
            prev = plainPrev, freezeThroughPage = 0, rtl = false)
        assertEquals(4, upgraded.slotCount)
        assertEquals(1, upgraded.slotHalf(0)); assertEquals(2, upgraded.slotHalf(1))
        assertEquals(0, upgraded.firstPage(0)); assertEquals(0, upgraded.firstPage(1))
        assertEquals(1, upgraded.firstPage(2)); assertEquals(0, upgraded.slotHalf(2))
        assertEquals(2, upgraded.firstPage(3)); assertEquals(0, upgraded.slotHalf(3))
        // 未知页 1/2 恒 1 slot，无重复无跳页（页 1→slot2、页 2→slot3）
        assertEquals(2, upgraded.pageToSpread(1)); assertEquals(3, upgraded.pageToSpread(2))
    }
}
