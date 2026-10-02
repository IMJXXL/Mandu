package com.mangareader.sr

/** 超分模型（已精简为 3 个，适配手机屏幕阅读场景，2x 铺满、4x 已过余） */
enum class SRModel(
    val key: String,
    val displayName: String,
    /** 一句话人话说明：面板里选中该档位时显示（原面板底部大段"档位说明"的唯一事实源） */
    val description: String,
    val scale: Int,
    val requiresVulkan: Boolean,
    val modelDirName: String,
    val modelType: String,
    val isBuiltIn: Boolean,
) {
    QUICK_OPTIMIZE(
        key = "quick_optimize",
        // 快速优化档（默认）：非神经网络，无权重文件——纯 CPU 双边滤波+锐化，原图尺寸毫秒级。
        displayName = "快速优化 1x（算法）",
        description = "不放大。毫秒级去噪点脏斑、拉锐线条；图够大、只想要干净时选它。",
        scale = 1,
        requiresVulkan = false,
        modelDirName = "",
        modelType = "algorithm",
        isBuiltIn = true,
    ),
    LANCZOS_2X(
        key = "lanczos_2x",
        displayName = "无损放大 2x",
        description = "放大 2 倍。纯 CPU 重采样，线条锐利不吃配置，没有 Vulkan 也能用。",
        scale = 2,
        requiresVulkan = false,
        modelDirName = "",
        modelType = "lanczos",
        isBuiltIn = true,
    ),
    REALCUGAN_2X_CONSERVATIVE(
        key = "realcugan_2x_conservative",
        displayName = "CUGAN 2x 保守",
        description = "放大 2 倍。漫画特化、线条最稳，画质优先；装 NPU 模型包后可走 NPU。",
        scale = 2,
        requiresVulkan = true,
        modelDirName = "realcugan-2x-conservative",
        modelType = "realcugan",
        isBuiltIn = true,
    ),
    ANIMEVIDEOV3_2X(
        key = "animevideov3_2x",
        // 与 4x 档同一套权重（md5 一致），走 Interp 2x 上采样分支。
        // 实算 0.62 vs CUGAN 1.42 MMACs/px：比 2x 保守少 2.3 倍算力；与 4x 档推理同速（卷积都在输入分辨率），省的是输出内存/IO
        displayName = "动漫V3 2x 极速",
        description = "放大 2 倍。比保守档快约一倍，线条略软；省电、批量首选，可走 NPU。",
        scale = 2,
        requiresVulkan = true,
        modelDirName = "animevideov3-2x",
        modelType = "realesrgan",
        isBuiltIn = true,
    ),
    REALESRGAN_ANIME_FAST(
        key = "realesrgan_anime_fast",
        displayName = "动漫V3 4x 大屏",
        description = "放大 4 倍。平板大屏细看用，手机上过剩；倍数越高越慢越占缓存。",
        scale = 4,
        requiresVulkan = true,
        modelDirName = "realesrgan-anime-fast",
        modelType = "realesrgan",
        isBuiltIn = true,
    ),
    WAIFU2X_1X(
        key = "waifu2x_cunet_1x",
        // waifu2x cunet 1x 降噪（固定 312 输入/256 业务区/28 贴边，blob Input1→Eltwise4）
        displayName = "waifu2x 1x 清理",
        description = "不放大。神经网络级去噪，快速优化不够干净时选它。",
        scale = 1,
        requiresVulkan = true,
        modelDirName = "waifu2x-cunet-1x",
        modelType = "waifu2x_cunet",
        isBuiltIn = true,
    );
    // 2026-08-28 移除 REALESRGAN_GENERAL_FAST（通用 快速）：用户设备上该模型推理必闪退。
    // 已选过它的用户靠 fromKey 兜底自动落回默认档。

    companion object {
        fun fromKey(key: String): SRModel =
            entries.firstOrNull { it.key == key } ?: QUICK_OPTIMIZE

        fun selectModel(scale: Int, denoiseLevel: DenoiseLevel, quality: Quality = Quality.BALANCED): SRModel =
            when {
                scale <= 1 -> if (denoiseLevel == DenoiseLevel.STRONG) WAIFU2X_1X else QUICK_OPTIMIZE
                scale >= 4 -> REALESRGAN_ANIME_FAST
                else -> REALCUGAN_2X_CONSERVATIVE
            }
    }

    /** 降噪强度是否对本档生效（算法档映射滤波参数；CUGAN/waifu2x 映射变体模型；V3 无降噪输入） */
    val denoiseCapable: Boolean
        get() = modelType != "realesrgan" && modelType != "lanczos"

    /** 有按强度切换的变体模型（切换强度需重载引擎） */
    val hasDenoiseVariants: Boolean
        get() = key == "realcugan_2x_conservative" || key == "waifu2x_cunet_1x"

    /** 按降噪强度解析实际模型目录（有变体档的模型按强度换文件，其余原样；"关闭"=原默认模型，行为不变） */
    fun resolveDirName(denoise: DenoiseLevel): String = when (key) {
        "realcugan_2x_conservative" -> when (denoise) {
            DenoiseLevel.LIGHT -> "realcugan-2x-denoise1x"
            DenoiseLevel.STRONG -> "realcugan-2x-denoise3x"
            else -> modelDirName
        }
        "waifu2x_cunet_1x" -> when (denoise) {
            DenoiseLevel.LIGHT -> "waifu2x-cunet-1x-noise1"
            DenoiseLevel.STRONG -> "waifu2x-cunet-1x-noise3"
            else -> modelDirName
        }
        else -> modelDirName
    }

    /** 需要随包解包的目录（基础 + 全部变体） */
    fun assetDirs(): List<String> = when (key) {
        "realcugan_2x_conservative" -> listOf(modelDirName, "realcugan-2x-denoise1x", "realcugan-2x-denoise3x")
        "waifu2x_cunet_1x" -> listOf(modelDirName, "waifu2x-cunet-1x-noise1", "waifu2x-cunet-1x-noise3")
        else -> listOf(modelDirName)
    }.filter { it.isNotEmpty() }
}
