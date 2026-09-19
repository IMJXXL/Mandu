package com.mangareader.viewer

import java.io.File
import java.security.MessageDigest
import splitties.init.appCtx

/**
 * 封面动图原始字节副档（2026-09-19）：
 * 归档/文件夹书第一页是动图时，物化 JPEG 封面会把首帧固化成静图。
 * 本缓存按封面 cacheKey 存两种状态：
 * - `.raw`    ：动图首页原始字节（远程封面加载器 wantRaw=true 时直接返回，
 *               Coil 平台解码器按请求尺寸播放）
 * - `.static` ：已探测确认首页为静图（后续直接走既有 JPEG 物化路径，不再探测）
 * 探测每次封面只发生一次，之后永久分流。
 */
object CoverAnimRawCache {

    private fun dir(): File = File(appCtx.applicationInfo.dataDir, "cache/cover_animraw")

    private fun keyFile(cacheKey: String, suffix: String): File {
        val hex = MessageDigest.getInstance("SHA-256")
            .digest(cacheKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(dir(), "$hex$suffix")
    }

    fun rawFile(cacheKey: String): File = keyFile(cacheKey, ".raw")

    fun readRaw(cacheKey: String): ByteArray? =
        rawFile(cacheKey).takeIf { it.isFile && it.length() > 0 }?.readBytes()

    /** 副档文件本体（浏览封面动图直接以文件为 data，ImageDecoder 走文件源） */
    fun readRawFile(cacheKey: String): File? =
        rawFile(cacheKey).takeIf { it.isFile && it.length() > 0 }

    fun writeRaw(cacheKey: String, bytes: ByteArray) {
        runCatching {
            val f = rawFile(cacheKey)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp." + System.nanoTime())
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        }
    }

    fun isStatic(cacheKey: String): Boolean =
        keyFile(cacheKey, ".static").isFile

    fun markStatic(cacheKey: String) {
        runCatching {
            val f = keyFile(cacheKey, ".static")
            f.parentFile?.mkdirs()
            f.writeText("1")
        }
    }

    private fun isMagic(bytes: ByteArray, offset: Int, s: String): Boolean {
        if (bytes.size < offset + s.length) return false
        for (i in s.indices) {
            if (bytes[offset + i] != s[i].code.toByte()) return false
        }
        return true
    }

    /** 头部魔数嗅探 mime（副档只存动图，GIF/动图WebP/APNG 之外不会落盘）。 */
    fun mimeOf(bytes: ByteArray): String = when {
        isMagic(bytes, 0, "GIF") -> "image/gif"
        bytes.size > 12 && isMagic(bytes, 0, "RIFF") && isMagic(bytes, 8, "WEBP") -> "image/webp"
        isMagic(bytes, 0, "\u0089PNG") -> "image/png"
        bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        else -> "image/png"
    }
}
