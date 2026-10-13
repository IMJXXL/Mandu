package com.mangareader.keys

import android.content.Context
import android.view.KeyEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 硬件按键映射（2026-10-03）：蓝牙遥控器 / 翻页器 / 蓝牙键盘 / 电纸书侧键。
 *
 * 原理：这类设备在 Android 上都是标准 HID 键盘，按键以 KeyEvent 形式派发给有焦点的
 * 窗口——不需要任何蓝牙权限或蓝牙代码，只要 App 会接按键。所以本功能 =「按键码→功能」
 * 映射表，阅读页挂在 MainActivity.dispatchKeyEvent 第一直接力上。
 *
 * 优先级：用户显式映射 > 音量键翻页旧链路（未映射的音量键原样让给 VolumeKeyHandler，
 * 保留其菜单守卫与方向反转）> 系统。BACK 键默认不在映射表里（退出阅读仍走系统返回）。
 */

/** 按键可绑定的功能——只列阅读器真实存在的动作，不画不存在的饼 */
enum class KeyAction(val label: String) {
    NONE("无作用"),
    NEXT_PAGE("下一页"),
    PREV_PAGE("上一页"),
    /** 条漫族=滚动一屏（长按连续滚）；翻页族=顺向翻一页 */
    SCROLL_FORWARD("顺向滚动并换页"),
    SCROLL_BACKWARD("逆向滚动并换页"),
    TOGGLE_MENU("呼出/关闭菜单"),
    TOGGLE_READER_SETTINGS("打开/关闭阅读设置"),
    ADD_BOOKMARK("插入/取消书签"),
    BOOKMARK_LIST("书签列表"),
    NEXT_CHAPTER("下一章/下一卷"),
    PREV_CHAPTER("上一章/上一卷"),
    BRIGHTNESS_UP("亮度调亮"),
    BRIGHTNESS_DOWN("亮度调暗"),
    TOGGLE_DIRECTION("切换左右阅读方向"),
    /** 单页→双页(横开)→单页…与设置面板同一条 config 路径 */
    TOGGLE_PAGE_LAYOUT("单页/双页切换"),
    /** VERT→FLIP→RTL→VERT 循环；条漫变体(VERT_PAGER/WEBTOON_GAP)归入 VERT 位 */
    MODE_CYCLE("循环切换阅读模式"),
    /** 开=用记住的间隔(无则 8 秒)；关=停。间隔本体仍在阅读设置里调 */
    TOGGLE_AUTO_FLIP("自动翻页 开/关"),
}

@Serializable
data class KeyMapping(
    val keyCode: Int,
    /** KeyAction.name；存字符串保证跨版本枚举增删不炸 */
    val action: String,
    val label: String = "",
) {
    fun actionEnum(): KeyAction = KeyAction.entries.firstOrNull { it.name == action } ?: KeyAction.NONE

    companion object {
        fun of(code: Int, a: KeyAction, label: String = "") = KeyMapping(code, a.name, label)
    }
}

/** 阅读器/侦测对话框挂 handler，MainActivity.dispatchKeyEvent 第一直接力后转发 */
object HardwareKeyRouter {
    @Volatile
    var handler: ((keyCode: Int, event: KeyEvent) -> Boolean)? = null
}

object KeyMapStore {

    private const val PREF = "manga_reader"
    private const val KEY = "hw_key_mappings"
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cache: List<KeyMapping>? = null

    /**
     * 初始默认表（首次读取时落盘，之后完全交给用户增删改）。
     * 覆盖常见遥控器的按键：十字键/OK（盒子和翻页器）、PageUp/Down（电纸书侧键）、
     * 媒体上一曲/下一曲和播放暂停（自拍/多媒体遥控）、空格和 J/K（蓝牙键盘）。
     * 音量键不入默认表——默认保持调音量，想当翻页键在阅读设置里开开关或在下方显式映射。
     */
    fun defaults(): List<KeyMapping> = listOf(
        KeyMapping.of(KeyEvent.KEYCODE_DPAD_UP, KeyAction.SCROLL_BACKWARD, "十字键 ↑"),
        KeyMapping.of(KeyEvent.KEYCODE_DPAD_DOWN, KeyAction.SCROLL_FORWARD, "十字键 ↓"),
        KeyMapping.of(KeyEvent.KEYCODE_DPAD_LEFT, KeyAction.PREV_PAGE, "十字键 ←"),
        KeyMapping.of(KeyEvent.KEYCODE_DPAD_RIGHT, KeyAction.NEXT_PAGE, "十字键 →"),
        KeyMapping.of(KeyEvent.KEYCODE_DPAD_CENTER, KeyAction.TOGGLE_MENU, "OK 键"),
        KeyMapping.of(KeyEvent.KEYCODE_ENTER, KeyAction.TOGGLE_MENU, "回车键"),
        KeyMapping.of(KeyEvent.KEYCODE_SPACE, KeyAction.NEXT_PAGE, "空格键"),
        KeyMapping.of(KeyEvent.KEYCODE_PAGE_UP, KeyAction.PREV_PAGE, "翻页键 PageUp"),
        KeyMapping.of(KeyEvent.KEYCODE_PAGE_DOWN, KeyAction.NEXT_PAGE, "翻页键 PageDown"),
        KeyMapping.of(KeyEvent.KEYCODE_MEDIA_NEXT, KeyAction.NEXT_PAGE, "媒体「下一曲」"),
        KeyMapping.of(KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyAction.PREV_PAGE, "媒体「上一曲」"),
        KeyMapping.of(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyAction.TOGGLE_MENU, "播放/暂停键"),
        KeyMapping.of(KeyEvent.KEYCODE_J, KeyAction.NEXT_PAGE, "J 键"),
        KeyMapping.of(KeyEvent.KEYCODE_K, KeyAction.PREV_PAGE, "K 键"),
    )

    /** 读映射表（线程安全，首次读取时把默认表落盘成可编辑的初始内容） */
    fun mappings(ctx: Context): List<KeyMapping> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY, null)
            val list = if (raw == null) {
                val d = defaults()
                prefs.edit().putString(KEY, json.encodeToString(d)).apply()
                d
            } else {
                try {
                    json.decodeFromString<List<KeyMapping>>(raw)
                } catch (_: Exception) {
                    // ★ 审计修复：JSON 损坏自愈回默认表并落盘——原先静默空表，下次 save
                    //   会把用户映射无声清空
                    val d = defaults()
                    prefs.edit().putString(KEY, json.encodeToString(d)).apply()
                    d
                }
            }
            cache = list
            return list
        }
    }

    fun save(ctx: Context, list: List<KeyMapping>) {
        synchronized(this) {
            cache = list
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY, json.encodeToString(list)).apply()
        }
    }

    fun resetDefaults(ctx: Context) = save(ctx, defaults())

    /** KeyEvent.keyCodeToString 的友好版：去 KEYCODE_ 前缀、下划线转空格（对齐参考 app 的 KEYCODE_VOLUME_UP 风格） */
    fun keyCodeName(code: Int): String =
        KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace('_', ' ')
}
