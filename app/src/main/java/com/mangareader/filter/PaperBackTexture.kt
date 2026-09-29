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
 * 米白底 + 当前纸张 preset 的反射纹理 multiply，无漫画内容。
 * 按 (presetId, 屏幕宽高) 缓存一张，双页/单页 curl 共用。
 *
 * 与页面烘纹理（PaperPageTexture）同源不同途：页面是"内容×纹理"，纸背是"米白×纹理"，
 * 让翻起的页背面看起来是这本书用纸的背面。
 */
object PaperBackTexture {

    private var cached: Bitmap? = null
    private var cachedKey = ""

    /** 取纸背图（不存在或 preset/尺寸变化时烘焙；同步路径——curl 组装在 IO 线程或首次组合） */
    @Synchronized
    fun get(context: Context, presetId: Int, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val key = "$presetId:${width}x${height}"
        if (key == cachedKey) return cached
        val raw = PaperTexture.loadSync(context, presetId) ?: return null
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // 米白纸底（暖调，与纸纹 mean≈247 的亮度校准匹配）
        canvas.drawColor(Color.rgb(0xF2, 0xEC, 0xE1))
        // 纹理 multiply：反射图叠上去，高光区≈无变化、暗纹区压出纸纹
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        }
        val src = raw
        val scale = maxOf(width.toFloat() / src.width, height.toFloat() / src.height)
        val dw = (src.width * scale).toInt()
        val dh = (src.height * scale).toInt()
        // 镜像平铺避免接缝（与 PaperPageTexture 同策略）
        var x = 0
        var flip = false
        while (x < width) {
            var y = 0
            var vFlip = false
            while (y < height) {
                val m = Paint(paint)
                m.alpha = 255
                canvas.save()
                canvas.translate(x.toFloat(), y.toFloat())
                canvas.scale(if (flip) -1f else 1f, if (vFlip) -1f else 1f)
                canvas.drawBitmap(src, 0f, 0f, m)
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
