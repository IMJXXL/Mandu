package com.mangareader.panel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 逐格阅读设置预设：数据模型 + SharedPreferences JSON 存储 + 三个内置默认预设。
 * 预设内容为 panelGuide* 20 字段的取值集合，全局存储不按书记忆。
 */
object PanelGuidePreset {

    private const val PREF_KEY = "panelGuidePresets"
    private const val PREF_FILE = "mangareader_global"

    data class Preset(
        val id: String,
        val name: String,
        val isBuiltin: Boolean,
        val sensitivity: Int,
        val dim: Int,
        val anim: Boolean,
        val rtl: Int,
        val dimMode: Int,
        val splitMode: Int,
        val transitionMode: Int,
        val displayMode: Int,
        val cropMode: Int,
        val slideMs: Long,
        val precision: Int,
        val previewEnabled: Boolean,
        val progressEnabled: Boolean,
        val swipeInvert: Boolean,
        val tapInvert: Boolean,
        val foldJoinEnabled: Boolean,
        val autoRotateEnabled: Boolean,
        val autoRotateDir: Int,
        val crossPageEnabled: Boolean,
    )

    val BUILTIN_STANDARD = Preset(
        id = "builtin_standard", name = "标准", isBuiltin = true,
        sensitivity = 1, dim = 55, anim = true, rtl = 0,
        dimMode = 0, splitMode = 0, transitionMode = 0, displayMode = 0,
        cropMode = 2, slideMs = 180L, precision = 1,
        previewEnabled = false, progressEnabled = false,
        swipeInvert = false, tapInvert = false,
        foldJoinEnabled = false, autoRotateEnabled = false,
        autoRotateDir = 2, crossPageEnabled = false,
    )

    val BUILTIN_LOOSE = Preset(
        id = "builtin_loose", name = "宽松", isBuiltin = true,
        sensitivity = 0, dim = 55, anim = true, rtl = 0,
        dimMode = 0, splitMode = 0, transitionMode = 0, displayMode = 0,
        cropMode = 2, slideMs = 180L, precision = 0,
        previewEnabled = false, progressEnabled = false,
        swipeInvert = false, tapInvert = false,
        foldJoinEnabled = false, autoRotateEnabled = false,
        autoRotateDir = 2, crossPageEnabled = false,
    )

    val BUILTIN_STRICT = Preset(
        id = "builtin_strict", name = "严格", isBuiltin = true,
        sensitivity = 2, dim = 55, anim = true, rtl = 0,
        dimMode = 0, splitMode = 0, transitionMode = 0, displayMode = 0,
        cropMode = 2, slideMs = 180L, precision = 1,
        previewEnabled = false, progressEnabled = false,
        swipeInvert = false, tapInvert = false,
        foldJoinEnabled = false, autoRotateEnabled = false,
        autoRotateDir = 2, crossPageEnabled = false,
    )

    private val builtins = listOf(BUILTIN_STANDARD, BUILTIN_LOOSE, BUILTIN_STRICT)

    internal object Store {
        fun loadAll(context: Context): List<Preset> {
            val user = loadUser(context)
            return builtins + user
        }

        fun save(context: Context, preset: Preset) {
            val user = loadUser(context).toMutableList()
            val idx = user.indexOfFirst { it.id == preset.id || it.name == preset.name }
            if (idx >= 0) user[idx] = preset else user.add(preset)
            saveUser(context, user)
        }

        fun delete(context: Context, id: String) {
            if (builtins.any { it.id == id }) return
            val user = loadUser(context).filterNot { it.id == id }
            saveUser(context, user)
        }

        fun rename(context: Context, id: String, newName: String) {
            if (builtins.any { it.id == id }) return
            val user = loadUser(context).map {
                if (it.id == id) it.copy(name = newName) else it
            }
            saveUser(context, user)
        }

        private fun loadUser(context: Context): List<Preset> {
            return try {
                val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                val json = prefs.getString(PREF_KEY, null) ?: return emptyList()
                val arr = JSONArray(json)
                val out = ArrayList<Preset>()
                for (i in 0 until arr.length()) {
                    try {
                        out.add(presetFromJson(arr.getJSONObject(i)))
                    } catch (_: Exception) {
                    }
                }
                out
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun saveUser(context: Context, presets: List<Preset>) {
            try {
                val arr = JSONArray()
                for (p in presets) arr.put(presetToJson(p))
                context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                    .edit().putString(PREF_KEY, arr.toString()).apply()
            } catch (_: Exception) {
            }
        }

        private fun presetToJson(p: Preset): JSONObject {
            return JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("sensitivity", p.sensitivity)
                put("dim", p.dim)
                put("anim", p.anim)
                put("rtl", p.rtl)
                put("dimMode", p.dimMode)
                put("splitMode", p.splitMode)
                put("transitionMode", p.transitionMode)
                put("displayMode", p.displayMode)
                put("cropMode", p.cropMode)
                put("slideMs", p.slideMs)
                put("precision", p.precision)
                put("previewEnabled", p.previewEnabled)
                put("progressEnabled", p.progressEnabled)
                put("swipeInvert", p.swipeInvert)
                put("tapInvert", p.tapInvert)
                put("foldJoinEnabled", p.foldJoinEnabled)
                put("autoRotateEnabled", p.autoRotateEnabled)
                put("autoRotateDir", p.autoRotateDir)
                put("crossPageEnabled", p.crossPageEnabled)
            }
        }

        private fun presetFromJson(o: JSONObject): Preset {
            return Preset(
                id = o.optString("id", UUID.randomUUID().toString()),
                name = o.optString("name", "未命名"),
                isBuiltin = false,
                sensitivity = o.optInt("sensitivity", 1),
                dim = o.optInt("dim", 55),
                anim = o.optBoolean("anim", true),
                rtl = o.optInt("rtl", 0),
                dimMode = o.optInt("dimMode", 0),
                splitMode = o.optInt("splitMode", 0),
                transitionMode = o.optInt("transitionMode", 0),
                displayMode = o.optInt("displayMode", 0),
                cropMode = o.optInt("cropMode", 2),
                slideMs = o.optLong("slideMs", 180L),
                precision = o.optInt("precision", 1),
                previewEnabled = o.optBoolean("previewEnabled", false),
                progressEnabled = o.optBoolean("progressEnabled", false),
                swipeInvert = o.optBoolean("swipeInvert", false),
                tapInvert = o.optBoolean("tapInvert", false),
                foldJoinEnabled = o.optBoolean("foldJoinEnabled", false),
                autoRotateEnabled = o.optBoolean("autoRotateEnabled", false),
                autoRotateDir = o.optInt("autoRotateDir", 2),
                crossPageEnabled = o.optBoolean("crossPageEnabled", false),
            )
        }
    }
}