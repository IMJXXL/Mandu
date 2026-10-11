package com.mangareader.backup.export

import android.content.Context
import com.mangareader.backup.format.SettingsPayload

object SettingsExporter {
    private val readingKeys = setOf(
        "scrollMode", "clickFlipType", "isTrimWhiteBorder", "trimFactor", "trimThreshold",
        "trimWhiteRatio", "imageMarginH", "readBackground", "isEinkMode", "einkDecodeSize",
        "einkUiHideDelay", "pdfQuality", "autoFlipInterval", "isGrayscale", "isInvertColors",
        "isFlipFlashEnabled", "flipFlashDuration", "flipFlashInterval", "flipFlashColor",
        "isEinkDither", "einkDitherAlgorithm", "einkDitherLevels", "einkEndpointPassthrough",
        "pageLayout", "useSSIV", "panNavigation", "doublePageShift", "doublePageSplit",
        "doublePageReverse", "centerGapType", "forceFullDecode", "doubleMergeSmart",
        "isFlipAnimation", "isShowPageNum", "isKeepScreenOn", "isImmersiveMode", "hideNavBarInReader",
        "orientation", "landscapeZoom", "imageScaleMode", "zoomStart", "autoWebtoon", "chapterTransition",
        "seamlessNextVolume",
        // ★ 白名单补齐（2026-10-11 审查）：此前约 30 个全局键不导出——恢复后滤镜/纹理/
        //   仿真翻页/长图分割/横屏自动/氛围音等整组回默认
        "clickFlipInvertH", "isDoubleTapZoom", "isPageCurlAnimation", "isCurlContentAreaOnly",
        "curlBackContentAlpha", "curlShadowAlpha", "spineShadowAlpha",
        "autoDoubleLandscape", "landscapeAutoWebtoon", "doublePageStart", "autoDoubleLayout",
        "tileModeEnabled", "downsampleTier", "tallSplitEnabled", "tallSplitSensitivity",
        "brightness", "screenBrightness", "brightnessEnabled", "screenBrightnessEnabled",
        "contrast", "contrastEnabled", "gamma", "gammaEnabled",
        "textureEnabled", "texturePresetId", "textureAlpha", "textureConcentration", "textureScope",
        "ambienceSound", "ambienceVolume",
        "reader_volume_keys", "reader_volume_keys_inverted", "long_press_save",
        "reader_hw_keys_enabled", "hw_key_mappings",
        "bookmark_note_enabled", "default_read_mode",
        "sr_enabled", "sr_upscale_enabled", "sr_model", "sr_denoise", "sr_quality",
        "sr_enable_gpu", "sr_bw_enabled", "sr_bw_gray_levels", "sr_bw_density",
        "sr_compare", "sr_preload_override", "sr_realtime_cache_mb",
        "sr_engine", "sr_npu_block", "sr_npu_test_ok", "sr_artifact_cache_mb",
    )

    fun export(context: Context): SettingsPayload {
        val ctx = context.applicationContext
        val mainPrefs = ctx.getSharedPreferences("manga_reader", Context.MODE_PRIVATE)
        val globalSettings = mutableMapOf<String, String>()
        val perBookOverrides = mutableMapOf<String, String>()
        mainPrefs.all.forEach { (k, v) ->
            if (k.startsWith("book_")) {
                val suffix = k.substringAfter("book_").substringAfter("_", "")
                if (suffix in readingKeys) perBookOverrides[k] = v.toString()
            } else if (k in readingKeys) {
                globalSettings[k] = v.toString()
            } else if (k.startsWith("read_mode_") || k.startsWith("orientation_")) {
                perBookOverrides[k] = v.toString()
            } else if (k.startsWith("sr_book_")) {
                perBookOverrides[k] = v.toString()
            }
        }
        val configPrefs = ctx.getSharedPreferences("reader_configs", Context.MODE_PRIVATE)
        val namedPresets = mutableMapOf<String, String>()
        configPrefs.all.forEach { (k, v) ->
            if (k.startsWith("config_")) namedPresets[k] = v.toString()
        }
        return SettingsPayload(globalSettings, namedPresets, perBookOverrides)
    }
}