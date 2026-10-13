package com.mangareader.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 阅读顺序 + 置信度单测 */
class PanelOrderConfidenceTest {

    private fun box(x0: Int, y0: Int, x1: Int, y1: Int) = PanelComponents.Box(x0, y0, x1, y1)

    // ———— 顺序 ————

    @Test
    fun `ltr grid reads rows top-down left-to-right`() {
        val a = box(0, 0, 99, 99)
        val b = box(110, 0, 209, 99)
        val c = box(0, 110, 99, 209)
        val d = box(110, 110, 209, 209)
        val out = PanelOrder.sortReadingOrder(listOf(d, c, b, a), rtl = false)
        assertEquals(listOf(a, b, c, d), out)
    }

    @Test
    fun `rtl grid reads rows top-down right-to-left`() {
        val a = box(0, 0, 99, 99)
        val b = box(110, 0, 209, 99)
        val c = box(0, 110, 99, 209)
        val d = box(110, 110, 209, 209)
        val out = PanelOrder.sortReadingOrder(listOf(a, b, c, d), rtl = true)
        assertEquals(listOf(b, a, d, c), out)
    }

    @Test
    fun `staggered row with slight y offset still same row`() {
        // B 比 A 低 20（重叠 80/100 ≥ 50%）→ 同行，A 在前（LTR）
        val a = box(0, 0, 99, 99)
        val b = box(110, 20, 209, 119)
        val out = PanelOrder.sortReadingOrder(listOf(b, a), rtl = false)
        assertEquals(listOf(a, b), out)
    }

    @Test
    fun `wide bottom panel comes after two top panels`() {
        val a = box(0, 0, 99, 99)
        val b = box(110, 0, 209, 99)
        val wide = box(0, 120, 209, 219)
        val out = PanelOrder.sortReadingOrder(listOf(wide, b, a), rtl = false)
        assertEquals(listOf(a, b, wide), out)
    }

    @Test
    fun `fixup moves left panel before right panel across rows noise`() {
        // 三行乱序输入 + 中间行两格，验证修正回路不破坏正确序
        val a = box(0, 0, 99, 99)
        val b = box(110, 0, 209, 99)
        val c = box(0, 110, 99, 209)
        val d = box(110, 110, 209, 209)
        val out = PanelOrder.sortReadingOrder(listOf(c, d, a, b), rtl = false)
        assertEquals(listOf(a, b, c, d), out)
    }

    // ———— 置信度 ————

    @Test
    fun `clean grid scores high no fallback`() {
        val boxes = listOf(
            box(0, 0, 149, 229), box(168, 0, 319, 229),
            box(0, 248, 149, 479), box(168, 248, 319, 479),
        )
        val r = PanelConfidence.evaluate(boxes, 320, 480, rtl = false)
        assertFalse("规整 2×2 不应降级 conf=${r.pageConf}", r.fallback)
        assertTrue("置信度应高 conf=${r.pageConf}", r.pageConf >= 0.7f)
        assertEquals(4, r.boxes.size)
        assertEquals(0, r.boxes[0].order)
        assertEquals(3, r.boxes[3].order)
        // 归一化检查
        assertTrue(r.boxes[0].x == 0f && r.boxes[0].y == 0f)
        assertTrue(r.boxes[3].right == 1f && r.boxes[3].bottom == 1f)
    }

    @Test
    fun `blank page falls back to single full-page box`() {
        val r = PanelConfidence.evaluate(emptyList(), 320, 480, rtl = false)
        assertTrue(r.fallback)
        assertEquals(1, r.boxes.size)
        assertEquals(0f, r.boxes[0].x, 1e-6f)
        assertEquals(1f, r.boxes[0].right, 1e-6f)
    }

    @Test
    fun `sparse fragments fall back`() {
        // 覆盖率 ~10% 的一堆小框 → 置信度不足 → 降级整页
        val boxes = listOf(
            box(0, 0, 39, 39), box(60, 0, 99, 39),
            box(120, 0, 159, 39), box(180, 0, 219, 39),
        )
        val r = PanelConfidence.evaluate(boxes, 320, 480, rtl = false)
        assertTrue("低覆盖应降级 conf=${r.pageConf}", r.fallback)
        assertEquals(1, r.boxes.size)
    }

    @Test
    fun `full page single panel is not fallback`() {
        val splash = box(0, 0, 319, 479)
        val r = PanelConfidence.evaluate(listOf(splash), 320, 480, rtl = false)
        assertFalse("满版单格是正常结果", r.fallback)
        assertEquals(1, r.boxes.size)
    }

    @Test
    fun `sensitivity threshold moves fallback boundary`() {
        // 低覆盖+无中缝的一组框（conf≈0.49）：宽松档(0.35)通过、严格档(0.65)降级
        val boxes = listOf(
            box(0, 0, 319, 99),      // 320×100
            box(0, 100, 319, 199),   // 320×100，与上框垂直相邻
        )
        val lenient = PanelConfidence.evaluate(boxes, 320, 480, rtl = false, confFail = 0.35f)
        val strict = PanelConfidence.evaluate(boxes, 320, 480, rtl = false, confFail = 0.65f)
        assertFalse("宽松档不应降级 conf=${lenient.pageConf}", lenient.fallback)
        assertTrue("严格档应降级 conf=${strict.pageConf}", strict.fallback)
        assertEquals("同一组框置信度本身不应随门槛变化", lenient.pageConf, strict.pageConf, 1e-6f)
    }
}
