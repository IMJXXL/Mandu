package com.mangareader.data

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * 阅读偏好三层模型（2026-10-03 九方评审定稿，终版 9.1 分）：
 *
 *   生效顺序：临时值(会话) > 本书覆盖 > 全局值 > 出厂默认
 *
 * - 【全局值】每个设置项一份，阅读设置页/偏好页改；
 * - 【本书覆盖】在书内改动且总开关开（或字段豁免）时写入 book_<id>_<key>，只对本书生效；
 * - 【临时值】总开关关时书内改动只进内存会话层，退出阅读（reader 路由弹出返回栈）全清；
 * - 【豁免字段】pageLayout：覆盖恒参与解析（总开关关也生效），写入时同步清该字段临时桶
 *   （T+Oe 态不可达，QA 终审裁定）；
 * - 【条件锁定】翻页闪烁组：全局开关开着=整组接管（读写全局），关了=单本自决——沿用既有语义。
 *
 * 写入必须走 [write]/[writeGlobal]/[clearBook] 单键路由，禁止整包 save 写书内键
 * （全量种子化是 852 时代"改一个滑条写 66 键"的根源，QA 红线）。
 */
object ReaderScope {

    /** 总开关「本书改动单独记」：开=书内改动写本书覆盖；关=仅会话层，退出阅读丢弃 */
    const val MASTER_KEY = "pref_center_book_override_enabled"

    private const val PAGE_LAYOUT_KEY = "pageLayout"

    /** 恒全局项：书内改动直接写全局键（UI 层配"已对所有书生效"Toast 披露） */
    val GLOBAL_ONLY_KEYS: Set<String> = setOf(
        "isFlipAnimation", "isPageCurlAnimation", "isCurlContentAreaOnly",
        "curlBackContentAlpha", "curlShadowAlpha", "spineShadowAlpha",
        "isShowPageNum", "isKeepScreenOn", "readBackground",
        "isEinkMode", "isEinkDither", "einkDitherAlgorithm", "einkDitherLevels",
        "einkEndpointPassthrough", "einkDecodeSize", "einkUiHideDelay",
        "pdfQuality", "isImmersiveMode", "orientation", "autoFlipInterval",
        "landscapeZoom", "fitScreen", "autoWebtoon", "chapterTransition",
        "seamlessNextVolume", "autoDoubleLandscape", "forceFullDecode",
        "tileModeEnabled", "downsampleTier", "tallSplitEnabled",
    )

    /** 本书可覆盖项（书键 = book_<id>_<key>；总开关关时休眠→读全局，豁免项除外） */
    val BOOK_KEYS: Set<String> = setOf(
        "scrollMode", "clickFlipType", "clickFlipInvertH",
        "isTrimWhiteBorder", "trimFactor", "trimThreshold", "trimWhiteRatio", "imageMarginH",
        "isGrayscale", "isInvertColors",
        "isFlipFlashEnabled", "flipFlashDuration", "flipFlashInterval", "flipFlashColor",
        "useSSIV", "panNavigation", "doublePageShift", "doublePageSplit", "doublePageReverse",
        "centerGapType",
        "brightness", "screenBrightness", "contrast", "gamma",
        "brightnessEnabled", "screenBrightnessEnabled", "contrastEnabled", "gammaEnabled",
        "textureEnabled", "texturePresetId", "textureAlpha", "textureConcentration", "textureScope",
        "ambienceSound", "ambienceVolume",
    )

    /** 总开关豁免项：覆盖恒参与解析（总开关关也生效）——遥控/菜单切单双页"按了就记住" */
    val MASTER_EXEMPT_KEYS: Set<String> = setOf(PAGE_LAYOUT_KEY)

    /** 条件锁定组（全局开=接管读写全局，关=单本自决）：翻页闪烁组，沿用既有语义 */
    val CONDITIONAL_LOCK_KEYS: Set<String> = setOf(
        "isFlipFlashEnabled", "flipFlashDuration", "flipFlashInterval", "flipFlashColor",
    )

    fun masterOn(prefs: SharedPreferences): Boolean = prefs.safeGetBoolean(MASTER_KEY, true)

