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