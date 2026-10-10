package com.mangareader.mobi

import java.io.Closeable

/**
 * MOBI/AZW/AZW3/PDB/PRC 记录级流式读取（2026-10-05 快路径，纯 Kotlin 无 Android 依赖）。
 *
 * PDB 容器把所有记录偏移集中在文件头（78B 头 + 每记录 8B），图片以裸 JPEG/PNG/GIF/WebP/BMP
 * 字节独占记录存放——开书只需读头几 KB，翻页是一次精确区间读。这与 libmobi 的
 * mobi_load_file（全档记录驻留内存）相反，故定位为 libmobi 的**前置加速层**：
 * 解析/取图任何一步失手，调用方原样落回 libmobi → Java 魔法扫描，原链路零改动。
 *
 * 只触碰图片记录，刻意不解析文本（PalmDOC/HUFF-CDIC 解压、trailing entries、
 * recindex 阅读序都不在本层）——MOBI 格式真正的坑全在文本侧。
 */
class MobiFile private constructor(
    private val recordOffsets: LongArray,
    private val fileSize: Long,
    private val numRecords: Int,
    val resourceStart: Int,
    val resourceEnd: Int,
) {
    private val imageRecords = ArrayList<Int>()
    private val imageExts = ArrayList<String>()

    val imageCount: Int get() = imageRecords.size

    var probedThrough: Int = resourceStart
        private set

    var structureComplete: Boolean = false
        private set

    private var scanningFallback = false

    /** 第 [imageIndex] 张图（0 基）的扩展名，如 "jpg"/"png"。 */
    fun imageExt(imageIndex: Int): String? = imageExts.getOrNull(imageIndex)

    /** 第 [imageIndex] 张图所在记录的字节长度（由记录表推得，零 IO）。 */
    fun imageSize(imageIndex: Int): Long? {
        if (imageIndex !in imageRecords.indices) return null
        val rec = imageRecords[imageIndex]
        val end = if (rec + 1 < recordOffsets.size) recordOffsets[rec + 1] else fileSize
        return (end - recordOffsets[rec]).coerceAtLeast(0L)
    }

    fun recordSizeAt(rec: Int): Long {
        if (rec !in recordOffsets.indices) return -1L
        val end = if (rec + 1 < recordOffsets.size) recordOffsets[rec + 1] else fileSize
        return (end - recordOffsets[rec]).coerceAtLeast(0L)
    }

    fun probeRecordExt(source: SeekableSource, rec: Int): String? {
        if (rec !in recordOffsets.indices) return null
        if (recordSizeAt(rec) < 12) return null
        val probe = ByteArray(12)
        if (source.readAt(recordOffsets[rec], probe, 0, 12) < 12) return null
        return imageExtOf(probe)
    }

    fun probeMore(source: SeekableSource, targetImageCount: Int): Int {
        if (structureComplete) return imageRecords.size
        if (targetImageCount <= imageRecords.size) return imageRecords.size
        val probe = ByteArray(12)
        val scanEnd = if (scanningFallback) numRecords else resourceEnd
        var i = probedThrough
        while (i < scanEnd && imageRecords.size < targetImageCount) {
            val usable = !scanningFallback || recordEnd(i) - recordOffsets[i] >= 64
            if (usable) {
                probeRecordAt(source, i, probe)?.let { (rec, ext) ->
                    imageRecords.add(rec)
                    imageExts.add(ext)
                }
            }
            i++
        }
        probedThrough = i
        if (probedThrough >= scanEnd) structureComplete = true
        return imageRecords.size
    }

    fun hydrateFromCache(exts: List<String>, complete: Boolean) {
        imageRecords.clear()
        imageExts.clear()
        for (ext in exts) {
            imageRecords.add(resourceStart + imageRecords.size)
            imageExts.add(ext)
        }
        if (complete) {
            probedThrough = resourceEnd
            structureComplete = true
        } else {
            probedThrough = resourceStart + exts.size
            structureComplete = false
        }
    }

    /**
     * 读第 [imageIndex] 张图的原始字节。返回整条记录不做 FFD9/IEND 截尾——与 libmobi
     * 路径行为一致，解码器容忍记录尾部少量填充。越界/超长/读不满返回 null（调用方回退）。
     */
    fun readImage(source: SeekableSource, imageIndex: Int, maxBytes: Long = MAX_RECORD_BYTES): ByteArray? {
        if (imageIndex !in imageRecords.indices) return null
        val rec = imageRecords[imageIndex]
        val off = recordOffsets[rec]
        val end = if (rec + 1 < recordOffsets.size) recordOffsets[rec + 1] else fileSize
        val len = end - off
        if (len <= 0L || len > maxBytes) return null
        return source.readFullyAt(off, len.toInt())
    }

    private fun recordEnd(rec: Int): Long =
        if (rec + 1 < recordOffsets.size) recordOffsets[rec + 1] else fileSize

    private fun probeRecordAt(source: SeekableSource, i: Int, probe: ByteArray): Pair<Int, String>? {
        val len = (recordEnd(i) - recordOffsets[i]).toInt()
        if (len < 12) return null
        if (source.readAt(recordOffsets[i], probe, 0, 12) < 12) return null
        val ext = imageExtOf(probe) ?: return null
        return i to ext
    }

    companion object {
        /** PDB 规范记录数上限（u16），同时防空表/坏表。 */
        const val MAX_RECORDS = 32767

        /** 单条记录读取上限：防御坏偏移产生天文数字分配（超长扫描页也远小于此）。 */
        const val MAX_RECORD_BYTES = 64L * 1024 * 1024

        /** 最近一次拒收原因（2026-10-06 "有的 mobi 能读有的不行"工单）：open 返回 null 时
         *  写明具体路径，MobiEngine/DocumentExtractPageLoader 透传进 error 消息——下一轮
         *  日志/toast 直接区分 DRM、坏表、0 图还是 IO。无 android 依赖故用伴生字段。 */
        @JvmField
        @Volatile
        var lastReject: String = ""

        private fun reject(reason: String): MobiFile? {
            lastReject = reason
            return null
        }

        /**
         * 解析 PDB 头 + 记录表 + MOBI 头，魔数探测资源区图片记录。
         * 返回 null = 不是可流式读取的 MOBI（结构坏/DRM），调用方回退既有路径；
         * imageCount == 0 合法（纯文字书/无图 PDB），同样走回退。
         *
         * probeLimit < 0 全量探测（现状行为）；== 0 只读头+记录表不探测图片；
         * > 0 探测到该条图片数即停（structureComplete=false，probeMore 续探）。
         */
        fun open(source: SeekableSource, probeLimit: Int = -1): MobiFile? {
            val size = source.size
            if (size < 78L + 8) return reject("file too small ($size B)")
            val head = source.readFullyAt(0, 78) ?: return reject("PDB header read failed")
            val numRecords = u16(head, 76)
            if (numRecords !in 1..MAX_RECORDS) return reject("bad record count ($numRecords)")
            val list = source.readFullyAt(78, numRecords * 8) ?: return reject("record table read failed")
            val offsets = LongArray(numRecords)
            var prev = 0L
            for (i in 0 until numRecords) {
                val o = u32(list, i * 8)
                if (o < prev || o >= size) return reject("record table broken at #$i (offset=$o size=$size)")
                offsets[i] = o
                prev = o
            }
            fun recordEnd(i: Int): Long = if (i + 1 < numRecords) offsets[i + 1] else size

            // 记录 0：PalmDOC 头 16B [+ MOBI 头]
            val rec0Len = (recordEnd(0) - offsets[0]).toInt()
            if (rec0Len < 16) return reject("rec0 too small ($rec0Len B)")
            val rec0 = source.readFullyAt(offsets[0], minOf(rec0Len, 4096)) ?: return reject("rec0 read failed")
            val textRecordCount = u16(rec0, 8)
            if (u16(rec0, 12) != 0) return reject("DRM/encrypted (encryption=${u16(rec0, 12)})")

            // MOBI 头圈定资源区（firstNonBook/firstImage 为 MOBI 头内偏移 64/92）；
            // 无 MOBI 头（纯 PalmDOC PDB）则从文本记录之后探测
            var firstImage = -1
            var firstNonBook = numRecords
            if (rec0Len >= 16 + 96 + 4 &&
                rec0[16] == 'M'.code.toByte() && rec0[17] == 'O'.code.toByte() &&
                rec0[18] == 'B'.code.toByte() && rec0[19] == 'I'.code.toByte()
            ) {
                val fi = i32(rec0, 16 + 92)
                val fnb = i32(rec0, 16 + 64)
                if (fi in 1 until numRecords) firstImage = fi
                if (fnb in 1..numRecords) firstNonBook = fnb
            }
            // 资源区 = 文本记录 1..textRecordCount 之后、非书目记录之前。
            // 下界双保险：压缩文本首字节理论上可能撞上图片魔数，绝不进入探测区
            val start = maxOf(firstImage, textRecordCount + 1).coerceAtLeast(1)
            val end = minOf(firstNonBook, numRecords)

            val mobi = MobiFile(offsets, size, numRecords, start, end)
            if (probeLimit == 0) {
                mobi.probedThrough = start
                mobi.structureComplete = false
                return mobi
            }
            if (end > start) {
                mobi.probeMore(source, if (probeLimit > 0) probeLimit else Int.MAX_VALUE)
            } else {
                mobi.probedThrough = end
                mobi.structureComplete = true
            }
            // ★ 兜底轮（2026-10-06 "有的 mobi 不行"）：firstNonBook/firstImage 由转换工具写入，
            //   异常值（azw3 复合书指进 KF8 区、工具 bug 写小）会把探测区收成空/截断 → 0 图 →
            //   整书拒收。主区 0 图时按"记录序全扫（跳 rec0 与 <64B 垃圾）"再试一轮——转换正常
            //   的书主区即命中（不进此分支）；不规范书以可能引入少量文本误报换整本可开。
            if (mobi.imageRecords.isEmpty() && (start > 1 || end < numRecords)) {
                mobi.scanningFallback = true
                mobi.probedThrough = 1
                mobi.structureComplete = false
                mobi.probeMore(source, if (probeLimit > 0) probeLimit else Int.MAX_VALUE)
                if (mobi.imageRecords.isNotEmpty()) lastReject = ""
            }
            if (mobi.imageRecords.isEmpty()) lastReject = "no image records (probe [$start,$end) of $numRecords recs)"
            return mobi
        }

        private fun imageExtOf(b: ByteArray): String? = when {
            b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpg"
            b[0] == 0x89.toByte() && b[1] == 0x50.toByte() &&
                b[2] == 0x4E.toByte() && b[3] == 0x47.toByte() -> "png"
            b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() &&
                b[2] == 'F'.code.toByte() && b[3] == '8'.code.toByte() -> "gif"
            b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
                b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
                b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() &&
                b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte() -> "webp"
            b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte() -> "bmp"
            else -> null
        }

        private fun u16(b: ByteArray, off: Int): Int =
            ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

        private fun u32(b: ByteArray, off: Int): Long =
            ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
                ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

        private fun i32(b: ByteArray, off: Int): Int =
            u32(b, off).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

/** 随机可寻址字节源（MobiFile 的最小读接口，实现方决定 fd/文件/内存）。 */
interface SeekableSource : Closeable {
    val size: Long

    /** 从 [offset] 读最多 [len] 字节进 [buf] 的 [off] 处，返回实际字节数（≤0=读不到）。 */
    fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int

    override fun close()
}

/** 读满 [len] 字节；读不满（截断/EOF）返回 null。 */
internal fun SeekableSource.readFullyAt(offset: Long, len: Int): ByteArray? {
    if (len < 0) return null
    val buf = ByteArray(len)
    var pos = 0
    while (pos < len) {
        val n = readAt(offset + pos, buf, pos, len - pos)
        if (n <= 0) return null
        pos += n
    }
    return buf
}
