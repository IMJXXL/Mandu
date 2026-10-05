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
            put("spineShadowAlpha", config.spineShadowAlpha.toDouble())
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
            put("einkEndpointPassthrough", config.einkEndpointPassthrough)
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
            put("isDoubleTapZoom", config.isDoubleTapZoom)
            put("panNavigation", config.panNavigation)
            put("doublePageShift", config.doublePageShift)
            put("doublePageSplit", config.doublePageSplit)
            put("doublePageReverse", config.doublePageReverse)
            put("autoWebtoon", config.autoWebtoon)
            put("chapterTransition", config.chapterTransition)
            put("centerGapType", config.centerGapType)
                put("autoDoubleLandscape", config.autoDoubleLandscape)
                put("landscapeAutoWebtoon", config.landscapeAutoWebtoon)
                put("doubleMergeSmart", config.doubleMergeSmart)
                put("clickFlipInvertH", config.clickFlipInvertH)
                put("isPageCurlAnimation", config.isPageCurlAnimation)
            put("forceFullDecode", config.forceFullDecode)
            // 滤镜/阅读区组（此前缺失：切换配置方案会把滤镜与亮度等重置为默认）
            put("brightness", config.brightness)
            put("screenBrightness", config.screenBrightness)
            put("contrast", config.contrast)
            put("gamma", config.gamma)
            put("brightnessEnabled", config.brightnessEnabled)
            put("screenBrightnessEnabled", config.screenBrightnessEnabled)
            put("contrastEnabled", config.contrastEnabled)
            put("gammaEnabled", config.gammaEnabled)
            put("textureEnabled", config.textureEnabled)
            put("texturePresetId", config.texturePresetId)
            put("textureAlpha", config.textureAlpha)
            put("textureConcentration", config.textureConcentration)
            put("ambienceSound", config.ambienceSound)
            put("ambienceVolume", config.ambienceVolume)
            put("imageScaleMode", config.imageScaleMode)
            put("zoomStart", config.zoomStart)
            put("seamlessNextVolume", config.seamlessNextVolume)
            put("tileModeEnabled", config.tileModeEnabled)
            put("tallSplitEnabled", config.tallSplitEnabled)
            put("tallSplitSensitivity", config.tallSplitSensitivity)
            put("downsampleTier", config.downsampleTier.name)
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
                isCurlContentAreaOnly = json.optBoolean("isCurlContentAreaOnly", true),
                curlBackContentAlpha = json.optDouble("curlBackContentAlpha", 0.1).toFloat(),
                curlShadowAlpha = json.optDouble("curlShadowAlpha", 0.2).toFloat(),
                spineShadowAlpha = json.optDouble("spineShadowAlpha", 0.09).toFloat(),
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
                einkEndpointPassthrough = json.optInt("einkEndpointPassthrough", 0).coerceIn(0, 8),
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
                isDoubleTapZoom = json.optBoolean("isDoubleTapZoom", true),
                panNavigation = json.optBoolean("panNavigation", true),
                doublePageShift = json.optInt("doublePageShift", 0),
                doublePageSplit = json.optBoolean("doublePageSplit", false),
                doublePageReverse = json.optBoolean("doublePageReverse", false),
                autoWebtoon = json.optBoolean("autoWebtoon", true),
                chapterTransition = json.optBoolean("chapterTransition", true),
                centerGapType = json.optInt("centerGapType", 0),
                autoDoubleLandscape = json.optBoolean("autoDoubleLandscape", false),
                landscapeAutoWebtoon = json.optBoolean("landscapeAutoWebtoon", false),
                doubleMergeSmart = json.optBoolean("doubleMergeSmart", true),
                clickFlipInvertH = json.optBoolean("clickFlipInvertH", false),
                isPageCurlAnimation = json.optBoolean("isPageCurlAnimation", false),
                forceFullDecode = json.optBoolean("forceFullDecode", false),
                // 滤镜/阅读区组（与 save 镜像）
                brightness = json.optInt("brightness", 0),
                screenBrightness = json.optInt("screenBrightness", 0),
                contrast = json.optInt("contrast", 100),
                gamma = json.optInt("gamma", 100),
                brightnessEnabled = json.optBoolean("brightnessEnabled", false),
                screenBrightnessEnabled = json.optBoolean("screenBrightnessEnabled", false),
                contrastEnabled = json.optBoolean("contrastEnabled", false),
                gammaEnabled = json.optBoolean("gammaEnabled", false),
                textureEnabled = json.optBoolean("textureEnabled", false),
                texturePresetId = json.optInt("texturePresetId", 0),
                textureAlpha = json.optInt("textureAlpha", 15),
                textureConcentration = json.optInt("textureConcentration", 100),
                ambienceSound = json.optString("ambienceSound", ""),
                ambienceVolume = json.optInt("ambienceVolume", 60),
                imageScaleMode = json.optInt("imageScaleMode", 1).let { if (it in 0..4) it else 1 },
                zoomStart = json.optInt("zoomStart", 0).let { if (it in 0..3) it else 0 },
                seamlessNextVolume = json.optBoolean("seamlessNextVolume", true),
                tileModeEnabled = json.optBoolean("tileModeEnabled", false),
                tallSplitEnabled = json.optBoolean("tallSplitEnabled", false),
                tallSplitSensitivity = json.optInt("tallSplitSensitivity", 0).coerceIn(0, 2),
                downsampleTier = try { ReaderConfig.DownsampleTier.valueOf(json.getString("downsampleTier")) } catch (_: Exception) { ReaderConfig.DownsampleTier.STANDARD }
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
