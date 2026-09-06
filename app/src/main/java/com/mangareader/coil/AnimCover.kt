package com.mangareader.coil

import android.graphics.BitmapFactory
import coil3.asImage
import android.graphics.drawable.BitmapDrawable
import coil3.ImageLoader
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlin.math.max

/**
 * 静态首帧解码器（「动态封面」关闭时使用，2026-09-06）：
 * 对动图字节只解第一帧位图（BitmapFactory 天然取首帧），按请求尺寸采样。
 * 动态封面开启时不挂此解码器，由 Coil 全局动图解码器播放动画
 * （与参考项目一致：coverThumb 小尺寸解码，帧小才流畅）。
 */
class StaticFirstFrameDecoder(
    private val result: SourceFetchResult,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val bytes = result.source.source().readByteArray()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        val maxEdge = max(bounds.outWidth, bounds.outHeight)
        // 目标：最长边 ≤ 1024（书架缩略图足够，帧小才流畅）
        while (maxEdge / (sample * 2) >= 1024) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: throw IllegalStateException("static cover decode failed")
        val drawable = BitmapDrawable(options.context?.resources, bmp)
        return DecodeResult(drawable.asImage(), false)
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder =
            StaticFirstFrameDecoder(result, options)
    }
}

private fun Int.toPx(): Int = if (this <= 0) 1024 else this
