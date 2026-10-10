package com.mangareader.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SortGroup(
    val name: String,
    val keywords: List<String>,
)

data class GroupSortConfig(
    val enabled: Boolean = false,
    val folderFirst: Boolean = true,
    val groups: List<SortGroup> = DEFAULT_GROUPS,
    val version: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("folderFirst", folderFirst)
        put("version", version)
        val arr = JSONArray()
        for (g in groups) {
            arr.put(JSONObject().apply {
                put("name", g.name)
                put("keywords", JSONArray().also { kw -> g.keywords.forEach { kw.put(it) } })
            })
        }
        put("groups", arr)
    }

    companion object {
        const val PREF_KEY = "group_sort_config"

        val DEFAULT_GROUPS = listOf(
            SortGroup("单行本", listOf("卷", "vol")),
            SortGroup("章节", listOf("章", "话", "回")),
            SortGroup("番外篇", listOf("番外")),
            SortGroup("特别篇", listOf("特别篇")),
            SortGroup("外传", listOf("外传", "前传")),
        )

        val DEFAULT = GroupSortConfig()

        fun fromJson(json: JSONObject): GroupSortConfig {
            return try {
                val groups = mutableListOf<SortGroup>()
                val arr = json.optJSONArray("groups")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val name = obj.optString("name").ifEmpty { "分组${i + 1}" }
                        val kwArr = obj.optJSONArray("keywords")
                        val keywords = if (kwArr != null) {
                            (0 until kwArr.length()).mapNotNull { kwArr.optString(it).takeIf { k -> k.isNotEmpty() } }
                        } else emptyList()
                        groups.add(SortGroup(name, keywords))
                    }
                }
                if (groups.isEmpty()) groups.addAll(DEFAULT_GROUPS)
                GroupSortConfig(
                    enabled = json.optBoolean("enabled", false),
                    folderFirst = json.optBoolean("folderFirst", true),
                    groups = groups,
                    version = json.optInt("version", 1),
                )
            } catch (_: Exception) {
                DEFAULT
            }
        }

        fun load(context: Context): GroupSortConfig {
            val prefs = context.getSharedPreferences("manga_reader", Context.MODE_PRIVATE)
            val str = prefs.getString(PREF_KEY, null) ?: return DEFAULT
            return try {
                fromJson(JSONObject(str))
            } catch (_: Exception) {
                DEFAULT
            }
        }

        fun save(context: Context, config: GroupSortConfig) {
            val prefs = context.getSharedPreferences("manga_reader", Context.MODE_PRIVATE)
            prefs.edit().putString(PREF_KEY, config.toJson().toString()).apply()
        }
    }
}

object GroupSortConfigCache {
    @Volatile
    private var cached: GroupSortConfig? = null

    fun get(context: Context): GroupSortConfig {
        return cached ?: synchronized(this) {
            cached ?: GroupSortConfig.load(context).also { cached = it }
        }
    }

    fun refresh(context: Context) {
        cached = GroupSortConfig.load(context)
    }

    fun invalidate() {
        cached = null
    }
}