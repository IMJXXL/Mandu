package com.mangareader.filter

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.Log
import android.view.View
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object PaperTexture {
    // 追加新预设只能放数组末尾：texturePresetId 持久化的是数组下标，中间插入会让已保存选择错位
    val PRESETS = arrayOf(
        "textures/paper_cotton.webp" to "棉纸（细纹）",
        "textures/paper_cotton2.webp" to "棉纸（粗纹）",
        "textures/paper_kraft.webp" to "牛皮纸",
        "textures/paper_kraft2.webp" to "牛皮纸2",
        "textures/paper_kraft_box.webp" to "牛皮纸盒",
        "textures/paper_sketch.webp" to "素描纸",
        "textures/paper_xuan.webp" to "宣纸",
        "textures/paper_cream.webp" to "米白纤维",
        "textures/paper_woodfree.webp" to "道林纸",
        "textures/paper_laid.webp" to "罗纹纸",
        "textures/paper_newsprint.webp" to "新闻纸",
        "textures/paper_oldpage.webp" to "仿旧书页",
        "textures/paper_real.webp" to "书卷纸",
    )

    private val cache = HashMap<Int, Bitmap>()
    private val pending = HashSet<Int>()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * ★ 各预设整图平均 RGB（离线对 64×64 缩图取样 embed，运行时零开销）：仿真翻页背面纸底贴色用。
     *   顺序与 [PRESETS] 一致；追加新预设时同步补一行。
     */
    val PRESET_MEAN_RGB = arrayOf(
        intArrayOf(245, 236, 216), // 棉纸（细纹）
        intArrayOf(246, 234, 214), // 棉纸（粗纹）
        intArrayOf(246, 185, 117), // 牛皮纸
        intArrayOf(243, 175, 112), // 牛皮纸2
        intArrayOf(245, 205, 159), // 牛皮纸盒
        intArrayOf(240, 239, 234), // 素描纸
        intArrayOf(246, 243, 233), // 宣纸
        intArrayOf(246, 245, 238), // 米白纤维
        intArrayOf(247, 245, 241), // 道林纸
        intArrayOf(246, 243, 235), // 罗纹纸
        intArrayOf(246, 245, 237), // 新闻纸
        intArrayOf(245, 235, 204), // 仿旧书页
        intArrayOf(247, 246, 239), // 书卷纸
    )

    /**
     * ★ 背面纸底贴色：纸纹以正片叠底烘进页面位图（有效纹理=s·tex+255(1-s)，MULTIPLY），
     *   背面镜像内容因此自带纹理色调——纸底取「背景色 × 同款折算平均反射率」即与正面
     *   纸调一致；透出滑条任意位置两层同色叠加结果恒为目标色，无需透明度补偿。
     *   逐通道 m = (s·M + 255(1-s))/255，结果 = base·m，alpha 恒 255。
     */
    fun texturedTone(base: Int, presetId: Int, concentration: Int): Int {
        val id = presetId.coerceIn(0, PRESETS.size - 1)
        val mean = PRESET_MEAN_RGB[id]
        val s = concentration.coerceIn(0, 100) / 100f
        fun ch(v: Int, m: Int): Int {
            val eff = s * m + 255f * (1 - s)
            return (v * eff / 255f).toInt().coerceIn(0, 255)
        }
        return android.graphics.Color.rgb(
            ch((base shr 16) and 0xFF, mean[0]),
            ch((base shr 8) and 0xFF, mean[1]),
            ch(base and 0xFF, mean[2]),
        )
    }

    fun load(context: Context, presetId: Int, onReady: (Int, Bitmap?) -> Unit) {
        val id = presetId.coerceIn(0, PRESETS.size - 1)
        synchronized(cache) {
            cache[id]?.let { onReady(id, it); return }
            if (!pending.add(id)) return
        }
        executor.execute {
            val bitmap = try {
                BitmapFactory.decodeStream(context.assets.open(PRESETS[id].first))
            } catch (e: Exception) {
                Log.e("PaperTexture", "decode failed: ${PRESETS[id].first}", e)
                null
            }
            if (bitmap != null) {
                synchronized(cache) { cache[id] = bitmap }
            }
            synchronized(pending) { pending.remove(id) }
            (context as? Activity)?.runOnUiThread { onReady(id, bitmap) }
                ?: onReady(id, bitmap)
        }
    }

    fun peek(presetId: Int): Bitmap? = synchronized(cache) { cache[presetId] }

    /** 解码管线同步加载（后台 IO 线程调用；与异步 load 共用缓存，竞态最多重复解码一次） */
    fun loadSync(context: Context, presetId: Int): Bitmap? {
        val id = presetId.coerceIn(0, PRESETS.size - 1)
        synchronized(cache) { cache[id]?.let { return it } }
        val bitmap = try {
            BitmapFactory.decodeStream(context.assets.open(PRESETS[id].first))
        } catch (e: Exception) {
            Log.e("PaperTexture", "decode failed: ${PRESETS[id].first}", e)
            null
        }
        if (bitmap != null) synchronized(cache) { cache[id] = bitmap }
        return bitmap
    }
}