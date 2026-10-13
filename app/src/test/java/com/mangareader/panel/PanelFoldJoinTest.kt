package com.mangareader.panel

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelFoldJoinTest {

    private fun grayPixel(lum: Int): Int = (0xFF shl 24) or (lum shl 16) or (lum shl 8) or lum

    @Test
    fun `findBand detects fold in spread`() {
        val w = 1000; val h = 500
        val bandX0 = 490; val bandX1 = 510
        val sample = { x: Int, y: Int ->
            if (x in bandX0..bandX1) grayPixel(10) else grayPixel(240)
        }
        val band = PanelFoldJoin.findBand(w, h, sample)
        assertNotNull("含折痕跨页大图应检测到条带", band)
        if (band != null) {
            assertTrue("条带左边界≥480", band[0] >= 480)
            assertTrue("条带右边界≤520", band[1] <= 520)
        }
    }

    @Test
    fun `findBand returns null for normal page`() {
        val w = 800; val h = 1200
        val sample = { x: Int, y: Int -> grayPixel(240) }
        assertNull("普通单页无黑条带应返回 null", PanelFoldJoin.findBand(w, h, sample))
    }

    @Test
    fun `findBand returns null for all dark`() {
        val w = 1000; val h = 500
        val sample = { x: Int, y: Int -> grayPixel(10) }
        assertNull("全黑画面条带触边不算折痕", PanelFoldJoin.findBand(w, h, sample))
    }

    @Test
    fun `findBand returns null for wide band`() {
        val w = 1000; val h = 500
        val bandX0 = 400; val bandX1 = 600
        val sample = { x: Int, y: Int ->
            if (x in bandX0..bandX1) grayPixel(10) else grayPixel(240)
        }
        assertNull("条带宽度超 4% 不移除", PanelFoldJoin.findBand(w, h, sample))
    }

    @Test
    fun `findBand returns null for off-centre band`() {
        val w = 1000; val h = 500
        val bandX0 = 200; val bandX1 = 220
        val sample = { x: Int, y: Int ->
            if (x in bandX0..bandX1) grayPixel(10) else grayPixel(240)
        }
        assertNull("条带偏离中心超 3% 不移除", PanelFoldJoin.findBand(w, h, sample))
    }

    @Test
    fun `findBand returns null for small image`() {
        val sample = { x: Int, y: Int -> grayPixel(0) }
        assertNull("小图不检测", PanelFoldJoin.findBand(40, 40, sample))
    }

    @Test
    fun `findBand returns null when centre not dark`() {
        val w = 1000; val h = 500
        val sample = { x: Int, y: Int ->
            if (x in 200..220) grayPixel(10) else grayPixel(240)
        }
        assertNull("中心列非黑列返回 null", PanelFoldJoin.findBand(w, h, sample))
    }

    @Test
    fun `findBand detects fold with content on both sides`() {
        val w = 1000; val h = 500
        val bandX0 = 490; val bandX1 = 510
        val sample = { x: Int, y: Int ->
            when (x) {
                in bandX0..bandX1 -> grayPixel(10)
                in 0..100 -> grayPixel(100)
                in 900..999 -> grayPixel(100)
                else -> grayPixel(240)
            }
        }
        val band = PanelFoldJoin.findBand(w, h, sample)
        assertNotNull("两侧有内容的折痕应检测到", band)
    }

    @Test
    fun `findBand trim columns constant is positive`() {
        assertTrue("修剪列数应>0", PanelTunables.FOLD_TRIM_COLUMNS > 0)
    }
}