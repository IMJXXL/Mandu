package com.mangareader.coil

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.ByteBufferMetadata
import coil3.decode.ContentMetadata
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.github.penfeizhou.animation.apng.APNGDrawable
import com.github.penfeizhou.animation.loader.ByteBufferLoader
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import okio.FileSystem
import okio.use

/**
 * Coil decoder for animated PNG (APNG) — the platform [coil3.gif.AnimatedImageDecoder]
 * (ImageDecoder) only handles animated GIF/WebP, so remote APNG pages/thumbs would
 * otherwise render as a static first frame. Local playback already routes through
 * apng4android (PagerReaderView.makeAnimDrawable); this brings the same engine to
 * the Coil pipeline (remote pages, covers, OPDS/Komga images).
 *
 * Must be registered BEFORE AnimatedImageDecoder, which would otherwise claim the
 * file as a plain (static) PNG.
 */
@OptIn(coil3.annotation.ExperimentalCoilApi::class)
class AnimatedApngDecoder(private val source: ByteBuffer) : Decoder {
    override suspend fun decode(): DecodeResult {
        val loader = object : ByteBufferLoader() {
            override fun getByteBuffer(): ByteBuffer = source
        }
        return DecodeResult(APNGDrawable(loader).asImage(), false)
    }

    object Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ) = if (isApngSource(result.source.source())) {
            result.source.toByteBufferOrNull()?.let { AnimatedApngDecoder(it) }
        } else {
            null
        }
    }
}

/** PNG signature + an `acTL` chunk before `IDAT`/`IEND` (APNG spec requirement). */
internal fun isApngSource(source: okio.BufferedSource): Boolean = try {
    source.peek().use { peek ->
        if (!peek.request(8)) return false
        val sig = peek.readByteArray(8)
        if (!sig.contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            )
        ) {
            return false
        }
        while (peek.request(8)) {
            val len = peek.readInt()
            if (len < 0) return false
            val type = peek.readUtf8(4)
            when (type) {
                "acTL" -> return true
                "IDAT", "IEND" -> return false
            }
            if (!peek.request(len + 4L)) return false
            peek.skip(len + 4L)
        }
        false
    }
} catch (_: Exception) {
    false
}

private fun ImageSource.toByteBufferOrNull(): ByteBuffer? {
    if (fileSystem === FileSystem.SYSTEM) {
        val file = fileOrNull()
        if (file != null) {
            return file.toFile().inputStream().mapReadOnly()
        }
    }
    return when (val metadata = metadata) {
        is ContentMetadata -> metadata.assetFileDescriptor.createInputStream().mapReadOnly()
        is ByteBufferMetadata -> metadata.byteBuffer
        else -> null
    }
}

private fun FileInputStream.mapReadOnly(): ByteBuffer = channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }
