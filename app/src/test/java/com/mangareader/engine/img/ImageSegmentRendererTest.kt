package com.mangareader.engine.img

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长图分段采样规则 JVM 测试（sampleSizeFor 为纯函数）。
 *
 * 规则：取最大 2 的幂 s 使 imageWidth/s ≥ cap（cap = max(目标宽, 720)）——
 * 宁可保住原生宽度再精确缩，也不落在屏宽一半以下（fit-width 显示下保清晰度）。
 */
class ImageSegmentRendererTest {

    @Test
    fun `窄图不降采样`() {
        assertEquals(1, ImageSegmentRenderer.sampleSizeFor(800, 1080))
        assertEquals(1, ImageSegmentRenderer.sampleSizeFor(1080, 1080))
        assertEquals(1, ImageSegmentRenderer.sampleSizeFor(720, 1080))
    }

    @Test
    fun `超宽图按 2 的幂降到不低于目标宽`() {
        // 5000：/8=625 < 1080，/4=1250 ≥ 1080 → s=4（1250 宽再由解码侧精确缩到屏宽）
        assertEquals(4, ImageSegmentRenderer.sampleSizeFor(5000, 1080))
        assertEquals(2, ImageSegmentRenderer.sampleSizeFor(5000, 2500))
        // 恰好整除：2160/2=1080 == cap → s=2，解码宽恰为目标宽
        assertEquals(2, ImageSegmentRenderer.sampleSizeFor(2160, 1080))
    }

    @Test
    fun `小屏目标宽下限 720`() {
        // 目标宽 360 → cap 提到 720：1200/2=600 < 720 → s=1 保住 600 以上原生宽
        assertEquals(1, ImageSegmentRenderer.sampleSizeFor(1200, 360))
        assertEquals(2, ImageSegmentRenderer.sampleSizeFor(2400, 360))
    }

    @Test
    fun `退化输入`() {
        assertEquals(1, ImageSegmentRenderer.sampleSizeFor(0, 1080))
        assertEquals(1, ImageSegmentRenderer.sampleSizeFor(1, 1080))
    }

    @Test
    fun `段缓存键携带屏幕尺寸`() {
        val a = ImageSegmentRenderer.segmentCacheKey("img:doc|0|dir", 2, 1080, 2400)
        val b = ImageSegmentRenderer.segmentCacheKey("img:doc|0|dir", 2, 1440, 2560)
        assertTrue("不同屏幕尺寸必须得到不同键（旧几何缓存不可达）", a != b)
        assertTrue(a.contains("seg2") && a.contains("w1080") && a.contains("m2400"))
    }
}
