package com.mangareader.smbbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SMB 桥接纯逻辑单元测试（smb_embed_lv119 任务8.1）：
 * filePath/coverPath 解析（spec 6.2 格式）、不支持格式判定（spec 5.5）、扫描格式集合（spec 5.2.1 规则 3）。
 */
class SmbBridgeLogicTest {

    @Test
    fun `parseBookPath parses id share and relative path`() {
        val ref = SmbPaths.parseBookPath("smb:3:comics/one piece/cb01.zip")
        assertTrue(ref != null)
        ref!!
        assertEquals(3L, ref.serverId)
        assertEquals("comics", ref.share)
        assertEquals("one piece/cb01.zip", ref.rel)
        assertEquals("comics/one piece/cb01.zip", ref.fullRel)
        assertEquals("zip", ref.ext)
    }

    @Test
    fun `parseBookPath handles archive member suffix`() {
        val ref = SmbPaths.parseBookPath("smb:7:books/a.zip:inner/page.png")
        assertTrue(ref != null)
        ref!!
        assertEquals(7L, ref.serverId)
        assertEquals("books", ref.share)
        // 外层文件扩展名用于格式分发：先剥成员后缀再取基名
        assertEquals("zip", ref.ext)
        assertEquals("a.zip:inner/page.png", ref.rel)
    }

    @Test
    fun `parseBookPath folder book has empty ext`() {
        val ref = SmbPaths.parseBookPath("smb:1:comics/imgdir")
        assertTrue(ref != null)
        assertEquals("", ref!!.ext)
        assertEquals("imgdir", ref.rel)
    }

    @Test
    fun `parseBookPath rejects malformed input`() {
        assertNull(SmbPaths.parseBookPath("smb:abc:share/x"))
        assertNull(SmbPaths.parseBookPath("smb:"))
        assertNull(SmbPaths.parseBookPath("smb:1:noshare"))
        assertNull(SmbPaths.parseBookPath("smb:1:share/"))
    }

    @Test
    fun `parseCoverPath mirrors bookPath scheme`() {
        val cover = SmbPaths.coverPath(5L, "share", "dir/a.cbz")
        assertEquals("cv:smb:5:share/dir/a.cbz", cover)
        val ref = SmbPaths.parseCoverPath(cover)
        assertTrue(ref != null)
        assertEquals(5L, ref!!.serverId)
        assertEquals("share", ref.share)
        assertEquals("dir/a.cbz", ref.rel)
        assertEquals("cbz", ref.ext)
    }

    @Test
    fun `bookPath round trip`() {
        val p = SmbPaths.bookPath(9L, "media", "comics/x.rar")
        assertEquals("smb:9:media/comics/x.rar", p)
        val ref = SmbPaths.parseBookPath(p)!!
        assertEquals("rar", ref.ext)
        assertEquals("comics/x.rar", ref.rel)
    }

    @Test
    fun `server root source composes body without extra share`() {
        // server-root 源（share 空）：walk 出的路径首段即真实 share，bookPath 不再插 share 段
        val p = SmbPaths.bookPath(2L, "", "media/x.zip")
        assertEquals("smb:2:media/x.zip", p)
        val ref = SmbPaths.parseBookPath(p)!!
        assertEquals("media", ref.share)
        assertEquals("x.zip", ref.rel)
        assertEquals("media/x.zip", ref.fullRel)
    }

    @Test
    fun `unsupported formats empty after mobi streaming M2`() {
        // 2026-10-06：mobi/azw/azw3 走 DocumentExtractPageLoader 记录级流式（M2），
        // Unsupported 前置守卫曾把流式分支拦成死代码——黑名单清空，全链放开
        assertFalse(SmbUnsupportedFormatHandler.isUnsupported("mobi"))
        assertFalse(SmbUnsupportedFormatHandler.isUnsupported("AZW3"))
        assertFalse(SmbUnsupportedFormatHandler.isUnsupported("azw"))
        assertFalse(SmbUnsupportedFormatHandler.isUnsupported("cbz"))
        assertFalse(SmbUnsupportedFormatHandler.isUnsupported("cbr"))
        assertFalse(SmbUnsupportedFormatHandler.isUnsupported("pdf"))
        assertFalse(SmbUnsupportedFormatHandler.isUnsupported("epub"))
    }

    @Test
    fun `scan formats cover spec 5_2_1 rule 3`() {
        val expected = setOf("zip", "cbz", "rar", "cbr", "7z", "cb7", "tar", "cbt", "pdf", "epub", "mobi", "azw3")
        assertEquals(expected, SmbScanner.SCANNABLE_FORMATS)
    }

    @Test
    fun `version guard min api is 26`() {
        assertEquals(26, SmbVersionGuard.MIN_API)
    }
}
