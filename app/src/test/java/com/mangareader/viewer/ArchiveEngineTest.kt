package com.mangareader.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 嵌套归档章节支持单测（commit-4）。
 * 纯 JVM——只测不依赖 Context/native 的纯函数：白名单判定 + isFlatZip 守卫。
 * listZipDirs/children 依赖 SAF+NativeEngine，标记为真机验收项。
 */
class ArchiveEngineTest {

    @Test
    fun `白名单内格式返回 true`() {
        val cases = listOf(
            "book.mobi", "book.azw", "book.azw3", "book.prc", "book.pdb",
            "book.zip", "book.cbz", "book.rar", "book.cbr",
            "book.7z", "book.cb7", "book.tar", "book.cbt", "book.tgz", "book.txz",
        )
        for (name in cases) {
            assertTrue("应判为嵌套归档: $name", ArchiveEngine.isNestedArchive(name))
        }
    }

    @Test
    fun `epub_pdf_不在白名单返回 false`() {
        assertFalse(ArchiveEngine.isNestedArchive("novel.epub"))
        assertFalse(ArchiveEngine.isNestedArchive("doc.pdf"))
        assertFalse(ArchiveEngine.isNestedArchive("photo.jpg"))
        assertFalse(ArchiveEngine.isNestedArchive("image.png"))
    }

    @Test
    fun `大小写不敏感`() {
        assertTrue(ArchiveEngine.isNestedArchive("BOOK.MOBI"))
        assertTrue(ArchiveEngine.isNestedArchive("Book.Cbz"))
        assertTrue(ArchiveEngine.isNestedArchive("book.7Z"))
        assertTrue(ArchiveEngine.isNestedArchive("book.TAR"))
    }

    @Test
    fun `无扩展名或空字符串返回 false`() {
        assertFalse(ArchiveEngine.isNestedArchive(""))
        assertFalse(ArchiveEngine.isNestedArchive("noext"))
        assertFalse(ArchiveEngine.isNestedArchive("path/to/file"))
    }

    @Test
    fun `NESTED_ARCHIVE_EXTS 含 15 种扩展名且不含 epub_pdf`() {
        assertEquals(15, ArchiveEngine.NESTED_ARCHIVE_EXTS.size)
        assertFalse(ArchiveEngine.NESTED_ARCHIVE_EXTS.contains("epub"))
        assertFalse(ArchiveEngine.NESTED_ARCHIVE_EXTS.contains("pdf"))
        assertTrue(ArchiveEngine.NESTED_ARCHIVE_EXTS.contains("mobi"))
        assertTrue(ArchiveEngine.NESTED_ARCHIVE_EXTS.contains("prc"))
        assertTrue(ArchiveEngine.NESTED_ARCHIVE_EXTS.contains("pdb"))
    }

    @Test
    fun `isFlatZip 空 children 返回 true`() {
        val root = ArchiveEngine.ZipDirNode("", "", true)
        assertTrue(ArchiveEngine.isFlatZip(root))
    }

    @Test
    fun `isFlatZip 全非 dir children 返回 true`() {
        val root = ArchiveEngine.ZipDirNode("", "", true, mutableListOf(
            ArchiveEngine.ZipDirNode("a.mobi", "a.mobi", false),
            ArchiveEngine.ZipDirNode("b.cbz", "b.cbz", false),
        ))
        assertTrue(ArchiveEngine.isFlatZip(root))
    }

    @Test
    fun `isFlatZip 含 dir children 返回 false`() {
        val root = ArchiveEngine.ZipDirNode("", "", true, mutableListOf(
            ArchiveEngine.ZipDirNode("sub", "sub/", true),
            ArchiveEngine.ZipDirNode("a.mobi", "a.mobi", false),
        ))
        assertFalse(ArchiveEngine.isFlatZip(root))
    }
}