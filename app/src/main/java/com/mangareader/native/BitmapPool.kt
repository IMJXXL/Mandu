package com.mangareader.native

import android.graphics.Bitmap
import java.util.concurrent.ConcurrentLinkedQueue

object BitmapPool {
    private val pool = ConcurrentLinkedQueue<Bitmap>()
    private var maxSize = 10

    fun setSize(size: Int) { maxSize = size }

    fun acquire(width: Int, height: Int, config: Bitmap.Config = Bitmap.Config.RGB_565): Bitmap {
        // Try to reuse a bitmap from pool
        while (true) {
            val bmp = pool.poll() ?: break
            if (!bmp.isRecycled && bmp.width == width && bmp.height == height && bmp.config == config) {
                bmp.eraseColor(0)
                return bmp
            }
            if (!bmp.isRecycled) bmp.recycle()
        }
        // Create new if pool empty
        return Bitmap.createBitmap(width, height, config)
    }

    fun release(bmp: Bitmap?) {
        if (bmp == null || bmp.isRecycled) return
        if (pool.size < maxSize) {
            pool.offer(bmp)
        } else {
            bmp.recycle()
        }
    }

    fun clear() {
        while (true) {
            val bmp = pool.poll() ?: break
            if (!bmp.isRecycled) bmp.recycle()
        }
    }
}
