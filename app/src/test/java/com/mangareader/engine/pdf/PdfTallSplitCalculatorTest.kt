package com.mangareader.engine.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 分割几何 JVM 属性测试（纯 Kotlin，无 Android 依赖）。
 *
 * 覆盖 A1 均分语义的不变量：
 *  1. 全覆盖：所有段矩形联合 == [0, H]，无缝（bottom_i == top_(i+1)）无叠、段高恒正
 *  2. 等高：全段高度一致（浮点容差）
 *  3. 封顶不截断：H 远超 64×splitH 时 64 段仍覆盖整页（回归旧算法封顶后 splitH 不重算的丢尾 bug）
 *  4. 薄片消除：页高刚过整倍数时全段等高（回归旧算法极矮末段）
 * 另覆盖 isTallPage / segmentCount / 越界钳制 / 退化输入边界。
 */
class PdfTallSplitCalculatorTest {

    private fun rects(pageHeight: Float, splitH: Float): List<PdfTallSplitCalculator.SplitRect> {
        val n = PdfTallSplitCalculator.segmentCount(pageHeight, splitH)
        return (1..n).map { PdfTallSplitCalculator.segmentRect(100f, pageHeight, splitH, it) }
    }

    private fun assertFullCoverage(pageHeight: Float, splitH: Float) {
        val rs = rects(pageHeight, splitH)
        assertTrue("至少一段", rs.isNotEmpty())
        assertEquals("第一段必须从 0 开始", 0f, rs.first().top, 1e-4f)
        assertEquals("最后一段必须到页底", pageHeight, rs.last().bottom, 1e-4f)
        for (i in 1 until rs.size) {
            assertEquals("段 $i 必须与前段无缝衔接", rs[i - 1].bottom, rs[i].top, 1e-4f)
            assertTrue("段 $i 高度必须为正", rs[i].height > 0f)
        }
        assertEquals("段高总和必须等于页高（无叠无缝）", pageHeight.toDouble(), rs.sumOf { it.height.toDouble() }, 1e-3)
    }

    @Test
    fun `均分语义 - 常规页全覆盖无缝`() {
        for (h in listOf(1000f, 5000f, 12345f)) {
            for (splitH in listOf(2000f, 4444f, 8000f)) {
                assertFullCoverage(h, splitH)
            }
        }
    }

    @Test
    fun `薄片消除 - 页高刚过整倍数时全段等高`() {
        // 旧算法：H=2.1×splitH → n=3 → 末段仅 0.1×splitH（薄片）；新算法 3 等段
        val splitH = 4444f
        val h = 2.1f * splitH
        assertEquals(3, PdfTallSplitCalculator.segmentCount(h, splitH))
        val heights = rects(h, splitH).map { it.height }
        assertTrue("全段等高: $heights", heights.max() - heights.min() < 1f)
        assertTrue("段高接近均分值", abs(heights[0] - h / 3) < 1f)
    }

    @Test
    fun `封顶不截断 - 超长页 64 段仍全覆盖`() {
        // 旧算法：H=100×splitH → n 封顶 64 但 splitH 不变 → 尾部 36×splitH 内容丢失
        val splitH = 1000f
        val h = 100f * splitH
        assertEquals(64, PdfTallSplitCalculator.segmentCount(h, splitH))
        assertFullCoverage(h, splitH)
    }

    @Test
    fun `段索引越界钳制到有效范围`() {
        val n = PdfTallSplitCalculator.segmentCount(5000f, 2000f)
        val over = PdfTallSplitCalculator.segmentRect(100f, 5000f, 2000f, 999)
        assertEquals("越界索引落末段底", 5000f, over.bottom, 1e-4f)
        assertEquals(
            "越界索引顶等于末段顶",
            PdfTallSplitCalculator.segmentRect(100f, 5000f, 2000f, n).top, over.top, 0f,
        )
        val under = PdfTallSplitCalculator.segmentRect(100f, 5000f, 2000f, 0)
        assertEquals("0/负索引钳到首段", 0f, under.top, 1e-4f)
    }

    @Test
    fun `退化输入返回单段`() {
        assertEquals(1, PdfTallSplitCalculator.segmentCount(0f, 1000f))
        assertEquals(1, PdfTallSplitCalculator.segmentCount(1000f, 0f))
        assertEquals(1, PdfTallSplitCalculator.segmentCount(-5f, 1000f))
        assertEquals(1, PdfTallSplitCalculator.segmentCount(1000f, -1f))
        assertEquals(0f, PdfTallSplitCalculator.segmentRect(100f, 0f, 1000f, 1).height, 1e-4f)
    }

    @Test
    fun `isTallPage 边界`() {
        val splitH = PdfTallSplitCalculator.optimalSplitHeightPx(2400)
        assertTrue(PdfTallSplitCalculator.isTallPage(1000f, 5000f, splitH))
        assertFalse("比例不足 3 不拆", PdfTallSplitCalculator.isTallPage(1000f, 2999f, 4000f))
        assertFalse("高宽非正不拆", PdfTallSplitCalculator.isTallPage(0f, 5000f, 4000f))
        assertFalse("不高于 splitH 不拆", PdfTallSplitCalculator.isTallPage(1000f, 5000f, 5000f))
    }

    @Test
    fun `splitHeightPdf 与屏幕联动`() {
        // ★ 段高=2 屏（2026-09-26 回退）：×2 时一段≈一个原漫画页（01.pdf 实测 35 段=真实页数）；
        //   889a49d 的 ×1 把每页切两半（页数翻倍、预载屏覆盖减半）已撤销
        assertEquals(4800f, PdfTallSplitCalculator.splitHeightPdf(2400, 1080, 1080f), 0.01f)
        assertEquals(4096f, PdfTallSplitCalculator.optimalSplitHeightPx(0), 0.01f)
        // screenWidth 非法时回退 optimalSplitHeightPx(screenMax)×2 = 4800（不是 4096 默认值）
        assertEquals(4800f, PdfTallSplitCalculator.splitHeightPdf(2400, 0, 1080f), 0.01f)
    }

    @Test
    fun `segmentRectByCount 与均分语义一致`() {
        // 渲染端按 expand 的权威段数（segmentTotal）直接均分——与 segmentRect 同构
        val n = PdfTallSplitCalculator.segmentCount(42901f, 1226f)
        val viaSplitH = PdfTallSplitCalculator.segmentRect(595f, 42901f, 1226f, 2)
        val viaCount = PdfTallSplitCalculator.segmentRectByCount(595f, 42901f, n, 2)
        assertEquals(viaSplitH.top, viaCount.top, 1e-4f)
        assertEquals(viaSplitH.bottom, viaCount.bottom, 1e-4f)
        // 段索引越界钳制同样生效
        val over = PdfTallSplitCalculator.segmentRectByCount(595f, 42901f, n, 999)
        assertEquals(42901f, over.bottom, 1e-4f)
    }
}
