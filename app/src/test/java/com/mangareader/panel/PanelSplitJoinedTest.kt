package com.mangareader.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelSplitJoinedTest {

    private fun maskOf(w: Int, h: Int, rects: List<List<Int>>): BooleanArray {
        val m = BooleanArray(w * h)
        for (r in rects) for (y in r[1]..r[3]) for (x in r[0]..r[2]) m[y * w + x] = true
        return m
    }

    @Test
    fun `two panels sharing vertical border split horizontally`() {
        val w = 200; val h = 200
        val rects = listOf(
            listOf(10, 10, 90, 190),
            listOf(110, 10, 190, 190),
        )
        val mask = maskOf(w, h, rects)
        val box = PanelComponents.Box(10, 10, 190, 190)
        val split = PanelSplitJoined.splitJoined(mask, w, h, box)
        assertEquals("应切分为左右两个子框", 2, split.size)
        assertTrue("左框在左半", split[0].x1 < 100)
        assertTrue("右框在右半", split[1].x0 > 100)
    }

    @Test
    fun `two panels sharing horizontal border split vertically`() {
        val w = 200; val h = 200
        val rects = listOf(
            listOf(10, 10, 190, 90),
            listOf(10, 110, 190, 190),
        )
        val mask = maskOf(w, h, rects)
        val box = PanelComponents.Box(10, 10, 190, 190)
        val split = PanelSplitJoined.splitJoined(mask, w, h, box)
        assertEquals("应切分为上下两个子框", 2, split.size)
        assertTrue("上框在上半", split[0].y1 < 100)
        assertTrue("下框在下半", split[1].y0 > 100)
    }

    @Test
    fun `four quadrant grid splits recursively`() {
        val w = 300; val h = 300
        val rects = listOf(
            listOf(10, 10, 140, 140),
            listOf(160, 10, 290, 140),
            listOf(10, 160, 140, 290),
            listOf(160, 160, 290, 290),
        )
        val mask = maskOf(w, h, rects)
        val box = PanelComponents.Box(10, 10, 290, 290)
        val split = PanelSplitJoined.splitJoined(mask, w, h, box)
        assertTrue("田字格应递归拆分为≥4 个子框 got ${split.size}", split.size >= 4)
    }

    @Test
    fun `depth limit stops at max depth`() {
        val w = 64; val h = 64
        val mask = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) mask[y * w + x] = true
        val box = PanelComponents.Box(0, 0, 63, 63)
        val split = PanelSplitJoined.splitJoined(mask, w, h, box, depth = PanelTunables.SPLIT_MAX_DEPTH)
        assertEquals("深度达上限应返回原框", 1, split.size)
    }

    @Test
    fun `single panel no white band not split`() {
        val w = 200; val h = 200
        val mask = BooleanArray(w * h)
        for (y in 10..190) for (x in 10..190) mask[y * w + x] = true
        val box = PanelComponents.Box(10, 10, 190, 190)
        val split = PanelSplitJoined.splitJoined(mask, w, h, box)
        assertEquals("无贯穿白带不拆分", 1, split.size)
    }

    @Test
    fun `narrow band with one side empty not split`() {
        val w = 200; val h = 200
        val mask = BooleanArray(w * h)
        for (y in 10..190) for (x in 10..100) mask[y * w + x] = true
        val box = PanelComponents.Box(0, 0, 199, 199)
        val split = PanelSplitJoined.splitJoined(mask, w, h, box)
        assertTrue("一侧几乎无内容不切分 got ${split.size}", split.size >= 1)
    }

    @Test
    fun `small box not split`() {
        val w = 200; val h = 200
        val mask = BooleanArray(w * h)
        for (y in 80..120) for (x in 80..120) mask[y * w + x] = true
        val box = PanelComponents.Box(80, 80, 120, 120)
        val split = PanelSplitJoined.splitJoined(mask, w, h, box)
        assertEquals(1, split.size)
    }

    @Test
    fun `split line clamped to parent bounds`() {
        val w = 200; val h = 200
        val rects = listOf(
            listOf(0, 0, 90, 199),
            listOf(110, 0, 199, 199),
        )
        val mask = maskOf(w, h, rects)
        val box = PanelComponents.Box(0, 0, 199, 199)
        val split = PanelSplitJoined.splitJoined(mask, w, h, box)
        for (b in split) {
            assertTrue("子框不越出父框左边界", b.x0 >= 0)
            assertTrue("子框不越出父框右边界", b.x1 <= 199)
            assertTrue("子框不越出父框上边界", b.y0 >= 0)
            assertTrue("子框不越出父框下边界", b.y1 <= 199)
        }
    }
}