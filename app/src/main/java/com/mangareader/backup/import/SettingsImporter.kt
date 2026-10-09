package com.mangareader.backup.`import`

import android.content.Context
import com.mangareader.backup.format.SettingsPayload

object SettingsImporter {
    private val booleanKeys = setOf(
        "isFlipAnimation", "isShowPageNum", "isKeepScreenOn", "isTrimWhiteBorder",
        "isEinkMode", "isImmersiveMode", "hideNavBarInReader", "isGrayscale", "isInvertColors",
        "isFlipFlashEnabled", "useSSIV", "landscapeZoom", "imageScaleMode", "zoomStart", "isEinkDither",
        "panNavigation", "doublePageSplit", "doublePageReverse", "autoWebtoon", "doubleMergeSmart",
        "chapterTransition", "seamlessNextVolume", "forceFullDecode",
        "reader_volume_keys", "reader_volume_keys_inverted", "long_press_save",
        "reader_hw_keys_enabled",
        "bookmark_note_enabled", "sr_enabled", "sr_upscale_enabled", "sr_enable_gpu",
        "sr_bw_enabled", "sr_compare",
    )
    private val intKeys = setOf(
        "trimThreshold", "einkDecodeSize", "autoFlipInterval", "flipFlashDuration",
        "einkDitherAlgorithm", "einkDitherLevels", "einkEndpointPassthrough",
        "flipFlashInterval", "flipFlashColor", "orientation", "imageMarginH",
        "doublePageShift", "centerGapType", "default_read_mode", "sr_model",
        "sr_quality", "sr_bw_gray_levels", "sr_bw_density", "sr_preload_override",
        "sr_realtime_cache_mb",
    )
    private val floatKeys = setOf("trimFactor", "trimWhiteRatio", "pdfQuality", "sr_denoise")
    private val longKeys = setOf("einkUiHideDelay")

    private fun keySuffix(key: String): String =
        if (key.startsWith("book_")) key.substringAfter("book_").substringAfter("_", "") else key

    fun `import`(context: Context, payload: SettingsPayload) {
        val ctx = context.applicationContext
        val mainPrefs = ctx.getSharedPreferences("manga_reader", Context.MODE_PRIVATE)
        val mainEditor = mainPrefs.edit()
        val applyTyped: (String, String) -> Unit = { k, v ->
            val s = keySuffix(k)
            when {
                s in booleanKeys -> mainEditor.putBoolean(k, v == "true")
                s in intKeys -> mainEditor.putInt(k, v.toIntOrNull() ?: 0)
                s in floatKeys -> mainEditor.putFloat(k, v.toFloatOrNull() ?: 0f)
                s in longKeys -> mainEditor.putLong(k, v.toLongOrNull() ?: 0L)
                k.startsWith("read_mode_") || k.startsWith("orientation_") -> mainEditor.putInt(k, v.toIntOrNull() ?: 0)
                else -> mainEditor.putString(k, v)
            }
        }
        payload.globalSettings.forEach { (k, v) -> applyTyped(k, v) }
        payload.perBookOverrides.forEach { (k, v) -> applyTyped(k, v) }
        mainEditor.apply()

        val configPrefs = ctx.getSharedPreferences("reader_configs", Context.MODE_PRIVATE)
        val configEditor = configPrefs.edit()
        payload.namedPresets.forEach { (k, v) -> configEditor.putString(k, v) }
        configEditor.apply()
    }
}
