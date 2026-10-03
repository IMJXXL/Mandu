package com.mangareader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 三层模型单测（2026-10-03 评审 P0 清单）：
 * 优先级（临时>覆盖>全局>默认）、总开关休眠、豁免字段 T+Oe 不可达、条件锁定、写路由、load 零写盘。
 * 用 FakeSharedPreferences 纯 JVM 跑（本机 Robolectric 引导即静默崩溃，环境怪癖）。
 */
class ReaderScopeModelTest {

    private val prefs = FakeSharedPreferences()

    @Before
    fun setUp() {
        prefs.edit().clear().apply()
        ReaderSessionStore.clearAll()
        // 默认总开关开
        prefs.edit().putBoolean(ReaderScope.MASTER_KEY, true).apply()
    }

    private fun putGlobal(key: String, v: Any) {
        when (v) {
            is Boolean -> prefs.edit().putBoolean(key, v)
            is Int -> prefs.edit().putInt(key, v)
            is Float -> prefs.edit().putFloat(key, v)
            is String -> prefs.edit().putString(key, v)
            else -> throw IllegalArgumentException()
        }.apply()
    }

    // ── 优先级：临时 > 覆盖 > 全局 > 默认 ──

    @Test
    fun `priority default when nothing set`() {
        assertEquals(0, ReaderConfig.load(prefs, 1L).brightness)
    }

    @Test
    fun `priority global beats default`() {
        putGlobal("brightness", 50)
        assertEquals(50, ReaderConfig.load(prefs, 1L).brightness)
    }

    @Test
    fun `priority book override beats global`() {
        putGlobal("brightness", 50)
        putGlobal("book_1_brightness", 30)
        assertEquals(30, ReaderConfig.load(prefs, 1L).brightness)
        // 别的书不受影响
        assertEquals(50, ReaderConfig.load(prefs, 2L).brightness)
    }

    @Test
    fun `priority session beats book override`() {
        putGlobal("brightness", 50)
        putGlobal("book_1_brightness", 30)
        ReaderSessionStore.put(1L, "brightness", 10)
        assertEquals(10, ReaderConfig.load(prefs, 1L).brightness)
        // 会话按书隔离：书 2 无覆盖 → 全局打底
        assertEquals(50, ReaderConfig.load(prefs, 2L).brightness)
    }

    // ── 总开关：关 = 本书覆盖休眠（读全局），会话仍生效 ──

    @Test
    fun `master off makes book override dormant`() {
        putGlobal("brightness", 50)
        putGlobal("book_1_brightness", 30)
        prefs.edit().putBoolean(ReaderScope.MASTER_KEY, false).apply()
        assertEquals(50, ReaderConfig.load(prefs, 1L).brightness)
        // 会话不受总开关影响
        ReaderSessionStore.put(1L, "brightness", 10)
        assertEquals(10, ReaderConfig.load(prefs, 1L).brightness)
    }

    @Test
    fun `master on restores book override`() {
        putGlobal("book_1_brightness", 30)
        prefs.edit().putBoolean(ReaderScope.MASTER_KEY, false).apply()
        assertEquals(0, ReaderConfig.load(prefs, 1L).brightness)
        prefs.edit().putBoolean(ReaderScope.MASTER_KEY, true).apply()
        assertEquals(30, ReaderConfig.load(prefs, 1L).brightness)
    }

    // ── 豁免字段（pageLayout）：覆盖恒参与解析 ──

    @Test
    fun `exempt pageLayout override survives master off`() {
        putGlobal("pageLayout", "DOUBLE")
        putGlobal("book_1_pageLayout", "DOUBLE_SHIFTED")
        prefs.edit().putBoolean(ReaderScope.MASTER_KEY, false).apply()
        assertEquals(
            ReaderConfig.PageLayout.DOUBLE_SHIFTED,
            ReaderConfig.load(prefs, 1L).pageLayout,
        )
        // 无覆盖的书读全局（852 语义默认层）
        assertEquals(
            ReaderConfig.PageLayout.DOUBLE,
            ReaderConfig.load(prefs, 2L).pageLayout,
        )
    }

    @Test
    fun `exempt field write clears session bucket - T plus Oe unreachable`() {
        prefs.edit().putBoolean(ReaderScope.MASTER_KEY, false).apply()
        // 模拟遗留临时态
        ReaderSessionStore.put(1L, "pageLayout", "SINGLE")
        assertTrue(ReaderSessionStore.get(1L)?.containsKey("pageLayout") == true)
        // 豁免字段写：恒写本书覆盖并清临时桶（T+Oe 不可达，技术宅 E2 定案）
        ReaderScope.write(prefs, 1L, "pageLayout", "DOUBLE_SHIFTED")
        assertFalse(ReaderSessionStore.get(1L)?.containsKey("pageLayout") == true)
        assertEquals(
            ReaderConfig.PageLayout.DOUBLE_SHIFTED,
            ReaderConfig.load(prefs, 1L).pageLayout,
        )
    }

