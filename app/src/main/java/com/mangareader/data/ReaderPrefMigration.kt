package com.mangareader.data

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * 阅读偏好一次性迁移（scope_mig_v1，2026-10-03 九方评审定稿）：
 *
 * 1. 快照：迁移前把 manga_reader 全量序列化 JSON 落 filesDir/pref_snapshots/（滚动保留 3 份）
 *    ——不用文件拷贝，prefs XML 正被异步 apply 重写时拷贝会拿到撕裂文件（QA C2/存储裁定）。
 *    手写 JSON：纯 JVM 单测里 android 的 org.json 是 stub（方法抛 not mocked）。
 * 2. pageLayout 僵尸键消费闸门（QA C1）：
 *    - 全局键已存在（852b2fbd 的 donor 回填跑过）→ 删除 book_<id>_pageLayout 僵尸键，
 *      保住用户在 852 全局语义下的观测状态；
 *    - 全局键不存在（pre-852 直升本版）→ 僵尸键【转正为本书覆盖】（零删除、零行为变化），
 *      恰好恢复 pre-852 的按书语义 = 本版目标语义；
 *    - 由此 donor 回填不再在 load 内执行（load 零写盘硬不变量）。
 * 3. readBackground 补种写随迁移移出读路径（原 ReadingSettingsScreen 读路径写盘）。
 * 4. 存量 book_ 键整体转正为覆盖层——零删除（不做"值==默认即删"比对：全局偏离默认时
 *    会误删用户显式设置，如全局开灰阶+书内关灰阶；幽灵覆盖由偏好页显式清理）。
 * 5. ambienceSound Int→String、downsampleTier←forceFullDecode 归一保留在读路径（零写盘）。
 */
object ReaderPrefMigration {
    private const val FLAG = "scope_mig_v1_done"
    private const val PREFS = "manga_reader"

    fun runIfNeeded(context: Context) =
        runIfNeeded(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
            File(context.filesDir, "pref_snapshots"),
        )

