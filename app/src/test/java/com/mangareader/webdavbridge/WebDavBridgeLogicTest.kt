package com.mangareader.webdavbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebDAV 桥接纯逻辑单元测试（smb_embed_lv119 任务18.1）：
 * filePath/coverPath 解析（无 share 段）、需全量下载格式判定（spec 5.11）。
 */
class WebDavBridgeLogicTest {

    @Test
    fun `parseBookPath parses id and relative path without share segment`() {
        val ref = WebDavPaths.parseBookPath("webdav:4:comics/one piece/cb01.zip")
        assertTrue(ref != null)
        ref!!
        assertEquals(4L, ref.serverId)
        assertEquals("comics/one piece/cb01.zip", ref.remotePath)
        assertEquals("zip", ref.ext)
    }

    @Test
    fun `parseBookPath folder book has empty ext`() {
        val ref = WebDavPaths.parseBookPath("webdav:2:photos dir")
        assertTrue(ref != null)
        assertEquals("", ref!!.ext)
    }

    @Test
    fun `parseBookPath rejects malformed input`() {
        assertNull(WebDavPaths.parseBookPath("webdav:xyz:rel"))
        assertNull(WebDavPaths.parseBookPath("webdav:"))
        assertNull(WebDavPaths.parseBookPath("webdav:1:"))
    }

    @Test
    fun `coverPath round trip`() {
        val cover = WebDavPaths.coverPath(6L, "dir/b.cbz")
        assertEquals("cv:webdav:6:dir/b.cbz", cover)
        val ref = WebDavPaths.parseCoverPath(cover)
        assertTrue(ref != null)
        assertEquals(6L, ref!!.serverId)
        assertEquals("cbz", ref.ext)
    }

    @Test
    fun `bookPath format has no share segment`() {
        assertEquals("webdav:11:comics/x.7z", WebDavPaths.bookPath(11L, "comics/x.7z"))
    }

    @Test
    fun `unsupported formats are mobi and azw3 only`() {
        assertTrue(WebDavUnsupportedFormatHandler.isUnsupported("mobi"))
        assertTrue(WebDavUnsupportedFormatHandler.isUnsupported("azw3"))
        assertFalse(WebDavUnsupportedFormatHandler.isUnsupported("cbz"))
        assertFalse(WebDavUnsupportedFormatHandler.isUnsupported("pdf"))
    }

    @Test
    fun `scan formats match smb side`() {
        assertEquals(
            com.mangareader.smbbridge.SmbScanner.SCANNABLE_FORMATS,
            WebDavScanner.SCANNABLE_FORMATS,
        )
    }
}
