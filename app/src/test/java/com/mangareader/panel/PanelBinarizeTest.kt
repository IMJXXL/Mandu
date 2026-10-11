package com.mangareader.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 二值化单测：合成页面（白纸+黑框），纯 JVM */
class PanelBinarizeTest {

    private fun whitePage(w: Int, h: Int, bg: Int = 240) = IntArray(w * h) { bg }

    /** 闭区间填充矩形 */
    private fun fillRect(gray: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int, v: Int = 30) {
        for (y in y0..y1) for (x in x0..x1) gray[y * w + x] = v
    }

    @Test
    fun `otsu splits bimodal page`() {
        val w = 64
        val g = IntArray(w * w) { 240 }
        // 下半全黑
        for (y in w / 2 until w) for (x in 0 until w) g[y * w + x] = 30
        val thr = PanelBinarize.otsuThreshold(g)
        assertTrue("阈值应落在两峰之间, got $thr", thr in 30..240)
    }

    @Test
    fun `otsu on uniform page degrades to zero without crash`() {
        val g = IntArray(64 * 64) { 200 }
        val thr = PanelBinarize.otsuThreshold(g)
        assertTrue(thr in 0..255)
    }

    @Test
    fun `ink mask marks borders not panel interior`() {
        val w = 96
        val h = 64
        val g = whitePage(w, h)
        // 两个黑框格：外框 (4,4)-(45,59)、(50,4)-(91,59)，边宽3
        fillRect(g, w, 4, 4, 45, 59)
        fillRect(g, w, 7, 7, 42, 56, 240) // 内部挖白
        fillRect(g, w, 50, 4, 91, 59)
        fillRect(g, w, 53, 7, 88, 56, 240)
        val mask = PanelBinarize.buildInkMask(g, w, h)
        // 边框上是墨
        assertTrue(mask[4 * w + 20])
        assertTrue(mask[20 * w + 50])
        // 格内是白
        assertFalse(mask[20 * w + 20])
        assertFalse(mask[20 * w + 70])
    }

    @Test
    fun `uneven scan detects blocks with clean background`() {
        val w = 96
        val h = 64
        // 发黄页：底色带噪（150~180 循环），墨块 30。块 12×12 < 自适应窗 31，
        // 窗内必混底色（纯大块内部会均值=块色而漏检，真实页有纹理不踩，合成样张必须避开）
        val g = IntArray(w * h) { 150 + (it % 31) }
        fillRect(g, w, 10, 10, 21, 21, 30)
        fillRect(g, w, 55, 25, 66, 36, 30)
        val mask = PanelBinarize.buildInkMask(g, w, h)
        assertTrue("墨块应检出", mask[15 * w + 15])
        assertTrue(mask[30 * w + 60])
        assertFalse("底色不应判墨", mask[3 * w + 3])
    }

    @Test
    fun `dark page polarity flips so bright panels become ink`() {
        val w = 64
        val h = 64
        // 黑底亮面板页：底 20（占 76%），亮块 230。极性归一化后墨水=亮块（内容）
        val g = IntArray(w * h) { 20 }
        fillRect(g, w, 8, 8, 30, 30, 230)
        fillRect(g, w, 40, 20, 56, 50, 230)
        val mask = PanelBinarize.buildInkMask(g, w, h)
        assertTrue("亮面板应成为内容", mask[15 * w + 15])
        assertTrue(mask[35 * w + 48])
        assertFalse("黑底不应判内容", mask[2 * w + 2])
    }

    @Test
    fun `close bridges gap smaller than kernel`() {
        val w = 40
        val h = 40
        val mask = BooleanArray(w * h)
        // 两个 6x10 竖块，水平间隙 4（< k=9）
        for (y in 10 until 30) {
            for (x in 10 until 16) mask[y * w + x] = true
            for (x in 20 until 26) mask[y * w + x] = true
        }
        val closed = PanelBinarize.close(mask, w, h, 9)
        // 间隙中线被桥接
        assertTrue(closed[20 * w + 18])
        // 原块保留
        assertTrue(closed[20 * w + 12])
    }

    @Test
    fun `close keeps large shape intact`() {
        val w = 60
        val h = 60
        val mask = BooleanArray(w * h)
        for (y in 10 until 50) for (x in 10 until 50) mask[y * w + x] = true
        val closed = PanelBinarize.close(mask, w, h, 3)
        // 大实心块闭运算后中心不变、总墨量近似不变
        assertTrue(closed[30 * w + 30])
        val before = mask.count { it }
        val after = closed.count { it }
        assertEquals(before.toLong(), after.toLong())
    }

    @Test
    fun `close kernel size is dpi independent and bounded`() {
        assertEquals(3, PanelBinarize.closeKernelSize(600, 800))
        assertEquals(3, PanelBinarize.closeKernelSize(960, 720))  // 0.003*720=2.16 → 3
        assertTrue(PanelBinarize.closeKernelSize(960, 960) <= 5)
    }

    @Test
    fun `blank page yields empty mask`() {
        val g = IntArray(64 * 64) { 240 }
        val mask = PanelBinarize.buildInkMask(g, 64, 64)
        assertTrue(mask.none { it })
    }
}
