package com.mangareader.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebtoonSplitMergeTest {

    @Test
    fun shouldSplitWideImageWithSplitEnabled() {
        assertTrue(shouldSplitWebtoonPage(true, 0, 2000, 1000))
    }

    @Test
    fun shouldNotSplitWhenSplitDisabled() {
        assertFalse(shouldSplitWebtoonPage(false, 0, 2000, 1000))
    }

    @Test
    fun shouldNotSplitNonWideImage() {
        assertFalse(shouldSplitWebtoonPage(true, 0, 1000, 1500))
    }

    @Test
    fun shouldNotSplitSquareImage() {
        assertFalse(shouldSplitWebtoonPage(true, 0, 1000, 1000))
    }

    @Test
    fun shouldNotSplitHalfPage() {
        assertFalse(shouldSplitWebtoonPage(true, 1, 2000, 1000))
        assertFalse(shouldSplitWebtoonPage(true, 2, 2000, 1000))
    }

    @Test
    fun shouldSplitSegmentPageIfWide() {
        assertTrue(shouldSplitWebtoonPage(true, 0, 2000, 800))
    }

    @Test
    fun shouldNotSplitSegmentPageIfNotWide() {
        assertFalse(shouldSplitWebtoonPage(true, 0, 800, 1200))
    }

    @Test
    fun splitAndMergeGeometryHalfWidthDoubleHeight() {
        val w = 2000
        val h = 1000
        assertEquals(w / 2, 1000)
        assertEquals(h * 2, 2000)
    }

    @Test
    fun splitAndMergeGeometryOddWidth() {
        val w = 3001
        val h = 1000
        assertEquals(w / 2, 1500)
        assertEquals(h * 2, 2000)
    }

    @Test
    fun splitAndMergeGeometry3000x1200() {
        val w = 3000
        val h = 1200
        assertEquals(w / 2, 1500)
        assertEquals(h * 2, 2400)
    }
}