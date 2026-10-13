package com.mangareader.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelBackgroundTest {

    @Test
    fun `estimate white page returns high value`() {
        val w = 100; val h = 100
        val gray = IntArray(w * h) { 240 }
        val bg = PanelBackground.estimate(gray, w, h)
        assertTrue("白底页背景应≥230, got $bg", bg >= 230)
    }

    @Test
    fun `estimate black page returns low value`() {
        val w = 100; val h = 100
        val gray = IntArray(w * h) { 20 }
        val bg = PanelBackground.estimate(gray, w, h)
        assertTrue("黑底页背景应≤40, got $bg", bg <= 40)
    }

    @Test
    fun `estimate colored background returns mid value`() {
        val w = 100; val h = 100
        val gray = IntArray(w * h) { 128 }
        val bg = PanelBackground.estimate(gray, w, h)
        assertTrue("彩色背景应≈128, got $bg", bg in 120..136)
    }

    @Test
    fun `estimate damp page with dark border returns border value`() {
        val w = 100; val h = 100
        val gray = IntArray(w * h) { 200 }
        for (y in 0 until 5) for (x in 0 until w) gray[y * w + x] = 160
        for (y in h - 5 until h) for (x in 0 until w) gray[y * w + x] = 160
        val bg = PanelBackground.estimate(gray, w, h, 0.05f)
        assertTrue("受潮页边带应≈160, got $bg", bg in 150..170)
    }

    @Test
    fun `maskRelativeTo marks pixels far from background`() {
        val w = 10; val h = 10
        val gray = IntArray(w * h) { 200 }
        gray[5 * w + 5] = 50
        val mask = PanelBackground.maskRelativeTo(gray, w, h, 200, 30)
        assertTrue("与背景差>delta应为ink", mask[5 * w + 5])
        assertFalse("与背景差<=delta应非ink", mask[0])
    }

    @Test
    fun `isWhiteBackground threshold at 200`() {
        assertTrue(PanelBackground.isWhiteBackground(240))
        assertTrue(!PanelBackground.isWhiteBackground(150))
    }

    @Test
    fun `estimateRgb pink background returns triple`() {
        val w = 100; val h = 100
        val rgb = IntArray(w * h) { (255 shl 16) or (200 shl 8) or 200 }
        val bg = PanelBackground.estimateRgb(rgb, w, h)
        assertEquals(255, bg.first)
        assertEquals(200, bg.second)
        assertEquals(200, bg.third)
    }

    @Test
    fun `estimateRgb white page returns white triple`() {
        val w = 100; val h = 100
        val rgb = IntArray(w * h) { (255 shl 16) or (255 shl 8) or 255 }
        val bg = PanelBackground.estimateRgb(rgb, w, h)
        assertEquals(Triple(255, 255, 255), bg)
    }

    @Test
    fun `estimateRgb pure colour returns that colour`() {
        val w = 80; val h = 80
        val rgb = IntArray(w * h) { (30 shl 16) or (100 shl 8) or 200 }
        val bg = PanelBackground.estimateRgb(rgb, w, h)
        assertEquals(Triple(30, 100, 200), bg)
    }

    @Test
    fun `maskRelativeToRgb marks colour differing pixels`() {
        val w = 10; val h = 10
        val bgColour = (255 shl 16) or (200 shl 8) or 200
        val rgb = IntArray(w * h) { bgColour }
        val blueLine = (50 shl 16) or (50 shl 8) or 255
        rgb[5 * w + 5] = blueLine
        val mask = PanelBackground.maskRelativeToRgb(rgb, w, h, Triple(255, 200, 200), 30)
        assertTrue("RGB 通道差>delta 应为前景", mask[5 * w + 5])
        assertFalse("与背景一致应非前景", mask[0])
    }

    @Test
    fun `maskRelativeToRgb grey page degrades to grey difference`() {
        val w = 10; val h = 10
        val rgb = IntArray(w * h) { (200 shl 16) or (200 shl 8) or 200 }
        rgb[3 * w + 3] = (50 shl 16) or (50 shl 8) or 50
        val mask = PanelBackground.maskRelativeToRgb(rgb, w, h, Triple(200, 200, 200), 30)
        assertTrue(mask[3 * w + 3])
        assertFalse(mask[0])
    }

    @Test
    fun `isColourBackground detects colour vs grey`() {
        assertTrue(PanelBackground.isColourBackground(Triple(255, 200, 200)))
        assertFalse(PanelBackground.isColourBackground(Triple(200, 200, 200)))
        assertTrue(PanelBackground.isColourBackground(Triple(30, 100, 200)))
    }
}

