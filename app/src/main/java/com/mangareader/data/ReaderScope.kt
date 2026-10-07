package com.mangareader.data

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * 阅读偏好三层模型（2026-10-04 用户定稿终版）：
 *
 *   生效顺序：临时值(会话) > 本书覆盖 > 全局值 > 出厂默认
 *
 * - 【全局值】所有书的默认值：偏好页里「全局」徽章的项直接改；
 * - 【本书覆盖】书里调过的值，**永远最高优先**——总开关只管"之后的新调整记不记"，
 *   绝不影响已记住的覆盖（读取侧无开关）；
 * - 【临时值】总开关关闭时书内的新调整只进内存会话层，退出阅读丢弃；
 * - 【固定全局】大设置 › 阅读设置 纳管的项恒全局（不进偏好页）。
 * - 总开关语义（用户定稿）：开=书内新调整按作用域记忆；关=不再记住新调整（只对之后生效），
 *   已记住的书本设置继续生效。
 *
 * 写入必须走 [write]/[writeGlobal]/[clearBook] 单键路由，禁止整包 save 写书内键
 * （全量种子化是 852 时代"改一个滑条写 66 键"的根源，QA 红线）。
 */
object ReaderScope {

    /** 总开关「本书改动单独记」：开=书内改动写本书覆盖；关=仅会话层，退出阅读丢弃 */
    const val MASTER_KEY = "pref_center_book_override_enabled"

    /** 阅读模式（单页/条漫族）：特殊双键项——书层 read_mode_<id>，全局层 default_read_mode */
    const val READ_MODE_KEY = "readMode"
    const val READ_MODE_GLOBAL_KEY = "default_read_mode"
    fun readModeBookKey(bookId: Long) = "read_mode_$bookId"

    /**
     * 预设全局项（= 本功能引入前 ReaderConfig.save 无前缀写的全集 + 用户拍板项）：
     * 这些项书内改动直接写全局键，对所有书生效；偏好页可把单项改成"本书单独"。
     */
    val PRESET_GLOBAL_KEYS: Set<String> = setOf(
        "pageLayout", "autoDoubleLandscape", "landscapeAutoWebtoon", "doubleMergeSmart", // 用户拍板：单双页布局/横屏自动双页全局语义（智能配对随布局全局语义）
        "orientation", "readBackground",
        "isFlipAnimation", "isPageCurlAnimation", "isCurlContentAreaOnly",
        "curlBackContentAlpha", "curlShadowAlpha", "spineShadowAlpha",
        "imageScaleMode", "zoomStart", "landscapeZoom", "useSSIV", "isDoubleTapZoom",
        "isShowPageNum", "isKeepScreenOn", "isImmersiveMode",
        "autoWebtoon", "chapterTransition", "seamlessNextVolume", "autoFlipInterval",
        "isEinkMode", "isEinkDither", "einkDitherAlgorithm", "einkDitherLevels",
        "einkEndpointPassthrough", "einkDecodeSize", "einkUiHideDelay",
        "pdfQuality", "forceFullDecode", "tileModeEnabled", "downsampleTier", "tallSplitEnabled", "tallSplitSensitivity",
        "isFlipFlashEnabled", "flipFlashDuration", "flipFlashInterval", "flipFlashColor",
        "autoFlipInterval", "clickFlipType", "clickFlipInvertH", "readBackground",
    )

    /** 预设"本书单独"项（= 引入前 save 带前缀写的全集）：书内改动只记本书 */
    val BOOK_KEYS: Set<String> = setOf(
        "scrollMode", "clickFlipType", "clickFlipInvertH", "panNavigation",
        "isTrimWhiteBorder", "trimFactor", "trimThreshold", "trimWhiteRatio", "imageMarginH",
        "isGrayscale", "isInvertColors",
        "doublePageShift", "doublePageSplit", "doublePageReverse", "centerGapType",
        "autoDoubleLayout", // ★ 横屏自动双页档位记忆：按书（2026-10-07 这本下次横屏触发用改过的档位）
        "brightness", "screenBrightness", "contrast", "gamma",
        "brightnessEnabled", "screenBrightnessEnabled", "contrastEnabled", "gammaEnabled",
        "textureEnabled", "texturePresetId", "textureAlpha", "textureConcentration", "textureScope",
        "ambienceSound", "ambienceVolume",
    )