    // ── 单项作用域注册表（偏好页可改，仅存偏离预设的项；简单 k=v; 格式，纯 JVM 单测安全）──
    private const val SCOPE_REG_KEY = "pref_center_scope_reg"
    const val SCOPE_BOOK = "book"
    const val SCOPE_GLOBAL = "global"

    /** 解析作用域注册表：key → SCOPE_BOOK/SCOPE_GLOBAL（未出现的键=跟随预设） */
    fun parseScopeReg(prefs: SharedPreferences): Map<String, String> {
        val raw = prefs.getString(SCOPE_REG_KEY, null) ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        raw.split(';').forEach { seg ->
            if (seg.isBlank()) return@forEach
            val i = seg.indexOf('=')
            if (i > 0) out[seg.substring(0, i)] = seg.substring(i + 1)
        }
        return out
    }

    /** 单项作用域：显式指定 > 预设。BOOK_KEYS 预设单本，GLOBAL_ONLY_KEYS 预设全局。 */
    fun userScope(prefs: SharedPreferences, key: String): String {
        val reg = parseScopeReg(prefs)
        reg[key]?.let { return it }
        return if (key in GLOBAL_ONLY_KEYS) SCOPE_GLOBAL else SCOPE_BOOK
    }

    fun setScope(prefs: SharedPreferences, key: String, scope: String) {
        val reg = parseScopeReg(prefs).toMutableMap()
        val preset = if (key in GLOBAL_ONLY_KEYS) SCOPE_GLOBAL else SCOPE_BOOK
        if (scope == preset) reg.remove(key) else reg[key] = scope
        val raw = reg.entries.joinToString(";") { "${it.key}=${it.value}" }
        prefs.edit().putString(SCOPE_REG_KEY, raw).apply()
    }

    /** 偏好页用：某项当前被用户指为"单本生效"吗（预设单本或显式指 book） */
    fun isBookScope(prefs: SharedPreferences, key: String): Boolean =
        userScope(prefs, key) == SCOPE_BOOK

    /** 条件锁定是否生效（翻页闪烁全局开关开着） */
    fun conditionalLockActive(prefs: SharedPreferences): Boolean =
        prefs.safeGetBoolean("isFlipFlashEnabled", false)

    /** 该键此次写入应落的层：GLOBAL=全局键；BOOK=本书覆盖；SESSION=会话层 */
    enum class Layer { GLOBAL, BOOK, SESSION }

    fun resolveLayer(prefs: SharedPreferences, bookId: Long, key: String): Layer {
        // 显式指定的作用域才豁免总开关；未指定的键跟随预设/总开关
        val explicit = parseScopeReg(prefs)[key]
        return when {
            explicit == SCOPE_GLOBAL -> Layer.GLOBAL
            key in GLOBAL_ONLY_KEYS -> Layer.GLOBAL // 预设恒全局
            key in CONDITIONAL_LOCK_KEYS && conditionalLockActive(prefs) -> Layer.GLOBAL
            bookId <= 0 -> Layer.GLOBAL
            explicit == SCOPE_BOOK -> Layer.BOOK // 显式指单本：豁免总开关
            key in MASTER_EXEMPT_KEYS -> Layer.BOOK // 豁免字段（pageLayout）：任何状态都写本书
            masterOn(prefs) -> Layer.BOOK
            else -> Layer.SESSION
        }
    }

    // ── 写路由（单键） ──

    fun write(context: Context, bookId: Long, key: String, value: Any) =
        write(context.getSharedPreferences("manga_reader", Context.MODE_PRIVATE), bookId, key, value)

    fun write(prefs: SharedPreferences, bookId: Long, key: String, value: Any) {
        when (resolveLayer(prefs, bookId, key)) {
            Layer.GLOBAL -> putRaw(prefs.edit(), key, value)
            Layer.BOOK -> {
                putRaw(prefs.edit(), "book_${bookId}_$key", value)
                // E2 定案：豁免字段写入时同步清该字段临时桶（T+Oe 不可达）
                if (key in MASTER_EXEMPT_KEYS) ReaderSessionStore.remove(bookId, key)
            }
            Layer.SESSION -> ReaderSessionStore.put(bookId, key, value)
        }
    }

