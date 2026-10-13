package com.mangareader.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelFrameEvidenceTest {

    @Test
    fun `frameSides full rectangle border returns four`() {
        val w = 40
        val box = PanelComponents.Box(10, 10, 30, 30)
        val pixels = mutableListOf<Int>()
        for (y in 10..30) pixels.add(y * w + 10)
        for (y in 10..30) pixels.add(y * w + 30)
        for (x in 10..30) pixels.add(10 * w + x)
        for (x in 10..30) pixels.add(30 * w + x)
        val sides = PanelFrameEvidence.frameSides(pixels.toIntArray(), pixels.size, w, box, 2)
        assertEquals("完整矩形边框应识别4条边", 4, sides)
    }

    @Test
    fun `frameSides filled solid block returns four`() {
        val w = 40
        val box = PanelComponents.Box(10, 10, 25, 25)
        val pixels = mutableListOf<Int>()
        for (y in 10..25) for (x in 10..25) pixels.add(y * w + x)
        val sides = PanelFrameEvidence.frameSides(pixels.toIntArray(), pixels.size, w, box, 2)
        assertEquals("实心块应识别4条边", 4, sides)
    }

    @Test
    fun `frameSides scattered noise returns zero`() {
        val w = 50
        val box = PanelComponents.Box(5, 5, 45, 45)
        val pixels = intArrayOf(
            10 * w + 12, 15 * w + 38, 20 * w + 7, 25 * w + 41,
            30 * w + 15, 35 * w + 30, 8 * w + 25, 40 * w + 20,
            18 * w + 22, 28 * w + 33
        )
        val sides = PanelFrameEvidence.frameSides(pixels, pixels.size, w, box, 1)
        assertEquals("散乱噪声不应识别任何边", 0, sides)
    }

    @Test
    fun `frameSides degenerate box returns zero`() {
        val w = 40
        val box = PanelComponents.Box(10, 10, 10, 10)
        val pixels = intArrayOf(10 * w + 10)
        val sides = PanelFrameEvidence.frameSides(pixels, 1, w, box, 2)
        assertEquals("退化box应返回0", 0, sides)
    }

    @Test
    fun `frameSides partial border with sentinel fill returns four`() {
        val w = 40
        val box = PanelComponents.Box(10, 10, 30, 30)
        val pixels = mutableListOf<Int>()
        for (y in 10..30) pixels.add(y * w + 10)
        for (y in 10..30) pixels.add(y * w + 30)
        val sides = PanelFrameEvidence.frameSides(pixels.toIntArray(), pixels.size, w, box, 2)
        assertEquals(4, sides)
    }

    @Test
    fun `frameSides three side border returns four`() {
        val w = 40
        val box = PanelComponents.Box(10, 10, 30, 30)
        val pixels = mutableListOf<Int>()
        for (y in 10..30) pixels.add(y * w + 10)
        for (x in 10..30) pixels.add(10 * w + x)
        for (x in 10..30) pixels.add(30 * w + x)
        val sides = PanelFrameEvidence.frameSides(pixels.toIntArray(), pixels.size, w, box, 2)
        assertEquals("三边填充应识别4条边", 4, sides)
    }
}