    /** 重置前手动快照（滚动保留 3 份，与迁移快照同目录、同回滚通道） */
    fun snapshotNow(context: Context): Boolean = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dir = File(context.filesDir, "pref_snapshots").apply { mkdirs() }
        val TAB = 9.toChar()
        val NL = 10.toChar()
        val BS = 92.toChar()
        fun esc(t: String): String {
            val sb = StringBuilder()
            t.forEach { c ->
                when (c) {
                    BS -> { sb.append(BS); sb.append(BS) }
                    TAB -> { sb.append(BS); sb.append('t') }
                    NL -> { sb.append(BS); sb.append('n') }
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }
        val lines = mutableListOf("#pref_reset")
        prefs.all.forEach { (k, v) ->
            when (v) {
                is Boolean -> lines.add("b" + TAB + esc(k) + TAB + v)
                is Int -> lines.add("i" + TAB + esc(k) + TAB + v)
                is Long -> lines.add("l" + TAB + esc(k) + TAB + v)
                is Float -> lines.add("f" + TAB + esc(k) + TAB + v)
                is String -> lines.add("s" + TAB + esc(k) + TAB + esc(v))
                else -> {}
            }
        }
        dir.listFiles()?.filter { it.name.startsWith("pref_reset_") }
            ?.sortedByDescending { it.name }?.drop(2)?.forEach { it.delete() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US)
            .format(java.util.Date()) + "_" + (0..999).random()
        File(dir, "pref_reset_$stamp.txt").writeText(lines.joinToString(NL.toString()))
        true
    }.getOrDefault(false)

    fun runIfNeeded(prefs: SharedPreferences, snapshotDir: File) {
        if (prefs.getBoolean(FLAG, false)) return
        synchronized(ReaderPrefMigration) {
            if (prefs.getBoolean(FLAG, false)) return
            runCatching {
                // ① 快照（迁移可审计可回溯；转正清单随快照记录）
                // TSV 格式：type<TAB>key<TAB>value（纯文本可解析，回滚用）
                val TAB = 9.toChar()
                val NL = 10.toChar()
                val BS = 92.toChar()
                val all = prefs.all
                fun esc(t: String): String {
                    val sb = StringBuilder()
                    t.forEach { c ->
                        when (c) {
                            BS -> { sb.append(BS); sb.append(BS) }
                            TAB -> { sb.append(BS); sb.append('t') }
                            NL -> { sb.append(BS); sb.append('n') }
                            else -> sb.append(c)
                        }
                    }
                    return sb.toString()
                }
                val lines = mutableListOf("#scope_mig_v1")
                val zombies = all.keys.filter { it.endsWith("_pageLayout") && it.startsWith("book_") }
                lines.add("#zombies=" + zombies.joinToString(","))
                lines.add("#transfers=" + all.keys.count { it.startsWith("book_") })
                all.forEach { (k, v) ->
                    when (v) {
                        is Boolean -> lines.add("b" + TAB + esc(k) + TAB + v)
                        is Int -> lines.add("i" + TAB + esc(k) + TAB + v)
                        is Long -> lines.add("l" + TAB + esc(k) + TAB + v)
                        is Float -> lines.add("f" + TAB + esc(k) + TAB + v)
                        is String -> lines.add("s" + TAB + esc(k) + TAB + esc(v))
                        else -> {}
                    }
                }
                val dir = snapshotDir.apply { mkdirs() }
                // 滚动保留 3 份
                dir.listFiles()?.filter { it.name.startsWith("scope_mig_v1_") }
                    ?.sortedByDescending { it.name }?.drop(2)?.forEach { it.delete() }
                val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US)
                    .format(java.util.Date()) + "_" + (0..999).random()
                File(dir, "scope_mig_v1_$stamp.txt").writeText(lines.joinToString(NL.toString()))

                val ed = prefs.edit()
                // ② pageLayout 消费闸门
                val globalExists = prefs.contains("pageLayout")
                if (globalExists) {
                    // 852 donor 已消费 → 僵尸键删除（保 852 观测状态）
                    zombies.forEach { ed.remove(it) }
                }
                // else：pre-852 直升 → 僵尸键转正为本书覆盖（保留不动=零行为变化）
                // ③ readBackground 补种（原 ReadingSettingsScreen 读路径写移出）
                if (!prefs.contains("readBackground")) ed.putString("readBackground", "WHITE")
                ed.putBoolean(FLAG, true)
                ed.apply()
            }
        }
    }

    /** ★ 条漫分割默认值迁移（tallsplit_off_v1，2026-10-05，用户"条漫分割需要默认关闭"）：
     *  旧版默认开且 save 无条件写全局键——已装设备全部残留 tallSplitEnabled=true，仅改初始默认
     *  默认对老用户无效；一次性置回关，之后用户在阅读设置里手动开启照常记住。 */
    fun tallSplitDefaultOffIfNeeded(prefs: SharedPreferences) {
        if (prefs.getBoolean("tallsplit_off_v1_done", false)) return
        synchronized(ReaderPrefMigration) {
            if (prefs.getBoolean("tallsplit_off_v1_done", false)) return
            prefs.edit()
                .putBoolean("tallSplitEnabled", false)
                .putBoolean("tallsplit_off_v1_done", true)
                .apply()
        }
    }

    /** ★ 画面缩放档位迁移（scale_mode_v1，2026-10-05，画面缩放重做 fitScreen 布尔→imageScaleMode 档位）：
     *  fitScreen=true（完整显示）→ 档位 0（MODE_CONTAIN）；false/缺省（铺满屏幕=旧默认）→ 不写，
     *  直接落新默认 1（MODE_FIT_WIDTH），行为与旧默认一致。写过的设备以 contains("imageScaleMode") 幂等跳过。 */
    fun scaleModeFromLegacyFitScreenIfNeeded(prefs: SharedPreferences) {
        if (prefs.contains("imageScaleMode")) return
        if (!prefs.getBoolean("fitScreen", false)) return
        synchronized(ReaderPrefMigration) {
            if (prefs.contains("imageScaleMode")) return
            prefs.edit()
                .putInt("imageScaleMode", 0)
                .putBoolean("scale_mode_v1_done", true)
                .apply()
        }
    }

