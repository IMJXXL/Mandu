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
        assertTrue(d("某书 v3.5.epub").contains("Vol.03.5"))
    }
    @Test fun volumeRange() {
        assertEquals(1, v("某全集 v01-09.cbz"))
        assertTrue(d("某全集 v01-09.cbz").contains("Vol.01-Vol.09"))
    }

    // ===== 标题 + 章节 =====
    @Test fun chapterStyle() {
        val p = BookTitleParser.parse("[组] 一拳超人 c042.5.zip")
        assertEquals("一拳超人", p.title)
        assertEquals(42.5, p.chapter!!, 1e-9)
        assertTrue(d("[组] 一拳超人 c042.5.zip").contains("Ch.42.5"))
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
        // 纯数字文件名无结构 → parse 标题为空（Kavita 语义），displayTitle 兜底原名
        assertEquals("", t("03.cbz"))
        assertEquals("03", d("03.cbz"))
    }
    @Test fun dotInTitlePreserved() {
        // 名字含点但非扩展名 → 不截断
        assertEquals("Fate.Zero", t("Fate.Zero 第1卷.zip"))
    }

    // ===== 繁转简（仅 displayTitle 应用） =====
    @Test fun traditionalToSimplified() {
        assertEquals("进击的巨人 Vol.01", d("進擊的巨人 v01.cbz"))
        assertTrue(t("進擊的巨人 v01.cbz").contains("進"))
    }
    @Test fun t2sOffKeepsTraditional() {
        assertEquals("進擊的巨人 Vol.01", BookTitleParser.displayTitle("進擊的巨人 v01.cbz", t2s = false))
    }
    @Test fun simplifiedUnchanged() {
        assertEquals("进击的巨人 Vol.01", d("进击的巨人 v01.cbz"))
    }

    // ===== 显示名 =====
    @Test fun displayVolumeZeroPadded() {
        assertEquals("海贼王 Vol.99", d("海贼王 Vol.99.zip"))
        assertEquals("斗破苍穹 Vol.03", d("斗破苍穹 第03卷.cbz"))
    }

    // ===== 卷字在后（卷01，日版常见） =====
    @Test fun cjkVolumePrefix() {
        assertEquals("海贼王", t("海贼王 卷01.cbz"))
        assertEquals(1, v("海贼王 卷01.cbz"))
        assertEquals("进击的巨人 Vol.01", d("進擊的巨人 卷01.mobi"))
    }

    // ===== 系列名在括号链里（[组][系列]卷01 形态） =====
    @Test fun seriesInsideBrackets() {
        assertEquals("猫之眼 Vol.01", d("[Comic][CAT__S.EYE][貓之眼][北条司][HK]Vol.01.rar"))
        assertEquals("废渊战鬼GACHIAKUTA Vol.01", d("[Kmoe][廢淵戰鬼GACHIAKUTA]卷01.mobi"))
        // 无中文括号段 → 取最长
        assertEquals("Berserk Vol.02", d("[Group][Berserk] v02.cbz"))
    }

    // ===== 原文件名提取（批量改名/长按详情共用） =====
    @Test fun fileNameOfSafPaths() {
        // SAF 紧凑路径：末段 |ext 是扩展名后缀，不是文件名
        assertEquals(
            "[Comic][CAT__S.EYE][貓之眼][北条司][HK]Vol.01.rar",
            BookTitleParser.fileNameOf("a:com.android.externalstorage.documents|primary:浩克下载|primary:浩克下载/[Comic][CAT__S.EYE][貓之眼][北条司][HK]Vol.01.rar|rar")
        )
        // z:（zip 内子目录浏览）+ 尾部 //
        assertEquals(
            "[Kmoe][廢淵戰鬼GACHIAKUTA]卷01.mobi",
            BookTitleParser.fileNameOf("z:a:com.android.externalstorage.documents|primary:浩克下载|primary:浩克下载/[Kmoe][廢淵戰鬼GACHIAKUTA]卷01.mobi|mobi//")
        )
        // 目录书 d: 无 |ext 后缀
        assertEquals("浪客行", BookTitleParser.fileNameOf("d:com.android.externalstorage.documents|primary:漫画|primary:漫画/浪客行"))
        // 普通绝对路径 / 远程路径 / 纯文件名
        assertEquals("海贼王 Vol.01.zip", BookTitleParser.fileNameOf("/storage/emulated/0/漫画/海贼王 Vol.01.zip"))
        assertEquals("海贼王 Vol.01.cbz", BookTitleParser.fileNameOf("remote:1:comic/海贼王 Vol.01.cbz"))
        assertEquals("某书.cbr", BookTitleParser.fileNameOf("\\\\nas\\comic\\某书.cbr"))
    }

    // ===== 改名按钮端到端：SAF 路径 → 原文件名 → 干净书名 =====
    @Test fun retitleEndToEndFromSafPath() {
        val raw = BookTitleParser.fileNameOf(
            "a:com.android.externalstorage.documents|primary:浩克下载|primary:浩克下载/[Comic][CAT__S.EYE][貓之眼][北条司][HK]Vol.01.rar|rar"
        )
        assertEquals("猫之眼 Vol.01", BookTitleParser.displayTitle(raw))
    }

    // ===== epub 带站点尾巴（剑风传奇实库样本） =====
    @Test fun epubWithSiteTags() {
        val raw = BookTitleParser.fileNameOf(
            "a:com.android.externalstorage.documents|primary:Download/2.漫画/普通漫画|primary:Download/2.漫画/普通漫画/剑风传奇/烙印勇士 - 01卷 (三浦建太郎) (z-library.sk, 1lib.sk, z-lib.sk).epub|epub"
        )
        assertEquals("烙印勇士 Vol.01", BookTitleParser.displayTitle(raw))
        val raw2 = BookTitleParser.fileNameOf(
            "a:com.android.externalstorage.documents|primary:Download/2.漫画/普通漫画|primary:Download/2.漫画/普通漫画/剑风传奇/烙印勇士 - 011卷 (三浦建太郎) (z-library.sk, 1lib.sk, z-lib.sk).epub|epub"
        )
        assertEquals("烙印勇士 Vol.11", BookTitleParser.displayTitle(raw2))
    }

    // ===== 子文件夹章节书：系列名沿祖先链逐级找（上级未必是系列——分组夹/合集要跳过） =====
    @Test fun subfolderChapterUsesAncestorSeries() {
        // 河合庄实库样本：…/新漫画-A(树根)/我们大家的河合庄/第1话新室友
        val p = "d:com.android.externalstorage.documents|primary:Download/异次元2/local/新漫画-A|primary:Download/异次元2/local/新漫画-A/我们大家的河合庄/第1话新室友"
        assertEquals(listOf("我们大家的河合庄"), BookTitleParser.ancestorNamesOf(p))
        assertEquals(
            "我们大家的河合庄 Ch.01",
            BookTitleParser.displayTitle(BookTitleParser.fileNameOf(p), true, BookTitleParser.ancestorNamesOf(p))
        )
    }

    @Test fun ancestorSeriesSkipsCategoryFolders() {
        // 用户实库结构：A(树根)/日漫/2025/哆啦a梦/短篇/第1话 —— 类目夹（短篇/日漫）与 2025/A 全跳，取哆啦a梦
        val anc = listOf("短篇", "哆啦a梦", "2025", "日漫", "A")
        assertEquals("哆啦a梦", BookTitleParser.seriesFromAncestors(anc))
        // 树根=A 时祖先不含 A：同样成立
        assertEquals("哆啦a梦", BookTitleParser.seriesFromAncestors(listOf("短篇", "哆啦a梦", "2025", "日漫")))
        // 短篇里还有单篇夹 → 最近可信优先（单篇名当系列名，单篇本身就是一本书）
        assertEquals("野比大雄的恐龙", BookTitleParser.seriesFromAncestors(listOf("野比大雄的恐龙", "短篇", "哆啦a梦", "2025", "日漫")))
        // 类目夹下面没有任何真系列 → null（退回原名+Ch.N）
        assertEquals(null, BookTitleParser.seriesFromAncestors(listOf("短篇", "2025", "日漫")))
        // 端到端：SAF 路径 → 原名 → 显示名（树根=A）
        val p = "d:com.android.externalstorage.documents|primary:A|primary:A/日漫/2025/哆啦a梦/短篇/第1话"
        assertEquals(listOf("短篇", "哆啦a梦", "2025", "日漫"), BookTitleParser.ancestorNamesOf(p))
        assertEquals("哆啦a梦 Ch.01", BookTitleParser.displayTitle(BookTitleParser.fileNameOf(p), true, BookTitleParser.ancestorNamesOf(p)))
        // 精确匹配不误伤真系列名："哆啦A梦短篇集"含"短篇"二字但不是类目夹
        assertEquals("哆啦A梦短篇集", BookTitleParser.seriesFromAncestors(listOf("哆啦A梦短篇集", "日漫")))
    }

    @Test fun ancestorSeriesSkipsGroupingFolders() {
        // 用户场景：合集/A/哆啦A梦/第1话新室友 —— A 和合集都不是系列名
        val anc = listOf("哆啦A梦", "A", "日本漫画合集")
        assertEquals("哆啦A梦", BookTitleParser.seriesFromAncestors(anc))
        // 书直接在分组夹 A 里 → A 与合集都不可信 → 返回 null（退回原名+Ch.N）
        assertEquals(null, BookTitleParser.seriesFromAncestors(listOf("A", "日本漫画合集")))
        // 深层级：…/合集/哆啦A梦/第01卷/第1话 → 跳过卷标记层，取哆啦A梦
        assertEquals("哆啦A梦", BookTitleParser.seriesFromAncestors(listOf("第01卷", "哆啦A梦", "日本漫画合集")))
        // 纯数字/章节名/卷标记开头的祖先都不是系列
        assertEquals(null, BookTitleParser.seriesFromAncestors(listOf("01", "第1话开端", "v01")))
        // 章节标记直接在树根下 → 无祖先可用 → 原名+Ch.N
        val rootBook = "d:com.android.externalstorage.documents|primary:漫画|primary:漫画/第1话开端"
        assertEquals(emptyList<String>(), BookTitleParser.ancestorNamesOf(rootBook))
        assertEquals("第1话开端 Ch.01", BookTitleParser.displayTitle(BookTitleParser.fileNameOf(rootBook), true, BookTitleParser.ancestorNamesOf(rootBook)))
        // 自身有意义的名字 → 不动
        assertEquals("浪客行", BookTitleParser.displayTitle("浪客行", true, listOf("漫画")))
    }
}

