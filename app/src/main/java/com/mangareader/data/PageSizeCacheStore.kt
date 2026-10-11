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

    // ★ 进程内镜像：磁盘写失败/竞态时保证同进程内 Reader 入口与 setup 重入读到同一张表，
    //   展开结果永不分叉（二开也从这里直取，磁盘只在冷启动生效）
    private val mem = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Map<String, IntArray>>>()

    // ★ 身份归一（2026-10-06）：同一本书的多种 path 形式（书架/浏览器/切卷/继续阅读）
    //   必须落到同一张缓存——键在入口统一归一，调用方再传什么形式都救得回来
    private fun cacheKey(bookPath: String): String =
        md5(RemotePaths.normalizeBookKey(bookPath))

    fun load(ctx: Context, bookPath: String, stamp: String): Map<String, IntArray>? {
        val key = cacheKey(bookPath)
        mem[key]?.let { (s, sizes) -> return if (s == stamp) sizes else null }
        return try {
            val f = java.io.File(ctx.filesDir, "page_sizes/$key.json")
            if (!f.exists()) return null
            val obj = JSONObject(f.readText())
            val fileStamp = obj.optString("stamp")
            val sizesObj = obj.optJSONObject("sizes") ?: return null
            val out = HashMap<String, IntArray>(sizesObj.length())
            for (k in sizesObj.keys()) {
                val arr = sizesObj.getJSONArray(k)
                out[k] = intArrayOf(arr.getInt(0), arr.getInt(1))
            }
            val frozen = java.util.Collections.unmodifiableMap(out)
            if (fileStamp != stamp && frozen.isEmpty()) return null
            // ★ v1.384 stamp 迁移（宽图二分退役过渡）：pageList 从展开表(196)回归原页表(98)
            //   时 stamp 随页数变化，旧尺寸表键=localIdx|source 与原页键完全兼容——直接迁移
            //   而非整体作废重探（rar 全量解压探测 60s）。误配风险（同路径换内容）由渲染器
            //   侧尺寸复核+渐进 observePageDim 修正兜底
            if (fileStamp != stamp) {
                mem[key] = stamp to frozen
                save(ctx, bookPath, stamp, frozen)
            } else {
                mem[key] = stamp to frozen
            }
            frozen
        } catch (_: Exception) { null }
    }

    fun save(ctx: Context, bookPath: String, stamp: String, sizes: Map<String, IntArray>) {
        try {
            val key = cacheKey(bookPath)
            mem[key] = stamp to java.util.Collections.unmodifiableMap(HashMap(sizes))
            val dir = java.io.File(ctx.filesDir, "page_sizes")
            if (!dir.exists()) dir.mkdirs()
            val obj = JSONObject().put("stamp", stamp).put("savedAt", System.currentTimeMillis())
            val sizesObj = JSONObject()
            for ((k, v) in sizes) {
                if (v.size >= 2) sizesObj.put(k, JSONArray().put(v[0]).put(v[1]))
            }
            obj.put("sizes", sizesObj)
            // ★ tmp+rename 原子写：直写最终路径在崩溃/并发双写者下会留截断 JSON（损失免探收益）
            val dest = java.io.File(dir, "$key.json")
            val tmp = java.io.File(dir, "$key-${System.nanoTime()}.tmp")
            tmp.writeText(obj.toString())
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } catch (_: Exception) {
            // 尺寸表写盘失败静默跳过：仅损失二开免探收益，不影响功能（内存镜像已生效）
        }
    }

    private fun md5(s: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
