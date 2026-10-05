package com.mangareader.native

import kotlin.math.min

/**
 * ★ 画面缩放单一语义层（2026-10-05 重做，对齐 mihon ReaderPageImageView/SSIV fork 模型）。
 *
 * 此前缩放公式内联分散在 pager applyScale/applySplitScale、curl 单页/双页/inset/spineBounds
 * 多处，靠注释互相"对齐"，曾产生"仿真缺平板防糊封顶横屏被放大"这类分裂 bug；本对象是
 * 唯一的公式出处，各读者的显示矩形/初始中心/对齐全部从这里派生。纯 Kotlin 无 Android
 * 依赖，可 JVM 单测（ScalePlanTest）。
 *
 * 档位语义与 mihon scale_type_* 一致；智能适应取自 tachiyomi SSIV fork 的 minScale()：
 * 竖版页（sHeight>sWidth）贴宽、横版页贴高——两个维度都尽量占满。
 */
object ScalePlan {

    /** 完整显示：等比 contain，哪边先到屏幕边界就停，完整不裁切（mihon Fit screen/SCALE_TYPE_CENTER_INSIDE） */
    const val MODE_CONTAIN = 0
    /** 拉伸：等比 cover 填满屏幕（mihon Stretch——SSIV fork 同为等比实现，取两向 fit 的大者，溢出一侧裁切） */
    const val MODE_STRETCH = 5
    /** 适应宽度：宽度贴满，竖版页可能上下超出（mihon Fit width，无平板封顶） */
    const val MODE_FIT_WIDTH = 1
    /** 贴满高度：高度贴满，宽图可能左右超出（mihon Fit height）——仅可平移的视图实用（翻页可拖；仿真/条漫慎选） */
    const val MODE_FIT_HEIGHT = 2
    /** 原始尺寸：1:1 像素显示（小图不放大，大图超出部分靠平移；mihon Original size） */
    const val MODE_ORIGINAL = 3
    /** 智能适应：contain 但小图不放大超过原始尺寸（mihon Smart fit = min(contain, 1f)） */
    const val MODE_SMART = 4
    const val MODE_DEFAULT = MODE_FIT_WIDTH
    fun isValidMode(m: Int) = m in MODE_CONTAIN..MODE_STRETCH

    /** 缩放起始位置（mihon zoomStart）：自动=跟随阅读方向（RTL→对齐右缘） */
    const val ZOOM_AUTO = 0
    const val ZOOM_LEFT = 1
    const val ZOOM_CENTER = 2
    const val ZOOM_RIGHT = 3
    const val ZOOM_DEFAULT = ZOOM_AUTO
    fun isValidZoomStart(z: Int) = z in ZOOM_AUTO..ZOOM_RIGHT

    /**
     * landscapeZoom（自动放大横向图片）可插手的档位：仅"适应屏幕"基线
     * （mihon 同款：landscapeZoom 设置仅在 imageScaleType==1 即 Fit screen 档 enabled）。
     * 适应宽度档本来就是贴宽（插手是空操作），其余档是用户明确意图不插手。
     */
    fun landscapeZoomable(mode: Int): Boolean = mode == MODE_CONTAIN

    /**
     * 基线显示比例（mihon SSIV fork getScaleTypeScale 同款公式）。
     * @param isTablet 已废弃（mihon 对齐后各档无平板防糊封顶）；保留形参兼容既有调用方。
     */
    fun baseScale(mode: Int, vw: Float, vh: Float, iw: Float, ih: Float, @Suppress("UNUSED_PARAMETER") isTablet: Boolean = false): Float {
        if (iw <= 0f || ih <= 0f || vw <= 0f || vh <= 0f) return 1f
        val fitW = vw / iw
        val fitH = vh / ih
        return when (if (isValidMode(mode)) mode else MODE_DEFAULT) {
            MODE_CONTAIN -> minOf(fitW, fitH)
            MODE_STRETCH -> maxOf(fitW, fitH)
            MODE_FIT_WIDTH -> fitW
            MODE_FIT_HEIGHT -> fitH
            MODE_ORIGINAL -> 1f
            MODE_SMART -> minOf(minOf(fitW, fitH), 1f)
            else -> fitW
        }
    }

