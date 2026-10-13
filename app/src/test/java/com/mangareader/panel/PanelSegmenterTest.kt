package com.mangareader.panel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelSegmenterTest {

    @Test
    fun `accept empty list returns false`() {
        assertFalse(PanelSegmenter.accept(emptyList(), 100, 100))
    }

    @Test
    fun `accept single box covering most of page returns true`() {
        val w = 100; val h = 100
        val box = PanelComponents.Box(5, 5, 94, 94)
        assertTrue(PanelSegmenter.accept(listOf(box), w, h))
    }

    @Test
    fun `accept single box too small returns false`() {
        val w = 100; val h = 100
        val box = PanelComponents.Box(10, 10, 40, 40)
        assertFalse(PanelSegmenter.accept(listOf(box), w, h))
    }

    @Test
    fun `accept two boxes with good span returns true`() {
        val w = 100; val h = 100
        val top = PanelComponents.Box(5, 5, 94, 47)
        val bottom = PanelComponents.Box(5, 52, 94, 94)
        assertTrue(PanelSegmenter.accept(listOf(top, bottom), w, h))
    }

    @Test
    fun `accept too many candidates returns false`() {
        val w = 100; val h = 100
        val boxes = (0..40).map { PanelComponents.Box(it, 0, it + 2, 10) }
        assertFalse(PanelSegmenter.accept(boxes, w, h))
    }

    @Test
    fun `accept furniture layout returns false`() {
        val w = 100; val h = 200
        val strip = PanelComponents.Box(0, 0, 30, 199)
        val minors = (0..5).map { i ->
            PanelComponents.Box(50, i * 30, 60, i * 30 + 10)
        }
        assertFalse(PanelSegmenter.accept(listOf(strip) + minors, w, h))
    }

    @Test
    fun `accept scattered small boxes with poor span returns false`() {
        val w = 100; val h = 100
        val boxes = listOf(
            PanelComponents.Box(0, 0, 15, 15),
            PanelComponents.Box(80, 80, 95, 95),
        )
        assertFalse(PanelSegmenter.accept(boxes, w, h))
    }

    @Test
    fun `accept four quadrant boxes returns true`() {
        val w = 100; val h = 100
        val boxes = listOf(
            PanelComponents.Box(2, 2, 47, 47),
            PanelComponents.Box(52, 2, 97, 47),
            PanelComponents.Box(2, 52, 47, 97),
            PanelComponents.Box(52, 52, 97, 97),
        )
        assertTrue(PanelSegmenter.accept(boxes, w, h))
    }

    @Test
    fun `small image with high ink occupancy accepted`() {
        val w = 100; val h = 100
        val box = PanelComponents.Box(10, 10, 69, 69)
        val inkMask = BooleanArray(w * h)
        for (y in 10..69) for (x in 10..69) inkMask[y * w + x] = true
        assertTrue("面积 60%×60%=36%<60% 但墨水占用 100%≥70% 应接受",
            PanelSegmenter.accept(listOf(box), w, h, inkMask))
    }

    @Test
    fun `small image with low ink occupancy not accepted by ink`() {
        val w = 100; val h = 100
        val box = PanelComponents.Box(10, 10, 50, 50)
        val inkMask = BooleanArray(w * h)
        assertFalse("面积 41×41<60% 且墨水占用 0%<70% 不因墨水条件接受",
            PanelSegmenter.accept(listOf(box), w, h, inkMask))
    }

    @Test
    fun `large box accepted without ink mask`() {
        val w = 100; val h = 100
        val box = PanelComponents.Box(5, 5, 94, 94)
        assertTrue("面积≥60% 按原规则接受不看墨水占用",
            PanelSegmenter.accept(listOf(box), w, h, null))
    }

    @Test
    fun `inkMask null falls back to original rules`() {
        val w = 100; val h = 100
        val box = PanelComponents.Box(10, 10, 40, 40)
        assertFalse("面积 31×31<60% 且无 inkMask 按原规则不接受",
            PanelSegmenter.accept(listOf(box), w, h, null))
    }

    @Test
    fun `furniture page with high strip density rejected`() {
        val w = 100; val h = 200
        val strip = PanelComponents.Box(0, 0, 30, 199)
        val minors = (0..5).map { i ->
            PanelComponents.Box(50, i * 30, 60, i * 30 + 10)
        }
        val inkMask = BooleanArray(w * h)
        for (y in 0 until 200) for (x in 0..30) inkMask[y * w + x] = true
        for (b in minors) for (y in b.y0..b.y0 + 1) for (x in b.x0..b.x1) inkMask[y * w + x] = true
        assertFalse("主体条带墨水密度 100% 远高于小框 ~18% → 家具页拒绝",
            PanelSegmenter.accept(listOf(strip) + minors, w, h, inkMask))
    }

    @Test
    fun `normal grid not misdetected as furniture`() {
        val w = 100; val h = 100
        val boxes = listOf(
            PanelComponents.Box(2, 2, 47, 47),
            PanelComponents.Box(52, 2, 97, 47),
            PanelComponents.Box(2, 52, 47, 97),
            PanelComponents.Box(52, 52, 97, 97),
        )
        val inkMask = BooleanArray(w * h)
        for (b in boxes) for (y in b.y0..b.y1) for (x in b.x0..b.x1) inkMask[y * w + x] = true
        assertTrue("正常 4 格页不误判为家具页",
            PanelSegmenter.accept(boxes, w, h, inkMask))
    }
}