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
        get() = SRModel.fromKey(prefs.getString("sr_model", SRModel.QUICK_OPTIMIZE.key)!!)
        set(v) = prefs.edit().putString("sr_model", v.key).apply()

    var denoise: DenoiseLevel
        get() = DenoiseLevel.fromKey(prefs.getString("sr_denoise", DenoiseLevel.LIGHT.key)!!)
        set(v) = prefs.edit().putString("sr_denoise", v.key).apply()

    var quality: Quality
        get() = Quality.fromKey(prefs.getString("sr_quality", Quality.BALANCED.key)!!)
        set(v) = prefs.edit().putString("sr_quality", v.key).apply()

    /** 处理引擎：CPU 稳妥 / GPU(Vulkan) / NPU(骁龙)。默认 CPU（与旧 sr_enable_gpu=false 等价）。
     *  迁移：老版本只有 sr_enable_gpu 开关，true→GPU、false→CPU，见到 sr_engine 键之前按旧键解释。 */
    var engine: SrEngine
        get() {
            if (!prefs.contains("sr_engine")) {
                return if (prefs.getBoolean("sr_enable_gpu", false)) SrEngine.GPU else SrEngine.CPU
            }
            return SrEngine.fromKey(prefs.getString("sr_engine", SrEngine.CPU.key)!!)
        }
        set(v) = prefs.edit().putString("sr_engine", v.key).apply()

    /** 兼容旧引用：GPU 开关语义 = 引擎是否为 GPU */
    var enableGpu: Boolean
        get() = engine == SrEngine.GPU
        set(v) {
            engine = if (v) SrEngine.GPU else SrEngine.CPU
        }

    /** 设备封锁标记：NPU 实测死在 DSP 传输层（s=7 + 错误 14001）＝厂商不给应用开
     *  CDSP 无签名域（MIUI 部分机型实锤），再试多少次都一样——记住结论直接在面板明示，
     *  不再每次会话空跑三连败熔断。重新实测通过后自动清除。 */
    var npuDeviceBlock: Boolean
        get() = prefs.getBoolean("sr_npu_block", false)
        set(v) = prefs.edit().putBoolean("sr_npu_block", v).apply()

    /** NPU 实测通过标记：面板「测试 NPU」真实加载一次模型成功后置位（用户主动制）。
     *  路由硬性要求它为 true 才走 NPU——没测过/没装插件选了 NPU 也自动回 GPU。 */
    var npuTestOk: Boolean
        get() = prefs.getBoolean("sr_npu_test_ok", false)
        set(v) = prefs.edit().putBoolean("sr_npu_test_ok", v).apply()

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

    /** 实时缓存上限（MB，用户自定，默认 1000；范围 16~4096）。2026-10-03 默认 100→1000 */
    var realtimeCacheLimitMb: Int
        get() = prefs.getInt("sr_realtime_cache_mb", 1000)
        set(v) = prefs.edit().putInt("sr_realtime_cache_mb", v.coerceIn(16, 4096)).apply()

    /** ★ 页级持久产物上限（MB，用户自定，默认 1000；范围 16~4096）。
     *  实时增强结果永久落盘的总额度，超上限自动清最旧（2026-10-03 用户拍板三件套之一） */
    var artifactCacheLimitMb: Int
        get() = prefs.getInt("sr_artifact_cache_mb", 1000)
        set(v) = prefs.edit().putInt("sr_artifact_cache_mb", v.coerceIn(16, 4096)).apply()

    // ── 单作品覆盖 ──

    // ★ 降低摩尔纹（Komiho 对齐 2026-10-07）：sr_ 前缀自动进"恢复默认"纳管
    fun areaDownscale(): Boolean = prefs.getBoolean("sr_area_downscale", false)
    fun setAreaDownscale(v: Boolean) = prefs.edit().putBoolean("sr_area_downscale", v).apply()
    fun areaDownscaleStrength(): Int = prefs.getInt("sr_area_downscale_strength", 100)
    fun setAreaDownscaleStrength(v: Int) = prefs.edit().putInt("sr_area_downscale_strength", v).apply()

    // ★ 始终开启作用域（SCOPE_ALWAYS，2026-10-11）：全局+持久记忆，重启自动恢复直到手动关闭
    fun scopeAlways(): Boolean = prefs.getBoolean("sr_scope_always", false)
    fun setScopeAlways(v: Boolean) = prefs.edit().putBoolean("sr_scope_always", v).apply()

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
