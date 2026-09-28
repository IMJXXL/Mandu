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

    fun applyState(enabled: Boolean, presetId: Int, alphaPercent: Int, concentration: Int, multiply: Boolean, context: Context) {
        val id = presetId.coerceIn(0, PaperTexture.PRESETS.size - 1)
        // multiply 必须进去重键：曾经开关正片叠底因参数没变被 dedup 拦住，画面纹丝不动
        val req = "$enabled/$id/$alphaPercent/$concentration/$multiply"
        // AndroidView update 每次重组都会调到这里，状态没变直接返回，避免反复重建 Shader/ColorMatrix 和刷日志
        if (req == lastRequested && visibility == View.VISIBLE) return
        lastRequested = req
        Log.d("PaperTexture", "applyState enabled=$enabled preset=$id alpha=$alphaPercent conc=$concentration multiply=$multiply")
        if (!enabled || (if (multiply) concentration <= 0 else alphaPercent <= 0)) {
            hide()
            return
        }
        PaperTexture.peek(id)?.let { bind(it, id, alphaPercent, concentration, multiply); return }
        hide()
        PaperTexture.load(context, id) { bid, bitmap ->
            if (enabled && id == bid && bitmap != null) {
                post { bind(bitmap, bid, alphaPercent, concentration, multiply) }
            }
        }
    }

    private fun bind(bitmap: Bitmap, presetId: Int, alphaPercent: Int, concentration: Int, multiply: Boolean) {
        val key = "$presetId/$alphaPercent/$concentration/$multiply/${System.identityHashCode(bitmap)}"
        if (key == lastBound && visibility == View.VISIBLE) return
        lastBound = key
        Log.d("PaperTexture", "bind ${bitmap.width}x${bitmap.height} preset=$presetId alpha=$alphaPercent conc=$concentration multiply=$multiply")
        // MIRROR 镜像平铺：镜像边界差恒为 0，实拍照片（非周期）也严格无缝；REPEAT 下
        // 棉纸2/素描纸接缝实测比自然纹理重 1.6~1.9 倍，全强度乘法会显形成直线
        paint.shader = android.graphics.BitmapShader(bitmap, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
        if (multiply) {
            // 正片叠底（物理正确像真书）：纸纹是反射率图，page×refl。墨是 0，乘任何纸纹仍是纯黑。
            // 关键：alpha 必须 255 —— 之前沿用透明度滑条值（默认15%），乘法结果被往回插值
            // 85%，纹理调制量只剩 1~2%，这就是"纹理很淡没纸感"的根因。
            // 浓度滑条改为锚白缩放：refl' = 255(1-s) + refl·s，s=0 无纹理，s=1 全纸感。
            paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.MULTIPLY)
            paint.alpha = 255
            val s = concentration / 100f
            val w = 255f * (1 - s)
            paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                s, 0f, 0f, 0f, w,
                0f, s, 0f, 0f, w,
                0f, 0f, s, 0f, w,
                0f, 0f, 0f, 1f, 0f
            )))
        } else {
            // 普通混合（后备路径，硬件层异常设备逃生用）：alpha 混合会把墨洗灰，透明度控制
            // 叠加强度，对比度矩阵锚 128 增强图案
            paint.xfermode = null
            paint.alpha = (alphaPercent * 255 / 100).coerceIn(0, 255)
            val s = concentration / 100f
            paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                s, 0f, 0f, 0f, 128f * (1 - s),
                0f, s, 0f, 0f, 128f * (1 - s),
                0f, 0f, s, 0f, 128f * (1 - s),
                0f, 0f, 0f, 1f, 0f
            )))
        }
        visibility = View.VISIBLE
        invalidate()
    }
}
