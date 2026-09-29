package com.mangareader.filter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.BitmapShader
import java.util.Collections
import java.util.WeakHashMap

/**
 * 纸张纹理烘焙：把纸纹以正片叠底乘进「页面位图本身」，在各阅读器解码管线末端
 * （裁白→超分→墨水屏抖动之后、入缓存之前）调用 [apply]。
 *
 * 为什么烘进位图而不是屏幕覆盖层：覆盖层钉在屏幕上——翻页时纸动纹理不动（像玻璃），
 * 且盖住留边/页边距。烘进位图后纹理就是纸：随页翻动、随缩放、条漫随滚动，
 * 天然只作用于实际画面，且每帧零额外合成成本（反而省掉了原覆盖层的全屏 Xfermode）。
 *
 * generation 版本戳：设置变更自增，避免共享位图（PDF 共享缓存页、跨章 adopt 位图）
 * 在不同入口被重复叠纹；WeakHashMap 以位图实例记录已贴版本。
 */
object PaperPageTexture {

    @Volatile var enabled: Boolean = false
    @Volatile var presetId: Int = 0
    @Volatile var concentration: Int = 100
    @Volatile var generation: Int = 0

    @Volatile private var appContext: Context? = null
    @Volatile private var tex: Bitmap? = null
    @Volatile private var texId: Int = -1
    private val stamped = Collections.synchronizedMap(WeakHashMap<Bitmap, Int>())

    /** 阅读器 setup 时同步设置（幂等；值变化才升代数） */
    fun sync(context: Context, newEnabled: Boolean, newPresetId: Int, newConcentration: Int) {
        if (appContext == null) appContext = context.applicationContext
        val changed = enabled != newEnabled || presetId != newPresetId || concentration != newConcentration
        enabled = newEnabled
        presetId = newPresetId
        concentration = newConcentration
        if (changed) generation++
    }

    /** 设置运行时变更（阅读内面板拖动）：升代数让所有已贴位图视为未贴 */
    fun bumpGeneration() {
        generation++
    }

    /** 解码管线末端调用。返回值可能为原位图（禁用/已贴/失败）或新位图（原位图不可变时复制）。 */
    fun apply(bmp: Bitmap): Bitmap {
        if (!enabled || bmp.isRecycled) return bmp
        if (stamped[bmp] == generation) return bmp
        val texBmp = texture() ?: return bmp
        val target = if (bmp.isMutable) bmp else mutableCopy(bmp) ?: return bmp
        try {
            val s = concentration.coerceIn(0, 100) / 100f
            val w = 255f * (1 - s)
            val paint = Paint().apply {
                // MIRROR 镜像平铺：任何纹理严格无缝（实拍照片非周期）
                shader = BitmapShader(texBmp, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
                isFilterBitmap = true
                xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                alpha = 255
                colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                    s, 0f, 0f, 0f, w,
                    0f, s, 0f, 0f, w,
                    0f, 0f, s, 0f, w,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            Canvas(target).drawRect(0f, 0f, target.width.toFloat(), target.height.toFloat(), paint)
        } catch (_: Throwable) {
            return bmp
        }
        stamped[target] = generation
        return target
    }

    private fun mutableCopy(bmp: Bitmap): Bitmap? = try {
        bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, true)
    } catch (_: Throwable) {
        try { bmp.copy(Bitmap.Config.ARGB_8888, true) } catch (_: Throwable) { null }
    }

    private fun texture(): Bitmap? {
        val id = presetId.coerceIn(0, PaperTexture.PRESETS.size - 1)
        tex?.let { if (texId == id && !it.isRecycled) return it }
        val ctx = appContext ?: return null
        return PaperTexture.loadSync(ctx, id)?.also { tex = it; texId = id }
    }
}
