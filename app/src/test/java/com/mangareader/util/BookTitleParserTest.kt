package com.mangareader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** BookTitleParser 用例（规则参考 chaptr README 的公开示例与中文场景自写） */
class BookTitleParserTest {

    private fun t(name: String) = BookTitleParser.parse(name).title
    private fun v(name: String) = BookTitleParser.parse(name).volume
    private fun d(name: String) = BookTitleParser.displayTitle(name)

    // ===== 标题 + 卷号 =====
    @Test fun groupBracketsStripped() {
        assertEquals("进击的巨人", t("[汉化组] 进击的巨人 v03.cbz"))
        assertEquals(3, v("[汉化组] 进击的巨人 v03.cbz"))
    }
    @Test fun volDotStyle() {
        assertEquals("海贼王", t("海贼王 Vol.99.zip"))
        assertEquals(99, v("海贼王 Vol.99.zip"))
    }
    @Test fun volumeKeywordStyle() {
        assertEquals("某漫画", t("某漫画 Volume 11 (2020).zip"))
        assertEquals(11, v("某漫画 Volume 11 (2020).zip"))
    }
    @Test fun cjkVolumePostfix() {
        assertEquals("斗破苍穹", t("斗破苍穹 第03卷.cbz"))
        assertEquals(3, v("斗破苍穹 第03卷.cbz"))
        assertEquals("浪客行", t("[组] 浪客行 12巻.rar"))
        assertEquals(12, v("[组] 浪客行 12巻.rar"))
    }
    @Test fun decimalVolume() {
        assertEquals(3, v("某书 v3.5.epub"))
        assertTrue(d("某书 v3.5.epub").contains("3.5"))
    }
    @Test fun volumeRange() {
        assertEquals(1, v("某全集 v01-09.cbz"))
        assertTrue(d("某全集 v01-09.cbz").contains("v01-09"))
    }

    // ===== 标题 + 章节 =====
    @Test fun chapterStyle() {
        val p = BookTitleParser.parse("[组] 一拳超人 c042.5.zip")
        assertEquals("一拳超人", p.title)
        assertEquals(42.5, p.chapter!!, 1e-9)
        assertTrue(d("[组] 一拳超人 c042.5.zip").contains("c42.5"))
    }
    @Test fun chineseChapter() {
        assertEquals("某漫画", t("某漫画 第25话.zip"))
        assertEquals(25.0, BookTitleParser.parse("某漫画 第25话.zip").chapter!!, 1e-9)
    }
    @Test fun chapterKeyword() {
        assertEquals("一人之下", t("一人之下 Chapter 406.zip"))
        assertEquals(406.0, BookTitleParser.parse("一人之下 Chapter 406.zip").chapter!!, 1e-9)
    }

    // ===== 歧义与保守回退 =====
    @Test fun kaijuNo8BareNumber() {
        // 组名括号提供上下文 → 尾部裸数字按章节
        val p = BookTitleParser.parse("[组] Kaiju No. 8 036.cbz")
        assertEquals("Kaiju No. 8", p.title)
        assertEquals(36.0, p.chapter!!, 1e-9)
    }
    @Test fun plainTitleUnchanged() {
        // 无任何标记 → 原文（仅下划线转空格由 title 清理承担；纯标题保持）
        assertEquals("浪客行", t("浪客行.cbz"))
        assertEquals(null, v("浪客行.cbz"))
    }
    @Test fun numericFileFallsBack() {
        // 纯数字文件名不当书名 → 回退原名
        assertEquals("03", t("03.cbz"))
    }
    @Test fun dotInTitlePreserved() {
        // 名字含点但非扩展名 → 不截断
        assertEquals("Fate.Zero", t("Fate.Zero 第1卷.zip"))
    }

    // ===== 繁转简（仅 displayTitle 应用） =====
    @Test fun traditionalToSimplified() {
        assertEquals("进击的巨人 v01", d("進擊的巨人 v01.cbz"))
        assertTrue(t("進擊的巨人 v01.cbz").contains("進"))
    }
    @Test fun simplifiedUnchanged() {
        assertEquals("进击的巨人 v01", d("进击的巨人 v01.cbz"))
    }

    // ===== 显示名 =====
    @Test fun displayVolumeZeroPadded() {
        assertEquals("海贼王 v99", d("海贼王 Vol.99.zip"))
        assertEquals("斗破苍穹 v03", d("斗破苍穹 第03卷.cbz"))
    }
}
