package com.mangareader.data

import android.content.Context
import android.content.SharedPreferences

private fun SharedPreferences.safeGetBoolean(key: String, default: Boolean): Boolean =
    try { getBoolean(key, default) } catch (_: ClassCastException) { default }
private fun SharedPreferences.safeGetInt(key: String, default: Int): Int =
    try { getInt(key, default) } catch (_: ClassCastException) { default }
private fun SharedPreferences.safeGetFloat(key: String, default: Float): Float =
    try { getFloat(key, default) } catch (_: ClassCastException) { default }
private fun SharedPreferences.safeGetLong(key: String, default: Long): Long =
    try { getLong(key, default) } catch (_: ClassCastException) { default }

data class ReaderConfig(
    val scrollMode: ScrollMode = ScrollMode.HORIZONTAL_LEFT,
    val clickFlipType: ClickFlipType = ClickFlipType.LEFT_RIGHT,
    val isFlipAnimation: Boolean = true,
    val isPageCurlAnimation: Boolean = false,
    /** 双页仿真翻页「仅画面区域」模式：翻页动画只发生在画面矩形内，留边不参与（默认关=整页翻） */
    val isCurlContentAreaOnly: Boolean = false,
    /** 双页仿真翻页背面透出程度：翻页动画背面显示下一页内容的强度 0~1（0=纯背景色，1=完全清晰，默认 0.1=纸张背透） */
    val curlBackContentAlpha: Float = 0.1f,
    /** 仿真翻页投影阴影浓度 0~0.5（0=关闭阴影，默认 0.2） */
    val curlShadowAlpha: Float = 0.2f,
    /** 双页仿真翻页书脊沟阴影浓度 0~0.3（0=关闭，默认 0.09=现状）：书脊贴合处装订沟暗渐变 */
    val spineShadowAlpha: Float = 0.09f,
    val isShowPageNum: Boolean = true,
    val isKeepScreenOn: Boolean = true,
    val isTrimWhiteBorder: Boolean = false,
    val trimFactor: Float = 0.02f,
    /** 像素平均亮度超过此值视为"白色"，越小越激进（更多像素被识别为白色），越大越保守 */
    val trimThreshold: Int = 220,
    /** 一行/列中超过阈值的像素占比达到此值才算"白边"，越小越容易判定为白边，越大越保守 */
    val trimWhiteRatio: Float = 0.90f,
    val imageMarginH: Int = 0,
    val readBackground: ReadBackground = ReadBackground.WHITE,
    val isEinkMode: Boolean = false,
    /** 墨水屏灰阶抖动（256级模拟）：设备级特性，全局键（外部「阅读设置→墨水屏」开关，常规内出子面板）
     *  ★ 必须无前缀：外部设置页写全局键，阅读器 load 直读同名键，避开墨水屏单本组的"外部死设置"坑 */
    val isEinkDither: Boolean = false,
    /** 抖动算法：0=Floyd-Steinberg 1=Atkinson 2=Stucki 3=Bayer 4×4 */
    val einkDitherAlgorithm: Int = 0,
    /** 量化档位：16（主流 4bit 面板）/32/64 */
    val einkDitherLevels: Int = 16,
    val einkEndpointPassthrough: Int = 0,
    val einkDecodeSize: Int = 1920,
    val einkUiHideDelay: Long = 6000L,
    val pdfQuality: Float = 3.0f,
    val isImmersiveMode: Boolean = true,
    val orientation: Int = -1,
    val autoFlipInterval: Int = 0,
    val isGrayscale: Boolean = false,
    val isInvertColors: Boolean = false,
    val isFlipFlashEnabled: Boolean = false,
    val flipFlashDuration: Int = 100,
    val flipFlashInterval: Int = 1,
    val flipFlashColor: Int = 0,
    /** ★ 运行时标记（不落盘）：全局翻页闪烁已开启 → 本组值整组来自全局键，
     *   阅读内常规据此灰掉单本设置（全局 > 单本，见 load 的合并逻辑） */
    val pageLayout: PageLayout = PageLayout.SINGLE,
    val useSSIV: Boolean = true,
    val landscapeZoom: Boolean = false,
    val fitScreen: Boolean = false,
    val panNavigation: Boolean = true,
    val doublePageShift: Int = 0,
    val doublePageSplit: Boolean = false,
    val doublePageReverse: Boolean = false,

    val autoWebtoon: Boolean = true,
    val chapterTransition: Boolean = true,
    val centerGapType: Int = 0,

    /** ★ 横屏时自动使用双页（仅页面布局=单页时生效；全局语义，用户拍板放常规页） */
    val autoDoubleLandscape: Boolean = false,

    /** 点击翻页水平对调（左手模式）：区域矩形镜像，点右侧=上一页 */
    val clickFlipInvertH: Boolean = false,
    // ★ N1：卷末无缝续读（默认开；仅同一本书的分卷之间生效，跨书永不自动跳）
    val seamlessNextVolume: Boolean = true,
    val forceFullDecode: Boolean = false,
    val tileModeEnabled: Boolean = false,
    val downsampleTier: DownsampleTier = DownsampleTier.STANDARD,
    val tallSplitEnabled: Boolean = true,
    val brightness: Int = 0,
    val screenBrightness: Int = 0,
    val contrast: Int = 100,
    val gamma: Int = 100,
    val brightnessEnabled: Boolean = false,
    val screenBrightnessEnabled: Boolean = false,
    val contrastEnabled: Boolean = false,
    val gammaEnabled: Boolean = false,
    val textureEnabled: Boolean = false,
    val texturePresetId: Int = 0,
    val textureAlpha: Int = 15,
    val textureConcentration: Int = 100,
    /** ★ 纹理生效范围：0=仅图片（烘焙进页面位图，默认） 1=整屏幕（屏幕覆盖层，含留边背景） */
    val textureScope: Int = 0,
    // 氛围音选择：""=关 "rain"=预设雨声，其余=filesDir/ambience/ 下用户导入的文件名；音量 0-100
    val ambienceSound: String = "",
    val ambienceVolume: Int = 60
) {
    enum class ScrollMode { VERTICAL, HORIZONTAL_LEFT, HORIZONTAL_RIGHT }
    @Deprecated("Dead code - navigation uses Int field navigationMode instead") enum class ClickFlipType { NO, LEFT_RIGHT, DIAGONAL_LB_RT, DIAGONAL_LT_RB, L_SHAPE }
    @Deprecated("Dead code - navigationMode is stored as Int, this enum is never referenced") enum class NavigationMode(val label: String) {
        DEFAULT("默认"), L_NAV("L形"), KINDLISH("Kindlish"), EDGE("边沿"), RIGHT_LEFT("右与左")
    }
    enum class ReadBackground { DEFAULT, LIGHT, DARK, WHITE, GRAY }
    enum class PageLayout(val label: String) { SINGLE("单页"), DOUBLE("双页"), DOUBLE_SHIFTED("双页跳过首页") }
    enum class DownsampleTier(val threshold: Long, val label: String) {
        STANDARD(6_291_456L, "标准"), HD(12_582_912L, "高清"), ULTRA_HD(25_165_824L, "超清"), ORIGINAL(Long.MAX_VALUE, "原图")
    }

    fun save(context: Context, bookId: Long = -1) {
        val prefs = context.getSharedPreferences("manga_reader", Context.MODE_PRIVATE)
        val prefix = if (bookId >= 0) "book_${bookId}_" else ""
        prefs.edit().apply {
            putString("${prefix}scrollMode", scrollMode.name)
            // ★ 点击区域全局回退不固化：单本键未手动写过且值与全局一致时不落单本键，
            //   否则阅读内首次 save 即固化，外部全局卡片对该书永久失效
            val globalClickType = runCatching { prefs.getString("clickFlipType", null) }.getOrNull()
            if (prefix.isEmpty() || prefs.contains("${prefix}clickFlipType") || clickFlipType.name != globalClickType) {
                putString("${prefix}clickFlipType", clickFlipType.name)
            }
            val globalClickInvert = if (prefs.contains("clickFlipInvertH")) prefs.getBoolean("clickFlipInvertH", false) else null
            val globalInvertKnown = globalClickInvert != null
            if (prefix.isEmpty() || prefs.contains("${prefix}clickFlipInvertH") || !globalInvertKnown || clickFlipInvertH != globalClickInvert) {
                putBoolean("${prefix}clickFlipInvertH", clickFlipInvertH)
            }
            putBoolean("isFlipAnimation", isFlipAnimation)
            putBoolean("isPageCurlAnimation", isPageCurlAnimation)
            putBoolean("isCurlContentAreaOnly", isCurlContentAreaOnly)
            putFloat("curlBackContentAlpha", curlBackContentAlpha)
            putFloat("curlShadowAlpha", curlShadowAlpha)
            putFloat("spineShadowAlpha", spineShadowAlpha)
            putBoolean("isShowPageNum", isShowPageNum)
            putBoolean("isKeepScreenOn", isKeepScreenOn)
            putBoolean("${prefix}isTrimWhiteBorder", isTrimWhiteBorder)
            putFloat("${prefix}trimFactor", trimFactor)
            putInt("${prefix}trimThreshold", trimThreshold)
            putFloat("${prefix}trimWhiteRatio", trimWhiteRatio)
            putInt("${prefix}imageMarginH", imageMarginH)
            putString("readBackground", readBackground.name)
            putBoolean("isEinkMode", isEinkMode)
            putBoolean("isEinkDither", isEinkDither)
            putInt("einkDitherAlgorithm", einkDitherAlgorithm)
            putInt("einkDitherLevels", einkDitherLevels)
            putInt("einkEndpointPassthrough", einkEndpointPassthrough)
            putInt("einkDecodeSize", einkDecodeSize)
            putLong("einkUiHideDelay", einkUiHideDelay)
            putFloat("pdfQuality", pdfQuality)
            putBoolean("isImmersiveMode", isImmersiveMode)
            putInt("orientation", orientation)
            putInt("autoFlipInterval", autoFlipInterval)
            putBoolean("${prefix}isGrayscale", isGrayscale)
            putBoolean("${prefix}isInvertColors", isInvertColors)
            // ★ 翻页闪烁组固定全局（2026-10-04）：恒写全局键，值本就取自全局键（幂等）
            putBoolean("isFlipFlashEnabled", isFlipFlashEnabled)
            putInt("flipFlashDuration", flipFlashDuration)
            putInt("flipFlashInterval", flipFlashInterval)
            putInt("flipFlashColor", flipFlashColor)
            // ★ 2026-10-03 用户拍板：单双页（含"双页跳过首页"）改全局语义——恒写全局键，
            //   阅读内切换/遥控键切换/设置页下拉对所有书一并生效
            putString("pageLayout", pageLayout.name)
            putBoolean("${prefix}useSSIV", useSSIV)
            putBoolean("landscapeZoom", landscapeZoom)
            putBoolean("fitScreen", fitScreen)
            putBoolean("${prefix}panNavigation", panNavigation)
            putInt("${prefix}doublePageShift", doublePageShift)
            putBoolean("${prefix}doublePageSplit", doublePageSplit)
            putBoolean("${prefix}doublePageReverse", doublePageReverse)

            putBoolean("autoWebtoon", autoWebtoon)
            putBoolean("chapterTransition", chapterTransition)
            putBoolean("seamlessNextVolume", seamlessNextVolume)
            putInt("${prefix}centerGapType", centerGapType)
            // ★ 横屏自动双页为全局语义（用户拍板），恒写全局键
            putBoolean("autoDoubleLandscape", autoDoubleLandscape)
            putBoolean("forceFullDecode", forceFullDecode)
            putBoolean("tileModeEnabled", tileModeEnabled)
            putString("downsampleTier", downsampleTier.name)
            putBoolean("tallSplitEnabled", tallSplitEnabled)
            putInt("${prefix}brightness", brightness)
            putInt("${prefix}screenBrightness", screenBrightness)
            putInt("${prefix}contrast", contrast)
            putInt("${prefix}gamma", gamma)
            putBoolean("${prefix}brightnessEnabled", brightnessEnabled)
            putBoolean("${prefix}screenBrightnessEnabled", screenBrightnessEnabled)
            putBoolean("${prefix}contrastEnabled", contrastEnabled)
            putBoolean("${prefix}gammaEnabled", gammaEnabled)
            putBoolean("${prefix}textureEnabled", textureEnabled)
            putInt("${prefix}texturePresetId", texturePresetId)
            putInt("${prefix}textureAlpha", textureAlpha)
            putInt("${prefix}textureConcentration", textureConcentration)
            putInt("${prefix}textureScope", textureScope)
            putString("${prefix}ambienceSound", ambienceSound)
            putInt("${prefix}ambienceVolume", ambienceVolume)
            apply()
        }
    }

    companion object {
        fun load(context: Context, bookId: Long = -1): ReaderConfig =
            load(context.getSharedPreferences("manga_reader", Context.MODE_PRIVATE), bookId)

        fun load(prefs: SharedPreferences, bookId: Long = -1): ReaderConfig {
            val prefix = if (bookId >= 0) "book_${bookId}_" else ""
            // ★ 读取分屋（2026-10-04 终版）：书里调过的永远最高优先；
            //   "本书"作用域的项与全局键彻底脱钩——只认这本书自己的覆盖，
            //   没调过=出厂初始值（全局键的历史改动影响不到它们）；
            //   "全局"作用域的项 → 全局键；无书上下文(bookId<0) → 全局键（全局项页面用）。
            //   会话临时层在末尾统一覆盖。
            val scopeReg = ReaderScope.parseScopeReg(prefs)
            fun rawFor(key: String): Any? = when {
                key in ReaderScope.GLOBAL_LOCKED_KEYS -> prefs.all[key] // 固定全局：无视残留作用域记录
                bookId >= 0 && (scopeReg[key] ?: ReaderScope.presetScope(key)) == ReaderScope.SCOPE_BOOK ->
                    prefs.all["${prefix}$key"]
                else -> prefs.all[key]
            }
            fun rBoolean(key: String, def: Boolean): Boolean = (rawFor(key) as? Boolean) ?: def
            fun rInt(key: String, def: Int): Int = (rawFor(key) as? Int) ?: def
            fun rFloat(key: String, def: Float): Float = (rawFor(key) as? Float) ?: def
            fun rLong(key: String, def: Long): Long = (rawFor(key) as? Long) ?: def
            fun rString(key: String, def: String): String = (rawFor(key) as? String) ?: def
            var cfg = ReaderConfig(
                scrollMode = try { ScrollMode.valueOf(rString("scrollMode", "HORIZONTAL_LEFT")) } catch (_: Exception) { ScrollMode.HORIZONTAL_LEFT },
                clickFlipType = try { ClickFlipType.valueOf(rString("clickFlipType", "LEFT_RIGHT")) } catch (_: Exception) { ClickFlipType.LEFT_RIGHT },
                isFlipAnimation = rBoolean("isFlipAnimation", true),
                isPageCurlAnimation = rBoolean("isPageCurlAnimation", false),
                isCurlContentAreaOnly = rBoolean("isCurlContentAreaOnly", false),
                curlBackContentAlpha = rFloat("curlBackContentAlpha", 0.1f),
                curlShadowAlpha = rFloat("curlShadowAlpha", 0.2f),
                spineShadowAlpha = rFloat("spineShadowAlpha", 0.09f),
                isShowPageNum = rBoolean("isShowPageNum", true),
                isKeepScreenOn = rBoolean("isKeepScreenOn", true),
                isTrimWhiteBorder = rBoolean("isTrimWhiteBorder", false),
                trimFactor = rFloat("trimFactor", 0.02f),
                trimThreshold = rInt("trimThreshold", 220),
                trimWhiteRatio = rFloat("trimWhiteRatio", 0.90f),
                imageMarginH = rInt("imageMarginH", 0),
                readBackground = try { ReadBackground.valueOf(rString("readBackground", "WHITE")) } catch (_: Exception) { ReadBackground.WHITE },
                isEinkMode = rBoolean("isEinkMode", false),
                isEinkDither = rBoolean("isEinkDither", false),
                einkDitherAlgorithm = rInt("einkDitherAlgorithm", 0),
                einkDitherLevels = rInt("einkDitherLevels", 16),
                einkEndpointPassthrough = rInt("einkEndpointPassthrough", 0).coerceIn(0, 8),
                einkDecodeSize = rInt("einkDecodeSize", 1920),
                einkUiHideDelay = rLong("einkUiHideDelay", 6000L),
                pdfQuality = rFloat("pdfQuality", 3.0f),
                isImmersiveMode = rBoolean("isImmersiveMode", true),
                orientation = rInt("orientation", -1),
                autoFlipInterval = rInt("autoFlipInterval", 0),
                isGrayscale = rBoolean("isGrayscale", false),
                isInvertColors = rBoolean("isInvertColors", false),
                isFlipFlashEnabled = rBoolean("isFlipFlashEnabled", false),
                flipFlashDuration = rInt("flipFlashDuration", 100),
                flipFlashInterval = rInt("flipFlashInterval", 1),
                flipFlashColor = rInt("flipFlashColor", 0),
                // ★ 豁免字段（总开关关也生效）：本书覆盖优先，其次全局键（852 语义默认层）。
                pageLayout = try { PageLayout.valueOf(rString("pageLayout", "SINGLE")) } catch (_: Exception) { PageLayout.SINGLE },
                useSSIV = rBoolean("useSSIV", true),
                landscapeZoom = rBoolean("landscapeZoom", false),
                fitScreen = rBoolean("fitScreen", false),
                panNavigation = rBoolean("panNavigation", true),
                doublePageShift = rInt("doublePageShift", 0),
                doublePageSplit = rBoolean("doublePageSplit", false),
                doublePageReverse = rBoolean("doublePageReverse", false),

                autoWebtoon = rBoolean("autoWebtoon", true),
                chapterTransition = rBoolean("chapterTransition", true),
                centerGapType = rInt("centerGapType", 0),
                autoDoubleLandscape = rBoolean("autoDoubleLandscape", false),
                clickFlipInvertH = rBoolean("clickFlipInvertH", false),
                seamlessNextVolume = rBoolean("seamlessNextVolume", true),
                forceFullDecode = rBoolean("forceFullDecode", false),
                tileModeEnabled = rBoolean("tileModeEnabled", false),
                tallSplitEnabled = rBoolean("tallSplitEnabled", true),
                brightness = rInt("brightness", 0).coerceIn(-100, 100),
                screenBrightness = rInt("screenBrightness", 0).coerceIn(-100, 100),
                contrast = rInt("contrast", 100).coerceIn(0, 200),
                gamma = rInt("gamma", 100).coerceIn(50, 150),
                // 滤镜启用标志：分层读，未落键时从对应值派生（值已设=启用，兼容旧数据）
                brightnessEnabled = rBoolean("brightnessEnabled", rInt("brightness", 0) != 0),
                screenBrightnessEnabled = rBoolean("screenBrightnessEnabled", rInt("screenBrightness", 0) != 0),
                contrastEnabled = rBoolean("contrastEnabled", rInt("contrast", 100) != 100),
                gammaEnabled = rBoolean("gammaEnabled", rInt("gamma", 100) != 100),
                textureEnabled = rBoolean("textureEnabled", false),
                texturePresetId = rInt("texturePresetId", 0),
                textureAlpha = rInt("textureAlpha", 15).coerceIn(0, 40),
                textureConcentration = rInt("textureConcentration", 100).coerceIn(0, 100),
                textureScope = rInt("textureScope", 0).coerceIn(0, 1),
                // ambienceSound v1 曾以 Int 持久化（0关/1雨/2篝火/3咖啡），v2 为 String——
                // 裸 getString 读旧 Int 会 ClassCastException（任何加载配置的入口都崩）。
                // 读原始值做类型归一：1→"rain"，其余旧值→""（对应音色已删）
                ambienceSound = when (val raw = rawFor("ambienceSound")) {
                    is String -> raw
                    is Int -> if (raw == 1) "rain" else ""
                    is Long -> if (raw == 1L) "rain" else ""
                    else -> ""
                },
                ambienceVolume = rInt("ambienceVolume", 60).coerceIn(0, 100),
                downsampleTier = run {
                    when (val raw = rawFor("downsampleTier")) {
                        is String -> try { DownsampleTier.valueOf(raw) } catch (_: Exception) { DownsampleTier.STANDARD }
                        // 旧语义兜底：从未写过档位但开了强制完整解码 → 原图档
                        else -> if (rBoolean("forceFullDecode", false)) DownsampleTier.ORIGINAL else DownsampleTier.STANDARD
                    }
                }
            )
            // ★ 会话临时层覆盖（最高优先）：总开关关时的书内改动只存内存，退出阅读清空
            if (bookId > 0) {
                ReaderSessionStore.get(bookId)?.forEach { (k, v) -> cfg = applySessionOverride(cfg, k, v) }
            }
            return cfg
        }

        /** 会话层原始值回贴（key → 字段）：仅 ReaderScope.BOOK_KEYS 内的键会出现 */
        fun applySessionOverride(cfg: ReaderConfig, key: String, v: Any?): ReaderConfig {
            if (v == null) return cfg
            return when (key) {
                "scrollMode" -> cfg.copy(scrollMode = enumOr(v, ScrollMode.HORIZONTAL_LEFT))
                "clickFlipType" -> cfg.copy(clickFlipType = enumOr(v, ClickFlipType.LEFT_RIGHT))
                "clickFlipInvertH" -> cfg.copy(clickFlipInvertH = v as Boolean)
                "isTrimWhiteBorder" -> cfg.copy(isTrimWhiteBorder = v as Boolean)
                "trimFactor" -> cfg.copy(trimFactor = v as Float)
                "trimThreshold" -> cfg.copy(trimThreshold = v as Int)
                "trimWhiteRatio" -> cfg.copy(trimWhiteRatio = v as Float)
                "imageMarginH" -> cfg.copy(imageMarginH = v as Int)
                "isGrayscale" -> cfg.copy(isGrayscale = v as Boolean)
                "isInvertColors" -> cfg.copy(isInvertColors = v as Boolean)
                "isFlipFlashEnabled" -> cfg.copy(isFlipFlashEnabled = v as Boolean)
                "flipFlashDuration" -> cfg.copy(flipFlashDuration = v as Int)
                "flipFlashInterval" -> cfg.copy(flipFlashInterval = v as Int)
                "flipFlashColor" -> cfg.copy(flipFlashColor = v as Int)
                "useSSIV" -> cfg.copy(useSSIV = v as Boolean)
                "panNavigation" -> cfg.copy(panNavigation = v as Boolean)
                "doublePageShift" -> cfg.copy(doublePageShift = v as Int)
                "doublePageSplit" -> cfg.copy(doublePageSplit = v as Boolean)
                "doublePageReverse" -> cfg.copy(doublePageReverse = v as Boolean)
                "centerGapType" -> cfg.copy(centerGapType = v as Int)
                "brightness" -> cfg.copy(brightness = v as Int)
                "screenBrightness" -> cfg.copy(screenBrightness = v as Int)
                "contrast" -> cfg.copy(contrast = v as Int)
                "gamma" -> cfg.copy(gamma = v as Int)
                "brightnessEnabled" -> cfg.copy(brightnessEnabled = v as Boolean)
                "screenBrightnessEnabled" -> cfg.copy(screenBrightnessEnabled = v as Boolean)
                "contrastEnabled" -> cfg.copy(contrastEnabled = v as Boolean)
                "gammaEnabled" -> cfg.copy(gammaEnabled = v as Boolean)
                "textureEnabled" -> cfg.copy(textureEnabled = v as Boolean)
                "texturePresetId" -> cfg.copy(texturePresetId = v as Int)
                "textureAlpha" -> cfg.copy(textureAlpha = v as Int)
                "textureConcentration" -> cfg.copy(textureConcentration = v as Int)
                "textureScope" -> cfg.copy(textureScope = v as Int)
                "ambienceSound" -> cfg.copy(ambienceSound = v as String)
                "ambienceVolume" -> cfg.copy(ambienceVolume = v as Int)
                "pageLayout" -> cfg.copy(pageLayout = enumOr(v, PageLayout.SINGLE))
                // 以下为"预设全局但可切单本"的键：总开关关闭+作用域本书时会进会话层（2026-10-04）
                "orientation" -> cfg.copy(orientation = v as Int)
                "isFlipAnimation" -> cfg.copy(isFlipAnimation = v as Boolean)
                "isPageCurlAnimation" -> cfg.copy(isPageCurlAnimation = v as Boolean)
                "isCurlContentAreaOnly" -> cfg.copy(isCurlContentAreaOnly = v as Boolean)
                "curlBackContentAlpha" -> cfg.copy(curlBackContentAlpha = v as Float)
                "curlShadowAlpha" -> cfg.copy(curlShadowAlpha = v as Float)
                "spineShadowAlpha" -> cfg.copy(spineShadowAlpha = v as Float)
                "fitScreen" -> cfg.copy(fitScreen = v as Boolean)
                "landscapeZoom" -> cfg.copy(landscapeZoom = v as Boolean)
                "autoWebtoon" -> cfg.copy(autoWebtoon = v as Boolean)
                "chapterTransition" -> cfg.copy(chapterTransition = v as Boolean)
                "seamlessNextVolume" -> cfg.copy(seamlessNextVolume = v as Boolean)
                "autoDoubleLandscape" -> cfg.copy(autoDoubleLandscape = v as Boolean)
                else -> cfg
            }
        }

        private inline fun <reified T : Enum<T>> enumOr(v: Any, def: T): T =
            if (v is T) v else try { enumValueOf<T>(v.toString()) } catch (_: Exception) { def }

        // ── 字段访问表：diff（书内设置面板改了哪些项）与会话/覆盖取值共用单一来源 ──
        val FIELD_ACCESSORS: List<Pair<String, (ReaderConfig) -> Any>> = listOf(
            "scrollMode" to { it.scrollMode.name },
            "clickFlipType" to { it.clickFlipType.name },
            "clickFlipInvertH" to { it.clickFlipInvertH },
            "isTrimWhiteBorder" to { it.isTrimWhiteBorder },
            "trimFactor" to { it.trimFactor },
            "trimThreshold" to { it.trimThreshold },
            "trimWhiteRatio" to { it.trimWhiteRatio },
            "imageMarginH" to { it.imageMarginH },
            "isGrayscale" to { it.isGrayscale },
            "isInvertColors" to { it.isInvertColors },
            "isFlipFlashEnabled" to { it.isFlipFlashEnabled },
            "flipFlashDuration" to { it.flipFlashDuration },
            "flipFlashInterval" to { it.flipFlashInterval },
            "flipFlashColor" to { it.flipFlashColor },
            "useSSIV" to { it.useSSIV },
            "panNavigation" to { it.panNavigation },
            "doublePageShift" to { it.doublePageShift },
            "doublePageSplit" to { it.doublePageSplit },
            "doublePageReverse" to { it.doublePageReverse },
            "centerGapType" to { it.centerGapType },
            "brightness" to { it.brightness },
            "screenBrightness" to { it.screenBrightness },
            "contrast" to { it.contrast },
            "gamma" to { it.gamma },
            "brightnessEnabled" to { it.brightnessEnabled },
            "screenBrightnessEnabled" to { it.screenBrightnessEnabled },
            "contrastEnabled" to { it.contrastEnabled },
            "gammaEnabled" to { it.gammaEnabled },
            "textureEnabled" to { it.textureEnabled },
            "texturePresetId" to { it.texturePresetId },
            "textureAlpha" to { it.textureAlpha },
            "textureConcentration" to { it.textureConcentration },
            "textureScope" to { it.textureScope },
            "ambienceSound" to { it.ambienceSound },
            "ambienceVolume" to { it.ambienceVolume },
            "pageLayout" to { it.pageLayout.name },
            "isFlipAnimation" to { it.isFlipAnimation },
            "isPageCurlAnimation" to { it.isPageCurlAnimation },
            "isCurlContentAreaOnly" to { it.isCurlContentAreaOnly },
            "curlBackContentAlpha" to { it.curlBackContentAlpha },
            "curlShadowAlpha" to { it.curlShadowAlpha },
            "spineShadowAlpha" to { it.spineShadowAlpha },
            "isShowPageNum" to { it.isShowPageNum },
            "isKeepScreenOn" to { it.isKeepScreenOn },
            "readBackground" to { it.readBackground.name },
            "isEinkMode" to { it.isEinkMode },
            "isEinkDither" to { it.isEinkDither },
            "einkDitherAlgorithm" to { it.einkDitherAlgorithm },
            "einkDitherLevels" to { it.einkDitherLevels },
            "einkEndpointPassthrough" to { it.einkEndpointPassthrough },
            "einkDecodeSize" to { it.einkDecodeSize },
            "einkUiHideDelay" to { it.einkUiHideDelay },
            "pdfQuality" to { it.pdfQuality },
            "isImmersiveMode" to { it.isImmersiveMode },
            "orientation" to { it.orientation },
            "autoFlipInterval" to { it.autoFlipInterval },
            "landscapeZoom" to { it.landscapeZoom },
            "fitScreen" to { it.fitScreen },
            "autoWebtoon" to { it.autoWebtoon },
            "chapterTransition" to { it.chapterTransition },
            "autoDoubleLandscape" to { it.autoDoubleLandscape },
            "seamlessNextVolume" to { it.seamlessNextVolume },
            "forceFullDecode" to { it.forceFullDecode },
            "tileModeEnabled" to { it.tileModeEnabled },
            "downsampleTier" to { it.downsampleTier.name },
            "tallSplitEnabled" to { it.tallSplitEnabled },
        )

        /** 书内设置面板 diff：返回真正改动的键名（含全局键——由 ReaderScope 决定写层） */
        fun changedFields(old: ReaderConfig, new: ReaderConfig): List<String> =
            FIELD_ACCESSORS.mapNotNull { (k, get) -> if (get(old) != get(new)) k else null }
    }
}
