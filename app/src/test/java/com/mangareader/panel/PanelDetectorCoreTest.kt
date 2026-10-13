package com.mangareader.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 管线端到端单测：合成页面（白纸黑框）灰度直进核心管线 */
class PanelDetectorCoreTest {

    private fun grayPage(w: Int, h: Int, bg: Int = 240) = IntArray(w * h) { bg }

    private fun fillRect(g: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int, v: Int = 30) {
        for (y in y0..y1) for (x in x0..x1) g[y * w + x] = v
    }

    /** 画格框（外沿实心，内部挖白） */
    private fun frame(g: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int, t: Int = 4) {
        fillRect(g, w, x0, y0, x1, y0 + t - 1)
        fillRect(g, w, x0, y1 - t + 1, x1, y1)
        fillRect(g, w, x0, y0, x0 + t - 1, y1)
        fillRect(g, w, x1 - t + 1, y0, x1, y1)
    }

    @Test
    fun `two by two bordered grid detects four panels in reading order`() {
        val w = 320; val h = 480
        val g = grayPage(w, h)
        frame(g, w, 0, 0, 150, 230)      // 左上
        frame(g, w, 168, 0, 319, 230)    // 右上
        frame(g, w, 0, 248, 150, 479)    // 左下
        frame(g, w, 168, 248, 319, 479)  // 右下

        val ltr = PanelDetectorCore.analyze(g, w, h, rtl = false)
        assertFalse("2×2 网格不应降级 conf=${ltr.pageConf}", ltr.fallback)
        assertEquals(4, ltr.boxes.size)
        // LTR：左上→右上→左下→右下
        val ltrKeys = ltr.boxes.map { (it.x * 10).toInt() to (it.y * 10).toInt() }
        assertTrue(ltrKeys[0].first < ltrKeys[1].first && ltrKeys[0].second < ltrKeys[2].second)
        assertTrue(ltrKeys[1].second < ltrKeys[3].second)

        val rtl = PanelDetectorCore.analyze(g, w, h, rtl = true)
        assertEquals(4, rtl.boxes.size)
        assertTrue("RTL 首格应是右上", rtl.boxes[0].x > 0.5f)
        assertTrue("RTL 末格应是左下", rtl.boxes[3].x < 0.5f && rtl.boxes[3].y > 0.5f)
    }

    @Test
    fun `three row irregular grid keeps order and no fallback`() {
        val w = 320; val h = 480
        val g = grayPage(w, h)
        frame(g, w, 0, 0, 319, 150)      // 通栏
        frame(g, w, 0, 168, 150, 319)    // 左中
        frame(g, w, 168, 168, 319, 319)  // 右中
        frame(g, w, 0, 337, 319, 479)    // 通栏
        val r = PanelDetectorCore.analyze(g, w, h, rtl = false)
        assertFalse("通栏-双格-通栏不应降级 conf=${r.pageConf} boxes=${r.boxes}", r.fallback)
        assertEquals(4, r.boxes.size)
        assertTrue("首格通栏应在顶部 y=${r.boxes[0].y}", r.boxes[0].y < 0.1f)
        assertTrue("末格通栏应在底部 boxes=${r.boxes}", r.boxes[3].y > 0.68f)
        val row2 = r.boxes.slice(1..2)
        assertTrue("中行两格 y 应相同", (row2[0].y - row2[1].y).let { kotlin.math.abs(it) < 0.05f })
    }

    @Test
    fun `splash full bleed page is single panel not fallback`() {
        val w = 320; val h = 480
        val g = grayPage(w, h)
        fillRect(g, w, 2, 2, 317, 477, 60)  // 满版深色画
        val r = PanelDetectorCore.analyze(g, w, h, rtl = false)
        assertEquals(1, r.boxes.size)
        assertFalse("满版不应视为失败", r.fallback)
        assertTrue(r.boxes[0].w > 0.85f && r.boxes[0].h > 0.85f)
    }

    @Test
    fun `blank page falls back to whole page`() {
        val w = 320; val h = 480
        val g = grayPage(w, h)
        val r = PanelDetectorCore.analyze(g, w, h, rtl = false)
        assertTrue(r.fallback)
        assertEquals(1, r.boxes.size)
    }

    @Test
    fun `degenerate dimensions fall back safely`() {
        val r = PanelDetectorCore.analyze(IntArray(0), 0, 0, rtl = false)
        assertTrue(r.fallback)
    }

    @Test
    fun `performance sanity - 960x720 grid under budget`() {
        val w = 960; val h = 720
        val g = grayPage(w, h)
        // 3×3 网格
        val xs = listOf(0 to 300, 320 to 630, 650 to 959)
        val ys = listOf(0 to 225, 245 to 470, 490 to 719)
        for ((x0, x1) in xs) for ((y0, y1) in ys) frame(g, w, x0, y0, x1, y1)
        val t0 = System.nanoTime()
        val r = PanelDetectorCore.analyze(g, w, h, rtl = false)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(9, r.boxes.size)
        assertTrue("检测应在预算内(${ms}ms)", ms < 2000)  // CI 机器放宽；真机预算 50ms
    }

    @Test
    fun `rgb null preserves original behaviour`() {
        val w = 320; val h = 480
        val g = grayPage(w, h)
        frame(g, w, 0, 0, 150, 230)
        frame(g, w, 168, 0, 319, 230)
        frame(g, w, 0, 248, 150, 479)
        frame(g, w, 168, 248, 319, 479)
        val withRgb = PanelDetectorCore.analyze(g, w, h, rtl = false, rgb = null)
        val without = PanelDetectorCore.analyze(g, w, h, rtl = false)
        assertEquals(without.boxes.size, withRgb.boxes.size)
        assertEquals(without.fallback, withRgb.fallback)
    }

    @Test
    fun `pink background page with rgb does not lose panels`() {
        val w = 320; val h = 480
        val pinkBg = 240
        val g = IntArray(w * h) { pinkBg }
        val rgb = IntArray(w * h) { (250 shl 16) or (200 shl 8) or 200 }
        frame(g, w, 0, 0, 150, 230)
        frame(g, w, 168, 0, 319, 230)
        frame(g, w, 0, 248, 150, 479)
        frame(g, w, 168, 248, 319, 479)
        for (i in g.indices) {
            if (g[i] == 30) rgb[i] = (30 shl 16) or (30 shl 8) or 30
        }
        val r = PanelDetectorCore.analyze(g, w, h, rtl = false, rgb = rgb)
        assertTrue("粉底彩漫页传入 rgb 后分格不丢失 boxes=${r.boxes.size}", r.boxes.size >= 1)
    }
}
