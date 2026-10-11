package com.mangareader.backup.`import`

import android.content.Context
import com.mangareader.backup.format.SettingsPayload

object SettingsImporter {
    // ★ 类型表以实际读取端为准（对齐 ReaderConfig.kt 150-241 与 SrSettings.kt 的 get 类型）。
    //   此前 sr_model/sr_quality（String 键）在 intKeys、sr_denoise（String 键）在 floatKeys、
    //   sr_bw_density（Boolean 键）在 intKeys、imageScaleMode/zoomStart（Int 键）在 booleanKeys
    //   ——恢复写入类型错配，读取端 ClassCastException 崩溃或静默回默认（2026-10-11 审查）。
    private val booleanKeys = setOf(
        "isFlipAnimation", "isShowPageNum", "isKeepScreenOn", "isTrimWhiteBorder",
        "isEinkMode", "isImmersiveMode", "hideNavBarInReader", "isGrayscale", "isInvertColors",
        "isFlipFlashEnabled", "useSSIV", "landscapeZoom", "isEinkDither",
        "panNavigation", "doublePageSplit", "doublePageReverse", "autoWebtoon", "doubleMergeSmart",
        "chapterTransition", "seamlessNextVolume", "forceFullDecode",
        "clickFlipInvertH", "isDoubleTapZoom", "isPageCurlAnimation", "isCurlContentAreaOnly",
        "autoDoubleLandscape", "landscapeAutoWebtoon", "tileModeEnabled", "tallSplitEnabled",
        "brightnessEnabled", "screenBrightnessEnabled", "contrastEnabled", "gammaEnabled",
        "textureEnabled",
        "reader_volume_keys", "reader_volume_keys_inverted", "long_press_save",
        "reader_hw_keys_enabled",
        "bookmark_note_enabled", "sr_enabled", "sr_upscale_enabled", "sr_enable_gpu",
        "sr_bw_enabled", "sr_compare", "sr_npu_block", "sr_npu_test_ok",
    )
    private val intKeys = setOf(
        "trimThreshold", "einkDecodeSize", "autoFlipInterval", "flipFlashDuration",
        "einkDitherAlgorithm", "einkDitherLevels", "einkEndpointPassthrough",
        "flipFlashInterval", "flipFlashColor", "orientation", "imageMarginH",
        "doublePageShift", "centerGapType", "default_read_mode",
        "imageScaleMode", "zoomStart", "doublePageStart", "autoDoubleLayout",
        "tallSplitSensitivity", "brightness", "screenBrightness", "contrast", "gamma",
        "texturePresetId", "textureAlpha", "textureConcentration", "textureScope",
        "ambienceVolume",
        "sr_bw_gray_levels", "sr_preload_override", "sr_realtime_cache_mb", "sr_artifact_cache_mb",
    )
    private val floatKeys = setOf(
        "trimFactor", "trimWhiteRatio", "pdfQuality",
        "curlBackContentAlpha", "curlShadowAlpha", "spineShadowAlpha",
    )
    private val stringKeys = setOf(
        "scrollMode", "clickFlipType", "pageLayout", "readBackground", "downsampleTier",
        "ambienceSound", "hw_key_mappings",
        "sr_model", "sr_quality", "sr_denoise", "sr_engine",
    )
    private val longKeys = setOf("einkUiHideDelay")

    /** sr_book_<id>_<name> 单本覆盖的后缀名（Boolean/String 混型，对齐 SrSettings book* 读写） */
    private val srBookBooleanSuffixes = setOf("use", "enabled", "density")
    private val srBookStringSuffixes = setOf("model", "denoise", "quality")

    private fun keySuffix(key: String): String =
        if (key.startsWith("book_")) key.substringAfter("book_").substringAfter("_", "") else key

    /** ★ 导入键白名单：仅放行导出侧集合内的键（typed sets + 前缀族），恶意备份无法注入任意偏好 */
    private fun isImportable(key: String): Boolean {
        val s = keySuffix(key)
        if (s in booleanKeys || s in intKeys || s in floatKeys || s in stringKeys || s in longKeys) return true
        if (key.startsWith("read_mode_") || key.startsWith("orientation_")) return true
        if (key.startsWith("sr_book_")) {
            val sfx = key.removePrefix("sr_book_").substringAfter('_', "")
            return sfx in srBookBooleanSuffixes || sfx in srBookStringSuffixes
        }
        return false
    }

    fun `import`(context: Context, payload: SettingsPayload) {
        val ctx = context.applicationContext
        val mainPrefs = ctx.getSharedPreferences("manga_reader", Context.MODE_PRIVATE)
        val mainEditor = mainPrefs.edit()
        val applyTyped: (String, String) -> Unit = { k, v ->
            if (isImportable(k)) {
                val s = keySuffix(k)
                when {
                    k.startsWith("sr_book_") -> when (val sfx = k.removePrefix("sr_book_").substringAfter('_', "")) {
                        in srBookBooleanSuffixes -> mainEditor.putBoolean(k, v == "true")
                        in srBookStringSuffixes -> mainEditor.putString(k, v)
                        else -> mainEditor.putString(k, v)
                    }
                    s in booleanKeys -> mainEditor.putBoolean(k, v == "true")
                    s in intKeys -> mainEditor.putInt(k, v.toIntOrNull() ?: 0)
                    s in floatKeys -> mainEditor.putFloat(k, v.toFloatOrNull() ?: 0f)
                    s in longKeys -> mainEditor.putLong(k, v.toLongOrNull() ?: 0L)
                    k.startsWith("read_mode_") || k.startsWith("orientation_") -> mainEditor.putInt(k, v.toIntOrNull() ?: 0)
                    else -> mainEditor.putString(k, v)
                }
            }
        }
        payload.globalSettings.forEach { (k, v) -> applyTyped(k, v) }
        payload.perBookOverrides.forEach { (k, v) -> applyTyped(k, v) }
        mainEditor.apply()

        val configPrefs = ctx.getSharedPreferences("reader_configs", Context.MODE_PRIVATE)
        val configEditor = configPrefs.edit()
        payload.namedPresets.forEach { (k, v) ->
            // 命名预设仅放行 config_ 前缀（防任意 prefs 注入）
            if (k.startsWith("config_")) configEditor.putString(k, v)
        }
        configEditor.apply()
    }
}
