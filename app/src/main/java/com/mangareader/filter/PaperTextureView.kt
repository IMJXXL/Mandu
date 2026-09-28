package com.mangareader.filter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.view.View

class PaperTextureView(context: Context) : View(context) {

    private val paint = Paint()

    fun hide() {
        visibility = View.GONE
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    fun applyState(enabled: Boolean, presetId: Int, alphaPercent: Int, context: Context) {
        if (!enabled || alphaPercent <= 0) {
            hide()
            return
        }
        PaperTexture.peek(presetId)?.let { bind(it, alphaPercent); return }
        hide()
        PaperTexture.load(context, presetId) { id, bitmap ->
            if (enabled && presetId == id && bitmap != null) {
                post { bind(bitmap, alphaPercent) }
            }
        }
    }

    private fun bind(bitmap: Bitmap, alphaPercent: Int) {
        paint.shader = android.graphics.BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        paint.alpha = (alphaPercent * 255 / 100).coerceIn(0, 255)
        visibility = View.VISIBLE
        invalidate()
    }
}
