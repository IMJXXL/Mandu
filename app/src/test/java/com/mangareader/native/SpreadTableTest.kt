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
            val t = SpreadTable.build(n, shift = true)
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
        val t = SpreadTable.build(1, shift = true, smart = true)
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
        val t = SpreadTable.build(4, shift = true, smart = true, dims = d)
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
        val t = SpreadTable.build(0, shift = true, smart = true)
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
            val t = SpreadTable.build(n, double = false, shift = true, smart = true)
            assertEquals(n, t.slotCount)
            for (p in 0 until n) {
                assertEquals(p, t.pageToSpread(p))
                assertEquals(p, t.firstPage(p))
                assertEquals(-1, t.secondPage(p))
            }
        }
    }
}