    // ── 条件锁定（翻页闪烁组）──

    @Test
    fun `flash global takeover when global on`() {
        putGlobal("isFlipFlashEnabled", true)
        putGlobal("flipFlashDuration", 200)
        putGlobal("book_1_flipFlashDuration", 300)
        // 全局接管：书键不参与
        assertEquals(200, ReaderConfig.load(prefs, 1L).flipFlashDuration)
    }

    @Test
    fun `flash book decides when global off and master on`() {
        putGlobal("isFlipFlashEnabled", false)
        putGlobal("book_1_flipFlashDuration", 300)
        assertEquals(300, ReaderConfig.load(prefs, 1L).flipFlashDuration)
    }

    @Test
    fun `flash dormant when global off and master off`() {
        putGlobal("isFlipFlashEnabled", false)
        putGlobal("book_1_flipFlashDuration", 300)
        prefs.edit().putBoolean(ReaderScope.MASTER_KEY, false).apply()
        assertEquals(100, ReaderConfig.load(prefs, 1L).flipFlashDuration) // 出厂默认
    }

    // ── 写路由 ──

    @Test
    fun `write routes to book key when master on`() {
        ReaderScope.write(prefs, 1L, "brightness", 42)
        assertEquals(42, prefs.getInt("book_1_brightness", -1))
        assertEquals(-1, prefs.getInt("brightness", -1))
        assertEquals(42, ReaderConfig.load(prefs, 1L).brightness)
    }

    @Test
    fun `write routes to session when master off`() {
        prefs.edit().putBoolean(ReaderScope.MASTER_KEY, false).apply()
        ReaderScope.write(prefs, 1L, "brightness", 42)
        // 不落盘
        assertEquals(-1, prefs.getInt("book_1_brightness", -1))
        assertEquals(42, ReaderSessionStore.get(1L)?.get("brightness"))
        assertEquals(42, ReaderConfig.load(prefs, 1L).brightness)
    }

    @Test
    fun `global only key always writes global`() {
        prefs.edit().putBoolean(ReaderScope.MASTER_KEY, false).apply()
        ReaderScope.write(prefs, 1L, "tileModeEnabled", true)
        assertTrue(prefs.getBoolean("tileModeEnabled", false))
        assertEquals(true, ReaderConfig.load(prefs, 1L).tileModeEnabled)
    }

    // ── 清除 ──

    @Test
    fun `clearAllBook removes overrides and session bucket`() {
        ReaderScope.write(prefs, 1L, "brightness", 42)
        ReaderScope.write(prefs, 1L, "pageLayout", "DOUBLE")
        ReaderSessionStore.put(1L, "contrast", 150)
        ReaderScope.clearAllBook(prefs, 1L)
        assertFalse(prefs.contains("book_1_brightness"))
        assertFalse(prefs.contains("book_1_pageLayout"))
        assertTrue(ReaderSessionStore.get(1L).isNullOrEmpty())
        assertEquals(0, ReaderConfig.load(prefs, 1L).brightness)
    }

    // ── load 零写盘（硬不变量，QA C2）──

    @Test
    fun `load never writes prefs`() {
        putGlobal("brightness", 30)
        putGlobal("book_1_brightness", 20)
        putGlobal("book_2_pageLayout", "DOUBLE")
        val before = prefs.all.toMap()
        ReaderConfig.load(prefs, 1L)
        ReaderConfig.load(prefs, 2L)
        ReaderConfig.load(prefs, -1L)
        assertEquals(before, prefs.all)
    }

    // ── diff 与会话回贴 ──

    @Test
    fun `changedFields reports only real changes`() {
        val a = ReaderConfig()
        val b = a.copy(brightness = 30, pageLayout = ReaderConfig.PageLayout.DOUBLE)
        val changed = ReaderConfig.changedFields(a, b)
        assertEquals(setOf("brightness", "pageLayout"), changed.toSet())
    }

    @Test
    fun `session override roundtrip via applySessionOverride`() {
        val base = ReaderConfig()
        val keys = listOf(
            "brightness" to 30, "gamma" to 120, "isGrayscale" to true,
            "ambienceSound" to "rain", "pageLayout" to "DOUBLE_SHIFTED",
            "scrollMode" to "VERTICAL",
        )
        var cfg = base
        keys.forEach { (k, v) -> cfg = ReaderConfig.applySessionOverride(cfg, k, v) }
        assertEquals(30, cfg.brightness)
        assertEquals(120, cfg.gamma)
        assertEquals(true, cfg.isGrayscale)
        assertEquals("rain", cfg.ambienceSound)
        assertEquals(ReaderConfig.PageLayout.DOUBLE_SHIFTED, cfg.pageLayout)
        assertEquals(ReaderConfig.ScrollMode.VERTICAL, cfg.scrollMode)
    }
}
