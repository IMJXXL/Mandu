package com.mangareader.sr

enum class DenoiseLevel(val key: String, val displayName: String) {
    OFF("off", "关闭"),
    LIGHT("light", "轻度"),
    STRONG("strong", "强力");

    companion object {
        fun fromKey(key: String): DenoiseLevel = entries.firstOrNull { it.key == key } ?: LIGHT
    }
}

enum class Quality(val key: String, val displayName: String) {
    FAST("fast", "速度优先"),
    BALANCED("balanced", "均衡"),
    HIGH("high", "画质优先");

    companion object {
        fun fromKey(key: String): Quality = entries.firstOrNull { it.key == key } ?: BALANCED
    }
}

/** 漫画黑白后处理配置。grayLevels=0 表示不启用黑白后处理。 */
data class MangaBWPostProcessConfig(
    val grayLevels: Int = 32,
    val densityCorrection: Boolean = true,
)
