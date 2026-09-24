package com.mangareader.native.curl

import android.graphics.drawable.Drawable
import com.mangareader.viewer.AnimKind

data class CurlAnimData(
    val raw: ByteArray,
    val kind: AnimKind,
)

fun makeCurlAnimDrawable(raw: ByteArray, kind: AnimKind): Drawable? {
    return try {
        val buffer = java.nio.ByteBuffer.wrap(raw)
        val loader = object : com.github.penfeizhou.animation.loader.ByteBufferLoader() {
            override fun getByteBuffer(): java.nio.ByteBuffer = buffer
        }
        val drawable = when (kind) {
            AnimKind.WEBP -> com.github.penfeizhou.animation.webp.WebPDrawable(loader)
            AnimKind.APNG -> com.github.penfeizhou.animation.apng.APNGDrawable(loader)
            AnimKind.GIF -> com.github.penfeizhou.animation.gif.GifDrawable(loader)
            AnimKind.NONE -> return null
        }
        try { drawable.intrinsicWidth } catch (_: Throwable) {}
        try { drawable.intrinsicHeight } catch (_: Throwable) {}
        drawable
    } catch (_: Throwable) { null }
}

fun detectAnimKindFromBytes(raw: ByteArray): AnimKind {
    if (raw.size < 12) return AnimKind.NONE
    if (raw[0] == 0x47.toByte() && raw[1] == 0x49.toByte() && raw[2] == 0x46.toByte()) return AnimKind.GIF
    if (raw[0] == 0x52.toByte() && raw[1] == 0x49.toByte() && raw[2] == 0x46.toByte() && raw[3] == 0x46.toByte() &&
        raw[8] == 0x57.toByte() && raw[9] == 0x45.toByte() && raw[10] == 0x42.toByte() && raw[11] == 0x50.toByte()
    ) return AnimKind.WEBP
    if (raw[0] == 0x89.toByte() && raw[1] == 0x50.toByte() && raw[2] == 0x4E.toByte() && raw[3] == 0x47.toByte()) {
        val acTl = byteArrayOf(0x61, 0x63, 0x54, 0x4C)
        for (i in 0 until minOf(raw.size - 4, 200)) {
            if (raw[i] == acTl[0] && raw[i + 1] == acTl[1] && raw[i + 2] == acTl[2] && raw[i + 3] == acTl[3]) {
                return AnimKind.APNG
            }
        }
    }
    return AnimKind.NONE
}