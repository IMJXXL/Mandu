package com.mangareader.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ScalePlan（画面缩放单一语义层）单测：档位公式 / 平板防糊封顶 / 智能适应 /
 * zoomStart 对齐中心 / landscapeZoom 落点方向 / 双页跨页档位收敛。
 * 视口 1000x2000，位图 800x1200（竖版页）与 1200x800（宽图）为标准样例。
 */
class ScalePlanTest {

    private val vw = 1000f
    private val vh = 2000f

    @Test
    fun `五档基线比例公式`() {
        // 竖版页 800x1200: fitW=1000/800=1.25, fitH=2000/1200≈1.667
        val iw = 800f; val ih = 1200f
        assertEquals(1.25f, ScalePlan.baseScale(ScalePlan.MODE_CONTAIN, vw, vh, iw, ih), 1e-4f)
        assertEquals(1.25f, ScalePlan.baseScale(ScalePlan.MODE_FIT_WIDTH, vw, vh, iw, ih), 1e-4f)
        assertEquals(vh / ih, ScalePlan.baseScale(ScalePlan.MODE_FIT_HEIGHT, vw, vh, iw, ih), 1e-4f)
        assertEquals(1f, ScalePlan.baseScale(ScalePlan.MODE_ORIGINAL, vw, vh, iw, ih), 1e-4f)
        // 竖版页智能=贴宽
        assertEquals(1.25f, ScalePlan.baseScale(ScalePlan.MODE_SMART, vw, vh, iw, ih), 1e-4f)
        // 宽图 1200x800: fitW≈0.833, fitH=2.5；智能=贴高
        val iw2 = 1200f; val ih2 = 800f
        assertEquals(0.8333f, ScalePlan.baseScale(ScalePlan.MODE_CONTAIN, vw, vh, iw2, ih2), 1e-3f)
        assertEquals(2.5f, ScalePlan.baseScale(ScalePlan.MODE_SMART, vw, vh, iw2, ih2), 1e-4f)
    }

    @Test
    fun `平板防糊封顶仅作用于贴宽分支`() {
        // 位图原生宽度 600 < 视口 1000：贴宽 fitW=1.667 封顶到 1
        val iw = 600f; val ih = 900f
        assertEquals(1f, ScalePlan.baseScale(ScalePlan.MODE_FIT_WIDTH, vw, vh, iw, ih, isTablet = true), 1e-4f)
        assertEquals(1.6666f, ScalePlan.baseScale(ScalePlan.MODE_FIT_WIDTH, vw, vh, iw, ih, isTablet = false), 1e-3f)
        // 位图比屏宽：封顶无影响（fitW<1）
        assertEquals(vw / 1200f, ScalePlan.baseScale(ScalePlan.MODE_FIT_WIDTH, vw, vh, 1200f, 900f, isTablet = true), 1e-4f)
        // 其他档位不受平板影响
        assertEquals(vh / 900f, ScalePlan.baseScale(ScalePlan.MODE_FIT_HEIGHT, vw, vh, 600f, 900f, isTablet = true), 1e-4f)
        assertEquals(minOf(vw / 600f, vh / 900f), ScalePlan.baseScale(ScalePlan.MODE_CONTAIN, vw, vh, 600f, 900f, isTablet = true), 1e-4f)
        assertEquals(1f, ScalePlan.baseScale(ScalePlan.MODE_ORIGINAL, vw, vh, 600f, 900f, isTablet = true), 1e-4f)
    }

    @Test
    fun `非法输入与越界档位兜底`() {
        assertEquals(1f, ScalePlan.baseScale(ScalePlan.MODE_FIT_WIDTH, vw, vh, 0f, 100f), 1e-4f)
        assertEquals(1f, ScalePlan.baseScale(ScalePlan.MODE_FIT_WIDTH, 0f, vh, 800f, 1200f), 1e-4f)
        // 越界档位回默认（贴宽）
        assertEquals(vw / 800f, ScalePlan.baseScale(99, vw, vh, 800f, 1200f), 1e-4f)
    }

