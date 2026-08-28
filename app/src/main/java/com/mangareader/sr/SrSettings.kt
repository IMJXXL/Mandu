package com.mangareader.sr

import android.content.Context
import android.content.SharedPreferences

/**
 * 超分设置（全局 + 单作品覆盖），全部存 SharedPreferences，不动 Room 数据库（零迁移风险）。
 * 单作品：useBookOverride(bookId)=true 时以 sr_book_* 键读取，否则读全局。
 */
class SrSettings(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("manga_reader", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("sr_enabled", false)
        set(v) = prefs.edit().putBoolean("sr_enabled", v).apply()

    /** 超分放大总开关（作用域内生效）。关闭时仍可单独用"黑白漫画优化"（原尺寸、省电费） */
    var upscaleEnabled: Boolean
        get() = prefs.getBoolean("sr_upscale_enabled", true)
        set(v) = prefs.edit().putBoolean("sr_upscale_enabled", v).apply()

    var model: SRModel
        get() = SRModel.fromKey(prefs.getString("sr_model", SRModel.REALCUGAN_2X_CONSERVATIVE.key)!!)
        set(v) = prefs.edit().putString("sr_model", v.key).apply()

    var denoise: DenoiseLevel
        get() = DenoiseLevel.fromKey(prefs.getString("sr_denoise", DenoiseLevel.LIGHT.key)!!)
        set(v) = prefs.edit().putString("sr_denoise", v.key).apply()

    var quality: Quality
        get() = Quality.fromKey(prefs.getString("sr_quality", Quality.BALANCED.key)!!)
        set(v) = prefs.edit().putString("sr_quality", v.key).apply()

    /** 否启用 GPU 计算（默认关；崩溃或发热明显时可改 CPU 强制关闭 GPU）
     *  用户明确开启时才允许走 Vulkan，否则跑 CPU。骁龙8+ 芯片除外 */
    var enableGpu: Boolean
        get() = prefs.getBoolean("sr_enable_gpu", false)
        set(v) = prefs.edit().putBoolean("sr_enable_gpu", v).apply()

    /** 黑白优化开关（默认关；打开后按 grayLevels/density 生效） */
    var bwEnabled: Boolean
        get() = prefs.getBoolean("sr_bw_enabled", false)
        set(v) = prefs.edit().putBoolean("sr_bw_enabled", v).apply()

    /** 灰阶量化级数（8/16/32/64；仅 bwEnabled 时生效） */
    var bwGrayLevels: Int
        get() = prefs.getInt("sr_bw_gray_levels", 32)
        set(v) = prefs.edit().putInt("sr_bw_gray_levels", v).apply()

    /** 网点（halftone）密度校正 */
    var bwDensityCorrection: Boolean
        get() = prefs.getBoolean("sr_bw_density", true)
        set(v) = prefs.edit().putBoolean("sr_bw_density", v).apply()

    /** 前后对比：分割线对比模式 */
    var compareEnabled: Boolean
        get() = prefs.getBoolean("sr_compare", false)
        set(v) = prefs.edit().putBoolean("sr_compare", v).apply()

    /** 手动预加载页数覆盖；-1 = 跟随基准测试档位 */
    var preloadOverride: Int
        get() = prefs.getInt("sr_preload_override", -1)
        set(v) = prefs.edit().putInt("sr_preload_override", v).apply()

    /** 实时缓存上限（MB，用户自定，默认 100；范围 16~4096） */
    var realtimeCacheLimitMb: Int
        get() = prefs.getInt("sr_realtime_cache_mb", 100)
        set(v) = prefs.edit().putInt("sr_realtime_cache_mb", v.coerceIn(16, 4096)).apply()

    // ── 单作品覆盖 ──

    fun useBookOverride(bookId: Long): Boolean = prefs.getBoolean("sr_book_${bookId}_use", false)
    fun setUseBookOverride(bookId: Long, v: Boolean) = prefs.edit().putBoolean("sr_book_${bookId}_use", v).apply()

    fun bookEnabled(bookId: Long): Boolean = prefs.getBoolean("sr_book_${bookId}_enabled", enabled)
    fun setBookEnabled(bookId: Long, v: Boolean) = prefs.edit().putBoolean("sr_book_${bookId}_enabled", v).apply()

    fun bookModel(bookId: Long): SRModel = SRModel.fromKey(prefs.getString("sr_book_${bookId}_model", model.key)!!)
    fun setBookModel(bookId: Long, v: SRModel) = prefs.edit().putString("sr_book_${bookId}_model", v.key).apply()

    /** 阅读时生效的最终开关 */
    fun effectiveEnabled(bookId: Long?): Boolean {
        if (bookId == null) return enabled
        return if (useBookOverride(bookId)) bookEnabled(bookId) else enabled
    }

    fun effectiveModel(bookId: Long?): SRModel {
        if (bookId != null && useBookOverride(bookId)) return bookModel(bookId)
        return model
    }

    fun bwConfigOrNull(): MangaBWPostProcessConfig? =
        if (bwEnabled) MangaBWPostProcessConfig(bwGrayLevels, bwDensityCorrection) else null
}
