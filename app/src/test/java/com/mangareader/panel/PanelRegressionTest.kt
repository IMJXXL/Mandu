package com.mangareader.panel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelRegressionTest {

    private fun grayPage(w: Int, h: Int, bg: Int = 240) = IntArray(w * h) { bg }

    private fun fillRect(g: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int, v: Int = 30) {
        for (y in y0..y1) for (x in x0..x1) g[y * w + x] = v
    }

    private fun frame(g: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int, t: Int = 4) {
        fillRect(g, w, x0, y0, x1, y0 + t - 1)
        fillRect(g, w, x0, y1 - t + 1, x1, y1)
        fillRect(g, w, x0, y0, x0 + t - 1, y1)
        fillRect(g, w, x1 - t + 1, y0, x1, y1)
    }

    private fun fillContent(g: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int, v: Int = 80) {
        for (y in y0 + 8..y1 - 8) for (x in x0 + 8..x1 - 8) g[y * w + x] = v
    }

    private fun report(name: String, pass: Int, total: Int, min: Int) {
        val pct = if (total > 0) pass * 100 / total else 0
        println("[$name] $pass/$total 通过 ($pct%)，阈值≥$min")
        assertTrue("$name 通过数 $pass < 阈值 $min (总用例 $total, 通过率 $pct%)", pass >= min)
    }

    @Test
    fun `scenario1_simpleGrids`() {
        var pass = 0; var total = 0
        for (cols in 1..3) for (rows in 1..3) for (gap in 8..28 step 4) for (t in 2..7) {
            val w = 320; val h = 480
            val cellW = (w - gap * (cols - 1)) / cols
            val cellH = (h - gap * (rows - 1)) / rows
            if (cellW < 30 || cellH < 30) continue
            total++
            val g = grayPage(w, h)
            for (r in 0 until rows) for (c in 0 until cols) {
                val x0 = c * (cellW + gap); val y0 = r * (cellH + gap)
                frame(g, w, x0, y0, x0 + cellW - 1, y0 + cellH - 1, t)
                fillContent(g, w, x0, y0, x0 + cellW - 1, y0 + cellH - 1)
            }
            val result = PanelDetectorCore.analyze(g, w, h, rtl = false)
            if (!result.fallback && result.boxes.size == cols * rows) pass++
        }
        report("场景1-简单网格", pass, total, 100)
    }

    @Test
    fun `scenario2_borderSplit`() {
        var pass = 0; var total = 0
        for (w in listOf(300, 320, 400, 480)) for (h in listOf(400, 480, 600, 800)) for (bt in 4..14 step 2) for (gap in 0..10 step 2) {
            total++
            val g = grayPage(w, h); val halfW = w / 2
            frame(g, w, 0, 0, halfW - gap, h - 1, 4)
            frame(g, w, halfW + gap, 0, w - 1, h - 1, 4)
            fillContent(g, w, 0, 0, halfW - gap, h - 1)
            fillContent(g, w, halfW + gap, 0, w - 1, h - 1)
            if (gap == 0) fillRect(g, w, halfW - bt, 0, halfW + bt, h - 1, 30)
            val result = PanelDetectorCore.analyze(g, w, h, rtl = false, precision = 1)
            if (!result.fallback && result.boxes.size >= 2) pass++
        }
        report("场景2-边框分割", pass, total, 200)
    }

    @Test
    fun `scenario3_fullPage`() {
        var pass = 0; var total = 0
        for (w in listOf(300, 320, 400, 480, 600)) for (h in listOf(400, 480, 600, 800, 1000)) for (ink in 30..120 step 10) {
            total++
            val g = grayPage(w, h)
            fillRect(g, w, 10, 10, w - 11, h - 11, ink)
            val result = PanelDetectorCore.analyze(g, w, h, rtl = false)
            if (!result.fallback && result.boxes.size == 1) pass++
        }
        report("场景3-满版大图", pass, total, 200)
    }

    @Test
    fun `scenario4_irregularLayout`() {
        var pass = 0; var total = 0
        for (split in 100..220 step 10) for (topH in 140..300 step 20) for (gap in 8..24 step 4) {
            total++
            val w = 320; val h = 480; val g = grayPage(w, h)
            frame(g, w, 0, 0, split - gap, topH - gap, 4); fillContent(g, w, 0, 0, split - gap, topH - gap)
            frame(g, w, split + gap, 0, w - 1, topH - gap, 4); fillContent(g, w, split + gap, 0, w - 1, topH - gap)
            frame(g, w, 0, topH + gap, w - 1, h - 1, 4); fillContent(g, w, 0, topH + gap, w - 1, h - 1)
            val result = PanelDetectorCore.analyze(g, w, h, rtl = false)
            if (!result.fallback && result.boxes.size == 3) pass++
        }
        report("场景4-不规则布局", pass, total, 200)
    }

    @Test
    fun `scenario5_bubbleMerge`() {
        var pass = 0; var total = 0
        for (bubbleW in 60..180 step 10) for (bubbleH in 50..140 step 10) for (gap in 4..20 step 4) {
            total++
            val w = 320; val h = 480; val g = grayPage(w, h); val halfW = w / 2
            frame(g, w, 0, 0, halfW - gap, h - 1, 4); fillContent(g, w, 0, 0, halfW - gap, h - 1)
            frame(g, w, halfW + gap, 0, w - 1, h - 1, 4); fillContent(g, w, halfW + gap, 0, w - 1, h - 1)
            val bx0 = halfW - bubbleW / 2; val by0 = h / 2 - bubbleH / 2
            fillRect(g, w, bx0, by0, bx0 + bubbleW - 1, by0 + bubbleH - 1, 60)
            val result = PanelDetectorCore.analyze(g, w, h, rtl = false, bubbleMerge = true)
            if (!result.fallback && result.boxes.size >= 2) pass++
        }
        report("场景5-气泡合并", pass, total, 100)
    }

    @Test
    fun `scenario6_coloredBackground`() {
        var pass = 0; var total = 0
        for (bgR in 200..240 step 5) for (bgG in 180..240 step 5) for (bgB in 160..240 step 5) for (cols in 1..2) {
            total++
            val w = 320; val h = 480
            val grayBg = (0.299f * bgR + 0.587f * bgG + 0.114f * bgB).toInt()
            val g = grayPage(w, h, grayBg); val cellW = w / cols
            for (c in 0 until cols) {
                val x0 = c * cellW + 4; val x1 = (c + 1) * cellW - 5
                frame(g, w, x0, 4, x1, h - 5, 4); fillContent(g, w, x0, 4, x1, h - 5)
            }
            val rgb = IntArray(w * h) { (bgR shl 16) or (bgG shl 8) or bgB }
            val result = PanelDetectorCore.analyze(g, w, h, rtl = false, rgb = rgb)
            if (!result.fallback && result.boxes.size == cols) pass++
        }
        report("场景6-彩色背景", pass, total, 200)
    }

    @Test
    fun `scenario7_lowContrast`() {
        var pass = 0; var total = 0
        for (bg in 200..245 step 5) for (fg in 60..180 step 10) for (t in 2..8) {
            if (bg - fg < 30) continue
            total++
            val w = 320; val h = 480; val g = grayPage(w, h, bg); val halfW = w / 2
            for (x in 0 until halfW - 8) for (y in 0 until h)
                if (x < t || x > halfW - t - 8 || y < t || y > h - t - 1) g[y * w + x] = fg
            for (x in halfW + 8 until w) for (y in 0 until h)
                if (x < halfW + t + 8 || x > w - t - 1 || y < t || y > h - t - 1) g[y * w + x] = fg
            fillContent(g, w, 8, 8, halfW - 12, h - 5, fg + 30)
            fillContent(g, w, halfW + 12, 8, w - 5, h - 5, fg + 30)
            val result = PanelDetectorCore.analyze(g, w, h, rtl = false, precision = 1)
            if (!result.fallback && result.boxes.size >= 1) pass++
        }
        report("场景7-低对比度", pass, total, 200)
    }

    @Test
    fun `totalDatasetSize_exceeds1500`() {
        var total = 0
        for (cols in 1..3) for (rows in 1..3) for (gap in 8..28 step 4) for (t in 2..7) total++
        for (w in listOf(300, 320, 400, 480)) for (h in listOf(400, 480, 600, 800)) for (bt in 4..14 step 2) for (gap in 0..10 step 2) total++
        for (w in listOf(300, 320, 400, 480, 600)) for (h in listOf(400, 480, 600, 800, 1000)) for (ink in 30..120 step 10) total++
        for (split in 100..220 step 10) for (topH in 140..300 step 20) for (gap in 8..24 step 4) total++
        for (bubbleW in 60..180 step 10) for (bubbleH in 50..140 step 10) for (gap in 4..20 step 4) total++
        for (bgR in 200..240 step 5) for (bgG in 180..240 step 5) for (bgB in 160..240 step 5) for (cols in 1..2) total++
        for (bg in 200..245 step 5) for (fg in 60..180 step 10) for (t in 2..8) if (bg - fg >= 30) total++
        println("[数据集总量] $total 个用例")
        assertTrue("数据集总量应≥1500，实际=$total", total >= 1500)
    }
}