    /** ★ 点击翻页区域设置迁移（tap_zone_relocate_v1，2026-10-07）：
     *  设置从外部阅读设置页迁入阅读器内，作用域从全局锁定改为本书单独（默认）。
     *  一次性清存量全局键与本书覆盖键及作用域注册表显式记录，不沿用旧值（用户拍板不考虑老用户兼容）。 */
    fun tapZoneRelocateIfNeeded(prefs: SharedPreferences) {
        if (prefs.getBoolean("tap_zone_relocate_v1_done", false)) return
        synchronized(ReaderPrefMigration) {
            if (prefs.getBoolean("tap_zone_relocate_v1_done", false)) return
            runCatching {
                val ed = prefs.edit()
                ed.remove("clickFlipType").remove("clickFlipInvertH")
                prefs.all.keys.forEach { k ->
                    if (k.startsWith("book_") && (k.endsWith("_clickFlipType") || k.endsWith("_clickFlipInvertH"))) ed.remove(k)
                }
                val reg = ReaderScope.parseScopeReg(prefs).toMutableMap()
                reg.remove("clickFlipType"); reg.remove("clickFlipInvertH")
                ed.putString("pref_center_scope_reg", reg.entries.joinToString(";") { "${it.key}=${it.value}" })
                ed.putBoolean("tap_zone_relocate_v1_done", true)
                ed.apply()
            }
        }
    }

    fun zoomStartDefaultCenterIfNeeded(prefs: SharedPreferences) {
        if (prefs.getBoolean("zoom_start_v2_done", false)) return
        synchronized(ReaderPrefMigration) {
            if (prefs.getBoolean("zoom_start_v2_done", false)) return
            if (prefs.contains("zoomStart") && prefs.getInt("zoomStart", 2) == 0) {
                prefs.edit().putInt("zoomStart", 2).apply()
            }
            prefs.edit().putBoolean("zoom_start_v2_done", true).apply()
        }
    }

    /** 回滚：从最近一份快照恢复 book_* 键（当前不在快照内的 book_ 键删除） */
    fun restoreLatestSnapshot(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dir = File(context.filesDir, "pref_snapshots")
        val latest = dir.listFiles()?.filter { it.name.startsWith("scope_mig_v1_") || it.name.startsWith("pref_reset_") }
            ?.maxByOrNull { it.name } ?: return false
        return restore(prefs, latest)
    }

    fun restore(prefs: SharedPreferences, snapshot: File): Boolean = runCatching {
        val lines = snapshot.readLines()
        data class E(val type: String, val key: String, val value: String)
        val entries = mutableListOf<E>()
        fun unesc(t: String): String {
            val TAB = 9.toChar()
            val NL = 10.toChar()
            val BS = 92.toChar()
            val sb = StringBuilder()
            var i = 0
            while (i < t.length) {
                val c = t[i]
                if (c == BS && i + 1 < t.length) {
                    when (t[i + 1]) {
                        'n' -> { sb.append(NL); i += 2 }
                        't' -> { sb.append(TAB); i += 2 }
                        BS -> { sb.append(BS); i += 2 }
                        else -> { sb.append(c); i += 1 }
                    }
                } else { sb.append(c); i += 1 }
            }
            return sb.toString()
        }
        lines.forEach { line ->
            if (line.startsWith("#")) return@forEach
            val TAB = 9.toChar()
            val parts = line.split(TAB)
            if (parts.size >= 3) entries.add(E(parts[0], unesc(parts[1]), parts.drop(2).joinToString(TAB.toString())))
        }
        val bookKeysInSnapshot = entries.map { it.key }.filter { it.startsWith("book_") }.toSet()
        val ed = prefs.edit()
        // 删除当前不在快照内的 book_ 键（重置新增的）
        prefs.all.keys.filter { it.startsWith("book_") && it !in bookKeysInSnapshot }
            .forEach { ed.remove(it) }
        entries.forEach { e ->
            when (e.type) {
                "b" -> ed.putBoolean(e.key, e.value.toBoolean())
                "i" -> ed.putInt(e.key, e.value.toInt())
                "l" -> ed.putLong(e.key, e.value.toLong())
                "f" -> ed.putFloat(e.key, e.value.toFloat())
                "s" -> ed.putString(e.key, e.value)
            }
        }
        ed.apply()
        true
    }.getOrDefault(false)
}
