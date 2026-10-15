package com.mangareader.cloudcommon.folder

import android.graphics.Bitmap
import android.graphics.BitmapFactory

object DownsampleFallback {

    private const val LARGE_IMAGE_THRESHOLD = 10 * 1024 * 1024

    fun decode(raw: ByteArray): Bitmap? {
        return try {
            BitmapFactory.decodeByteArray(raw, 0, raw.size)
        } catch (e: OutOfMemoryError) {
            System.gc()
            decodeWithDownsample(raw)
        }
    }

    fun decodeWithDownsample(raw: ByteArray): Bitmap? {
        var sampleSize = 2
        while (sampleSize <= 8) {
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                return BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
            } catch (_: OutOfMemoryError) {
                System.gc()
                sampleSize *= 2
            }
        }
        return null
    }

    fun isLargeImage(size: Int): Boolean = size > LARGE_IMAGE_THRESHOLD
}