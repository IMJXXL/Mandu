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

/**
 * 纸张纹理烘焙：把纸纹以正片叠底乘进「页面位图本身」，在各阅读器解码管线末端
 * （裁白→超分→墨水屏抖动之后、入缓存之前）调用 [apply]。
 *
 * 为什么烘进位图而不是屏幕覆盖层：覆盖层钉在屏幕上——翻页时纸动纹理不动（像玻璃），
 * 且盖住留边/页边距。烘进位图后纹理就是纸：随页翻动、随缩放、条漫随滚动，
 * 天然只作用于实际画面，且每帧零额外合成成本（反而省掉了原覆盖层的全屏 Xfermode）。
 */
object PaperPageTexture {

    @Volatile var enabled: Boolean = false
    @Volatile var presetId: Int = 0
    @Volatile var concentration: Int = 100
    /** ★ 生效范围：false=仅图片（只烘焙页面位图，默认） true=整屏幕（页面照旧烘焙 + 阅读器背景铺纸纹） */
    @Volatile var overlayMode: Boolean = false

    @Volatile private var appContext: Context? = null
    @Volatile private var tex: Bitmap? = null
    @Volatile private var texId: Int = -1

    /** 阅读器 setup 时同步设置（幂等）；运行时变更由 NativeReaderScreen 触发各视图清缓存重载 */
    fun sync(context: Context, newEnabled: Boolean, newPresetId: Int, newConcentration: Int, newOverlayMode: Boolean = false) {
        if (appContext == null) appContext = context.applicationContext
        enabled = newEnabled
        presetId = newPresetId
        concentration = newConcentration
        overlayMode = newOverlayMode
    }

    /**
     * 解码管线末端调用。返回烘焙后的位图；禁用/失败返回原位图。
     *
     * ★ 0930 夜修「换纸张叠加/关闭不消失」：烘焙一律落在副本上，永不原地改写输入——
     *   原实现命中可变位图（超分产物）时直接在原位图上叠纹，而 SrPipeline 缓存长期持有
     *   同一实例：换纸张→视图清缓存重载→SR 命中同一实例→再烘=叠加；关纹理后该实例
     *   已带纹照样被返回=关不掉。副本化后输入恒清洁，任意换纸张/开关都从干净源重烘。
     *   （代价：每次烘焙多一次整页拷贝；不可变解码位图原本就走拷贝，仅 SR 命中路径多一份）
     */
    fun apply(bmp: Bitmap): Bitmap {
        if (!enabled || bmp.isRecycled) return bmp
        val texBmp = texture() ?: return bmp
        val target = try {
            bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, true)
        } catch (_: Throwable) {
            return bmp
        }
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
        // 登记烘焙产物实例：SrPipeline 据此拒绝把烘焙图注册进 originals（防纹理平方）
        synchronized(bakedInstances) { bakedInstances.add(target) }
        return target
    }

    /** 该位图是否为烘焙产物（弱引用登记，位图回收后条目自动清除） */
    fun isBaked(bmp: Bitmap): Boolean = synchronized(bakedInstances) { bakedInstances.contains(bmp) }

    private val bakedInstances = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Bitmap, Boolean>())

    private fun texture(): Bitmap? {
        val id = presetId.coerceIn(0, PaperTexture.PRESETS.size - 1)
        tex?.let { if (texId == id && !it.isRecycled) return it }
        val ctx = appContext ?: return null
        return PaperTexture.loadSync(ctx, id)?.also { tex = it; texId = id }
    }
}