    fun writeGlobal(context: Context, key: String, value: Any) =
        writeGlobal(context.getSharedPreferences("manga_reader", Context.MODE_PRIVATE), key, value)

    fun writeGlobal(prefs: SharedPreferences, key: String, value: Any) {
        putRaw(prefs.edit(), key, value)
    }

    /** 清除本书单项覆盖（连临时桶同清——清除本书覆盖跨层语义，迁移测试用例） */
    fun clearBook(context: Context, bookId: Long, key: String) =
        clearBook(context.getSharedPreferences("manga_reader", Context.MODE_PRIVATE), bookId, key)

    fun clearBook(prefs: SharedPreferences, bookId: Long, key: String) {
        prefs.edit().remove("book_${bookId}_$key").apply()
        ReaderSessionStore.remove(bookId, key)
    }

    /** 清除本书全部覆盖（"恢复跟随全局"）——含条件锁定组以外的所有书键 */
    fun clearAllBook(context: Context, bookId: Long) =
        clearAllBook(context.getSharedPreferences("manga_reader", Context.MODE_PRIVATE), bookId, )

    fun clearAllBook(prefs: SharedPreferences, bookId: Long) {
        val ed = prefs.edit()
        (BOOK_KEYS + MASTER_EXEMPT_KEYS).forEach { ed.remove("book_${bookId}_$it") }
        ed.apply()
        ReaderSessionStore.clearBucket(bookId)
    }

    /** 本书覆盖了哪些项（偏好页/书内明细用） */
    fun overriddenKeys(prefs: SharedPreferences, bookId: Long): Set<String> =
        (BOOK_KEYS + MASTER_EXEMPT_KEYS).filter { prefs.contains("book_${bookId}_$it") }.toSet()

    private fun putRaw(ed: SharedPreferences.Editor, key: String, value: Any) {
        when (value) {
            is String -> ed.putString(key, value)
            is Int -> ed.putInt(key, value)
            is Boolean -> ed.putBoolean(key, value)
            is Float -> ed.putFloat(key, value)
            is Long -> ed.putLong(key, value)
            else -> ed.putString(key, value.toString())
        }.apply()
    }

    private fun SharedPreferences.safeGetBoolean(key: String, def: Boolean): Boolean =
        try { getBoolean(key, def) } catch (_: ClassCastException) { def }
}

/**
 * 会话临时层：进程级单例，按 bookId 分桶（key → 原始类型值）。
 * 存续 = reader 路由在返回栈中（书内跳书转场不清，从 B 返回 A 值还在）；
 * reader 路由完全弹出（真正退出阅读）或进程死亡时清全部（MainActivity 路由监听）。
 * 不落 prefs——"总开关关=退出阅读不保留"由内存态天然保证，进程死亡等同退出。
 */
object ReaderSessionStore {
    private val buckets = ConcurrentHashMap<Long, ConcurrentHashMap<String, Any?>>()

    /** 桶变更钩子：Reader 挂防抖 configVersion++（会话层不走 prefs，不触发监听） */
    @Volatile var onChange: (() -> Unit)? = null

    fun get(bookId: Long): Map<String, Any?>? = buckets[bookId]

    fun put(bookId: Long, key: String, value: Any?) {
        bucket(bookId)[key] = value
        onChange?.invoke()
    }

    fun remove(bookId: Long, key: String) {
        buckets[bookId]?.remove(key)
        onChange?.invoke()
    }

    fun clearBucket(bookId: Long) {
        buckets.remove(bookId)
        onChange?.invoke()
    }

    /** 真正退出阅读（reader 路由弹出返回栈）时全清 */
    fun clearAll() {
        if (buckets.isNotEmpty()) {
            buckets.clear()
            onChange?.invoke()
        }
    }

    private fun bucket(bookId: Long): ConcurrentHashMap<String, Any?> =
        buckets.computeIfAbsent(bookId) { ConcurrentHashMap() }
}
