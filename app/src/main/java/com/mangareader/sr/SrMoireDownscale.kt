package com.mangareader.sr

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ★ 降低摩尔纹（Komiho 对齐 2026-10-07）：AI 2x 超分输出的高频网点晶格被显示层双线性
 * 整图缩小（2×2 tap 无低通）时与屏幕像素网格拍频出摩尔纹——开启后把 SR 结果按视口
 * 尺寸高斯低通回缩（native lanczos_engine），显示层变 ~1:1 不再缩小，拍频消失。
 *
 * 契约对齐 Komiho：fit 比（图/视口）≥ 0.85 不动（留余量防欠采样）；绝不放大；
 * 条漫只按宽度算 fit（高度是滚动维度）；视口未知（≤0）跳过；任何失败原样返回。
 * 入参永不回收（调用方/管线缓存持有所有权），返回新位图或入参本身。
 * 磁盘产物保持全尺寸——只有内存缓存与显示走回缩副本，未来更大屏/缩放不封顶。
 */
object SrMoireDownscale {

    private const val TRIGGER = 0.85f

    fun apply(bitmap: Bitmap, viewW: Int, viewH: Int, tallStrip: Boolean): Bitmap {
        // SrSettings 是类（SrCore.settings 实例），未初始化时视为关闭
        val enabled = try { SrCore.settings.areaDownscale() } catch (_: Throwable) { false }
        if (!enabled) return bitmap
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return bitmap
        if (viewW <= 0 || viewW == Int.MAX_VALUE) return bitmap
        if (!tallStrip && (viewH <= 0 || viewH == Int.MAX_VALUE)) return bitmap

        val fit = if (tallStrip) {
            viewW / bitmap.width.toFloat()
        } else {
            min(viewW / bitmap.width.toFloat(), viewH / bitmap.height.toFloat())
        }
        if (fit <= 0f || fit >= TRIGGER) return bitmap

        val tw = max(1, (bitmap.width * fit).roundToInt()).coerceAtMost(bitmap.width)
        val th = max(1, (bitmap.height * fit).roundToInt()).coerceAtMost(bitmap.height)
        if (tw == bitmap.width && th == bitmap.height) return bitmap

        return try {
            nativeAreaDownscaleTo(bitmap, tw, th, (try { SrCore.settings.areaDownscaleStrength() } catch (_: Throwable) { 100 }) / 100f)
        } catch (_: Throwable) {
            bitmap
        } ?: bitmap
    }

    private external fun nativeAreaDownscaleTo(
        bitmap: Bitmap, dstWidth: Int, dstHeight: Int, strength: Float,
    ): Bitmap
}
