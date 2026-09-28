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

    fun hide() {
        visibility = View.GONE
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    fun applyState(enabled: Boolean, presetId: Int, alphaPercent: Int, concentration: Int, context: Context) {
        Log.d("PaperTexture", "applyState enabled=$enabled preset=$presetId alpha=$alphaPercent conc=$concentration")
        if (!enabled || alphaPercent <= 0) {
            hide()
            return
        }
        PaperTexture.peek(presetId)?.let { bind(it, alphaPercent, concentration); return }
        hide()
        PaperTexture.load(context, presetId) { id, bitmap ->
            if (enabled && presetId == id && bitmap != null) {
                post { bind(bitmap, alphaPercent, concentration) }
            }
        }
    }

    private fun bind(bitmap: Bitmap, alphaPercent: Int, concentration: Int) {
        Log.d("PaperTexture", "bind ${bitmap.width}x${bitmap.height} alpha=$alphaPercent conc=$concentration")
        paint.shader = android.graphics.BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
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
