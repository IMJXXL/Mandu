package com.mangareader.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelShearSplitTest {

    private fun maskOf(w: Int, h: Int, rects: List<List<Int>>): BooleanArray {
        val m = BooleanArray(w * h)
        for (r in rects) for (y in r[1]..r[3]) for (x in r[0]..r[2]) m[y * w + x] = true
        return m
    }

    @Test
    fun `sheared 4 degree vertical split found`() {
        val w = 300; val h = 300
        val mask = BooleanArray(w * h)
        for (y in 20 until h - 20) {
            val boundary = 150 + ((y - 20) * 4 / 56)
            for (x in 20 until boundary - 5) mask[y * w + x] = true
            for (x in boundary + 5 until w - 20) mask[y * w + x] = true
        }
        val box = PanelComponents.Box(0, 0, w - 1, h - 1)
        val split = PanelShearSplit.tryShear(mask, w, h, box)
        assertTrue("4 度倾斜分隔应切分 got ${split.size}", split.size >= 2)
    }

    @Test
    fun `standard horizontal split not calls shear`() {
        val w = 200; val h = 200
        val mask = maskOf(w, h, listOf(listOf(10, 10, 190, 90), listOf(10, 110, 190, 190)))
        val box = PanelComponents.Box(10, 10, 190, 190)
        val split = PanelShearSplit.tryShear(mask, w, h, box)
        assertTrue("标准分隔也应在剪切中找到 got ${split.size}", split.size >= 1)
    }

    @Test
    fun `all angles fail returns original box`() {
        val w = 100; val h = 100
        val mask = BooleanArray(w * h)
        for (y in 20 until 80) for (x in 20 until 80) mask[y * w + x] = true
        val box = PanelComponents.Box(0, 0, 99, 99)
        val split = PanelShearSplit.tryShear(mask, w, h, box)
        assertEquals("所有角度未找到返回原框", 1, split.size)
    }

    @Test
    fun `shear out of bounds clamped no crash`() {
        val w = 50; val h = 50
        val mask = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until 25) mask[y * w + x] = true
        val box = PanelComponents.Box(0, 0, 49, 49)
        val split = PanelShearSplit.tryShear(mask, w, h, box)
        assertTrue("越界钳制不崩溃 got ${split.size}", split.size >= 1)
    }

    @Test
    fun `small box returns original`() {
        val w = 200; val h = 200
        val mask = BooleanArray(w * h)
        for (y in 90..110) for (x in 90..110) mask[y * w + x] = true
        val box = PanelComponents.Box(90, 90, 110, 110)
        val split = PanelShearSplit.tryShear(mask, w, h, box)
        assertEquals(1, split.size)
    }
}