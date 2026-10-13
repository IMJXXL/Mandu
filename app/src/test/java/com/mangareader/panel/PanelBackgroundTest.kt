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
}

