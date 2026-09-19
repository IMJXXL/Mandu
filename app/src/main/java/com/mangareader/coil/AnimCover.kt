package com.mangareader.coil

import android.graphics.BitmapFactory
import coil3.asImage
import android.graphics.drawable.BitmapDrawable
import coil3.ImageLoader
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlin.math.max

/**
 * APNG 封面静态门（对齐上游动图封面水平，2026-09-19）：
 * GIF/动图WebP 由平台 AnimatedImageDecoder 播放（按请求尺寸逐帧解码，帧小才流畅）；
 * APNG 平台不支持 → 落到全局 AnimatedApngDecoder（apng4android）会按**原始分辨率**逐帧
 * 软件解码，一张大 APNG 封面就能吃满 CPU（上游废弃同类自定义解码器的同一死因）。
 *
 * 封面请求挂本工厂：只认领 APNG → 解**静态首帧**（BitmapFactory 按请求尺寸采样），
 * 其余格式返回 null 落回全局组件（GIF/AWebP 动图、静态图走平台 BitmapFactoryDecoder）。
 */
object ApngFirstFrameDecoder : Decoder.Factory {

    override fun create(
        result: SourceFetchResult,
        options: Options,
        imageLoader: ImageLoader,
    ): Decoder? {
        if (!isApngSource(result.source.source())) return null
        return ApngFirstFrame(result, options)
    }

    private class ApngFirstFrame(
        private val result: SourceFetchResult,
        private val options: Options,
    ) : Decoder {
        override suspend fun decode(): DecodeResult {
            val bytes = result.source.source().readByteArray()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            val maxEdge = max(bounds.outWidth, bounds.outHeight)
            val target = listOfNotNull(
                (options.size.width as? coil3.size.Dimension.Pixels)?.px,
                (options.size.height as? coil3.size.Dimension.Pixels)?.px,
            ).maxOrNull() ?: 1024
            while (maxEdge / (sample * 2) >= target) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: throw IllegalStateException("apng first frame decode failed")
            val drawable = BitmapDrawable(options.context?.resources, bmp)
            return DecodeResult(drawable.asImage(), false)
        }
    }
}
