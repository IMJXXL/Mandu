package com.mangareader.mobi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * MobiFile 记录级解析单测（2026-10-05 mobi 快路径）。
 * 全部用合成 PDB/MOBI 字节流——构造器本身即解析器的字节级规格说明。
 */
class MobiFileTest {

    private val jpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00,
        0x01, 0x01, 0x00, 0x00, 0xFF.toByte(), 0xD9.toByte(),
    )
    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D,
        0x49, 0x48, 0x44, 0x52,
    )
    private val gif = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00)
    private val webp = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50, 0x01, 0x02)
    private val bmp = byteArrayOf(0x42, 0x4D, 0x46, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x36, 0x00, 0x00)
    private val textJunk = byteArrayOf(3, 1, 4, 1, 5, 9, 2, 6, 5, 3, 5, 8)
    private val flisJunk = byteArrayOf(0x46, 0x4C, 0x49, 0x53, 0x00, 0x00, 0x00, 0x00, 1, 2, 3, 4)

    private fun u32(v: Long) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    /** 记录 0：PalmDOC 头 16B + 可选 MOBI 头。 */
    private fun rec0(textRecordCount: Int, encryption: Int = 0, mobi: ByteArray? = null): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 2))                          // compression = PalmDOC
        out.write(ByteArray(2))                               // unused
        out.write(ByteArray(4))                               // textLength
        out.write(byteArrayOf(0, textRecordCount.toByte()))   // text record count
        out.write(byteArrayOf(0x10, 0))                       // recordSize = 4096
        out.write(byteArrayOf(0, encryption.toByte()))        // encryption
        out.write(ByteArray(2))                               // unused
        mobi?.let { out.write(it) }
        return out.toByteArray()
    }

    /** MOBI 头，headerLength=232，只填解析器关心的 firstNonBook(@64)/firstImage(@92)。 */
    private fun mobiHeader(firstImage: Int, firstNonBook: Int, version: Int = 6): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("MOBI".toByteArray())
        out.write(u32(232L))                    // headerLength
        out.write(u32(2L))                      // mobiType = book
        out.write(u32(65001L))                  // encoding
        out.write(u32(777L))                    // uniqueID
        out.write(u32(version.toLong()))        // fileVersion
        out.write(ByteArray(40))                // @24..63 索引/extra index 区
        out.write(u32(firstNonBook.toLong()))   // @64  First Non-book index
        out.write(u32(0L))                      // @68  fullNameOffset
        out.write(u32(0L))                      // @72  fullNameLength
        out.write(u32(9L))                      // @76  locale
        out.write(u32(0L))                      // @80  input language
        out.write(u32(0L))                      // @84  output language
        out.write(u32(6L))                      // @88  min version
        out.write(u32(firstImage.toLong()))     // @92  First Image index
        out.write(ByteArray(232 - 96))          // 补齐到 headerLength（前 96 字节已写满）
        return out.toByteArray()
    }

    /** 组 PDB 容器：78B 头 + 每记录 8B 变长记录表（偏移单调）+ 记录内容。 */
    private fun pdb(type: String, creator: String, records: Array<out ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        val name = ByteArray(32)
        "ut".toByteArray().copyInto(name)
        out.write(name)
        out.write(ByteArray(28))            // attrs/version/三个时间/modnum/appInfo/sortInfo
        out.write(type.toByteArray())       // @60  type
        out.write(creator.toByteArray())    // @64  creator
        out.write(ByteArray(8))             // uniqueIDseed + nextRecordListID
        out.write(u32(records.size.toLong()).copyOfRange(2, 4))  // @76 numRecords (u16 BE)
        var offset = 78 + records.size * 8
        records.forEachIndexed { i, r ->
            out.write(u32(offset.toLong()))
            out.write(byteArrayOf(0, (i ushr 8).toByte(), i.toByte(), 1))  // attrs(0) + uniqueID(3)
            offset += r.size
        }
        records.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun open(vararg records: ByteArray, type: String = "BOOK", creator: String = "MOBI"): MobiFile? =
        MobiFile.open(ByteArraySeekable(pdb(type, creator, records)))

    // ---- 资源区识别 ----

    @Test
    fun imagesInResourceRegionAreListedInRecordOrder() {
        val bytes = pdb(
            "BOOK", "MOBI", arrayOf(
                rec0(textRecordCount = 2, mobi = mobiHeader(firstImage = 3, firstNonBook = 6)),
                textJunk, textJunk,
                jpeg, png, gif,
                flisJunk,
            ),
        )
        val mf = MobiFile.open(ByteArraySeekable(bytes))!!
        assertEquals(3, mf.imageCount)
        assertEquals("jpg", mf.imageExt(0))
        assertEquals("png", mf.imageExt(1))
        assertEquals("gif", mf.imageExt(2))
        val src = ByteArraySeekable(bytes)
        assertArrayEquals(jpeg, mf.readImage(src, 0))
        assertArrayEquals(png, mf.readImage(src, 1))
        assertArrayEquals(gif, mf.readImage(src, 2))
    }

    @Test
    fun missingFirstImageProbesFromTextBound() {
        val mf = open(
            rec0(textRecordCount = 2, mobi = mobiHeader(firstImage = 0, firstNonBook = 6)),
            textJunk, textJunk,
            jpeg, png, gif,
            flisJunk,
        )!!
        assertEquals(3, mf.imageCount)
    }

    @Test
    fun firstNonBookBoundsTheProbe() {
        val mf = open(
            rec0(textRecordCount = 2, mobi = mobiHeader(firstImage = 3, firstNonBook = 5)),
            textJunk, textJunk,
            jpeg, png,
            jpeg,   // 非书目区（≥firstNonBook），必须排除
            flisJunk,
        )!!
        assertEquals(2, mf.imageCount)
    }

    @Test
    fun palmDocWithoutMobiHeaderProbesAfterText() {
        val mf = open(
            rec0(textRecordCount = 1),
            textJunk,
            jpeg, png,
        )!!
        assertEquals(2, mf.imageCount)
        assertEquals("jpg", mf.imageExt(0))
    }

    @Test
    fun textStartingWithImageMagicIsNotCounted() {
        // 压缩文本首字节撞上 FFD8：靠 textRecordCount 下界排除，不入页表
        val trickyText = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 9, 9, 9, 9, 9, 9, 9, 9, 9)
        val mf = open(
            rec0(textRecordCount = 1, mobi = mobiHeader(firstImage = 2, firstNonBook = 4)),
            trickyText,
            jpeg, png,
        )!!
        assertEquals(2, mf.imageCount)
    }

    @Test
    fun webpAndBmpDetected() {
        val mf = open(
            rec0(textRecordCount = 1, mobi = mobiHeader(firstImage = 2, firstNonBook = 4)),
            textJunk,
            webp, bmp,
        )!!
        assertEquals("webp", mf.imageExt(0))
        assertEquals("bmp", mf.imageExt(1))
    }

    @Test
    fun imagelessBookStillOpensWithZeroPages() {
        val mf = open(
            rec0(textRecordCount = 2, mobi = mobiHeader(firstImage = 3, firstNonBook = 6)),
            textJunk, textJunk, flisJunk,
        )!!
        assertEquals(0, mf.imageCount)
        assertNull(mf.imageExt(0))
    }

    // ---- 结构拒收（返回 null → 调用方回退 libmobi）----

    @Test
    fun drmIsRejected() {
        val mf = open(
            rec0(textRecordCount = 1, encryption = 1, mobi = mobiHeader(firstImage = 2, firstNonBook = 3)),
            textJunk, jpeg,
        )
        assertNull(mf)
        assert(MobiFile.lastReject.contains("DRM")) { MobiFile.lastReject }
    }

    @Test
    fun corruptOffsetsRejected() {
        // 记录表偏移非单调（rec0=100, rec1=50）
        val out = ByteArrayOutputStream()
        out.write(ByteArray(76))
        out.write(byteArrayOf(0, 2))   // numRecords = 2
        out.write(u32(100L))
        out.write(byteArrayOf(0, 0, 0, 1))
        out.write(u32(50L))
        out.write(byteArrayOf(0, 0, 0, 2))
        out.write(ByteArray(48))
        assertNull(MobiFile.open(ByteArraySeekable(out.toByteArray())))
        assert(MobiFile.lastReject.contains("record table broken")) { MobiFile.lastReject }
    }

    // ---- 兜底轮（2026-10-06 "有的 mobi 能读有的不行"）----

    @Test
    fun bogusFirstNonBookFallsBackToFullScan() {
        // 工具写坏 firstNonBook=1：主探测区 [3,1) 为空 → 兜底轮全记录扫出 3 张图。
        // 记录需 ≥64B（兜底轮滤短垃圾），给合成图补零填充（readImage 容忍记录尾部填充）
        fun pad(b: ByteArray) = b + ByteArray(64)
        val bytes = pdb(
            "BOOK", "MOBI", arrayOf(
                rec0(textRecordCount = 2, mobi = mobiHeader(firstImage = 3, firstNonBook = 1)),
                textJunk, textJunk,
                pad(jpeg), pad(png), pad(gif),
                flisJunk,
            ),
        )
        val mf = MobiFile.open(ByteArraySeekable(bytes))!!
        assertEquals(3, mf.imageCount)
        assertEquals("jpg", mf.imageExt(0))
        assertEquals("png", mf.imageExt(1))
        assertEquals("gif", mf.imageExt(2))
    }

    @Test
    fun bogusFirstImageFallsBackToFullScan() {
        // firstImage 指到资源区之后（工具 bug）：主区只剩 gif → 但若主区仍有图则不进兜底；
        // 这里 firstImage=99 使主区为空 → 兜底轮救回全部
        val mf = open(
            rec0(textRecordCount = 2, mobi = mobiHeader(firstImage = 99, firstNonBook = 6)),
            textJunk, textJunk,
            jpeg, png, gif,
            flisJunk,
        )!!
        assertEquals(3, mf.imageCount)
    }

    @Test
    fun fallbackIgnoresShortJunkRecords() {
        // 兜底轮跳过 <64B 垃圾记录：textJunk(12B)/flisJunk(12B) 不计入
        val mf = open(
            rec0(textRecordCount = 2, mobi = mobiHeader(firstImage = 99, firstNonBook = 99)),
            textJunk, textJunk,
            flisJunk, flisJunk,
        )!!
        assertEquals(0, mf.imageCount)
    }

    @Test
    fun rejectReasonsAreDistinct() {
        assertNull(MobiFile.open(ByteArraySeekable(ByteArray(10))))
        assert(MobiFile.lastReject.contains("too small")) { MobiFile.lastReject }
    }

    @Test
    fun offsetBeyondFileSizeRejected() {
        val out = ByteArrayOutputStream()
        out.write(ByteArray(76))
        out.write(byteArrayOf(0, 1))   // numRecords = 1
        out.write(u32(500L))           // 偏移出界
        out.write(byteArrayOf(0, 0, 0, 1))
        out.write(ByteArray(48))
        assertNull(MobiFile.open(ByteArraySeekable(out.toByteArray())))
    }

    @Test
    fun tinyFileRejected() {
        assertNull(MobiFile.open(ByteArraySeekable(ByteArray(10))))
    }

    @Test
    fun zeroRecordsRejected() {
        assertNull(MobiFile.open(ByteArraySeekable(pdb("BOOK", "MOBI", arrayOf()))))
    }

    // ---- 读取边界 ----

    @Test
    fun readImageOutOfRangeReturnsNull() {
        val bytes = pdb(
            "BOOK", "MOBI", arrayOf(
                rec0(textRecordCount = 1, mobi = mobiHeader(firstImage = 2, firstNonBook = 3)),
                textJunk, jpeg,
            ),
        )
        val mf = MobiFile.open(ByteArraySeekable(bytes))!!
        val src = ByteArraySeekable(bytes)
        assertNull(mf.readImage(src, -1))
        assertNull(mf.readImage(src, 1))
    }

    @Test
    fun readImageHonorsMaxBytes() {
        val bytes = pdb(
            "BOOK", "MOBI", arrayOf(
                rec0(textRecordCount = 1, mobi = mobiHeader(firstImage = 2, firstNonBook = 3)),
                textJunk, jpeg,
            ),
        )
        val mf = MobiFile.open(ByteArraySeekable(bytes))!!
        val src = ByteArraySeekable(bytes)
        assertNull(mf.readImage(src, 0, maxBytes = 4))
        assertArrayEquals(jpeg, mf.readImage(src, 0, maxBytes = MobiFile.MAX_RECORD_BYTES))
    }
}
