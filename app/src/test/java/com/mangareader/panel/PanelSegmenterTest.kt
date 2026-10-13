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
}