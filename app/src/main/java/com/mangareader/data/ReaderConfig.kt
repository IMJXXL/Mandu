package com.mangareader.data

import android.content.Context
import android.content.SharedPreferences

data class ReaderConfig(
    val scrollMode: ScrollMode = ScrollMode.HORIZONTAL_LEFT,
    val clickFlipType: ClickFlipType = ClickFlipType.LEFT_RIGHT,
    val isFlipAnimation: Boolean = true,
    val isShowPageNum: Boolean = true,
    val isKeepScreenOn: Boolean = true,
    val isTrimWhiteBorder: Boolean = false,
    val trimFactor: Float = 0.02f,
    /** 像素平均亮度超过此值视为"白色"，越小越激进（更多像素被识别为白色），越大越保守 */
    val trimThreshold: Int = 220,
    /** 一行/列中超过阈值的像素占比达到此值才算"白边"，越小越容易判定为白边，越大越保守 */
    val trimWhiteRatio: Float = 0.90f,
    val imageMarginH: Int = 0,
    val readBackground: ReadBackground = ReadBackground.DEFAULT,
    val isEinkMode: Boolean = false,
    val einkDecodeSize: Int = 1280,
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
    val pageLayout: PageLayout = PageLayout.SINGLE,
    val useSSIV: Boolean = true,
    val landscapeZoom: Boolean = false,
    val panNavigation: Boolean = true,
    val doublePageShift: Int = 0,
    val doublePageSplit: Boolean = false,
    val doublePageReverse: Boolean = false,

    val autoWebtoon: Boolean = true,
    val chapterTransition: Boolean = true,
    val centerGapType: Int = 0
) {
    enum class ScrollMode { VERTICAL, HORIZONTAL_LEFT, HORIZONTAL_RIGHT }
    @Deprecated("Dead code - navigation uses Int field navigationMode instead") enum class ClickFlipType { NO, LEFT_RIGHT, DIAGONAL_LB_RT, DIAGONAL_LT_RB }
    @Deprecated("Dead code - navigationMode is stored as Int, this enum is never referenced") enum class NavigationMode(val label: String) {
        DEFAULT("默认"), L_NAV("L形"), KINDLISH("Kindlish"), EDGE("边沿"), RIGHT_LEFT("右与左")
    }
    enum class ReadBackground { DEFAULT, LIGHT, DARK, WHITE, GRAY }
    enum class PageLayout(val label: String) { SINGLE("单页"), DOUBLE("双页"), DOUBLE_SHIFTED("双页跳过首页") }

    fun save(context: Context, bookId: Long = -1) {
        val prefs = context.getSharedPreferences("manga_reader", Context.MODE_PRIVATE)
        val prefix = if (bookId >= 0) "book_${bookId}_" else ""
        prefs.edit().apply {
            putString("${prefix}scrollMode", scrollMode.name)
            putString("${prefix}clickFlipType", clickFlipType.name)
            putBoolean("${prefix}isFlipAnimation", isFlipAnimation)
            putBoolean("${prefix}isShowPageNum", isShowPageNum)
            putBoolean("${prefix}isKeepScreenOn", isKeepScreenOn)
            putBoolean("${prefix}isTrimWhiteBorder", isTrimWhiteBorder)
            putFloat("${prefix}trimFactor", trimFactor)
            putInt("${prefix}trimThreshold", trimThreshold)
            putFloat("${prefix}trimWhiteRatio", trimWhiteRatio)
            putInt("${prefix}imageMarginH", imageMarginH)
            putString("${prefix}readBackground", readBackground.name)
            putBoolean("${prefix}isEinkMode", isEinkMode)
            putInt("${prefix}einkDecodeSize", einkDecodeSize)
            putLong("${prefix}einkUiHideDelay", einkUiHideDelay)
            putFloat("${prefix}pdfQuality", pdfQuality)
            putBoolean("${prefix}isImmersiveMode", isImmersiveMode)
            putInt("${prefix}orientation", orientation)
            putInt("${prefix}autoFlipInterval", autoFlipInterval)
            putBoolean("${prefix}isGrayscale", isGrayscale)
            putBoolean("${prefix}isInvertColors", isInvertColors)
            putBoolean("${prefix}isFlipFlashEnabled", isFlipFlashEnabled)
            putInt("${prefix}flipFlashDuration", flipFlashDuration)
            putInt("${prefix}flipFlashInterval", flipFlashInterval)
            putInt("${prefix}flipFlashColor", flipFlashColor)
            putString("${prefix}pageLayout", pageLayout.name)
            putBoolean("${prefix}useSSIV", useSSIV)
            putBoolean("${prefix}landscapeZoom", landscapeZoom)
            putBoolean("${prefix}panNavigation", panNavigation)
            putInt("${prefix}doublePageShift", doublePageShift)
            putBoolean("${prefix}doublePageSplit", doublePageSplit)
            putBoolean("${prefix}doublePageReverse", doublePageReverse)

            putBoolean("${prefix}autoWebtoon", autoWebtoon)
            putBoolean("${prefix}chapterTransition", chapterTransition)
            putInt("${prefix}centerGapType", centerGapType)
            apply()
        }
    }

    companion object {
        fun load(context: Context, bookId: Long = -1): ReaderConfig {
            val prefs = context.getSharedPreferences("manga_reader", Context.MODE_PRIVATE)
            val prefix = if (bookId >= 0) "book_${bookId}_" else ""
            return ReaderConfig(
                scrollMode = try { ScrollMode.valueOf(prefs.getString("${prefix}scrollMode", "HORIZONTAL_LEFT")!!) } catch (_: Exception) { ScrollMode.HORIZONTAL_LEFT },
                clickFlipType = try { ClickFlipType.valueOf(prefs.getString("${prefix}clickFlipType", "LEFT_RIGHT")!!) } catch (_: Exception) { ClickFlipType.LEFT_RIGHT },
                isFlipAnimation = prefs.getBoolean("${prefix}isFlipAnimation", true),
                isShowPageNum = prefs.getBoolean("${prefix}isShowPageNum", true),
                isKeepScreenOn = prefs.getBoolean("${prefix}isKeepScreenOn", true),
                isTrimWhiteBorder = prefs.getBoolean("${prefix}isTrimWhiteBorder", false),
                trimFactor = prefs.getFloat("${prefix}trimFactor", 0.02f),
                trimThreshold = prefs.getInt("${prefix}trimThreshold", 220),
                trimWhiteRatio = prefs.getFloat("${prefix}trimWhiteRatio", 0.90f),
                imageMarginH = prefs.getInt("${prefix}imageMarginH", 0),
                readBackground = try { ReadBackground.valueOf(prefs.getString("${prefix}readBackground", "DEFAULT")!!) } catch (_: Exception) { ReadBackground.DEFAULT },
                isEinkMode = prefs.getBoolean("${prefix}isEinkMode", false),
                einkDecodeSize = prefs.getInt("${prefix}einkDecodeSize", 1280),
                einkUiHideDelay = prefs.getLong("${prefix}einkUiHideDelay", 6000L),
                pdfQuality = prefs.getFloat("${prefix}pdfQuality", 3.0f),
                isImmersiveMode = prefs.getBoolean("${prefix}isImmersiveMode", true),
                orientation = prefs.getInt("${prefix}orientation", -1),
                autoFlipInterval = prefs.getInt("${prefix}autoFlipInterval", 0),
                isGrayscale = prefs.getBoolean("${prefix}isGrayscale", false),
                isInvertColors = prefs.getBoolean("${prefix}isInvertColors", false),
                isFlipFlashEnabled = prefs.getBoolean("${prefix}isFlipFlashEnabled", false),
                flipFlashDuration = prefs.getInt("${prefix}flipFlashDuration", 100),
                flipFlashInterval = prefs.getInt("${prefix}flipFlashInterval", 1),
                flipFlashColor = prefs.getInt("${prefix}flipFlashColor", 0),
                pageLayout = try { PageLayout.valueOf(prefs.getString("${prefix}pageLayout", "SINGLE")!!) } catch (_: Exception) { PageLayout.SINGLE },
                useSSIV = prefs.getBoolean("${prefix}useSSIV", true),
                landscapeZoom = prefs.getBoolean("${prefix}landscapeZoom", false),
                panNavigation = prefs.getBoolean("${prefix}panNavigation", true),
                doublePageShift = prefs.getInt("${prefix}doublePageShift", 0),
                doublePageSplit = prefs.getBoolean("${prefix}doublePageSplit", false),
                doublePageReverse = prefs.getBoolean("${prefix}doublePageReverse", false),

                autoWebtoon = prefs.getBoolean("${prefix}autoWebtoon", true),
                chapterTransition = prefs.getBoolean("${prefix}chapterTransition", true),
                centerGapType = prefs.getInt("${prefix}centerGapType", 0)
            )
        }
    }
}
