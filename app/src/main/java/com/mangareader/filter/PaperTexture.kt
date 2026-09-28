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
import android.view.View
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object PaperTexture {
    val PRESETS = arrayOf(
        "paper_cotton.webp" to "棉纸（细纹）",
        "paper_cotton2.webp" to "棉纸（粗纹）",
        "paper_kraft.webp" to "牛皮纸",
        "paper_kraft2.webp" to "牛皮纸2",
        "paper_kraft_box.webp" to "牛皮纸盒",
        "paper_sketch.webp" to "素描纸",
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
            } catch (_: Exception) {
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
}