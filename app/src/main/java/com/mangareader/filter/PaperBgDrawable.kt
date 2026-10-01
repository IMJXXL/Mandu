package com.mangareader.filter

import com.mangareader.R

import android.content.Context
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View

/**
 * ★ 整屏幕纸纹（textureScope=1）：阅读器背景用——先铺背景色，再以「仅图片」同款
 *   浓度矩阵 MULTIPLY 叠纸纹（真逐像素正片叠底，同画布内成立）。页面位图由
 *   PaperPageTexture 照旧烘焙，两层合成 = 全屏纸感。
 *   单实例可共享给一个视图的所有 holder（bounds 由各 view 自设）；纹理异步加载到位后自刷。
 */
class PaperBgDrawable(private val appContext: Context) : Drawable() {

    private var bg = 0
    private var presetId = -1
    private var paint: Paint? = null
    private var loadedPreset = -1

    fun update(bgColor: Int, preset: Int, concentration: Int) {
        var dirty = false
        if (bg != bgColor) { bg = bgColor; dirty = true }
        if (presetId != preset) { presetId = preset; paint = null; loadedPreset = -1; load(); dirty = true }
        if (dirty) rebuild(concentration)
        invalidateSelf()
    }

    private fun load() {
        val p = presetId
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        PaperTexture.load(appContext, p) { id, bmp ->
            // 异步回调可能跑在 PaperTexture 的加载线程（appContext 无 Activity 分发）——
            // paint/invalidateSelf 必须回主线程，否则已 attach 视图抛 CalledFromWrongThreadException
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                applyLoaded(id, bmp)
            } else {
                main.post { applyLoaded(id, bmp) }
            }
        }
    }

    private fun applyLoaded(id: Int, bmp: android.graphics.Bitmap?) {
        if (id != presetId || bmp == null || bmp.isRecycled) return
        paint = Paint().apply {
            shader = BitmapShader(bmp, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
            isFilterBitmap = true
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.MULTIPLY)
        }
        loadedPreset = id
        rebuild(PaperPageTexture.concentration)
        invalidateSelf()
    }

    private fun rebuild(concentration: Int) {
        val p = paint ?: return
        val s = concentration.coerceIn(0, 100) / 100f
        val w = 255f * (1 - s)
        p.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            s, 0f, 0f, 0f, w,
            0f, s, 0f, 0f, w,
            0f, 0f, s, 0f, w,
            0f, 0f, 0f, 1f, 0f
        )))
    }

    override fun draw(canvas: Canvas) {
        if (loadedPreset != presetId) { canvas.drawColor(bg); return }
        canvas.drawColor(bg)
        paint?.let { canvas.drawRect(bounds, it) }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /** 视图背景统一入口：整屏幕开 → 纹理 Drawable（复用挂 view.tag 上）；关 → 纯色 */
        fun apply(v: View, appContext: Context, bgColor: Int, on: Boolean, preset: Int, concentration: Int) {
            if (on && PaperPageTexture.enabled) {
                val d = (v.getTag(R.id.tag_paper_bg) as? PaperBgDrawable) ?: PaperBgDrawable(appContext).also {
                    v.setTag(R.id.tag_paper_bg, it)
                }
                d.update(bgColor, preset, concentration)
                v.background = d
            } else {
                if (v.getTag(R.id.tag_paper_bg) != null) {
                    v.setTag(R.id.tag_paper_bg, null)
                    v.background = null
                }
                v.setBackgroundColor(bgColor)
            }
        }
    }
}