    @Test
    fun `zoomStart对齐中心_溢出时显示起始缘`() {
        // 宽图 2000x1000 在 1000x2000 视口贴宽？不——贴宽溢出高度；用贴高档：scale=2, visW=500
        val iw = 2000f; val ih = 1000f
        val scale = ScalePlan.baseScale(ScalePlan.MODE_FIT_HEIGHT, vw, vh, iw, ih)
        assertEquals(2f, scale, 1e-4f)
        // LTR 自动 → 起始缘=左：center=visW/2=250（位图左缘贴视口左缘）
        assertEquals(250f, ScalePlan.centerX(ScalePlan.ZOOM_AUTO, isRtl = false, vw, iw, scale), 1e-4f)
        // RTL 自动 → 起始缘=右：center=iw-visW/2=1750
        assertEquals(1750f, ScalePlan.centerX(ScalePlan.ZOOM_AUTO, isRtl = true, vw, iw, scale), 1e-4f)
        // 显式居中
        assertEquals(1000f, ScalePlan.centerX(ScalePlan.ZOOM_CENTER, isRtl = true, vw, iw, scale), 1e-4f)
        // 显式左（无视方向）
        assertEquals(250f, ScalePlan.centerX(ScalePlan.ZOOM_LEFT, isRtl = true, vw, iw, scale), 1e-4f)
        assertEquals(1750f, ScalePlan.centerX(ScalePlan.ZOOM_RIGHT, isRtl = false, vw, iw, scale), 1e-4f)
        // 垂直：贴高档高向恰好填满（visH=1000=ih），顶对齐与居中同为 ih/2=500；
        // 顶对齐差异要用会纵向溢出的样例看：铺满档竖版页 800x1200 scale=1.25, visH=1600>1200
        assertEquals(500f, ScalePlan.centerY(ScalePlan.ZOOM_LEFT, vh, ih, scale), 1e-4f)
        assertEquals(500f, ScalePlan.centerY(ScalePlan.ZOOM_CENTER, vh, ih, scale), 1e-4f)
        val s2 = ScalePlan.baseScale(ScalePlan.MODE_FIT_WIDTH, vw, vh, 800f, 1200f)
        assertEquals(800f, ScalePlan.centerY(ScalePlan.ZOOM_LEFT, vh, 1200f, s2), 1e-4f)   // 顶对齐 visH/2=1600/2
        assertEquals(600f, ScalePlan.centerY(ScalePlan.ZOOM_CENTER, vh, 1200f, s2), 1e-4f) // 居中 ih/2
    }

    @Test
    fun `zoomStart对齐中心_留白时贴边（mihon窄页行为）`() {
        // 窄图 400x400 完整显示：contain=min(2.5, 5)=2.5，scaledW=1000=vw 恰好满宽？
        // 用原尺寸档：scale=1，visW=1000 > iw=400 → 无溢出，左对齐=贴视口左缘（center=visW/2=500）
        val iw = 400f; val ih = 400f
        assertEquals(500f, ScalePlan.centerX(ScalePlan.ZOOM_AUTO, isRtl = false, vw, iw, 1f), 1e-4f)
        assertEquals(-100f, ScalePlan.centerX(ScalePlan.ZOOM_AUTO, isRtl = true, vw, iw, 1f), 1e-4f) // 400-500
        assertEquals(200f, ScalePlan.centerX(ScalePlan.ZOOM_CENTER, isRtl = false, vw, iw, 1f), 1e-4f)
    }

