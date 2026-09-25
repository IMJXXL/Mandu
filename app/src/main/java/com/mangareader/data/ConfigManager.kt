package com.mangareader.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

class ConfigManager(private val context: Context) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("reader_configs", Context.MODE_PRIVATE)
    }

    fun saveConfig(name: String, config: ReaderConfig) {
        val json = JSONObject().apply {
            put("scrollMode", config.scrollMode.name)
            put("clickFlipType", config.clickFlipType.name)
            put("isFlipAnimation", config.isFlipAnimation)
            put("isCurlContentAreaOnly", config.isCurlContentAreaOnly)
            put("curlBackContentAlpha", config.curlBackContentAlpha.toDouble())
            put("curlShadowAlpha", config.curlShadowAlpha.toDouble())
            put("isShowPageNum", config.isShowPageNum)
            put("isKeepScreenOn", config.isKeepScreenOn)
            put("isTrimWhiteBorder", config.isTrimWhiteBorder)
            put("trimFactor", config.trimFactor.toDouble())
            put("trimThreshold", config.trimThreshold)
            put("trimWhiteRatio", config.trimWhiteRatio.toDouble())
            put("imageMarginH", config.imageMarginH)
            put("readBackground", config.readBackground.name)
            put("isEinkMode", config.isEinkMode)
            put("isEinkDither", config.isEinkDither)
            put("einkDitherAlgorithm", config.einkDitherAlgorithm)
            put("einkDitherLevels", config.einkDitherLevels)
            put("einkDecodeSize", config.einkDecodeSize)
            put("einkUiHideDelay", config.einkUiHideDelay)
            put("pdfQuality", config.pdfQuality.toDouble())
            put("isImmersiveMode", config.isImmersiveMode)
            put("orientation", config.orientation)
            put("autoFlipInterval", config.autoFlipInterval)
            put("isGrayscale", config.isGrayscale)
            put("isInvertColors", config.isInvertColors)
            put("isFlipFlashEnabled", config.isFlipFlashEnabled)
            put("flipFlashDuration", config.flipFlashDuration)
            put("flipFlashInterval", config.flipFlashInterval)
            put("flipFlashColor", config.flipFlashColor)
            put("pageLayout", config.pageLayout.name)
            put("useSSIV", config.useSSIV)
            put("landscapeZoom", config.landscapeZoom)
            put("panNavigation", config.panNavigation)
            put("doublePageShift", config.doublePageShift)
            put("doublePageSplit", config.doublePageSplit)
            put("doublePageReverse", config.doublePageReverse)
            put("autoWebtoon", config.autoWebtoon)
            put("chapterTransition", config.chapterTransition)
            put("centerGapType", config.centerGapType)
            put("forceFullDecode", config.forceFullDecode)
        }
        prefs.edit().putString("config_$name", json.toString()).apply()
    }

    fun loadConfig(name: String): ReaderConfig? {
        val jsonStr = prefs.getString("config_$name", null) ?: return null
        return try {
            val json = JSONObject(jsonStr)
            ReaderConfig(
                scrollMode = try { ReaderConfig.ScrollMode.valueOf(json.getString("scrollMode")) } catch (_: Exception) { ReaderConfig.ScrollMode.HORIZONTAL_LEFT },
                clickFlipType = try { ReaderConfig.ClickFlipType.valueOf(json.getString("clickFlipType")) } catch (_: Exception) { ReaderConfig.ClickFlipType.LEFT_RIGHT },
                isFlipAnimation = json.optBoolean("isFlipAnimation", true),
                isCurlContentAreaOnly = json.optBoolean("isCurlContentAreaOnly", false),
                curlBackContentAlpha = json.optDouble("curlBackContentAlpha", 0.1).toFloat(),
                curlShadowAlpha = json.optDouble("curlShadowAlpha", 0.2).toFloat(),
                isShowPageNum = json.optBoolean("isShowPageNum", true),
                isKeepScreenOn = json.optBoolean("isKeepScreenOn", true),
                isTrimWhiteBorder = json.optBoolean("isTrimWhiteBorder", false),
                trimFactor = json.optDouble("trimFactor", 0.02).toFloat(),
                trimThreshold = json.optInt("trimThreshold", 220),
                trimWhiteRatio = json.optDouble("trimWhiteRatio", 0.90).toFloat(),
                imageMarginH = json.optInt("imageMarginH", 0),
                readBackground = try { ReaderConfig.ReadBackground.valueOf(json.getString("readBackground")) } catch (_: Exception) { ReaderConfig.ReadBackground.DEFAULT },
                isEinkMode = json.optBoolean("isEinkMode", false),
                isEinkDither = json.optBoolean("isEinkDither", false),
                einkDitherAlgorithm = json.optInt("einkDitherAlgorithm", 0),
                einkDitherLevels = json.optInt("einkDitherLevels", 16),
                einkDecodeSize = json.optInt("einkDecodeSize", 1920),
                einkUiHideDelay = json.optLong("einkUiHideDelay", 6000L),
                pdfQuality = json.optDouble("pdfQuality", 3.0).toFloat(),
                isImmersiveMode = json.optBoolean("isImmersiveMode", true),
                orientation = json.optInt("orientation", -1),
                autoFlipInterval = json.optInt("autoFlipInterval", 0),
                isGrayscale = json.optBoolean("isGrayscale", false),
                isInvertColors = json.optBoolean("isInvertColors", false),
                isFlipFlashEnabled = json.optBoolean("isFlipFlashEnabled", false),
                flipFlashDuration = json.optInt("flipFlashDuration", 100),
                flipFlashInterval = json.optInt("flipFlashInterval", 1),
                flipFlashColor = json.optInt("flipFlashColor", 0),
                pageLayout = try { ReaderConfig.PageLayout.valueOf(json.getString("pageLayout")) } catch (_: Exception) { ReaderConfig.PageLayout.SINGLE },
                useSSIV = json.optBoolean("useSSIV", true),
                landscapeZoom = json.optBoolean("landscapeZoom", false),
                panNavigation = json.optBoolean("panNavigation", true),
                doublePageShift = json.optInt("doublePageShift", 0),
                doublePageSplit = json.optBoolean("doublePageSplit", false),
                doublePageReverse = json.optBoolean("doublePageReverse", false),
                autoWebtoon = json.optBoolean("autoWebtoon", true),
                chapterTransition = json.optBoolean("chapterTransition", true),
                centerGapType = json.optInt("centerGapType", 0),
                forceFullDecode = json.optBoolean("forceFullDecode", false)
            )
        } catch (_: Exception) { null }
    }

    fun listConfigs(): List<String> {
        return prefs.all.keys.filter { it.startsWith("config_") }.map { it.removePrefix("config_") }.sorted()
    }

    fun deleteConfig(name: String) {
        prefs.edit().remove("config_$name").apply()
    }

    fun renameConfig(oldName: String, newName: String) {
        val config = loadConfig(oldName) ?: return
        saveConfig(newName, config)
        deleteConfig(oldName)
    }
}
