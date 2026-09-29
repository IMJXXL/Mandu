package com.mangareader.filter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode

/**
 * 仿真翻页纸背（2026-09-29）：给 pagecurl 背面用的"纯纸纹"图——
 * 米白底 + 当前纸张 preset 的反射纹理（线性映射增强后）multiply，无漫画内容。
 * 按 (presetId, 屏幕宽高) 缓存一张，单页/双页 curl 共用。
 *
 * 数学：assets 纹理是"反射图"（明度均值 ≈247 的近白图），直接 multiply 到底色
 * 纹理只剩 ~3% 变化（纯白观感的根因）。绘制时挂线性映射 v'=2.08v-300——
 * 反射图 230~255 的纹路带 → 178~230（底色的 70%~90%），multiply 后纸纹清晰不脏。
 */
object PaperBackTexture {

    private var cached: Bitmap? = null
    private var cachedKey = ""

    /** 缓存快查（主线程安全，零 IO） */
    @Synchronized
    fun peek(presetId: Int, width: Int, height: Int, dark: Boolean = false): Bitmap? {
        if (width <= 0 || height <= 0) return null
        return if ("$presetId:${width}x${height}:$dark" == cachedKey) cached else null
    }

    /** 取纸背图（未缓存则烘焙；调用方应在后台线程——首帧允许短暂无纸背，随后回填）。
     *  dark=true 深色主题：深灰纸底，避免翻页背面亮块闪眼。 */
    @Synchronized
    fun get(context: Context, presetId: Int, width: Int, height: Int, dark: Boolean = false): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val key = "$presetId:${width}x${height}:$dark"
        if (key == cachedKey) return cached
        val raw = PaperTexture.loadSync(context, presetId) ?: return null
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // 米白纸底（暖调）
        canvas.drawColor(if (dark) Color.rgb(0x2B, 0x28, 0x24) else Color.rgb(0xF2, 0xEC, 0xE1))
        // multiply + 线性映射一步完成（无中间位图）
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            colorFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(
                2.08f, 0f, 0f, 0f, -300f,
                0f, 2.08f, 0f, 0f, -300f,
                0f, 0f, 2.08f, 0f, -300f,
                0f, 0f, 0f, 1f, 0f,
            ))
        }
        val scale = maxOf(width.toFloat() / raw.width, height.toFloat() / raw.height)
        val dw = (raw.width * scale).toInt()
        val dh = (raw.height * scale).toInt()
        // 镜像平铺避免接缝（与 PaperPageTexture 同策略）
        var x = 0
        var flip = false
        while (x < width) {
            var y = 0
            var vFlip = false
            while (y < height) {
                canvas.save()
                canvas.translate(x.toFloat(), y.toFloat())
                canvas.scale(if (flip) -1f else 1f, if (vFlip) -1f else 1f)
                canvas.drawBitmap(raw, 0f, 0f, paint)
                canvas.restore()
                y += dh
                vFlip = !vFlip
            }
            x += dw
            flip = !flip
        }
        cached = bmp
        cachedKey = key
        return bmp
    }
}
