package com.mangareader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * ★ 长图分割 §2：每书页尺寸表磁盘缓存（filesDir/page_sizes/<md5(bookPath)>.json）。
 *
 * 内容 {"stamp":..., "sizes":{"localIdx|source":[w,h]}}。首开探测写入，二开命中零探测秒开。
 * stamp = 页数 + ext 序列摘要（低成本失效判定）：加页/删页/换文件类型即整体失效；
 * 同名替换内容的极端情况由渲染器侧 BitmapRegionDecoder 实际尺寸复核兜底
 * （不符 → 整图兜底，不会错切错位）。
 * sizes 中 [0,0] 表示动图页（探测过、强制不拆），用于跳过重复探测。
 */
object PageSizeCacheStore {

    fun load(ctx: Context, bookPath: String, stamp: String): Map<String, IntArray>? {
        return try {
            val f = java.io.File(ctx.filesDir, "page_sizes/${md5(bookPath)}.json")
            if (!f.exists()) return null
            val obj = JSONObject(f.readText())
            if (obj.optString("stamp") != stamp) return null
            val sizesObj = obj.optJSONObject("sizes") ?: return null
            val out = HashMap<String, IntArray>(sizesObj.length())
            for (key in sizesObj.keys()) {
                val arr = sizesObj.getJSONArray(key)
                out[key] = intArrayOf(arr.getInt(0), arr.getInt(1))
            }
            out
        } catch (_: Exception) { null }
    }

    fun save(ctx: Context, bookPath: String, stamp: String, sizes: Map<String, IntArray>) {
        try {
            val dir = java.io.File(ctx.filesDir, "page_sizes")
            if (!dir.exists()) dir.mkdirs()
            val obj = JSONObject().put("stamp", stamp).put("savedAt", System.currentTimeMillis())
            val sizesObj = JSONObject()
            for ((k, v) in sizes) {
                if (v.size >= 2) sizesObj.put(k, JSONArray().put(v[0]).put(v[1]))
            }
            obj.put("sizes", sizesObj)
            java.io.File(dir, "${md5(bookPath)}.json").writeText(obj.toString())
        } catch (_: Exception) {
            // 尺寸表写盘失败静默跳过：仅损失二开免探收益，不影响功能
        }
    }

    private fun md5(s: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
