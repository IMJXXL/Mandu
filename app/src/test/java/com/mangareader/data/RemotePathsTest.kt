package com.mangareader.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 远程书路径身份归一单测（2026-10-06 拆分缓存"时灵时不灵"工单）。
 * 同一卷的多种 path 形式必须归一到同一字符串，探测缓存/进度才不会分叉。
 */
class RemotePathsTest {

    @Test
    fun webdavVariantsCollapseToSameKey() {
        val a = RemotePaths.normalizeBookKey("webdav:2:典藏版/第2卷.cbz")
        val b = RemotePaths.normalizeBookKey("webdav:2:/典藏版/第2卷.cbz")
        val c = RemotePaths.normalizeBookKey("webdav:2:典藏版//第2卷.cbz")
        val d = RemotePaths.normalizeBookKey("webdav:2:典藏版/第2卷.cbz/")
        assertEquals(a, b)
        assertEquals(a, c)
        assertEquals(a, d)
        assertEquals("webdav:2:典藏版/第2卷.cbz", a)
    }

    @Test
    fun smbVariantsCollapseToSameKey() {
        val a = RemotePaths.normalizeBookKey("smb:3:media/books/海贼王01.zip")
        val b = RemotePaths.normalizeBookKey("smb:3:/media/books/海贼王01.zip")
        val c = RemotePaths.normalizeBookKey("smb:3:media//books/海贼王01.zip")
        assertEquals(a, b)
        assertEquals(a, c)
        assertEquals("smb:3:media/books/海贼王01.zip", a)
    }

    @Test
    fun memberSuffixPreserved() {
        // zip-as-dir 成员路径：归一只动斜杠，不破坏 member 语义
        val a = RemotePaths.normalizeBookKey("webdav:2:dir/vol.zip:inner/1.png")
        val b = RemotePaths.normalizeBookKey("webdav:2:dir//vol.zip:inner//1.png")
        assertEquals(a, b)
        assertEquals("webdav:2:dir/vol.zip:inner/1.png", a)
    }

    @Test
    fun localPathsUntouched() {
        val local = "/storage/emulated/0/Books/vol.zip"
        assertEquals(local, RemotePaths.normalizeBookKey(local))
        val saf = "a:auth|tree|docId|zip"
        assertEquals(saf, RemotePaths.normalizeBookKey(saf))
        val z = "z:/sdcard/book.zip//inner/"
        assertEquals(z, RemotePaths.normalizeBookKey(z))
    }

    @Test
    fun malformedRemotePathsPassThrough() {
        // 解析失败（无 id 段/无路径）原样返回——宽容，绝不让书打不开
        assertEquals("webdav:xx", RemotePaths.normalizeBookKey("webdav:xx"))
        assertEquals("smb:", RemotePaths.normalizeBookKey("smb:"))
        assertEquals("", RemotePaths.normalizeBookKey(""))
    }

    @Test
    fun collapseSlashesEdgeCases() {
        assertEquals("a/b", RemotePaths.collapseSlashes("//a//b/"))
        assertEquals("a/b", RemotePaths.collapseSlashes("/a/b//"))
        assertEquals("", RemotePaths.collapseSlashes("///"))
    }
}
