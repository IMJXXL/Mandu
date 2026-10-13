package com.mangareader.panel

import com.mangareader.data.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelGuidePresetTest {

    @Test
    fun `loadAll returns three builtins when empty`() {
        val prefs = FakeSharedPreferences()
        val all = PanelGuidePreset.Store.loadAll(prefs)
        assertEquals("应返回三个内置预设", 3, all.size)
        assertTrue(all.any { it.name == "标准" })
        assertTrue(all.any { it.name == "宽松" })
        assertTrue(all.any { it.name == "严格" })
    }

    @Test
    fun `save then loadAll includes new preset`() {
        val prefs = FakeSharedPreferences()
        val preset = PanelGuidePreset.Preset(
            id = "test1", name = "我的预设", isBuiltin = false,
            sensitivity = 2, dim = 70, anim = false, rtl = 1,
            dimMode = 1, splitMode = 1, transitionMode = 2, displayMode = 1,
            cropMode = 0, slideMs = 300L, precision = 0,
            previewEnabled = true, progressEnabled = true,
            swipeInvert = true, tapInvert = true,
            foldJoinEnabled = true, autoRotateEnabled = true,
            autoRotateDir = 0, crossPageEnabled = true,
        )
        PanelGuidePreset.Store.save(prefs, preset)
        val all = PanelGuidePreset.Store.loadAll(prefs)
        assertEquals("应含 3 内置 + 1 自定义", 4, all.size)
        val saved = all.first { it.id == "test1" }
        assertEquals("我的预设", saved.name)
        assertEquals(2, saved.sensitivity)
        assertEquals(70, saved.dim)
        assertEquals(true, saved.crossPageEnabled)
    }

    @Test
    fun `delete custom preset removes it`() {
        val prefs = FakeSharedPreferences()
        val preset = PanelGuidePreset.Preset(
            id = "del1", name = "待删", isBuiltin = false,
            sensitivity = 1, dim = 55, anim = true, rtl = 0,
            dimMode = 0, splitMode = 0, transitionMode = 0, displayMode = 0,
            cropMode = 2, slideMs = 180L, precision = 1,
            previewEnabled = false, progressEnabled = false,
            swipeInvert = false, tapInvert = false,
            foldJoinEnabled = false, autoRotateEnabled = false,
            autoRotateDir = 2, crossPageEnabled = false,
        )
        PanelGuidePreset.Store.save(prefs, preset)
        PanelGuidePreset.Store.delete(prefs, "del1")
        val all = PanelGuidePreset.Store.loadAll(prefs)
        assertEquals("删除后只剩 3 内置", 3, all.size)
        assertFalse(all.any { it.id == "del1" })
    }

    @Test
    fun `delete builtin preset is no-op`() {
        val prefs = FakeSharedPreferences()
        PanelGuidePreset.Store.delete(prefs, "builtin_standard")
        val all = PanelGuidePreset.Store.loadAll(prefs)
        assertTrue("内置预设不可删除", all.any { it.id == "builtin_standard" })
    }

    @Test
    fun `rename custom preset updates name`() {
        val prefs = FakeSharedPreferences()
        val preset = PanelGuidePreset.Preset(
            id = "ren1", name = "原名", isBuiltin = false,
            sensitivity = 1, dim = 55, anim = true, rtl = 0,
            dimMode = 0, splitMode = 0, transitionMode = 0, displayMode = 0,
            cropMode = 2, slideMs = 180L, precision = 1,
            previewEnabled = false, progressEnabled = false,
            swipeInvert = false, tapInvert = false,
            foldJoinEnabled = false, autoRotateEnabled = false,
            autoRotateDir = 2, crossPageEnabled = false,
        )
        PanelGuidePreset.Store.save(prefs, preset)
        PanelGuidePreset.Store.rename(prefs, "ren1", "新名")
        val all = PanelGuidePreset.Store.loadAll(prefs)
        val renamed = all.first { it.id == "ren1" }
        assertEquals("新名", renamed.name)
        assertEquals("设置内容不变", 1, renamed.sensitivity)
    }

    @Test
    fun `rename builtin preset is no-op`() {
        val prefs = FakeSharedPreferences()
        PanelGuidePreset.Store.rename(prefs, "builtin_standard", "不可改")
        val all = PanelGuidePreset.Store.loadAll(prefs)
        val standard = all.first { it.id == "builtin_standard" }
        assertEquals("内置预设不可重命名", "标准", standard.name)
    }

    @Test
    fun `corrupted preset skipped`() {
        val prefs = FakeSharedPreferences()
        prefs.map["panelGuidePresets"] = "[{bad json}"
        val all = PanelGuidePreset.Store.loadAll(prefs)
        assertEquals("损坏预设跳过保留内置", 3, all.size)
    }

    @Test
    fun `builtin presets have correct sensitivity`() {
        assertEquals(1, PanelGuidePreset.BUILTIN_STANDARD.sensitivity)
        assertEquals(1, PanelGuidePreset.BUILTIN_STANDARD.precision)
        assertEquals(0, PanelGuidePreset.BUILTIN_LOOSE.sensitivity)
        assertEquals(0, PanelGuidePreset.BUILTIN_LOOSE.precision)
        assertEquals(2, PanelGuidePreset.BUILTIN_STRICT.sensitivity)
        assertEquals(1, PanelGuidePreset.BUILTIN_STRICT.precision)
    }

    @Test
    fun `save overwrite existing name`() {
        val prefs = FakeSharedPreferences()
        val p1 = PanelGuidePreset.Preset(
            id = "p1", name = "同 名", isBuiltin = false,
            sensitivity = 0, dim = 55, anim = true, rtl = 0,
            dimMode = 0, splitMode = 0, transitionMode = 0, displayMode = 0,
            cropMode = 2, slideMs = 180L, precision = 1,
            previewEnabled = false, progressEnabled = false,
            swipeInvert = false, tapInvert = false,
            foldJoinEnabled = false, autoRotateEnabled = false,
            autoRotateDir = 2, crossPageEnabled = false,
        )
        PanelGuidePreset.Store.save(prefs, p1)
        val p2 = p1.copy(id = "p2", sensitivity = 2)
        PanelGuidePreset.Store.save(prefs, p2)
        val all = PanelGuidePreset.Store.loadAll(prefs)
        assertEquals("同名覆盖不新增", 4, all.size)
        assertNotNull(all.first { it.name == "同 名" })
    }
}