    /** 全部纳管键（清除/统计用） */
    val ALL_KEYS: Set<String> = PRESET_GLOBAL_KEYS + BOOK_KEYS + READ_MODE_KEY

    /**
     * 固定全局项（2026-10-04 用户定稿）：大设置 › 阅读设置 里有的项一律恒全局，
     * 偏好页不提供"本书单独"切换（自动翻页/性能解码/墨水屏/翻页闪烁/点击翻页区域/阅读背景）。
     * 写读恒走全局键，显式作用域与总开关都改变不了。
     */
    val GLOBAL_LOCKED_KEYS: Set<String> = setOf(
        "autoFlipInterval",
        "pdfQuality", "tileModeEnabled", "tallSplitEnabled", "tallSplitSensitivity", "downsampleTier", "forceFullDecode",
        "isEinkMode", "isEinkDither", "einkDitherAlgorithm", "einkDitherLevels",
        "einkEndpointPassthrough", "einkDecodeSize", "einkUiHideDelay",
        "isFlipFlashEnabled", "flipFlashDuration", "flipFlashInterval", "flipFlashColor",
        "clickFlipType", "clickFlipInvertH", "readBackground",
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

    /** 项的预设作用域 */
    fun presetScope(key: String): String = if (key in PRESET_GLOBAL_KEYS) SCOPE_GLOBAL else SCOPE_BOOK

    /** 单项作用域：显式指定 > 预设。显式指定是用户在偏好页亲选的，永远生效。 */
    fun userScope(prefs: SharedPreferences, key: String): String {
        val reg = parseScopeReg(prefs)
        reg[key]?.let { return it }
        return presetScope(key)
    }

    fun setScope(prefs: SharedPreferences, key: String, scope: String) {
        val reg = parseScopeReg(prefs).toMutableMap()
        if (scope == presetScope(key)) reg.remove(key) else reg[key] = scope
        val raw = reg.entries.joinToString(";") { "${it.key}=${it.value}" }
        prefs.edit().putString(SCOPE_REG_KEY, raw).apply()
    }

    /** 偏好页用：某项当前被指为"本书单独"吗 */
    fun isBookScope(prefs: SharedPreferences, key: String): Boolean =
        userScope(prefs, key) == SCOPE_BOOK && key !in GLOBAL_LOCKED_KEYS

    /** 该键此次写入应落的层：GLOBAL=全局键；BOOK=本书覆盖；SESSION=会话层 */
    enum class Layer { GLOBAL, BOOK, SESSION }

    fun resolveLayer(prefs: SharedPreferences, bookId: Long, key: String): Layer {
        // 写入落层：全局作用域→全局键；本书作用域→总开关开=记到书，关=仅会话（退出丢弃）。
        // 注意：总开关只影响"新调整"的写入，已存在的本书覆盖在读取侧永远生效。
        return when {
            key in GLOBAL_LOCKED_KEYS -> Layer.GLOBAL // 阅读设置纳管的项：恒全局
            // ★ bookId!=0 即有稳定身份（stableBookId 路径哈希可正可负，Long 符号位与
            //   入库无关）——仅 0（无路由参数/临时打开）才恒全局
            bookId == 0L -> Layer.GLOBAL
            userScope(prefs, key) == SCOPE_GLOBAL -> Layer.GLOBAL
            masterOn(prefs) -> Layer.BOOK
            else -> Layer.SESSION
        }
    }

    // ── 写路由（单键） ──

    fun write(context: Context, bookId: Long, key: String, value: Any) =
        write(context.getSharedPreferences("manga_reader", Context.MODE_PRIVATE), bookId, key, value)

    fun write(prefs: SharedPreferences, bookId: Long, key: String, value: Any) {
        val layer = resolveLayer(prefs, bookId, key)
        when (layer) {
            Layer.GLOBAL -> {
                if (key == READ_MODE_KEY) {
                    // 阅读模式全局化：写全局默认并清本书残留，全局即刻接管这本书
                    prefs.edit().putInt(READ_MODE_GLOBAL_KEY, value as Int)
                        .remove(readModeBookKey(bookId)).apply()
                } else putRaw(prefs.edit(), key, value)
            }
            Layer.BOOK -> {
                if (key == READ_MODE_KEY) putRaw(prefs.edit(), readModeBookKey(bookId), value)
                else putRaw(prefs.edit(), "book_${bookId}_$key", value)
            }
            Layer.SESSION -> ReaderSessionStore.put(bookId, key, value)
        }
        // 落键层的写入清该字段临时桶，防旧临时值遮蔽新落盘值
        if (layer != Layer.SESSION) ReaderSessionStore.remove(bookId, key)
    }

    fun writeGlobal(context: Context, key: String, value: Any) =
        writeGlobal(context.getSharedPreferences("manga_reader", Context.MODE_PRIVATE), key, value)

    fun writeGlobal(prefs: SharedPreferences, key: String, value: Any) {
        if (key == READ_MODE_KEY) {
            prefs.edit().putInt(READ_MODE_GLOBAL_KEY, value as Int).apply()
        } else putRaw(prefs.edit(), key, value)
    }

    /** 阅读模式解析（Reader 初始化用）：会话 > 本书 > 全局默认(-1=未设置) */
    fun readModeOrdinal(prefs: SharedPreferences, bookId: Long): Int {
        (ReaderSessionStore.get(bookId)?.get(READ_MODE_KEY) as? Int)?.let { return it }
        val book = prefs.safeGetInt(readModeBookKey(bookId), -1)
        if (book in 0..99) return book
        return prefs.safeGetInt(READ_MODE_GLOBAL_KEY, -1)
    }

    /** 这本书有"用户亲手切过模式"的记录吗（autoWebtoon 智能覆盖守卫用） */
    fun hasBookModeChoice(prefs: SharedPreferences, bookId: Long): Boolean =
        ReaderSessionStore.get(bookId)?.containsKey(READ_MODE_KEY) == true ||
            prefs.contains(readModeBookKey(bookId))

    /** 清除本书单项覆盖（连临时桶同清——清除本书覆盖跨层语义，迁移测试用例） */
    fun clearBook(context: Context, bookId: Long, key: String) =
        clearBook(context.getSharedPreferences("manga_reader", Context.MODE_PRIVATE), bookId, key)

    fun clearBook(prefs: SharedPreferences, bookId: Long, key: String) {
        prefs.edit().remove("book_${bookId}_$key").apply()
        if (key == READ_MODE_KEY) prefs.edit().remove(readModeBookKey(bookId)).apply()
        ReaderSessionStore.remove(bookId, key)
    }

    /** 清除本书全部覆盖（"恢复跟随全局"） */
    fun clearAllBook(context: Context, bookId: Long) =
        clearAllBook(context.getSharedPreferences("manga_reader", Context.MODE_PRIVATE), bookId)

    fun clearAllBook(prefs: SharedPreferences, bookId: Long) {
        val ed = prefs.edit()
        ALL_KEYS.forEach { ed.remove("book_${bookId}_$it") }
        ed.remove(readModeBookKey(bookId))
        ed.apply()
        ReaderSessionStore.clearBucket(bookId)
    }

    /** 本书覆盖了哪些项（偏好页/书内明细用） */
    fun overriddenKeys(prefs: SharedPreferences, bookId: Long): Set<String> {
        val out = ALL_KEYS.filter { prefs.contains("book_${bookId}_$it") }.toMutableSet()
        if (prefs.contains(readModeBookKey(bookId))) out.add(READ_MODE_KEY)
        return out
    }

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

    private fun SharedPreferences.safeGetInt(key: String, def: Int): Int =
        try { getInt(key, def) } catch (_: ClassCastException) { def }
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
