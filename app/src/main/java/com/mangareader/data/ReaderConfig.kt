package com.mangareader.data

import android.content.Context
import android.content.SharedPreferences

private fun SharedPreferences.safeGetBoolean(key: String, default: Boolean): Boolean =
    try { getBoolean(key, default) } catch (_: ClassCastException) { default }
private fun SharedPreferences.safeGetInt(key: String, default: Int): Int =
    try { getInt(key, default) } catch (_: ClassCastException) { default }
private fun SharedPreferences.safeGetFloat(key: String, default: Float): Float =
    try { getFloat(key, default) } catch (_: ClassCastException) { default }
private fun SharedPreferences.safeGetLong(key: String, default: Long): Long =
    try { getLong(key, default) } catch (_: ClassCastException) { default }

data class ReaderConfig(
    val scrollMode: ScrollMode = ScrollMode.HORIZONTAL_LEFT,
    val clickFlipType: ClickFlipType = ClickFlipType.LEFT_RIGHT,
    val isFlipAnimation: Boolean = true,
    val isPageCurlAnimation: Boolean = false,
    /** 双页仿真翻页「仅画面区域」模式：翻页动画只发生在画面矩形内，留边不参与（默认关=整页翻） */
    val isCurlContentAreaOnly: Boolean = false,
    /** 双页仿真翻页背面透出程度：翻页动画背面显示下一页内容的强度 0~1（0=纯背景色，1=完全清晰，默认 0.1=纸张背透） */
    val curlBackContentAlpha: Float = 0.1f,
    /** 仿真翻页投影阴影浓度 0~0.5（0=关闭阴影，默认 0.2） */
    val curlShadowAlpha: Float = 0.2f,
    val isShowPageNum: Boolean = true,
    val isKeepScreenOn: Boolean = true,
    val isTrimWhiteBorder: Boolean = false,
    val trimFactor: Float = 0.02f,
    /** 像素平均亮度超过此值视为"白色"，越小越激进（更多像素被识别为白色），越大越保守 */
    val trimThreshold: Int = 220,
    /** 一行/列中超过阈值的像素占比达到此值才算"白边"，越小越容易判定为白边，越大越保守 */
    val trimWhiteRatio: Float = 0.90f,
    val imageMarginH: Int = 0,
    val readBackground: ReadBackground = ReadBackground.WHITE,
    val isEinkMode: Boolean = false,
    /** 墨水屏灰阶抖动（256级模拟）：误差扩散/有序抖动把 8bit 灰度映射到面板实际档位（需重进生效） */
    val isEinkDither: Boolean = false,
    /** 抖动算法：0=Floyd-Steinberg 1=Atkinson 2=Stucki 3=Bayer 4×4 */
    val einkDitherAlgorithm: Int = 0,
    /** 量化档位：16（主流 4bit 面板）/32/64 */
    val einkDitherLevels: Int = 16,
    val einkDecodeSize: Int = 1920,
    val einkUiHideDelay: Long = 6000L,
    val pdfQuality: Float = 3.0f,
    val isImmersiveMode: Boolean = true,
    val orientation: Int = -1,
    val autoFlipInterval: Int = 0,
    val isGrayscale: Boolean = false,
    val isInvertColors: Boolean = false,
    val isFlipFlashEnabled: Boolean = false,
    val flipFlashDuration: Int = 100,
    val flipFlashInterval: Int = 1,
    val flipFlashColor: Int = 0,
    /** ★ 运行时标记（不落盘）：全局翻页闪烁已开启 → 本组值整组来自全局键，
     *   阅读内常规据此灰掉单本设置（全局 > 单本，见 load 的合并逻辑） */
    val flipFlashGlobalActive: Boolean = false,
    val pageLayout: PageLayout = PageLayout.SINGLE,
    val useSSIV: Boolean = true,
    val landscapeZoom: Boolean = false,
    val fitScreen: Boolean = false,
    val panNavigation: Boolean = true,
    val doublePageShift: Int = 0,
    val doublePageSplit: Boolean = false,
    val doublePageReverse: Boolean = false,

    val autoWebtoon: Boolean = true,
    val chapterTransition: Boolean = true,
    val centerGapType: Int = 0,
    // ★ N1：卷末无缝续读（默认关；仅同一本书的分卷之间生效，跨书永不自动跳）
    val seamlessNextVolume: Boolean = false,
    val forceFullDecode: Boolean = false,
    val tileModeEnabled: Boolean = false,
    val downsampleTier: DownsampleTier = DownsampleTier.STANDARD,
    val tallSplitEnabled: Boolean = false
) {
    enum class ScrollMode { VERTICAL, HORIZONTAL_LEFT, HORIZONTAL_RIGHT }
    @Deprecated("Dead code - navigation uses Int field navigationMode instead") enum class ClickFlipType { NO, LEFT_RIGHT, DIAGONAL_LB_RT, DIAGONAL_LT_RB }
    @Deprecated("Dead code - navigationMode is stored as Int, this enum is never referenced") enum class NavigationMode(val label: String) {
        DEFAULT("默认"), L_NAV("L形"), KINDLISH("Kindlish"), EDGE("边沿"), RIGHT_LEFT("右与左")
    }
    enum class ReadBackground { DEFAULT, LIGHT, DARK, WHITE, GRAY }
    enum class PageLayout(val label: String) { SINGLE("单页"), DOUBLE("双页"), DOUBLE_SHIFTED("双页跳过首页") }
    enum class DownsampleTier(val threshold: Long, val label: String) {
        STANDARD(6_291_456L, "标准"), HD(12_582_912L, "高清"), ULTRA_HD(25_165_824L, "超清"), ORIGINAL(Long.MAX_VALUE, "原图")
    }

    fun save(context: Context, bookId: Long = -1) {
        val prefs = context.getSharedPreferences("manga_reader", Context.MODE_PRIVATE)
        val prefix = if (bookId >= 0) "book_${bookId}_" else ""
        prefs.edit().apply {
            putString("${prefix}scrollMode", scrollMode.name)
            putString("${prefix}clickFlipType", clickFlipType.name)
            putBoolean("isFlipAnimation", isFlipAnimation)
            putBoolean("isPageCurlAnimation", isPageCurlAnimation)
            putBoolean("isCurlContentAreaOnly", isCurlContentAreaOnly)
            putFloat("curlBackContentAlpha", curlBackContentAlpha)
            putFloat("curlShadowAlpha", curlShadowAlpha)
            putBoolean("isShowPageNum", isShowPageNum)
            putBoolean("isKeepScreenOn", isKeepScreenOn)
            putBoolean("${prefix}isTrimWhiteBorder", isTrimWhiteBorder)
            putFloat("${prefix}trimFactor", trimFactor)
            putInt("${prefix}trimThreshold", trimThreshold)
            putFloat("${prefix}trimWhiteRatio", trimWhiteRatio)
            putInt("${prefix}imageMarginH", imageMarginH)
            putString("${prefix}readBackground", readBackground.name)
            putBoolean("${prefix}isEinkMode", isEinkMode)
            putBoolean("${prefix}isEinkDither", isEinkDither)
            putInt("${prefix}einkDitherAlgorithm", einkDitherAlgorithm)
            putInt("${prefix}einkDitherLevels", einkDitherLevels)
            putInt("${prefix}einkDecodeSize", einkDecodeSize)
            putLong("${prefix}einkUiHideDelay", einkUiHideDelay)
            putFloat("${prefix}pdfQuality", pdfQuality)
            putBoolean("isImmersiveMode", isImmersiveMode)
            putInt("orientation", orientation)
            putInt("autoFlipInterval", autoFlipInterval)
            putBoolean("${prefix}isGrayscale", isGrayscale)
            putBoolean("${prefix}isInvertColors", isInvertColors)
            putBoolean("${prefix}isFlipFlashEnabled", isFlipFlashEnabled)
            putInt("${prefix}flipFlashDuration", flipFlashDuration)
            putInt("${prefix}flipFlashInterval", flipFlashInterval)
            putInt("${prefix}flipFlashColor", flipFlashColor)
            putString("${prefix}pageLayout", pageLayout.name)
            putBoolean("${prefix}useSSIV", useSSIV)
            putBoolean("landscapeZoom", landscapeZoom)
            putBoolean("fitScreen", fitScreen)
            putBoolean("${prefix}panNavigation", panNavigation)
            putInt("${prefix}doublePageShift", doublePageShift)
            putBoolean("${prefix}doublePageSplit", doublePageSplit)
            putBoolean("${prefix}doublePageReverse", doublePageReverse)

            putBoolean("autoWebtoon", autoWebtoon)
            putBoolean("chapterTransition", chapterTransition)
            putBoolean("seamlessNextVolume", seamlessNextVolume)
            putInt("${prefix}centerGapType", centerGapType)
            putBoolean("forceFullDecode", forceFullDecode)
            putBoolean("tileModeEnabled", tileModeEnabled)
            putString("downsampleTier", downsampleTier.name)
            putBoolean("tallSplitEnabled", tallSplitEnabled)
            apply()
        }
    }

    companion object {
        fun load(context: Context, bookId: Long = -1): ReaderConfig {
            val prefs = context.getSharedPreferences("manga_reader", Context.MODE_PRIVATE)
            val prefix = if (bookId >= 0) "book_${bookId}_" else ""
            // ★ 翻页闪烁「全局 > 单本」合并：全局开 = 整组接管（开关+参数全部取全局键），
            //   全局关 = 单本自决（读 book_ 前缀键）。全局键由 设置→阅读设置→墨水屏 写入。
            //   flipFlashGlobalActive 仅作运行时标记供阅读内 UI 灰置单本设置，不落盘。
            val globalFlash = prefs.safeGetBoolean("isFlipFlashEnabled", false)
            val flashPrefix = if (globalFlash) "" else prefix
            return ReaderConfig(
                scrollMode = try { ScrollMode.valueOf(prefs.getString("${prefix}scrollMode", "HORIZONTAL_LEFT")!!) } catch (_: Exception) { ScrollMode.HORIZONTAL_LEFT },
                clickFlipType = try { ClickFlipType.valueOf(prefs.getString("${prefix}clickFlipType", "LEFT_RIGHT")!!) } catch (_: Exception) { ClickFlipType.LEFT_RIGHT },
                isFlipAnimation = prefs.safeGetBoolean("isFlipAnimation", true),
                isPageCurlAnimation = prefs.safeGetBoolean("isPageCurlAnimation", false),
                isCurlContentAreaOnly = prefs.safeGetBoolean("isCurlContentAreaOnly", false),
                curlBackContentAlpha = prefs.safeGetFloat("curlBackContentAlpha", 0.1f),
                curlShadowAlpha = prefs.safeGetFloat("curlShadowAlpha", 0.2f),
                isShowPageNum = prefs.safeGetBoolean("isShowPageNum", true),
                isKeepScreenOn = prefs.safeGetBoolean("isKeepScreenOn", true),
                isTrimWhiteBorder = prefs.safeGetBoolean("${prefix}isTrimWhiteBorder", false),
                trimFactor = prefs.safeGetFloat("${prefix}trimFactor", 0.02f),
                trimThreshold = prefs.safeGetInt("${prefix}trimThreshold", 220),
                trimWhiteRatio = prefs.safeGetFloat("${prefix}trimWhiteRatio", 0.90f),
                imageMarginH = prefs.safeGetInt("${prefix}imageMarginH", 0),
                readBackground = try { ReadBackground.valueOf(prefs.getString("${prefix}readBackground", "WHITE")!!) } catch (_: Exception) { ReadBackground.WHITE },
                isEinkMode = prefs.safeGetBoolean("${prefix}isEinkMode", false),
                isEinkDither = prefs.safeGetBoolean("${prefix}isEinkDither", false),
                einkDitherAlgorithm = prefs.safeGetInt("${prefix}einkDitherAlgorithm", 0),
                einkDitherLevels = prefs.safeGetInt("${prefix}einkDitherLevels", 16),
                einkDecodeSize = prefs.safeGetInt("${prefix}einkDecodeSize", 1920),
                einkUiHideDelay = prefs.safeGetLong("${prefix}einkUiHideDelay", 6000L),
                pdfQuality = prefs.safeGetFloat("${prefix}pdfQuality", 3.0f),
                isImmersiveMode = prefs.safeGetBoolean("isImmersiveMode", true),
                orientation = prefs.safeGetInt("orientation", -1),
                autoFlipInterval = prefs.safeGetInt("autoFlipInterval", 0),
                isGrayscale = prefs.safeGetBoolean("${prefix}isGrayscale", false),
                isInvertColors = prefs.safeGetBoolean("${prefix}isInvertColors", false),
                isFlipFlashEnabled = prefs.safeGetBoolean("${flashPrefix}isFlipFlashEnabled", false),
                flipFlashDuration = prefs.safeGetInt("${flashPrefix}flipFlashDuration", 100),
                flipFlashInterval = prefs.safeGetInt("${flashPrefix}flipFlashInterval", 1),
                flipFlashColor = prefs.safeGetInt("${flashPrefix}flipFlashColor", 0),
                flipFlashGlobalActive = globalFlash,
                pageLayout = try { PageLayout.valueOf(prefs.getString("${prefix}pageLayout", "SINGLE")!!) } catch (_: Exception) { PageLayout.SINGLE },
                useSSIV = prefs.safeGetBoolean("${prefix}useSSIV", true),
                landscapeZoom = prefs.safeGetBoolean("landscapeZoom", false),
                fitScreen = prefs.safeGetBoolean("fitScreen", false),
                panNavigation = prefs.safeGetBoolean("${prefix}panNavigation", true),
                doublePageShift = prefs.safeGetInt("${prefix}doublePageShift", 0),
                doublePageSplit = prefs.safeGetBoolean("${prefix}doublePageSplit", false),
                doublePageReverse = prefs.safeGetBoolean("${prefix}doublePageReverse", false),

                autoWebtoon = prefs.safeGetBoolean("autoWebtoon", true),
                chapterTransition = prefs.safeGetBoolean("chapterTransition", true),
                centerGapType = prefs.safeGetInt("${prefix}centerGapType", 0),
                seamlessNextVolume = prefs.safeGetBoolean("seamlessNextVolume", false),
                forceFullDecode = prefs.safeGetBoolean("forceFullDecode", false),
                tileModeEnabled = prefs.safeGetBoolean("tileModeEnabled", false),
                tallSplitEnabled = prefs.safeGetBoolean("tallSplitEnabled", false),
                downsampleTier = if (prefs.contains("downsampleTier")) {
                    try { DownsampleTier.valueOf(prefs.getString("downsampleTier", "STANDARD")!!) } catch (_: Exception) { DownsampleTier.STANDARD }
                } else if (prefs.safeGetBoolean("forceFullDecode", false)) DownsampleTier.ORIGINAL else DownsampleTier.STANDARD
            )
        }
    }
}
