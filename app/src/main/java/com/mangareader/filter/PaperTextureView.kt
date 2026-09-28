package com.mangareader.filter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Shader
import android.util.Log
import android.view.View

class PaperTextureView(context: Context) : View(context) {

    private val paint = Paint()
    private var lastRequested: String? = null
    private var lastBound: String? = null

    fun hide() {
        lastBound = null
        visibility = View.GONE
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    fun applyState(enabled: Boolean, presetId: Int, alphaPercent: Int, concentration: Int, context: Context) {
        val id = presetId.coerceIn(0, PaperTexture.PRESETS.size - 1)
        val req = "$enabled/$id/$alphaPercent/$concentration"
        // AndroidView update 每次重组都会调到这里，状态没变直接返回，避免反复重建 Shader/ColorMatrix 和刷日志
        if (req == lastRequested && visibility == View.VISIBLE) return
        lastRequested = req
        Log.d("PaperTexture", "applyState enabled=$enabled preset=$id alpha=$alphaPercent conc=$concentration")
        if (!enabled || alphaPercent <= 0) {
            hide()
            return
        }
        PaperTexture.peek(id)?.let { bind(it, id, alphaPercent, concentration); return }
        hide()
        PaperTexture.load(context, id) { bid, bitmap ->
            if (enabled && id == bid && bitmap != null) {
                post { bind(bitmap, bid, alphaPercent, concentration) }
            }
        }
    }

    private fun bind(bitmap: Bitmap, presetId: Int, alphaPercent: Int, concentration: Int) {
        val key = "$presetId/$alphaPercent/$concentration/${System.identityHashCode(bitmap)}"
        if (key == lastBound && visibility == View.VISIBLE) return
        lastBound = key
        Log.d("PaperTexture", "bind ${bitmap.width}x${bitmap.height} preset=$presetId alpha=$alphaPercent conc=$concentration")
        paint.shader = android.graphics.BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        // 正片叠底合成（设置里勾选）：墨不被纸纹洗白，物理正确像真书；父容器需开硬件层配合。
        // 透明度滑条此时语义=纸感强度（乘法结果向原页插值）。
        paint.xfermode = if (context.getSharedPreferences("manga_reader", Context.MODE_PRIVATE)
                .getBoolean("texture_multiply", false))
            android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.MULTIPLY) else null
        paint.alpha = (alphaPercent * 255 / 100).coerceIn(0, 255)
        val s = concentration / 100f
        paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            s, 0f, 0f, 0f, 128f * (1 - s),
            0f, s, 0f, 0f, 128f * (1 - s),
            0f, 0f, s, 0f, 128f * (1 - s),
            0f, 0f, 0f, 1f, 0f
        )))
        visibility = View.VISIBLE
        invalidate()
    }
}
