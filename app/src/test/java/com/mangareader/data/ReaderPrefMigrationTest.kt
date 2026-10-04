package com.mangareader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 迁移矩阵单测（QA C1：四行升级路径）+ 快照 + 幂等。
 */
class ReaderPrefMigrationTest {

    private val prefs = FakeSharedPreferences()

    private val snapshotDir = File(System.getProperty("java.io.tmpdir"), "pref_snapshots_test")

    @Before
    fun setUp() {
        prefs.edit().clear().apply()
        snapshotDir.deleteRecursively()
    }

    @Test
    fun `row1 - 852 ran (global exists) - zombie keys deleted`() {
        prefs.edit()
            .putString("pageLayout", "DOUBLE")
            .putString("book_5_pageLayout", "DOUBLE_SHIFTED")
            .putString("book_9_pageLayout", "DOUBLE")
            .apply()
        ReaderPrefMigration.runIfNeeded(prefs, snapshotDir)
        assertFalse(prefs.contains("book_5_pageLayout"))
        assertFalse(prefs.contains("book_9_pageLayout"))
        assertEquals("DOUBLE", prefs.getString("pageLayout", null))
        assertEquals(ReaderConfig.PageLayout.DOUBLE, ReaderConfig.load(prefs, 5L).pageLayout)
    }

    @Test
    fun `row2 - pre-852 fresh to v2 (no global) - zombie keys become per-book overrides`() {
        prefs.edit()
            .putString("book_5_pageLayout", "DOUBLE_SHIFTED")
            .putString("book_9_pageLayout", "DOUBLE")
            .apply()
        ReaderPrefMigration.runIfNeeded(prefs, snapshotDir)
        // 转正=零删除：僵尸键保留为本书覆盖。pageLayout 现为预设全局（用户拍板），
        // 覆盖休眠读默认；用户把该项切回"本书单独"后老选择即刻恢复
        assertEquals("DOUBLE_SHIFTED", prefs.getString("book_5_pageLayout", null))
        assertFalse(prefs.contains("pageLayout"))
        assertEquals(ReaderConfig.PageLayout.SINGLE, ReaderConfig.load(prefs, 5L).pageLayout)
        ReaderScope.setScope(prefs, "pageLayout", ReaderScope.SCOPE_BOOK)
        assertEquals(ReaderConfig.PageLayout.DOUBLE_SHIFTED, ReaderConfig.load(prefs, 5L).pageLayout)
        assertEquals(ReaderConfig.PageLayout.DOUBLE, ReaderConfig.load(prefs, 9L).pageLayout)
    }

    @Test
    fun `row3 - brand new install - nothing beyond flag and readBackground seed`() {
        ReaderPrefMigration.runIfNeeded(prefs, snapshotDir)
        assertFalse(prefs.contains("pageLayout"))
        assertEquals("WHITE", prefs.getString("readBackground", null))
        assertTrue(prefs.getBoolean("scope_mig_v1_done", false))
    }

    @Test
    fun `snapshot written with meta and rolling keep of 3`() {
        repeat(5) {
            prefs.edit().putBoolean("scope_mig_v1_done", false).apply()
            ReaderPrefMigration.runIfNeeded(prefs, snapshotDir)
        }
        val dir = snapshotDir
        val files = dir.listFiles()?.filter { it.name.startsWith("scope_mig_v1_") } ?: emptyList()
        assertEquals(3, files.size)
        val text = files.first().readText()
        assertTrue(text.contains("#scope_mig_v1"))
        assertTrue(text.contains("readBackground"))
    }

    @Test
    fun `idempotent - second run changes nothing`() {
        prefs.edit()
            .putString("pageLayout", "DOUBLE")
            .putString("book_5_pageLayout", "DOUBLE_SHIFTED")
            .putInt("brightness", 30)
            .apply()
        ReaderPrefMigration.runIfNeeded(prefs, snapshotDir)
        val after1 = prefs.all.toMap()
        ReaderPrefMigration.runIfNeeded(prefs, snapshotDir)
        assertEquals(after1, prefs.all)
    }

    @Test
    fun `book keys transferred as overrides - existing book settings survive`() {
        // 整体转正：存量 book_ 键零删除，load 后仍按书生效
        prefs.edit()
            .putInt("book_1_brightness", 30)
            .putBoolean("book_1_isGrayscale", true)
            .putString("pageLayout", "SINGLE")
            .apply()
        ReaderPrefMigration.runIfNeeded(prefs, snapshotDir)
        assertEquals(30, ReaderConfig.load(prefs, 1L).brightness)
        assertEquals(true, ReaderConfig.load(prefs, 1L).isGrayscale)
        // 别的书不受影响
        assertEquals(0, ReaderConfig.load(prefs, 2L).brightness)
    }

    @Test
    fun `restore latest snapshot - book keys rolled back`() {
        putKeys()
        ReaderPrefMigration.runIfNeeded(prefs, snapshotDir)
        // 模拟重置：清掉覆盖并新增别的覆盖
        prefs.edit().remove("book_1_brightness").putInt("book_1_gamma", 130).apply()
        assertEquals(0, ReaderConfig.load(prefs, 1L).brightness)
        // 回滚
        val latest = snapshotDir.listFiles()!!.filter { it.name.startsWith("scope_mig_v1_") }.maxBy { it.name }
        assertTrue(ReaderPrefMigration.restore(prefs, latest))
        assertEquals(30, ReaderConfig.load(prefs, 1L).brightness)
        assertFalse(prefs.contains("book_1_gamma")) // 重置新增的键被回滚删除
    }

    private fun putKeys() {
        prefs.edit().putInt("book_1_brightness", 30).putBoolean("book_1_isGrayscale", true).apply()
    }
}