    /**
     * 水平对齐（位图空间）：-1=阅读起始缘（LTR=位图左缘 / RTL=位图右缘），0=居中，+1=结束缘。
     * mihon zoomStart AUTO 同款：L2R→LEFT、R2L→RIGHT。
     */
    fun alignX(zoomStart: Int, isRtl: Boolean): Int = when (zoomStart) {
        ZOOM_LEFT -> -1
        ZOOM_RIGHT -> 1
        ZOOM_AUTO -> if (isRtl) 1 else -1
        else -> 0
    }

    /** 垂直对齐：mihon zoomStart LEFT/RIGHT 同款顶对齐（PointF(0,0)/(sWidth,0) 的 y=0），居中选项垂直居中 */
    fun alignY(zoomStart: Int): Int = if (zoomStart == ZOOM_CENTER) 0 else -1

    /**
     * 初始显示中心 x（位图坐标，SSIV setScaleAndCenter 直接用）。
     * 对齐到某缘=位图该缘贴视口该缘；图像无横向溢出时表现为"贴边留白"（mihon 行为一致）。
     */
    fun centerX(zoomStart: Int, isRtl: Boolean, vw: Float, iw: Float, scale: Float): Float {
        if (scale <= 0f) return iw / 2f
        val visW = vw / scale
        return when (alignX(zoomStart, isRtl)) {
            -1 -> visW / 2f
            1 -> iw - visW / 2f
            else -> iw / 2f
        }
    }

    /** 初始显示中心 y（位图坐标）：LEFT/RIGHT 顶对齐，CENTER 垂直居中 */
    fun centerY(zoomStart: Int, vh: Float, ih: Float, scale: Float): Float {
        if (scale <= 0f) return ih / 2f
        val visH = vh / scale
        return when (alignY(zoomStart)) {
            -1 -> visH / 2f
            else -> ih / 2f
        }
    }

    /**
     * landscapeZoom 动画落点中心 x（位图坐标）：前进落在阅读起始缘、回退落在结束缘
     * （mihon landscapeZoom 同款：LEFT 前进→(0,0)、后退→(sWidth,0)；CENTER 保持居中）。
     * @param scale landscapeZoom 的目标比例（=vh/ih）
     */
    fun landscapeZoomCenterX(zoomStart: Int, isRtl: Boolean, forward: Boolean, vw: Float, iw: Float, scale: Float): Float {
        if (scale <= 0f) return iw / 2f
        val visW = vw / scale
        return when (alignX(zoomStart, isRtl)) {
            -1 -> if (forward) visW / 2f else iw - visW / 2f
            1 -> if (forward) iw - visW / 2f else visW / 2f
            else -> iw / 2f
        }
    }

    // ---- 不可平移视图的档位收敛 ----
    // 翻页 SSIV 可拖动平移，六档全部可用；仿真（Compose）与非 SSIV ImageView 静态显示
    // 没有平移手势，贴高/智能/拉伸对溢出方向会裁掉且拖不回来（用户要求"4 比 2 宽也不能漏 4"）。

    /** 双页仿真（合并跨页）：跨页天然宽图，原尺寸也几乎必然裁切——只留 完整显示/适应宽度 两档 */
    fun spreadMode(mode: Int): Int = if (mode == MODE_CONTAIN) MODE_CONTAIN else MODE_FIT_WIDTH

    /** 单页仿真 / 非 SSIV 静态图：溢出类档收敛到适应宽度（防不可找回的裁切），原尺寸/完整显示保留 */
    fun staticMode(mode: Int): Int = when (mode) {
        MODE_CONTAIN -> MODE_CONTAIN
        MODE_ORIGINAL -> MODE_ORIGINAL
        else -> MODE_FIT_WIDTH
    }

    /**
     * 显示矩形边距（屏空间）：对齐 -1=贴起始缘（start 侧无边距），0=居中，+1=贴结束缘。
     * 返回 (startGap, endGap)。模拟/静态视图按此铺 inset 或定位 Image；RTL 由调用方换侧。
     */
    fun edgeGaps(bias: Int, view: Float, disp: Float): Pair<Float, Float> {
        val slack = (view - disp).coerceAtLeast(0f)
        return when {
            bias < 0 -> 0f to slack
            bias > 0 -> slack to 0f
            else -> slack / 2f to slack / 2f
        }
    }
}
