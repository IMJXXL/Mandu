package com.mangareader.util

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 独立语料泛化测试（2026-10-01）：全部为调参语料（CorpusConformanceTest 的 391 条）**之外**的官方案例——
 * - independent_kavita.json：Kavita ComicParserTests/ParserTests 与 Mihon ChapterRecognitionTest 中
 *   chaptr 语料未收录的条目（54 条）
 * - ct_cases.json：comictagger 官方 filenameparser 测试（69 模板 × #/无# 两变体 = 128 条，欧美漫画域）
 * - synthetic_zh.json：中文合成用例（126 条，真值按模板构造计算，非解析器输出）
 *
 * 用途：泛化体检 + 回归底线。**不作为调参目标**——针对它再优化就是对独立集过拟合。
 */
class IndependentCorpusTest {

    private data class Case(
        val input: String,
        val source: String,
        val series: String?,
        val volume: String?,      // null=不断言；空串=必须为 null
        val chapter: String?,     // 同上
        val zhTitle: String? = null,
        val zhVolume: Int? = null,
        val zhVolumeDec: Int? = null,
        val zhVolumeEnd: Int? = null,
        val zhChapter: Double? = null,
        val zhChapterEnd: Double? = null,
        val isZh: Boolean = false,
    )

    private fun load(name: String): String =
        javaClass.getResourceAsStream("/corpus/$name")!!.readBytes().decodeToString()

    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'u' -> {
                        val hex = s.substring(i + 2, minOf(i + 6, s.length))
                        if (hex.length == 4 && hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                            sb.append(hex.toInt(16).toChar()); i += 6
                        } else { sb.append(c); i++ }
                    }
                    else -> { sb.append(c); i++ }
                }
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    private fun objects(name: String): List<Map<String, String?>> {
        val out = mutableListOf<Map<String, String?>>()
        for (m in Regex("\\{[^{}]*\\}").findAll(load(name))) {
            val map = linkedMapOf<String, String?>()
            for (f in Regex("\"(\\w+)\"\\s*:\\s*(null|\"(?:[^\"\\\\]|\\\\.)*\"|-?\\d+(?:\\.\\d+)?)").findAll(m.value)) {
                val raw = f.groupValues[2]
                map[f.groupValues[1]] = if (raw == "null") null
                else if (raw.startsWith('"')) unescape(raw.substring(1, raw.length - 1))
                else raw
            }
            if (map.containsKey("input")) out.add(map)
        }
        return out
    }

    private fun cases(): List<Case> {
        val out = mutableListOf<Case>()
        for (e in objects("independent_kavita.json")) {
            val ch = e["expected_chapter"] ?: e["expected_chapter_f64"]
            out.add(Case(e["input"]!!, e["source"] ?: "?", e["expected_series"], e["expected_volume"], ch))
        }
        for (e in objects("ct_cases.json")) {
            out.add(Case(e["input"]!!, e["source"] ?: "comictagger", e["expected_series"], e["expected_volume"], e["expected_chapter"]))
        }
        for (e in objects("synthetic_zh.json")) {
            out.add(
                Case(
                    e["input"]!!, "zh-synthetic", null, null, null,
                    zhTitle = e["title"], zhVolume = e["volume"]?.toIntOrNull(), zhVolumeDec = e["volumeDec"]?.toIntOrNull(),
                    zhVolumeEnd = e["volumeEnd"]?.toIntOrNull(), zhChapter = e["chapter"]?.toDoubleOrNull(),
                    zhChapterEnd = e["chapterEnd"]?.toDoubleOrNull(), isZh = true,
                )
            )
        }
        return out
    }

    private fun norm(s: String) = s.lowercase()
        .replace('_', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
        .trimEnd('.', '!', '?', '-', '…')
        .trim()

    private fun matchD(exp: String, got: Double?): Boolean {
        got ?: return false
        val dot = exp.indexOf('.')
        val w = if (dot < 0) exp else exp.substring(0, dot)
        if (got.toInt() != w.toIntOrNull()) return false
        if (dot < 0) return got - got.toInt() < 1e-9
        val decRaw = exp.substring(dot + 1)
        // 非数字小数段（comictagger "78.BEY" 期号标签）：我们只能到达数字前缀，只断言整数部分
        val decNum = decRaw.toIntOrNull() ?: return true
        val wantDec = decNum * Math.pow(10.0, (3 - decRaw.length).toDouble()).toInt()
        return Math.round((got - got.toInt()) * 1000).toInt() == wantDec
    }

    private fun matchNum(expPart: String, whole: Int?, dec: Int?): Boolean {
        whole ?: return false
        val dot = expPart.indexOf('.')
        val w = if (dot < 0) expPart else expPart.substring(0, dot)
        if (whole.toString() != w) return false
        if (dot < 0) return dec == null
        val decRaw = expPart.substring(dot + 1)
        val decNum = decRaw.toIntOrNull() ?: return true
        return dec == decNum * Math.pow(10.0, (3 - decRaw.length).toDouble()).toInt()
    }

    private fun volOk(exp: String?, p: BookTitleParser.Parsed): Boolean = when {
        exp == null -> true
        exp.isEmpty() -> p.volume == null
        else -> {
            val parts = exp.split("-")
            if (parts.size == 2) matchNum(parts[0], p.volume, p.volumeDec) && matchNum(parts[1], p.volumeEnd, p.volumeEndDec)
            else matchNum(exp, p.volume, p.volumeDec)
        }
    }

    private fun chOk(exp: String?, p: BookTitleParser.Parsed): Boolean = when {
        exp == null -> true
        exp.isEmpty() -> p.chapter == null
        else -> {
            val parts = exp.split("-")
            if (parts.size == 2) matchD(parts[0], p.chapter) && p.chapterEnd != null && matchD(parts[1], p.chapterEnd)
            else matchD(exp, p.chapter)
        }
    }

    @Test
    fun independentCorpus() {
        val cases = cases()
        assertTrue("独立语料加载异常: ${cases.size}", cases.size in 250..450)
        var pass = 0
        val bySrc = linkedMapOf<String, Pair<Int, Int>>()
        val fails = mutableListOf<String>()
        for (c in cases) {
            val p = BookTitleParser.parse(c.input)
            val ok: Boolean = if (c.isZh) {
                (c.zhTitle == null || norm(p.title) == norm(c.zhTitle)) &&
                    p.volume == c.zhVolume && p.volumeDec == c.zhVolumeDec && p.volumeEnd == c.zhVolumeEnd &&
                    eqD(p.chapter, c.zhChapter) && eqD(p.chapterEnd, c.zhChapterEnd)
            } else {
                (c.series == null || norm(p.title) == norm(c.series)) && volOk(c.volume?.takeIf { it != "None" }, p) &&
                    chOk(c.chapter?.takeIf { it != "None" }, p)
            }
            val cnt = bySrc.getOrPut(c.source) { 0 to 0 }
            bySrc[c.source] = if (ok) cnt.first + 1 to cnt.second else cnt.first to cnt.second + 1
            if (ok) pass++ else fails.add("[${c.source}] ${c.input}\n    want(series=${c.series} vol=${c.volume} ch=${c.chapter ?: c.zhChapter}) got(title=${p.title} v=${p.volume}.${p.volumeDec} ve=${p.volumeEnd} c=${p.chapter} ce=${p.chapterEnd})")
        }
        println("=== INDEPENDENT: $pass/${cases.size} ===")
        for ((k, v) in bySrc) println("  $k: ${v.first} pass / ${v.second} fail")
        println("=== FAILS (${fails.size}) ===")
        fails.forEach { println(it) }
        val rate = pass.toDouble() / cases.size
        println("=== RATE: ${"%.1f".format(rate * 100)}% ===")
        assertTrue("独立语料通过率低于 70%：$pass/${cases.size}", rate >= 0.70)
    }

    private fun eqD(a: Double?, b: Double?): Boolean =
        if (a == null && b == null) true else a != null && b != null && Math.abs(a - b) < 1e-9
}
