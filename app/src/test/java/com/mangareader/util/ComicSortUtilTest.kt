package com.mangareader.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ★ F5.3 测试架起步（JUnit4，纯 JVM 无 Android 依赖）。
 * ComicSortUtil 是全书库/阅读器排序的唯一真相源，F2.1 相邻书自然序也依赖它——这里锁住语义。
 */
class ComicSortUtilTest {

    @Test
    fun getPathDepth_countsBothSlashKinds() {
        assertEquals(0, ComicSortUtil.getPathDepth("file.zip"))
        assertEquals(2, ComicSortUtil.getPathDepth("a/b/c.zip"))
        assertEquals(2, ComicSortUtil.getPathDepth("a\\b\\c.zip"))
    }

    @Test
    fun comparator_shallowDepthFirst() {
        val sorted = listOf(
            "a/b/卷02.zip",
            "a/卷01.zip",
        ).sortedWith(ComicSortUtil.fileNameComparator)
        assertEquals(listOf("a/卷01.zip", "a/b/卷02.zip"), sorted)
    }

    @Test
    fun comparator_naturalOrderNotLexicographic() {
        val sorted = listOf(
            "/x/卷10.zip",
            "/x/卷2.zip",
            "/x/卷1.zip",
        ).sortedWith(ComicSortUtil.fileNameComparator)
        // 字典序会把 卷10 排在 卷2 前面；自然序必须 1 < 2 < 10
        assertEquals(listOf("/x/卷1.zip", "/x/卷2.zip", "/x/卷10.zip"), sorted)
    }
}
