package com.mangareader.coil

import android.graphics.BitmapFactory
import androidx.core.graphics.decodeDrawable
import androidx.core.util.component1
import androidx.core.util.component2
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import coil3.asImage
import android.graphics.drawable.BitmapDrawable
import coil3.ImageLoader
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.maxBitmapSize
import coil3.util.component1
import coil3.util.component2
import com.hippo.ehviewer.Settings
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * 封面动图总闸（2026-09-19）——书架/子项网格封面请求挂本工厂，替代 coil 默认分发：
 *
 * - APNG：一律静态首帧。平台 AnimatedImageDecoder 不支持 APNG，落到全局
 *   AnimatedApngDecoder（apng4android）会按**原始分辨率**逐帧软件解码，一张大 APNG
 *   封面就能吃满 CPU（上游废弃同类自定义解码器的同一死因）。
 * - GIF / 动图 WebP：[Settings.coverAnimated] 开启时走 [AnimatedCover]——ImageDecoder
 *   按"请求尺寸与 [ANIM_COVER_MAX] 取小"逐帧缩放解码（帧小才流畅），并登记
 *   [CoverAnimLimiter] 限制全局同时播放数量；关闭时解静态首帧。
 * - 其余格式返回 null 落回全局组件（静态图走平台 BitmapFactoryDecoder）。
 *
 * GIF/动图WebP 魔数嗅探：GIF87a/89a；RIFF..WEBP 且含 'ANIM' chunk（静态 WebP 不认领，
 * 走平台静态解码）。APNG 判定复用 [isApngSource]。
 */
object CoverAnimGateDecoder : Decoder.Factory {

    /** 动图封面帧的最长边上限：帧越小逐帧解码/上屏越便宜（显示尺寸约 300-420px） */
    const val ANIM_COVER_MAX = 512

    /**
     * ★ 封面动图总开关（2026-09-19 封存）：用户裁定全线下线恢复静态封面。
     * 必须与 [Settings.coverAnimated] 相与——封存只改了默认值，老用户 DataStore 里
     * 残留的 cover_animated=true 会把浏览页动图全部复活；此常量保证代码层永远关死，
     * 重新启用时翻回 true 即可（代码全保留）。
     */
    const val COVER_ANIM_ENABLED = false

    override fun create(
        result: SourceFetchResult,
        options: Options,
        imageLoader: ImageLoader,
    ): Decoder? {
        val source = result.source.source()
        return when {
            isApngSource(source) -> StaticFirstFrame(result, options)
            isAnimatedGifOrWebP(source) && COVER_ANIM_ENABLED && Settings.coverAnimated.value -> AnimatedCover(result, options)
            isAnimatedGifOrWebP(source) -> StaticFirstFrame(result, options)
            else -> null
        }
    }

    private fun isAnimatedGifOrWebP(source: okio.BufferedSource): Boolean = try {
        source.peek().use { peek ->
            if (!peek.request(12)) return false
            val head = peek.readByteArray(12)
            if (head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte()) {
                return true
            }
            if (head[0] == 'R'.code.toByte() && head[8] == 'W'.code.toByte() &&
                head[9] == 'E'.code.toByte() && head[10] == 'B'.code.toByte() && head[11] == 'P'.code.toByte()
            ) {
                // 动图 WebP：VP8X 后的 flag bit1=animation；宽限起见在前 64 字节找 'ANIM' chunk
                if (!peek.request(64)) return false
                val probe = peek.readByteArray(64)
                for (i in 12..probe.size - 4) {
                    if (probe[i] == 'A'.code.toByte() && probe[i + 1] == 'N'.code.toByte() &&
                        probe[i + 2] == 'I'.code.toByte() && probe[i + 3] == 'M'.code.toByte()
                    ) {
                        return true
                    }
                }
                // 64 字节内没有 ANIM：VP8X flag 判定（offset 20 的 bit1）
                return probe.size >= 21 && (probe[20].toInt() and 0x02) != 0
            }
            false
        }
    } catch (_: Exception) {
        false
    }

    /** 动图播放：ImageDecoder 按上限尺寸解码 + 全局同时播放限流。 */
    private class AnimatedCover(
        private val result: SourceFetchResult,
        private val options: Options,
    ) : Decoder {
        override suspend fun decode(): DecodeResult {
            val drawable = runInterruptible(Dispatchers.Default) {
                val bytes = result.source.source().readByteArray()
                // ImageDecoder.createSource(ByteBuffer) 要求直接缓冲区（coil 的
                // squashToDirectByteBuffer 同因）——堆缓冲会解码失败致封面灰块
                val buf = ByteBuffer.allocateDirect(bytes.size).put(bytes)
                buf.flip()
                val src = ImageDecoder.createSource(buf)
                src.decodeDrawable { info, _ ->
                    val (srcW, srcH) = info.size
                    val (dstW, dstH) = DecodeUtils.computeDstSize(
                        srcWidth = srcW,
                        srcHeight = srcH,
                        targetSize = options.size,
                        scale = options.scale,
                        maxSize = options.maxBitmapSize,
                    )
                    var tw = dstW
                    var th = dstH
                    val longEdge = max(tw, th)
                    if (longEdge > ANIM_COVER_MAX) {
                        val m = ANIM_COVER_MAX.toFloat() / longEdge
                        tw = (tw * m).toInt().coerceAtLeast(1)
                        th = (th * m).toInt().coerceAtLeast(1)
                    }
                    if (srcW > 0 && srcH > 0 && (tw != srcW || th != srcH)) {
                        setTargetSize(tw, th)
                    }
                }
            }
            if (drawable is AnimatedImageDrawable) {
                // registerAnimationCallback 必须在带 Looper 的线程调用（解码在 Default 工作线程）
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching { CoverAnimLimiter.register(drawable) }
                }
            }
            return DecodeResult(drawable.asImage(), true)
        }
    }

    /** 静态首帧（APNG / 开关关闭时的动图）：BitmapFactory 按请求尺寸采样。 */
    private class StaticFirstFrame(
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
            ) ?: throw IllegalStateException("cover first frame decode failed")
            val drawable = BitmapDrawable(options.context?.resources, bmp)
            return DecodeResult(drawable.asImage(), false)
        }
    }
}

/**
 * 全局动图封面同时播放上限：超限的 drawable 在启动瞬间被停住（显示首帧），
 * 让出逐帧解码与上屏成本。滚动导致的 stop（coil 对离屏 cell）会释放名额，
 * 再次进入屏幕时由 start 回调重新竞争。参考项目无此闸——它的动图封面是本地
 * 小图；我们的封面来自网络原始大动图，重量不可比，必须设闸。
 */
object CoverAnimLimiter {

    /** 同时播放的动图封面上限（可见 cell 一般 9-12，限 6 保住交互帧率） */
    const val MAX_PLAYING = 6

    private val playing: MutableSet<AnimatedImageDrawable> =
        Collections.newSetFromMap(ConcurrentHashMap<AnimatedImageDrawable, Boolean>())

    fun register(drawable: AnimatedImageDrawable) {
        drawable.registerAnimationCallback(object : android.graphics.drawable.Animatable2.AnimationCallback() {
            override fun onAnimationStart(drawable: Drawable) {
                val d = drawable as? AnimatedImageDrawable ?: return
                if (!playing.contains(d) && playing.size >= MAX_PLAYING) {
                    d.stop()
                } else {
                    playing.add(d)
                }
            }

            override fun onAnimationEnd(drawable: Drawable) {
                (drawable as? AnimatedImageDrawable)?.let { playing.remove(it) }
            }
        })
    }
}
