package com.mangareader.sr

/** 超分模型（已精简为 3 个，适配手机屏幕阅读场景，2x 铺满、4x 已过余） */
enum class SRModel(
    val key: String,
    val displayName: String,
    val scale: Int,
    val requiresVulkan: Boolean,
    val modelDirName: String,
    val modelType: String,
    val isBuiltIn: Boolean,
) {
    REALCUGAN_2X_CONSERVATIVE(
        key = "realcugan_2x_conservative",
        displayName = "Real-CUGAN 2x 保守（默认，最稳）",
        scale = 2,
        requiresVulkan = true,
        modelDirName = "realcugan-2x-conservative",
        modelType = "realcugan",
        isBuiltIn = true,
    ),
    REALESRGAN_ANIME_FAST(
        key = "realesrgan_anime_fast",
        displayName = "Real-ESRGAN 动漫 快速",
        scale = 4,
        requiresVulkan = true,
        modelDirName = "realesrgan-anime-fast",
        modelType = "realesrgan",
        isBuiltIn = true,
    ),
    REALESRGAN_GENERAL_FAST(
        key = "realesrgan_general_fast",
        displayName = "Real-ESRGAN 通用 快速",
        scale = 4,
        requiresVulkan = true,
        modelDirName = "realesrgan-general-fast",
        modelType = "realesrgan",
        isBuiltIn = true,
    );

    companion object {
        fun fromKey(key: String): SRModel =
            entries.firstOrNull { it.key == key } ?: REALCUGAN_2X_CONSERVATIVE

        fun selectModel(scale: Int, denoiseLevel: DenoiseLevel, quality: Quality = Quality.BALANCED): SRModel {
            return when {
                scale == 2 -> REALCUGAN_2X_CONSERVATIVE
                denoiseLevel == DenoiseLevel.STRONG -> REALESRGAN_GENERAL_FAST
                else -> REALESRGAN_ANIME_FAST
            }
        }
    }
}
