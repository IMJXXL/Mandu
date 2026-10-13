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
    fun `priority global beats default for global scope`() {
        // imageScaleMode 预设全局（2026-10-05 替代 fitScreen 布尔）：全局键生效
        putGlobal("imageScaleMode", 0)
        assertEquals(0, ReaderConfig.load(prefs, 1L).imageScaleMode)
    }

    @Test
    fun `book scope key ignores global key - factory fallback`() {
        // 用户定稿：本书作用域的项与全局键彻底脱钩，没调过的书用初始默认值
        putGlobal("brightness", 50)
        assertEquals(0, ReaderConfig.load(prefs, 1L).brightness)
    }

    @Test
    fun `priority book override beats everything`() {
        putGlobal("brightness", 50)
        putGlobal("book_1_brightness", 30)
        assertEquals(30, ReaderConfig.load(prefs, 1L).brightness)
        // 别的书没调过 → 初始默认值（全局键对本书项无效）
        assertEquals(0, ReaderConfig.load(prefs, 2L).brightness)
    }

    @Test
    fun `priority session beats book override`() {
        putGlobal("book_1_brightness", 30)
        ReaderSessionStore.put(1L, "brightness", 10)
        assertEquals(10, ReaderConfig.load(prefs, 1L).brightness)
        // 会话按书隔离：书 2 无覆盖 → 初始默认值
        assertEquals(0, ReaderConfig.load(prefs, 2L).brightness)
    }

    // ── 总开关：只管写入（新调整记不记），读取侧无开关 ──

    @Test
    fun `master off - existing book override still applies (read side has no switch)`() {
        putGlobal("brightness", 50)
        putGlobal("book_1_brightness", 30)
        prefs.edit().putBoolean(ReaderScope.MASTER_KEY, false).apply()
        // 用户定稿：书里调过的永远最高优先，总开关只管"之后的新调整记不记"
        assertEquals(30, ReaderConfig.load(prefs, 1L).brightness)
        // 会话层仍在本书覆盖之上
        ReaderSessionStore.put(1L, "brightness", 10)
        assertEquals(10, ReaderConfig.load(prefs, 1L).brightness)
    }

    @Test
    fun `master toggle never changes reads`() {
        putGlobal("book_1_brightness", 30)
        assertEquals(30, ReaderConfig.load(prefs, 1L).brightness)
        prefs.edit().putBoolean(ReaderScope.MASTER_KEY, false).apply()
        assertEquals(30, ReaderConfig.load(prefs, 1L).brightness)
        prefs.edit().putBoolean(ReaderScope.MASTER_KEY, true).apply()
        assertEquals(30, ReaderConfig.load(prefs, 1L).brightness)
    }

    // ── 预设作用域（2026-10-04 重构）：预设全局项读/写全局键；显式指定压过预设 ──

    @Test
    fun `preset global pageLayout ignores stale book key and writes global`() {
        // 852 时代遗留的本书键不再遮蔽全局语义（用户拍板：单双页全局）
        putGlobal("pageLayout", "DOUBLE")
        putGlobal("book_1_pageLayout", "DOUBLE_SHIFTED")
        assertEquals(ReaderConfig.PageLayout.DOUBLE, ReaderConfig.load(prefs, 1L).pageLayout)
        // 书内切换（预设全局）写全局键；遗留书键留存但不再参与解析
        ReaderScope.write(prefs, 1L, "pageLayout", "DOUBLE_SHIFTED")
        assertEquals("DOUBLE_SHIFTED", prefs.getString("pageLayout", null))
        assertEquals(ReaderConfig.PageLayout.DOUBLE_SHIFTED, ReaderConfig.load(prefs, 1L).pageLayout)
    }

    @Test
    fun `preset global pdfQuality is locked now`() {
        // 性能与解码组已按用户定稿固定全局：显式切单本也无效
        ReaderScope.setScope(prefs, "pdfQuality", ReaderScope.SCOPE_BOOK)
        putGlobal("pdfQuality", 3.0f)
        ReaderScope.write(prefs, 1L, "pdfQuality", 4.5f)
        assertEquals(4.5f, prefs.getFloat("pdfQuality", -1f), 0.001f)
        assertFalse(prefs.contains("book_1_pdfQuality"))
        assertEquals(4.5f, ReaderConfig.load(prefs, 1L).pdfQuality, 0.001f)
        assertEquals(4.5f, ReaderConfig.load(prefs, 2L).pdfQuality, 0.001f)
    }

    @Test
    fun `explicit book scope with master off goes session (no exemption anymore)`() {
        // 终版语义：总开关是一级总闸，关闭后所有"本书"作用域的新调整都不记忆
        ReaderScope.setScope(prefs, "orientation", ReaderScope.SCOPE_BOOK)
        prefs.edit().putBoolean(ReaderScope.MASTER_KEY, false).apply()
        ReaderScope.write(prefs, 1L, "orientation", 1)
        assertEquals(-1, prefs.getInt("book_1_orientation", -1))
        assertEquals(1, ReaderSessionStore.get(1L)?.get("orientation"))
        assertEquals(1, ReaderConfig.load(prefs, 1L).orientation)
    }

    @Test
    fun `explicit global scope overrides preset book`() {
        // 用户在偏好页把亮度（预设单本）切成"全局生效"→ 书内改亮度对所有书生效
        ReaderScope.setScope(prefs, "brightness", ReaderScope.SCOPE_GLOBAL)
        ReaderScope.write(prefs, 1L, "brightness", 70)
        assertEquals(70, prefs.getInt("brightness", -1))
        assertFalse(prefs.contains("book_1_brightness"))
        assertEquals(70, ReaderConfig.load(prefs, 1L).brightness)
        assertEquals(70, ReaderConfig.load(prefs, 2L).brightness)
    }

    // ── 阅读模式（readMode 特殊双键项）──

    @Test
    fun `readMode writes book key by preset and resolves per book`() {
        ReaderScope.write(prefs, 1L, ReaderScope.READ_MODE_KEY, 3)
        assertEquals(3, prefs.getInt("read_mode_1", -1))
        assertFalse(prefs.contains(ReaderScope.READ_MODE_GLOBAL_KEY))
        assertEquals(3, ReaderScope.readModeOrdinal(prefs, 1L))
        assertEquals(-1, ReaderScope.readModeOrdinal(prefs, 2L))
    }

    @Test
    fun `readMode explicit global writes default and clears book residue`() {
        prefs.edit().putInt("read_mode_1", 3).apply()
        ReaderScope.setScope(prefs, ReaderScope.READ_MODE_KEY, ReaderScope.SCOPE_GLOBAL)
        ReaderScope.write(prefs, 1L, ReaderScope.READ_MODE_KEY, 4)
        assertEquals(4, prefs.getInt(ReaderScope.READ_MODE_GLOBAL_KEY, -1))
        // 本书残留已清：全局即刻接管这本书
        assertFalse(prefs.contains("read_mode_1"))
        assertEquals(4, ReaderScope.readModeOrdinal(prefs, 1L))
        assertTrue(ReaderScope.hasBookModeChoice(prefs, 2L).not())
    }

    @Test
    fun `readMode session layer when master off`() {
        prefs.edit().putBoolean(ReaderScope.MASTER_KEY, false).apply()
        ReaderScope.write(prefs, 1L, ReaderScope.READ_MODE_KEY, 2)
        assertEquals(-1, prefs.getInt("read_mode_1", -1))
        assertEquals(2, ReaderScope.readModeOrdinal(prefs, 1L))
    }

    // ── 固定全局（翻页闪烁组等阅读设置纳管的项，2026-10-04 定稿）──

    @Test
    fun `flash group locked global - book key ignored on read and write`() {
        putGlobal("flipFlashDuration", 200)
        putGlobal("book_1_flipFlashDuration", 300)
        // 固定全局：书键不参与解析
        assertEquals(200, ReaderConfig.load(prefs, 1L).flipFlashDuration)
        // 书内调整（总开关开）也写全局键
        ReaderScope.write(prefs, 1L, "flipFlashDuration", 250)
        assertEquals(250, prefs.getInt("flipFlashDuration", -1))
        assertEquals(250, ReaderConfig.load(prefs, 1L).flipFlashDuration)
    }

    @Test
    fun `clickFlipType book scope writes to book key`() {
        ReaderScope.setScope(prefs, "clickFlipType", ReaderScope.SCOPE_BOOK)
        ReaderScope.write(prefs, 1L, "clickFlipType", "NO")
        assertEquals("NO", prefs.getString("book_1_clickFlipType", null))
        assertFalse(prefs.contains("clickFlipType"))
    }

    @Test
    fun `autoFlipInterval locked global survives master off`() {
        prefs.edit().putBoolean(ReaderScope.MASTER_KEY, false).apply()
        ReaderScope.write(prefs, 1L, "autoFlipInterval", 5000)
        assertEquals(5000, prefs.getInt("autoFlipInterval", 0))
        assertEquals(-1, prefs.getInt("book_1_autoFlipInterval", -1))
    }

    @Test
    fun `explicit book scope on preset global works - imageScaleMode`() {
        // 画面缩放档位（2026-10-05 替代 fitScreen 布尔）：预设全局、可切单本（不在固定全局集）
        ReaderScope.setScope(prefs, "imageScaleMode", ReaderScope.SCOPE_BOOK)
        ReaderScope.write(prefs, 1L, "imageScaleMode", 0)
        assertEquals(0, prefs.getInt("book_1_imageScaleMode", -1))
        assertEquals(0, ReaderConfig.load(prefs, 1L).imageScaleMode)
        // 其他书不受影响（初始默认 0=适应屏幕，2026-10-06 对齐 mihon）
        assertEquals(0, ReaderConfig.load(prefs, 2L).imageScaleMode)
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
        ReaderScope.write(prefs, 1L, ReaderScope.READ_MODE_KEY, 3)
        ReaderSessionStore.put(1L, "contrast", 150)
        ReaderScope.clearAllBook(prefs, 1L)
        assertFalse(prefs.contains("book_1_brightness"))
        assertFalse(prefs.contains("read_mode_1"))
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
