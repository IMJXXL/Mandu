package com.mangareader.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 连通域与框级处理单测：合成掩码 */
class PanelComponentsTest {

    private fun maskOf(w: Int, h: Int, rects: List<List<Int>>): BooleanArray {
        val m = BooleanArray(w * h)
        for (r in rects) for (y in r[1]..r[3]) for (x in r[0]..r[2]) m[y * w + x] = true
        return m
    }

    private fun cc(mask: BooleanArray, w: Int, h: Int) = PanelComponents.connectedComponents(mask, w, h)

    @Test
    fun `two separate rects yield two components`() {
        val w = 100; val h = 80
        val comps = cc(maskOf(w, h, listOf(listOf(10, 10, 40, 40), listOf(55, 20, 90, 60))), w, h)
        assertEquals(2, comps.size)
    }

    @Test
    fun `diagonal touch is 8-connected single component`() {
        val w = 20; val h = 20
        val comps = cc(maskOf(w, h, listOf(listOf(2, 2, 5, 5), listOf(6, 6, 9, 9))), w, h)
        assertEquals(1, comps.size)
    }

    @Test
    fun `grid page yields one candidate per cell`() {
        val w = 320; val h = 480
        // 2×2 网格：边框 4px，格间缝 16px
        val rects = ArrayList<List<Int>>()
        val xs = listOf(0 to 150, 168 to 319)
        val ys = listOf(0 to 230, 248 to 479)
        for ((x0, x1) in xs) for ((y0, y1) in ys) rects.add(listOf(x0, y0, x1, y1))
        val boxes = PanelComponents.toPanelRects(maskOf(w, h, rects), w, h)
        assertEquals("2×2 网格应得 4 框", 4, boxes.size)
        // 每框面积近似格子面积（±20%）
        for (b in boxes) {
            assertTrue(b.area in (140 * 220 * 0.8).toInt()..(160 * 240 * 1.2).toInt())
        }
    }

    @Test
    fun `scattered fragments of one borderless panel group into one box`() {
        val w = 300; val h = 400
        // 无边框格：内容碎块集中在右上区域，相互间隔 < 外扩(1.2%·400≈4px)... 用小间距 3px
        val rects = ArrayList<List<Int>>()
        var y = 30
        while (y < 160) {
            rects.add(listOf(180, y, 260, y + 8))
            y += 12
        }
        val boxes = PanelComponents.toPanelRects(maskOf(w, h, rects), w, h)
        assertTrue("碎片应归组为少量框: ${boxes.size}", boxes.size <= 2)
        val united = boxes.reduce { a, b -> a.union(b) }
        assertTrue(
            "组框应覆盖碎片域 boxes=$boxes united=$united",
            united.x0 <= 180 && united.x1 >= 260 && united.y0 <= 30 && united.y1 >= 158,
        )
    }

    @Test
    fun `splash page single component becomes one full box`() {
        val w = 300; val h = 400
        val boxes = PanelComponents.toPanelRects(maskOf(w, h, listOf(listOf(5, 5, 294, 394))), w, h)
        assertEquals(1, boxes.size)
        assertTrue(boxes[0].area > w * h * 0.85)
    }

    @Test
    fun `drop contained small box`() {
        val big = PanelComponents.Box(0, 0, 99, 199)
        val small = PanelComponents.Box(40, 60, 59, 119)
        val out = PanelComponents.dropContained(listOf(big, small))
        assertEquals(listOf(big), out)
    }

    @Test
    fun `nms suppresses heavy overlap keeps distinct`() {
        val a = PanelComponents.Box(0, 0, 99, 99)
        val b = PanelComponents.Box(10, 10, 109, 109)  // IoU≈0.68
        val c = PanelComponents.Box(150, 0, 249, 99)
        val out = PanelComponents.nms(listOf(a, b, c))
        assertEquals(2, out.size)
        assertTrue(out.contains(a) && out.contains(c))
    }

    @Test
    fun `deoverlap separates thin horizontal overlap`() {
        val a = PanelComponents.Box(0, 0, 59, 99)
        val b = PanelComponents.Box(50, 20, 109, 80)  // x 重叠 10px, y 含于 a
        val out = PanelComponents.deoverlap(listOf(a, b))
        assertEquals(2, out.size)
        val l = out.first { it.x0 <= 0 }
        val r = out.first { it.x1 >= 109 }
        assertTrue("去重叠后不再相交", l.interArea(r) == 0)
    }

    @Test
    fun `snap expands edges toward close neighbor only`() {
        val a = PanelComponents.Box(0, 0, 99, 199)
        val b = PanelComponents.Box(112, 0, 219, 199)  // 缝 12px（<6%·320=19）
        val out = PanelComponents.snapGutters(listOf(a, b), 320, 200, 320f)
        val l = out.first { it.x0 == 0 }
        val r = out.first { it.x1 == 219 }
        assertTrue("左框右缘应向缝外扩", l.x1 > 99)
        assertTrue("右框左缘应向缝外扩", r.x0 < 112)
        assertTrue("扩后不得相交", l.interArea(r) == 0)
    }
}