    @Test
    fun `landscapeZoom落点_前进起始缘回退结束缘`() {
        // 宽图 2000x1000，目标贴高 scale=2, visW=500
        val iw = 2000f; val ih = 1000f
        val scale = ScalePlan.baseScale(ScalePlan.MODE_FIT_HEIGHT, vw, vh, iw, ih)
        // LTR：前进→左缘 250，回退→右缘 1750
        assertEquals(250f, ScalePlan.landscapeZoomCenterX(ScalePlan.ZOOM_AUTO, false, forward = true, vw, iw, scale), 1e-4f)
        assertEquals(1750f, ScalePlan.landscapeZoomCenterX(ScalePlan.ZOOM_AUTO, false, forward = false, vw, iw, scale), 1e-4f)
        // RTL：前进→右缘 1750，回退→左缘 250
        assertEquals(1750f, ScalePlan.landscapeZoomCenterX(ScalePlan.ZOOM_AUTO, true, forward = true, vw, iw, scale), 1e-4f)
        assertEquals(250f, ScalePlan.landscapeZoomCenterX(ScalePlan.ZOOM_AUTO, true, forward = false, vw, iw, scale), 1e-4f)
        // 居中：恒居中（mihon CENTER 同款）
        assertEquals(1000f, ScalePlan.landscapeZoomCenterX(ScalePlan.ZOOM_CENTER, false, forward = true, vw, iw, scale), 1e-4f)
        // 显式 RIGHT：前进→右缘（与 AUTO-RTL 前进同效）
        assertEquals(1750f, ScalePlan.landscapeZoomCenterX(ScalePlan.ZOOM_RIGHT, false, forward = true, vw, iw, scale), 1e-4f)
    }

    @Test
    fun `landscapeZoom只在contain和贴宽档插手`() {
        assertTrue(ScalePlan.landscapeZoomable(ScalePlan.MODE_CONTAIN))
        assertTrue(ScalePlan.landscapeZoomable(ScalePlan.MODE_FIT_WIDTH))
        assertFalse(ScalePlan.landscapeZoomable(ScalePlan.MODE_FIT_HEIGHT))
        assertFalse(ScalePlan.landscapeZoomable(ScalePlan.MODE_ORIGINAL))
        assertFalse(ScalePlan.landscapeZoomable(ScalePlan.MODE_SMART))
    }

    @Test
    fun `不可平移视图档位收敛`() {
        // 双页跨页：天然宽图+原尺寸也必然裁——只留 contain/贴宽
        assertEquals(ScalePlan.MODE_CONTAIN, ScalePlan.spreadMode(ScalePlan.MODE_CONTAIN))
        assertEquals(ScalePlan.MODE_FIT_WIDTH, ScalePlan.spreadMode(ScalePlan.MODE_FIT_WIDTH))
        assertEquals(ScalePlan.MODE_FIT_WIDTH, ScalePlan.spreadMode(ScalePlan.MODE_FIT_HEIGHT))
        assertEquals(ScalePlan.MODE_FIT_WIDTH, ScalePlan.spreadMode(ScalePlan.MODE_ORIGINAL))
        assertEquals(ScalePlan.MODE_FIT_WIDTH, ScalePlan.spreadMode(ScalePlan.MODE_SMART))
        // 单页仿真/非SSIV静态：贴高/智能收敛铺满（防不可找回裁切），原尺寸保留
        assertEquals(ScalePlan.MODE_CONTAIN, ScalePlan.staticMode(ScalePlan.MODE_CONTAIN))
        assertEquals(ScalePlan.MODE_ORIGINAL, ScalePlan.staticMode(ScalePlan.MODE_ORIGINAL))
        assertEquals(ScalePlan.MODE_FIT_WIDTH, ScalePlan.staticMode(ScalePlan.MODE_FIT_WIDTH))
        assertEquals(ScalePlan.MODE_FIT_WIDTH, ScalePlan.staticMode(ScalePlan.MODE_FIT_HEIGHT))
        assertEquals(ScalePlan.MODE_FIT_WIDTH, ScalePlan.staticMode(ScalePlan.MODE_SMART))
    }

    @Test
    fun `edgeGaps按对齐偏置分布留白`() {
        // 1000 视口放 600 内容：居中=200/200；贴起=0/400；贴终=400/0；内容超界=0/0
        assertEquals(200f to 200f, ScalePlan.edgeGaps(0, 1000f, 600f))
        assertEquals(0f to 400f, ScalePlan.edgeGaps(-1, 1000f, 600f))
        assertEquals(400f to 0f, ScalePlan.edgeGaps(1, 1000f, 600f))
        assertEquals(0f to 0f, ScalePlan.edgeGaps(0, 1000f, 1500f))
    }
}
