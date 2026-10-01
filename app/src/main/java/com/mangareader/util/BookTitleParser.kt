package com.mangareader.util

/**
 * 漫画文件名智能解析 v2（2026-10-01，全面对齐并超越 chaptr 的两阶段管线）：
 * `[汉化组] 进击的巨人 v03 c042 (Digital).cbz` → title=进击的巨人 volume=3 chapter=42.0
 *
 * 架构（chaptr 同款，替换 v1 的正则链）：
 * 1. 分词（lexer）：括号 token（[...] / (...) / {...} / 【...】 / （...） / 〔...〕）+ 单词 token
 *    （Unicode 字母数字，含 CJK）+ 分隔符（空格 . _ - , : 等——**下划线/连字符是分隔符**，
 *    根治 v1 正则 \b 在 `Kenichi_v11_c90-98` 类下划线文件名上失灵的问题）；悬空括号吞到结尾。
 *    分词前做全角→半角归一（ｖ０１→v01，超出 chaptr 的能力面）。
 * 2. 逐 token 分类（chaptr 同款 pass 层级）：
 *    卷号 = 顶层序列 → 括号内二次分词（(v01)/[Volume 11]）→ CJK 标记（第N卷/1巻/N권，含跨 token 空格形态）
 *    章号 = 尾部 #N → 关键词序列（c/ch/chp/chapter/episode，含 v2 修订号）→ 括号内 → CJK（第N话/話/回/章/화）→ 裸数字兜底
 *    裸数字兜底（chaptr 门控全量移植）：前面有非数字词 + 非年份 + 后跟元数据（括号/组名代码词/卷章关键词/
 *    .小数/-范围/串尾），且前面无显式卷标记——`Kaiju No. 8 036` → 章 36 而 `Kaiju No. 8` 不误伤。
 *    字母后缀双约定：点分字母（Ch.4.a→4.1/b→4.2/extra→4.99/omake→4.98，Mihon 约定）+
 *    贴身字母（153b→153.5，Kavita 约定）。
 * 3. 标题切片：起点=前导括号链之后，终点=第一个结构标记 token / 已见词后的第一个括号 /
 *    扩展名点；只把**下划线**规整成空格，保留书名里的连字符（"Shimoneta - Manmaru Hen"）。
 *
 * 设计约束（不变）：
 * - 解析永远有兜底：识别不出结构时返回原文（去扩展名），绝不比旧行为差
 * - displayTitle 用于 Book.title 入库/显示：干净书名 + " Vol.01"/" Ch.042" 短缀 + 繁转简
 * - 原始文件名不在界面出现（Book.filePath 天然保留，长按/详情可查）
 */
object BookTitleParser {

    data class Parsed(
        val title: String,
        val volume: Int? = null,
        val volumeDec: Int? = null,
        val volumeEnd: Int? = null,
        val chapter: Double? = null,
        val chapterEnd: Double? = null,
        val volumeEndDec: Int? = null,
        val group: String? = null,
        val revision: Int? = null,
        val isOneshot: Boolean = false,
    )

    private val COMIC_EXTS = setOf("zip", "cbz", "rar", "cbr", "7z", "cb7", "tar", "cbt", "pdf", "epub", "mobi", "azw3", "txt")
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp")
    private val KNOWN_EXTS = COMIC_EXTS + IMAGE_EXTS

    // ===== 分词 =====

    private sealed class Tok {
        abstract val pos: Int

        class B(val s: String, override val pos: Int) : Tok()   // [...] 【...】 〔...〕
        class P(val s: String, override val pos: Int) : Tok()   // (...) （...）
        class C(val s: String, override val pos: Int) : Tok()   // {...}
        class W(val s: String, override val pos: Int) : Tok()
        class D(val c: Char, override val pos: Int) : Tok()
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || isCombiningMark(c) || c == '\'' || c == '!' || c == '?'

    /** 组合符号随宿主成词（泰语 ่/ี、天城文元音符号——否则 "เล่ม" 被拆成三个 token，卷关键词失灵） */
    private fun isCombiningMark(c: Char): Boolean {
        val t = Character.getType(c)
        return t == Character.NON_SPACING_MARK.toInt() ||
            t == Character.COMBINING_SPACING_MARK.toInt() ||
            t == Character.ENCLOSING_MARK.toInt()
    }

    private fun tokenize(input: String): List<Tok> {
        val out = ArrayList<Tok>(input.length / 4 + 4)
        var i = 0
        val n = input.length
        while (i < n) {
            val c = input[i]
            when {
                c == '[' || c == '【' || c == '〔' -> {
                    val close = if (c == '[') ']' else if (c == '【') '】' else '〕'
                    var j = i + 1
                    while (j < n && input[j] != close) j++
                    out.add(Tok.B(input.substring(i + 1, j), i))
                    i = if (j < n) j + 1 else j
                }
                c == '(' || c == '（' -> {
                    val close = if (c == '(') ')' else '）'
                    var j = i + 1
                    while (j < n && input[j] != close) j++
                    out.add(Tok.P(input.substring(i + 1, j), i))
                    i = if (j < n) j + 1 else j
                }
                c == '{' -> {
                    var j = i + 1
                    while (j < n && input[j] != '}') j++
                    out.add(Tok.C(input.substring(i + 1, j), i))
                    i = if (j < n) j + 1 else j
                }
                isWordChar(c) -> {
                    val start = i
                    while (i < n && isWordChar(input[i])) i++
                    out.add(Tok.W(input.substring(start, i), start))
                }
                else -> {
                    out.add(Tok.D(c, i))
                    i++
                }
            }
        }
        return out
    }

    // ===== 数字模型 =====

    /**
     * 小数以千分位整数存储（"5"→500、"005"→5、"99"→990、"125"→125）：
     * 保留前导零语义（mihon "024-005"→24.005），比较与换算都用同尺度。
     */
    private data class Num(val whole: Int, val dec: Int? = null) : Comparable<Num> {
        override fun compareTo(other: Num): Int {
            val c = whole.compareTo(other.whole)
            if (c != 0) return c
            return (dec ?: -1).compareTo(other.dec ?: -1)
        }

        fun toDouble(): Double {
            if (dec == null || dec == 0) return whole.toDouble()
            return whole + dec / 1000.0
        }
    }

    private data class Rng(val start: Num, val end: Num? = null)

    /** 数字串 → 千分位小数（"5"→500、"005"→5、"125"→125）；>3 位截尾 */
    private fun decFromDigits(str: String): Int? {
        val v = str.toIntOrNull() ?: return null
        return when {
            str.length >= 3 -> v
            else -> v * Math.pow(10.0, (3 - str.length).toDouble()).toInt()
        }
    }

    /** mihon 特殊话后缀 → 千分位小数（extra=.99 / omake=.98 / special=.97） */
    private val SPECIAL_DECIMAL_WORDS = mapOf("extra" to 990, "omake" to 980, "special" to 970)

    private fun specialDecimal(word: String): Int? {
        val low = word.lowercase()
        for ((k, v) in SPECIAL_DECIMAL_WORDS) if (low.startsWith(k)) return v
        return null
    }

    // ===== 关键词表（chaptr 同源 + CJK 扩充） =====

    private val VOL_PREFIXES = arrayOf("volume", "vol", "v", "sp", "s", "t")
    private val CH_PREFIXES = arrayOf("chapter", "chp", "ch", "c")
    private val VOL_KEYWORDS = arrayOf("vol", "volume", "tome", "том", "тома", "เล่ม", "เล่มที่")
    private val CH_KEYWORDS = arrayOf("ch", "chp", "chapter", "chapters", "episode", "глава", "главы")
    private val POSTFIX_VOL_KEYWORDS = setOf("том", "тома")   // 俄语后置式 "5 Том"
    private val CJK_VOL_MARKERS = charArrayOf('巻', '卷', '册', '冊', '권', '장')
    private val CJK_CH_MARKERS = charArrayOf('话', '話', '回', '章', '회', '화')
    private val CJK_VOL_PREFIXES = arrayOf("시즌")

    private val FORMAT_TAGS = setOf(
        "full color", "colored", "color", "b&w", "bw", "hd", "hi-res", "uhd",
        "raw", "raws", "manga", "audiobook", "english", "japanese", "chinese",
        "simplified chinese", "traditional chinese", "korean", "french", "spanish", "german", "italian",
    )
    private val EDITION_TAGS = setOf("omnibus", "omnibus edition", "uncensored")
    private val SOURCE_TAGS = setOf(
        "digital", "mangaplus", "manga plus", "viz", "viz media", "kodansha",
        "kodansha usa", "lezhin", "naver", "naver webtoon", "kakao", "kakao page", "scan",
    )

    // ===== 前缀/关键词匹配 =====

    /** 卷前缀剥离：volume/vol/v/sp/s/t + 全数字尾巴（"v01"→1、"S01"→1、"T2000"→2000、"SP02"→2） */
    private fun stripVolumePrefix(w: String): Int? {
        for (p in VOL_PREFIXES) {
            if (w.length > p.length && w.startsWith(p, ignoreCase = true)) {
                val rest = w.substring(p.length)
                if (rest.isNotEmpty() && rest.all { it in '0'..'9' }) return rest.toIntOrNull()
            }
        }
        return null
    }

    /** 韩语季前缀（시즌3 → 3） */
    private fun stripCjkVolPrefix(w: String): Int? {
        for (p in CJK_VOL_PREFIXES) {
            if (w.length > p.length && w.startsWith(p, ignoreCase = true)) {
                val rest = w.substring(p.length)
                var de = 0
                while (de < rest.length && rest[de] in '0'..'9') de++
                if (de > 0) return rest.substring(0, de).toIntOrNull()   // 尾随非数字（삽화2）忽略
            }
        }
        return null
    }

    /** 章词：chapter/chp/ch/c + 数字（"Chapter11"→11、"c006x1"→6）；尾巴支持 v修订（"Chapter11v2"→11 rev2）、
     *  贴身字母（"c42a"→42.1，a 取衡 .1 其余 .5，同 chapterBareWord）、特殊话（"c42extra"→42.99） */
    private fun parseChapterWord(w: String): Pair<Num, Int?>? {
        if (w.equals("c2c", ignoreCase = true)) return null   // 封面到封底扫描标签，不是章（c+2+c 恰好撞章词形态）
        for (p in CH_PREFIXES) {
            if (w.length > p.length && w.startsWith(p, ignoreCase = true)) {
                val rest = w.substring(p.length)
                var de = 0
                while (de < rest.length && rest[de] in '0'..'9') de++
                if (de == 0) continue
                val num = rest.substring(0, de).toIntOrNull() ?: continue
                val after = rest.substring(de)
                if (after.isEmpty()) return Pair(Num(num), null)
                if (after.length > 1 && (after[0] == 'v' || after[0] == 'V')) {
                    val rv = after.substring(1)
                    if (rv.isNotEmpty() && rv.all { it in '0'..'9' }) return Pair(Num(num), rv.toIntOrNull())
                }
                if (after.length > 1 && (after[0] == 'x' || after[0] == 'X')) {
                    val xv = after.substring(1)
                    if (xv.isNotEmpty() && xv.all { it in '0'..'9' }) return Pair(Num(num), null)
                }
                specialDecimal(after)?.let { return Pair(Num(num, it), null) }
                if (after.length == 1 && after[0] in 'a'..'z') {
                    return Pair(Num(num, if (after[0] == 'a') 100 else 500), null)
                }
                continue   // 其它尾巴 → 不认（chaptr 同语义）
            }
        }
        return null
    }

    private fun isVolKeyword(w: String) = VOL_KEYWORDS.any { w.equals(it, ignoreCase = true) }
    private fun isChKeyword(w: String) = CH_KEYWORDS.any { w.equals(it, ignoreCase = true) }

    /** 组名代码词：全大写字母+数字且至少一个字母（"RHS"/"MT"/"SCX"） */
    private fun isGroupCodeWord(w: String) =
        w.isNotEmpty() && w.any { it in 'A'..'Z' || it in 'a'..'z' } && w.all { it.isUpperCase() || it in '0'..'9' }

    private fun looksLikeYear(n: Int) = n in 1900..2099

    private fun looksLikeExtension(w: String) =
        w.length in 2..5 && w.all { it.isLetterOrDigit() && it.code < 128 } && w.any { it.isLetter() }

    /** 扩展数字：纯数字 / 数字+单个小写字母（Kavita 约定 =.5；'a' 按语料取衡 =.1，见 chapterBareWord）/ 序数词 / 其它尾巴只取前导数字 */
    private fun parseExtendedNumber(w: String): Num? {
        var de = 0
        while (de < w.length && w[de] in '0'..'9') de++
        if (de == 0) return null
        val whole = w.substring(0, de).toIntOrNull() ?: return null
        val suffix = w.substring(de)
        if (suffix.isEmpty()) return Num(whole)
        if (suffix.length == 1 && suffix[0] in 'a'..'z') return Num(whole, if (suffix[0] == 'a') 100 else 500)
        if (suffix.length == 2 && (suffix == "st" || suffix == "nd" || suffix == "rd" || suffix == "th")) return Num(whole)
        return Num(whole)
    }

    /** 章号裸词（标记后/裸数字兜底用）：^\d+v\d+$（修订）|^\d+[a-z]$（Kavita .5）|^\d+(st|nd|rd|th)$（序数） */
    private fun chapterBareWord(w: String): Triple<Num, Int?, Boolean>? {
        var de = 0
        while (de < w.length && w[de] in '0'..'9') de++
        if (de == 0) return null
        val whole = w.substring(0, de).toIntOrNull() ?: return null
        val tail = w.substring(de)
        if (tail.isEmpty()) return Triple(Num(whole), null, false)
        // v 修订尾巴："5v"（可带 .2 后续，属修订不属小数）/"11v002"
        if (tail == "v" || (tail.length > 1 && tail[0] == 'v' && tail.substring(1).all { it in '0'..'9' })) {
            val rev = if (tail.length > 1) tail.substring(1).toIntOrNull() else null
            return Triple(Num(whole), rev, true)
        }
        if (tail.length == 1 && tail[0] in 'a'..'z') {
            // mihon "19a"→19.1 与 Kavita "153b"→153.5 的字母约定在语料里并存：a→.1、其余→.5 取衡
            return Triple(Num(whole, if (tail[0] == 'a') 100 else 500), null, false)
        }
        specialDecimal(tail)?.let { return Triple(Num(whole, it), null, false) }   // "42extra" → 42.99（胶着尾巴）
        if (tail == "st" || tail == "nd" || tail == "rd" || tail == "th") return Triple(Num(whole), null, false)
        return null
    }

    /** CJK 词内标记数字：先找标记字符前的数字（1巻/第03卷/13화），再找标记后的数字（卷5） */
    private fun cjkMarkerNumberInWord(w: String, markers: CharArray): Int? {
        for ((mi, c) in w.withIndex()) {
            if (!markers.contains(c)) continue
            var ds = mi
            while (ds > 0 && w[ds - 1] in '0'..'9') ds--
            if (ds < mi) {
                val digits = w.substring(ds, mi)
                val n = digits.toIntOrNull()
                if (n != null) return n
            }
            var de = mi + 1
            while (de < w.length && w[de] in '0'..'9') de++
            if (de > mi + 1) {
                val digits = w.substring(mi + 1, de)
                val n = digits.toIntOrNull()
                if (n != null) return n
            }
        }
        return null
    }

    /** 纯标记词（"卷"/"话" 单独成 token，"第 03 卷" 空格形态） */
    private fun isBareMarkerWord(w: String, markers: CharArray) = w.length <= 2 && w.any { markers.contains(it) }

    /** 第N 前缀词（"第1-3卷" 的 "第1" 段，供范围起点识别） */
    private fun daiPrefixNumber(w: String): Int? {
        if (w.length < 2 || w[0] != '第') return null
        var de = 1
        while (de < w.length && w[de] in '0'..'9') de++
        if (de == 1) return null
        return w.substring(1, de).toIntOrNull()
    }

    // ===== 范围/数字构建 =====

    /** 标记后数字：跳过最多 3 个分隔符（"Vol 1"/"Vol. 1"/"Vol . 1"）；章语境收裸章词（"5v"/"011v002"） */
    private fun parseNumberAfterMarker(tokens: List<Tok>, start: Int, chapterContext: Boolean): Rng? {
        var i = start
        var delims = 0
        while (i < tokens.size && delims < 3) {
            when (val t = tokens[i]) {
                is Tok.D -> { delims++; i++ }
                is Tok.W -> {
                    if (chapterContext) {
                        chapterBareWord(t.s)?.let { (num, _, swallow) ->
                            return buildRange(tokens, i + 1, num, true, swallow)
                        }
                    }
                    val n = t.s.toIntOrNull() ?: return null
                    return buildRange(tokens, i + 1, Num(n), chapterContext)
                }
                else -> return null
            }
        }
        return null
    }

    /** 小数后缀：'.'（卷/章通用）或 ','（章语境，mihon 欧式小数）；章语境另收 点分字母/extra/omake/special */
    private fun parseDecimalSuffix(tokens: List<Tok>, pos: Int, chapterContext: Boolean): Pair<Num, Int>? {
        val d = tokens.getOrNull(pos) as? Tok.D ?: return null
        if (d.c != '.' && !(chapterContext && d.c == ',')) return null
        val w = (tokens.getOrNull(pos + 1) as? Tok.W)?.s ?: return null
        if (w.isNotEmpty() && w.all { it in '0'..'9' }) {
            return decFromDigits(w)?.let { Pair(Num(0, it), pos + 2) }
        }
        if (chapterContext) {
            specialDecimal(w)?.let { return Pair(Num(0, it), pos + 2) }
            if (w.length == 1 && w[0] in 'a'..'z') {
                val idx = w[0] - 'a' + 1
                return Pair(Num(0, if (idx <= 9) idx * 100 else idx * 10), pos + 2)
            }
        }
        return null
    }

    /** 范围尾：'-'/'~' + 数字词（章语境兼容 c01-c04）；倒退→null（buildRange 已把小数形先行截走） */
    private fun parseRangeEnd(tokens: List<Tok>, pos: Int, start: Num, chapterContext: Boolean): Num? {
        val d = tokens.getOrNull(pos) as? Tok.D ?: return null
        if (d.c != '-' && d.c != '~') return null
        val w = (tokens.getOrNull(pos + 1) as? Tok.W)?.s ?: return null
        var end: Num = parseExtendedNumber(w)
            ?: (if (chapterContext) parseChapterWord(w)?.let { it.first } else null)
            ?: stripVolumePrefix(w)?.let { Num(it) }   // 卷范围尾带前缀："v01-v05"
            ?: return null
        if (end.dec == null) {
            parseDecimalSuffix(tokens, pos + 2, chapterContext)?.let { (n, _) -> if (n.dec != null) end = Num(end.whole, n.dec) }
        }
        if (end < start) return null
        return end
    }

    private fun buildRange(tokens: List<Tok>, cursor: Int, startWhole: Num, chapterContext: Boolean, swallowDecimal: Boolean = false): Rng {
        var start = startWhole
        var next = cursor
        if (!swallowDecimal) {
            parseDecimalSuffix(tokens, next, chapterContext)?.let { (n, after) ->
                if (n.dec != null) { start = Num(startWhole.whole, n.dec); next = after }
            }
        }
        if (chapterContext) {
            // mihon 章语境：'-a'/'-extra'/倒退三位数是起始章的小数而非范围尾
            //（"ch 122-a"→122.1、"Ch.123-extra"→123.99、"024-005"→24.005；"c01-c04"/"150-153b" 仍是范围）
            val d = tokens.getOrNull(next) as? Tok.D
            val w = if (d != null && (d.c == '-' || d.c == '~')) (tokens.getOrNull(next + 1) as? Tok.W)?.s else null
            if (w != null) {
                specialDecimal(w)?.let { return Rng(Num(start.whole, it)) }
                if (w.length == 1 && w[0] in 'a'..'z') {
                    val idx = w[0] - 'a' + 1
                    return Rng(Num(start.whole, if (idx <= 9) idx * 100 else idx * 10))
                }
                if (w.length >= 3 && w.all { it in '0'..'9' }) {
                    val e = w.toIntOrNull()
                    if (e != null && e < start.whole) return Rng(Num(start.whole, decFromDigits(w) ?: 0))
                }
            }
        }
        val end: Num? = parseRangeEnd(tokens, next, start, chapterContext)
        if (end == null && chapterContext) {
            // 空格分隔的 mihon 特殊话："404 extravol002" → 404.99
            val w1 = (tokens.getOrNull(next) as? Tok.W)?.s
            if (w1 != null) {
                specialDecimal(w1)?.let { return Rng(Num(start.whole, it)) }
            } else if ((tokens.getOrNull(next) as? Tok.D)?.c == ' ') {
                val w2 = (tokens.getOrNull(next + 1) as? Tok.W)?.s
                if (w2 != null) specialDecimal(w2)?.let { return Rng(Num(start.whole, it)) }
            }
        }
        return Rng(start, end)
    }

    /** CJK 标记前的范围起点："1-3卷" 的 W("1")；扩展收 "第1-3卷" 的 W("第1") */
    private fun precedingRangeStart(tokens: List<Tok>, i: Int): Int? {
        if (i < 2) return null
        val d = tokens[i - 1] as? Tok.D ?: return null
        if (d.c != '-' && d.c != '~') return null
        val w = (tokens[i - 2] as? Tok.W)?.s ?: return null
        if (w.isNotEmpty() && w.all { it in '0'..'9' }) return w.toIntOrNull()
        return daiPrefixNumber(w)
    }

    /** CJK 词内标记数字（带原始数字串：前导零语义 "05"→0.05 需按串长定千分位） */
    private fun cjkMarkerNumberRawInWord(w: String, markers: CharArray): Pair<Int, String>? {
        for ((mi, c) in w.withIndex()) {
            if (!markers.contains(c)) continue
            var ds = mi
            while (ds > 0 && w[ds - 1] in '0'..'9') ds--
            if (ds < mi) {
                val digits = w.substring(ds, mi)
                val n = digits.toIntOrNull()
                if (n != null) return Pair(n, digits)
            }
            var de = mi + 1
            while (de < w.length && w[de] in '0'..'9') de++
            if (de > mi + 1) {
                val digits = w.substring(mi + 1, de)
                val n = digits.toIntOrNull()
                if (n != null) return Pair(n, digits)
            }
        }
        return null
    }

    private fun precedingDecimalCombined(tokens: List<Tok>, i: Int, fractionalRaw: String): Num? {
        if (i < 2) return null
        val d = tokens[i - 1] as? Tok.D ?: return null
        if (d.c != '.') return null
        val w = (tokens[i - 2] as? Tok.W)?.s ?: return null
        val whole = w.toIntOrNull() ?: return null
        val dec = decFromDigits(fractionalRaw) ?: return null
        return Num(whole, dec)
    }

    /** 标记前的数字（跨最多 2 个分隔符："第 03 卷"/"13 화" 空格形态；chaptr 只对俄语做，我们对 CJK 也做） */
    private fun parseNumberBeforeMarker(tokens: List<Tok>, markerIdx: Int): Int? {
        var i = markerIdx - 1
        var delims = 0
        while (i >= 0 && delims <= 2) {
            when (val t = tokens[i]) {
                is Tok.D -> { delims++; i-- }
                is Tok.W -> {
                    if (t.s.isNotEmpty() && t.s.all { it in '0'..'9' }) return t.s.toIntOrNull()
                    val dai = daiPrefixNumber(t.s) ?: return null
                    return dai
                }
                else -> return null
            }
        }
        return null
    }

    // ===== 卷号检测 =====

    private fun detectVolume(tokens: List<Tok>): Rng? {
        detectVolumeInSeq(tokens)?.let { return it }
        for (t in tokens) {
            val inner = when (t) {
                is Tok.P -> t.s
                is Tok.B -> t.s
                is Tok.C -> t.s
                else -> continue
            }
            detectVolumeInSeq(tokenize(inner))?.let { return it }
        }
        return detectCjkMarkerSeq(tokens, CJK_VOL_MARKERS)
    }

    private fun detectVolumeInSeq(tokens: List<Tok>): Rng? {
        for ((i, t) in tokens.withIndex()) {
            val w = (t as? Tok.W)?.s ?: continue
            stripVolumePrefix(w)?.let { return buildRange(tokens, i + 1, Num(it), false) }
            stripCjkVolPrefix(w)?.let { return buildRange(tokens, i + 1, Num(it), false) }
            if (isVolKeyword(w)) {
                parseNumberAfterMarker(tokens, i + 1, false)?.let { return it }
                if (w.lowercase() in POSTFIX_VOL_KEYWORDS) {
                    parseNumberBeforeMarker(tokens, i)?.let { return Rng(Num(it)) }
                }
            }
        }
        return null
    }

    private fun detectCjkMarkerSeq(tokens: List<Tok>, markers: CharArray): Rng? {
        for ((i, t) in tokens.withIndex()) {
            val w = (t as? Tok.W)?.s ?: continue
            val found = cjkMarkerNumberRawInWord(w, markers) ?: continue
            precedingRangeStart(tokens, i)?.let { rs ->
                return if (rs <= found.first) Rng(Num(rs), Num(found.first)) else Rng(Num(rs))   // 倒退："38-1화" → 38
            }
            precedingDecimalCombined(tokens, i, found.second)?.let { return Rng(it) }
            return Rng(Num(found.first))
        }
        // 纯标记词："第 03 卷"/"13 화" 空格形态——标记词本身无数字，向前找数字
        for ((i, t) in tokens.withIndex()) {
            val w = (t as? Tok.W)?.s ?: continue
            if (!isBareMarkerWord(w, markers)) continue
            parseNumberBeforeMarker(tokens, i)?.let { return Rng(Num(it)) }
        }
        return null
    }

    // ===== 章号检测 =====

    private fun detectChapter(tokens: List<Tok>): Rng? {
        // 尾部 #N 优先（无 CJK 章标记时）；"Monster #8 Ch.001" 不误触，"13회#2" 走 CJK
        detectHashChapter(tokens)?.let { return it }
        detectChapterInSeq(tokens)?.let { return it }
        for (t in tokens) {
            val inner = when (t) {
                is Tok.P -> t.s
                is Tok.B -> t.s
                is Tok.C -> t.s
                else -> continue
            }
            detectChapterInSeq(tokenize(inner))?.let { return it }
        }
        detectCjkMarkerSeq(tokens, CJK_CH_MARKERS)?.let { return it }
        return detectBareChapter(tokens)
    }

    private fun detectChapterInSeq(tokens: List<Tok>): Rng? {
        var sawColon = false
        for ((i, t) in tokens.withIndex()) {
            if ((t as? Tok.D)?.c == ':') sawColon = true
            val w = (t as? Tok.W)?.s ?: continue
            // 冒号后的 "Chapter N" 是章节副标题而非编号（"Tokyo ESP 027: Part 002: Chapter 001" → 27）
            if (sawColon && isChKeyword(w)) continue
            parseChapterWord(w)?.let { (num, _) -> return buildRange(tokens, i + 1, num, true) }
            if (isChKeyword(w)) {
                parseNumberAfterMarker(tokens, i + 1, true)?.let { return it }
            }
        }
        return null
    }

    private fun detectHashChapter(tokens: List<Tok>): Rng? {
        if (tokens.none { (it as? Tok.D)?.c == '#' }) return null
        for (t in tokens) {
            val w = (t as? Tok.W)?.s ?: continue
            if (cjkMarkerNumberInWord(w, CJK_CH_MARKERS) != null) return null
        }
        for (i in tokens.indices) {
            val d = tokens[i] as? Tok.D ?: continue
            if (d.c != '#') continue
            val w = (tokens.getOrNull(i + 1) as? Tok.W)?.s ?: continue
            val n = w.toIntOrNull() ?: continue
            val atEnd = tokens.drop(i + 2).all { t ->
                when (t) {
                    is Tok.D -> true
                    is Tok.W -> looksLikeExtension(t.s)
                    else -> false
                }
            }
            if (atEnd) return Rng(Num(n))
        }
        return null
    }

    /** 第一个显式卷标记 token 的下标（裸数字门控：卷标记意味着"已有结构化编号"，之前的数字是书名一部分） */
    private fun findFirstVolumeMarkerIdx(tokens: List<Tok>): Int? = tokens.indexOfFirst { t ->
        (t as? Tok.W)?.s?.let { w ->
            // 只认"带数字"的卷标记（v01/1巻/시즌3）——裸 Vol 关键词不算，
            // 否则 "Solanin 028 Vol. 2" 的章号会被后面的 Vol 吃掉（chaptr 语料语义）
            stripVolumePrefix(w) != null || stripCjkVolPrefix(w) != null ||
                cjkMarkerNumberInWord(w, CJK_VOL_MARKERS) != null
        } == true
    }.takeIf { it >= 0 }

    private fun findFirstStructuredMarkerIdx(tokens: List<Tok>): Int? = tokens.indexOfFirst { t ->
        (t as? Tok.W)?.s?.let { w -> isMarkerWord(w) } == true
    }.takeIf { it >= 0 }

    /** 裸数字后面是否"跟着元数据"而非书名正文（chaptr 全量移植 + ':' 冒号 / ',' 欧式小数 / mihon 特殊话扩展） */
    private fun followedByMetadata(tokens: List<Tok>, startIdx: Int): Boolean {
        val d = tokens.getOrNull(startIdx) as? Tok.D
        val w = (tokens.getOrNull(startIdx + 1) as? Tok.W)?.s
        if (d != null && w != null && w.isNotEmpty()) {
            if ((d.c == '.' || d.c == ',') && (w.all { it in '0'..'9' } || looksLikeExtension(w) ||
                    (w.length == 1 && w[0] in 'a'..'z') || specialDecimal(w) != null)
            ) return true
            if ((d.c == '-' || d.c == '~') && parseExtendedNumber(w) != null) return true
        }
        var prevDelim: Char? = null
        for (k in startIdx until tokens.size) {
            when (val t = tokens[k]) {
                is Tok.D -> {
                    if (t.c == ':') return true   // "Fairy Tail 404: 00:00" 类章节标题冒号
                    prevDelim = t.c
                    continue
                }
                is Tok.B, is Tok.P, is Tok.C -> return true
                is Tok.W -> return isGroupCodeWord(t.s) || isChKeyword(t.s) || isVolKeyword(t.s) || specialDecimal(t.s) != null ||
                    // mihon：裸数字后空格+正文词 → 章节标题续文（"Bleach 567 Down With Snowwhite" → 567）
                    (prevDelim == ' ' && t.s.any { it.isLetter() })
            }
        }
        return true   // 串尾
    }

    private fun detectBareChapter(tokens: List<Tok>): Rng? {
        val minIdx = (findFirstVolumeMarkerIdx(tokens) ?: -1) + 1
        var sawNonNumeric = false
        var prevWasVolKeyword = false
        var prev1: String? = null
        var prev2: String? = null
        var lastDelim: Char? = null
        fun isVolWord(x: String?) = x != null && (isVolKeyword(x) || stripVolumePrefix(x) != null || stripCjkVolPrefix(x) != null)
        for ((i, t) in tokens.withIndex()) {
            if (t is Tok.D) lastDelim = t.c
            val w = (t as? Tok.W)?.s ?: continue
            val allDigits = w.isNotEmpty() && w.all { it in '0'..'9' }
            val n = w.toIntOrNull()
            // 卷号自身的小数位/范围尾不算章（"Vol 2.5"的5、"v16-17"的17）；
            // 连接符只认 . - ~（"X-Men v1 #201" 的井号、"S01 014" 的空格之后数字仍可成章）
            val volJoin = lastDelim == '.' || lastDelim == '-' || lastDelim == '~'
            val volOwn = (isVolWord(prev1) && volJoin) ||
                (prev1 != null && prev1.all { it in '0'..'9' } && isVolWord(prev2) && volJoin)
            if (i >= minIdx && allDigits && sawNonNumeric && !prevWasVolKeyword && !volOwn &&
                n != null && !looksLikeYear(n) && followedByMetadata(tokens, i + 1)
            ) {
                return buildRange(tokens, i + 1, Num(n), true)
            }
            // 贴身字母/序数/修订裸词（"153b"→153.5、"2nd"→2、"011v002"→11）
            if (i >= minIdx && !allDigits && sawNonNumeric && !prevWasVolKeyword) {
                val isOrdinal = w.length > 2 && w.substring(w.length - 2) in setOf("st", "nd", "rd", "th") &&
                    w.dropLast(2).isNotEmpty() && w.dropLast(2).all { it in '0'..'9' }
                if (isOrdinal) {
                    parseExtendedNumber(w)?.let { return Rng(it) }
                } else {
                    val ext = parseExtendedNumber(w)
                    if (ext != null && followedByMetadata(tokens, i + 1)) {
                        return buildRange(tokens, i + 1, ext, true)
                    }
                }
            }
            if (!allDigits) sawNonNumeric = true
            prevWasVolKeyword = isVolKeyword(w)
            prev2 = prev1; prev1 = w
        }
        return null
    }

    // ===== 标题切片 =====

    private fun isMarkerWord(w: String): Boolean =
        stripVolumePrefix(w) != null || stripCjkVolPrefix(w) != null || isVolKeyword(w) ||
            parseChapterWord(w) != null || isChKeyword(w) ||
            cjkMarkerNumberInWord(w, CJK_VOL_MARKERS) != null || cjkMarkerNumberInWord(w, CJK_CH_MARKERS) != null

    /**
     * 词内 CJK 标记的切点（返回词内下标；0=整词都是标记）：
     * - 标记字符后带数字（"海贼王卷01"）→ 标记从字符本身开始，前面是标题
     * - 标记字符前带数字（"海贼王01卷"/"第03卷"/"12巻"）→ 标记从数字串开始，再让掉一个'第'前缀
     */
    private fun cjkMarkerCutIndex(w: String): Int {
        for ((mi, c) in w.withIndex()) {
            if (!CJK_VOL_MARKERS.contains(c) && !CJK_CH_MARKERS.contains(c)) continue
            var de = mi + 1
            while (de < w.length && w[de] in '0'..'9') de++
            if (de > mi + 1) return mi
            var ds = mi
            while (ds > 0 && w[ds - 1] in '0'..'9') ds--
            if (ds < mi) return if (ds > 0 && w[ds - 1] == '第') ds - 1 else ds
            return 0
        }
        return 0
    }

    private fun containsVolumeKeyword(content: String): Boolean {
        val lower = content.lowercase()
        if (lower.contains("volume") || lower.startsWith("vol ") || lower.startsWith("vol.")) return true
        return lower.startsWith("第") && lower.any { CJK_VOL_MARKERS.contains(it) }
    }

    private fun isNonGroupBracket(content: String): Boolean {
        val lower = content.lowercase().trim()
        if (containsVolumeKeyword(content)) return true
        if (lower in FORMAT_TAGS || lower in EDITION_TAGS || lower in SOURCE_TAGS) return true
        return false
    }

    private fun detectGroup(tokens: List<Tok>): String? {
        for (t in tokens) {
            val s = when (t) {
                is Tok.B -> t.s
                else -> continue
            }
            if (s.isNotBlank() && !isNonGroupBracket(s)) return s.trim()
        }
        return null
    }

    private fun detectOneshot(tokens: List<Tok>, hasVolume: Boolean, hasChapter: Boolean): Boolean {
        for (t in tokens) {
            when (t) {
                is Tok.D -> continue
                is Tok.B -> {
                    val digits = t.s.isNotEmpty() && t.s.length >= 5 && t.s.all { it in '0'..'9' }
                    if (digits) return true
                    return false
                }
                else -> return false
            }
        }
        if (hasVolume || hasChapter) return false
        return tokens.any { (it as? Tok.W)?.s?.let { w -> w.equals("oneshot", true) || w.equals("one-shot", true) } == true }
    }

    private fun findTitleEnd(s: String, toks: List<Tok>): Int {
        var seenWord = false
        var sawNonNumeric = false
        var prevVolKeyword = false
        val hasLaterMarker = findFirstStructuredMarkerIdx(toks) != null
        for ((i, t) in toks.withIndex()) {
            when (t) {
                is Tok.W -> {
                    seenWord = true
                    if (isMarkerWord(t.s)) {
                        // 词内 CJK 标记：标记前的文字仍属标题（"海贼王卷01"→"海贼王"、"幽游白书 完全版卷06"→"幽游白书 完全版"）
                        return t.pos + cjkMarkerCutIndex(t.s)
                    }
                    val allDigits = t.s.isNotEmpty() && t.s.all { it in '0'..'9' }
                    if (!hasLaterMarker && allDigits && sawNonNumeric && !prevVolKeyword &&
                        followedByMetadata(toks, i + 1)
                    ) {
                        val n = t.s.toIntOrNull()
                        if (n == null || !looksLikeYear(n)) return t.pos
                    }
                    if (!allDigits) sawNonNumeric = true
                    prevVolKeyword = isVolKeyword(t.s)
                }
                is Tok.B, is Tok.P, is Tok.C -> if (seenWord) return t.pos
                is Tok.D -> {}
            }
        }
        val dot = s.lastIndexOf('.')
        if (dot > 0) {
            val ext = s.substring(dot + 1)
            if (ext.isNotEmpty() && ext.length <= 4 && ext.lowercase() in KNOWN_EXTS) return dot
        }
        return s.length
    }

    private fun findTitleStart(s: String, toks: List<Tok>): Pair<Int, List<String>> {
        var cursor = 0
        val inners = mutableListOf<String>()
        for (t in toks) {
            when (t) {
                is Tok.D -> continue
                is Tok.B -> {
                    // [Volume 11] 是标记不是组名：跳过它继续链（比 chaptr 的"标题从 0 开始"更符合直觉）
                    if (containsVolumeKeyword(t.s)) { cursor = t.pos + t.s.length + 2; inners.add(t.s); continue }
                    cursor = t.pos + t.s.length + 2
                    inners.add(t.s)
                }
                is Tok.P -> { cursor = t.pos + t.s.length + 2; inners.add(t.s) }
                is Tok.C -> { cursor = t.pos + t.s.length + 2; inners.add(t.s) }
                is Tok.W -> return cursor to inners
            }
        }
        return cursor to inners
    }

    // ===== 主入口 =====

    private val NUM_ONLY = Regex("\\d+(\\.\\d+)?")
    private val WS_RUN = Regex("[ _]+")
    private val MULTI_SPACE = Regex("\\s{2,}")

    fun parse(fileName: String, parents: List<String> = emptyList()): Parsed {
        val stripped = stripExt(fileName).trim()
        val s = normalizeWidth(stripped)
        if (s.isEmpty()) return Parsed("")

        val toks = tokenize(s)
        val volume = detectVolume(toks)
        val chapter = detectChapter(toks)
        val group = detectGroup(toks)
        val oneshot = detectOneshot(toks, volume != null, chapter != null)

        var title = detectTitle(s, toks) ?: ""

        // Kavita 语义：无可识别结构（卷/章标记、被识别的标签/年份括号、裸章号）→ 系列名为空——
        // 不比 Kavita 多猜（"Vol 1.cbz"/"Love Hina - Special.cbz" 的系列本该来自祖先目录）；
        // 展示层 displayTitle 对空标题兜底原文，UX 不回退。
        // CJK 例外：中日韩文件名常无标记、文件名即书名（"浪客行.cbz"）——不判空
        if (!hasRecognizedStructure(toks) && !hasCjkRun(s)) {
            title = ""
        } else if (title.isEmpty() || title.matches(NUM_ONLY)) {
            val (start, inners) = findTitleStart(s, toks)
            val chain = inners.ifEmpty {
                toks.filterIsInstance<Tok.B>().map { it.s }
            }
            val cjk = chain.filter { g -> g.any { c -> c.code in 0x4E00..0x9FFF } }
            val picked = cjk.maxByOrNull { it.length } ?: chain.maxByOrNull { it.length }
            title = when {
                picked != null -> picked.replace(WS_RUN, " ").trim()
                (chapter != null || volume != null) && parents.isNotEmpty() ->
                    seriesFromAncestors(parents)?.replace(WS_RUN, " ")?.trim()
                        ?.takeIf { it.isNotBlank() } ?: ""
                else -> ""
            }
        }

        return Parsed(
            title = title,
            volume = volume?.start?.whole,
            volumeDec = volume?.start?.dec,
            volumeEnd = volume?.end?.whole,
            chapter = chapter?.start?.toDouble(),
            chapterEnd = chapter?.end?.toDouble(),
            volumeEndDec = volume?.end?.dec,
            group = group,
            revision = chapter?.let { r -> toks.firstNotNullOfOrNull { t -> (t as? Tok.W)?.s?.let { parseChapterWord(it)?.second } } },
            isOneshot = oneshot,
        )
    }

    /** 出版年括号：" (2017-2018)" */
    private val YEAR_BRACKET = Regex("\\d{4}(?:-\\d{2,4})?")

    /** CJK 书名约定：中日韩文件名常无任何结构标记、文件名即书名（"浪客行.cbz"）——此类不判空系列名 */
    private fun hasCjkRun(s: String): Boolean = s.any { c ->
        val v = c.code
        v in 0x4E00..0x9FFF || v in 0x3400..0x4DBF || v in 0x3040..0x30FF ||
            v in 0xAC00..0xD7AF || v in 0xF900..0xFAFF
    }

    /**
     * Kavita 语义的结构判定：卷/章标记词、被识别的标签/年份括号、括号内章关键词、裸章号。
     * 全无 → 系列名为空（系列本该来自祖先目录）。单字母 c 前缀不作为结构判据（"(C92)" 是漫展编号不是章）。
     */
    private fun hasRecognizedStructure(toks: List<Tok>): Boolean {
        if (findFirstStructuredMarkerIdx(toks) != null) return true
        if (detectBareChapter(toks) != null) return true
        for (t in toks) {
            val inner = when (t) {
                is Tok.B -> t.s
                is Tok.P -> t.s
                is Tok.C -> t.s
                else -> continue
            }
            val c = inner.trim()
            if (isNonGroupBracket(c) || c.matches(YEAR_BRACKET)) return true
            for (x in tokenize(inner)) {
                val w = (x as? Tok.W)?.s ?: continue
                if (isVolKeyword(w) || stripVolumePrefix(w) != null || isChKeyword(w)) return true
                if (parseChapterWord(w) != null && !w.startsWith("c", ignoreCase = true)) return true
            }
        }
        return false
    }

    private fun detectTitle(s: String, toks: List<Tok>): String? {
        val end = findTitleEnd(s, toks)
        val (start, _) = findTitleStart(s, toks)
        if (start >= end) return null
        var t = s.substring(start, end)
        t = t.trim()
        t = t.trimEnd('-', '_', ',', '#', ':', '·', '、', '~')
        t = t.replace('_', ' ')
        t = t.replace(MULTI_SPACE, " ").trim()
        return t.ifEmpty { null }
    }

    /** 书架显示名：干净书名（繁转简）+ 卷/章短缀（Vol.01 / Ch.42 风格，范围 Vol.01-Vol.09 / Ch.01-08）。 */
    fun displayTitle(fileName: String, t2s: Boolean = true, parents: List<String> = emptyList()): String {
        val base = stripExt(fileName).trim()
        return try {
            val p = parse(fileName, parents)
            // 空标题（Kavita 无结构语义）→ 兜底原文（下划线规整），仍照常补卷/章短缀（"第1话开端" → "第1话开端 Ch.01"）
            val head = if (p.title.isBlank()) {
                val fb = base.replace(WS_RUN, " ").trim()
                if (t2s) toSimplified(fb) else fb
            } else if (t2s) toSimplified(p.title) else p.title
            val sb = StringBuilder(head)
            when {
                p.volume != null -> {
                    sb.append(" Vol.").append("%02d".format(p.volume))
                    p.volumeDec?.let { sb.append(".").append(decStr(it)) }
                    p.volumeEnd?.let {
                        sb.append("-Vol.").append("%02d".format(it))
                        p.volumeEndDec?.let { d -> sb.append(".").append(decStr(d)) }
                    }
                }
                p.chapter != null -> {
                    sb.append(" Ch.").append(fmtNum(p.chapter))
                    p.chapterEnd?.let { sb.append("-Ch.").append(fmtNum(it)) }
                }
                p.isOneshot -> sb.append(" [短篇]")
            }
            sb.toString().trim()
        } catch (_: Throwable) {
            if (t2s) toSimplified(base) else base
        }
    }

    /** 千分位小数 → 显示串（500→"5"、125→"125"、0→"0"） */
    private fun decStr(dec: Int): String = dec.toString().trimEnd('0').ifEmpty { "0" }

    private fun fmtNum(ch: Double): String {
        val whole = kotlin.math.floor(ch).toInt().coerceAtLeast(0)
        val decScaled = kotlin.math.round((ch - whole) * 1000).toInt()
        val decStr = if (decScaled <= 0) "" else decScaled.toString().trimEnd('0')
        val w = "%02d".format(whole)
        return if (decStr.isEmpty()) w else "$w.$decStr"
    }

    /** 全角→半角归一（全角数字/字母/常用符号；全角括号保留走 CJK 括号通道） */
    private fun normalizeWidth(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when {
                c == '　' -> sb.append(' ')
                c == '－' -> sb.append('-')
                c == '～' -> sb.append('~')
                c == '：' -> sb.append(':')
                c == '．' -> sb.append('.')
                c == '｛' -> sb.append('{')
                c == '｝' -> sb.append('}')
                c in '０'..'９' -> sb.append(('0' + (c - '０')))
                c in 'Ａ'..'Ｚ' -> sb.append(('A' + (c - 'Ａ')))
                c in 'ａ'..'ｚ' -> sb.append(('a' + (c - 'ａ')))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * 从任意 Book.filePath 形态提取原文件名（长按详情、批量改名共用，别处不要再手写截取）：
     * - SAF 紧凑路径 "a:<auth>|<treeDocId>|<docId>|<ext>" / "z:a:...|<ext>//"——末段 |ext 是扩展名后缀，须剥掉
     * - 目录书 "d:<auth>|<treeDocId>|<docId>" 与普通/WebDAV/SMB 路径——取最后一个 / 后的段
     */
    fun fileNameOf(path: String): String {
        val p = path.trim().trimEnd('/')
        var s = p
        if ((p.startsWith("a:") || p.startsWith("z:")) && s.contains('|')) {
            val i = s.lastIndexOf('|')
            val tail = s.substring(i + 1)
            // | 后是 1-5 位字母数字才当扩展名后缀剥（防文件名里恰好有 | 误伤）
            if (tail.isNotEmpty() && tail.length <= 5 && tail.all { it.isLetterOrDigit() }) s = s.substring(0, i)
        }
        return s.substringAfterLast('/').substringAfterLast('\\')
    }

    /** 收藏夹/分组夹关键词：祖先目录名含这些词时不当地系列名（"日本漫画合集"、"2.漫画"、Download） */
    private val COLLECTION_WORDS = listOf("合集", "收藏", "漫画", "杂图", "manga", "comic", "download", "下载")

    /**
     * 类目夹名（**精确匹配**，不 substring——防误伤"哆啦A梦短篇集"这类真系列名）：
     * "A/日漫/2025/哆啦a梦/短篇/第1话" 结构里 短篇/日漫 是分类词不是系列名。
     */
    private val CATEGORY_DIR_NAMES = setOf(
        "短篇", "短篇集", "单行本", "未分类", "其他", "其它", "杂项", "待整理",
        "日漫", "国漫", "韩漫", "美漫", "港漫", "连载", "完结",
    )

    /**
     * 祖先目录名（从最近到最远，不含树根本身——树根是用户选的扫描根，不是系列名）。
     * SAF d:/a: 书按 docId 层级取；z:（zip 内部）与其他路径形态返回空。
     */
    fun ancestorNamesOf(path: String): List<String> {
        val p = path.trim().trimEnd('/')
        if (!p.startsWith("d:") && !p.startsWith("a:")) return emptyList()
        var body = p
        if (p.startsWith("a:") && body.contains('|')) {
            val i = body.lastIndexOf('|')
            val tail = body.substring(i + 1)
            if (tail.isNotEmpty() && tail.length <= 5 && tail.all { it.isLetterOrDigit() }) body = body.substring(0, i)
        }
        val segs = body.split('|')
        if (segs.size < 3) return emptyList()
        val treeDocId = segs[1]
        val docId = segs.drop(2).joinToString("|")
        // docId 去掉树根前缀后剩余的目录段，从最近到最远
        val rel = if (docId.startsWith("$treeDocId/")) docId.substring(treeDocId.length + 1) else docId
        val parts = rel.split('/')
        if (parts.size < 2) return emptyList()  // 无层级（直接在树根下）→ 无祖先
        return parts.dropLast(1).reversed()
    }

    /**
     * 从祖先链（最近优先）挑系列名：跳过纯字母短名（"A"）、纯数字、章节名（"第1话…"）、
     * 卷标记开头的名字（"01卷"、"第03卷"——但"幽游白书完全版 第03卷"这种"书名+卷标记"不算，
     * 卷标记只在开头才跳）；收藏夹关键词跳过；全不可信返回 null。
     */
    fun seriesFromAncestors(ancestors: List<String>): String? {
        for (raw in ancestors) {
            val n = raw.trim()
            if (n.isEmpty()) continue
            if (n.length <= 2 && n.all { it.code < 128 }) continue   // "A"/"B"/"01" 分组夹
            if (n.matches(NUM_ONLY)) continue                        // 纯数字
            val toks = tokenize(normalizeWidth(n))
            // 章节名祖先（"第1话开端"/"c01"/"Chapter 5"）：cjk 章标记词内检测补上"第N话"形态
            if (toks.any { (it as? Tok.W)?.s?.let { w ->
                        parseChapterWord(w) != null || isChKeyword(w) ||
                            cjkMarkerNumberInWord(w, CJK_CH_MARKERS) != null
                    } == true }) continue
            if (toks.firstOrNull() is Tok.W && (toks[0] as Tok.W).s.let { w ->
                    stripVolumePrefix(w) != null || cjkMarkerNumberInWord(w, CJK_VOL_MARKERS) != null || isVolKeyword(w)
                }
            ) continue
            if (COLLECTION_WORDS.any { n.lowercase().contains(it) }) continue
            if (n in CATEGORY_DIR_NAMES) continue                     // "短篇"/"日漫" 类目夹（精确匹配）
            return n
        }
        return null
    }

    private fun stripExt(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return name
        return if (name.substring(dot + 1).lowercase() in KNOWN_EXTS) name.substring(0, dot) else name
    }

    // ===== 繁转简（OpenCC TSCharacters 单字表；冷僻字保持原样） =====
    private const val T_TRAAD = "㑯㑳㑶㓨㘚㜄㜏㠏㥮㩜㩳㩵䁻䃮䊷䋙䋚䋹䋻䍦䎱䙡䜀䝼䥇䥑䥱䦛䦟䯀䰾䱷䱽䲁䲘䴉丟並乾亂亙亞佇佈佔併來侖侶侷俁係俔俠俥俬倀倆倈倉個們倖倫倲偉偑側偵偽傌傑傖傘備傢傭傯傳傴債傷傾僂僅僉僑僕僞僥僨僱價儀儁儂億儈儉儎儐儔儕儘償優儲儷儸儺儻儼兇兌兒兗內兩冊冑冪凈凍凜凱別刪剄則剋剎剗剛剝剮剴創剷劃劄劇劉劊劌劍劏劑劚勁動務勛勝勞勢勩勱勳勵勸勻匭匯匱區協卹卻卽厙厠厤厭厲厴參叄叢吒吳吶呂咼員唄唸問啓啞啟啢喎喚喪喫喬單喲嗆嗇嗊嗎嗚嗩嗶嘆嘍嘓嘔嘖嘗嘜嘩嘮嘯嘰嘵嘸嘽噁噓噚噝噠噥噦噯噲噴噸噹嚀嚇嚌嚐嚕嚙嚥嚦嚨嚮嚲嚳嚴嚶囀囁囂囅囈囉囌囑囪圇國圍園圓圖團垻埡埰執堅堊堖堝堯報場塊塋塏塒塗塚塢塤塵塹墊墜墮墰墳墶墻墾壇壋壎壓壘壙壚壜壞壟壠壢壩壪壯壺壼壽夠夢夥夾奐奧奩奪奬奮奼妝姍姦娛婁婦婭媧媯媰媼媽嫋嫗嫵嫺嫻嫿嬀嬃嬈嬋嬌嬙嬡嬤嬪嬰嬸孃孋孌孫學孿宮寀寢實寧審寫寬寵寶將專尋對導尷屆屍屓屜屢層屨屬岡峯峴島峽崍崑崗崙崢崬嵐嵗嵾嶁嶄嶇嶔嶗嶠嶢嶧嶨嶮嶸嶺嶼嶽巋巒巔巖巰巹帥師帳帶幀幃幓幗幘幟幣幫幬幷幹幾庫廁廂廄廈廎廕廚廝廟廠廡廢廣廩廬廳弒弔弳張強彆彈彌彎彔彙彠彥彫彲彿後徑從徠復徵徹恆恥悅悞悵悶悽惡惱惲惻愛愜愨愴愷愾慄態慍慘慚慟慣慤慪慫慮慳慶慺慼慾憂憊憐憑憒憖憚憤憫憮憲憶懇應懌懍懞懟懣懤懨懲懶懷懸懺懼懾戀戇戔戧戩戰戱戲戶扞拋拚挩挱挾捨捫捱捲掃掄掆掗掙掛採揀揚換揮揯損搖搗搧搵搶摑摜摟摯摳摶摺摻撈撏撐撓撝撟撣撥撫撲撳撻撾撿擁擄擇擊擋擓擔據擠擡擣擬擯擰擱擲擴擷擺擻擼擽擾攄攆攏攔攖攙攛攜攝攢攣攤攪攬敎敓敗敘敵數斂斃斆斕斬斷於旂旣昇時晉晝暈暉暘暢暫曄曆曇曉曏曖曠曨曬書會朧朮東枴柵柺査桿梔梘條梟梲棄棊棖棗棟棡棧棲棶椏椲楊楓楨業極榘榦榪榮榲榿構槍槓槤槧槨槮槳槶槼樁樂樅樑樓標樞樢樣樧樫樳樸樹樺樿橈橋機橢橫檁檉檔檜檟檢檣檮檯檳檸檻櫃櫓櫚櫛櫝櫞櫟櫥櫧櫨櫪櫫櫬櫱櫳櫸櫻欄欅權欏欒欖欞欽歎歐歟歡歲歷歸歿殘殞殤殨殫殭殮殯殰殲殺殻殼毀毆毿氂氈氌氣氫氬氳氾汎汙決沒沖況泝洩洶浹涇涗涼淒淚淥淨淩淪淵淶淺渙減渢渦測渾湊湞湧湯溈準溝溫溮溳溼滄滅滌滎滙滬滯滲滷滸滻滾滿漁漊漚漢漣漬漲漵漸漿潁潑潔潙潚潛潤潯潰潷潿澀澆澇澐澗澠澤澦澩澮澱澾濁濃濄濕濘濚濛濜濟濤濧濫濰濱濺濼濾瀂瀅瀆瀇瀉瀋瀏瀕瀘瀝瀟瀠瀦瀧瀨瀰瀲瀾灃灄灑灕灘灝灡灣灤灧灩災為烏烴無煉煒煙煢煥煩煬煱熅熒熗熱熲熾燁燈燉燒燙燜營燦燬燭燴燶燻燼燾爍爐爛爭爲爺爾牀牆牘牴牽犖犛犢犧狀狹狽猙猶猻獁獃獄獅獎獨獪獫獮獰獱獲獵獷獸獺獻獼玀現琱琺琿瑋瑒瑣瑤瑩瑪瑲璉璡璣璦璫璯環璵璸璽璿瓊瓏瓔瓚甌甕產産畝畢畫異畵當疇疊痙痠痾瘂瘋瘍瘓瘞瘡瘧瘮瘲瘺瘻療癆癇癉癒癘癟癡癢癤癥癧癩癬癭癮癰癱癲發皁皚皰皸皺盃盜盞盡監盤盧盪眞眥眾睏睜睞瞘瞜瞞瞶瞼矇矓矚矯硃硜硤硨硯碕碩碭碸確碼碽磑磚磠磣磧磯磽磾礄礎礙礦礪礫礬礱祕祿禍禎禕禡禦禪禮禰禱禿秈稅稈稏稜稟種稱穀穇穌積穎穠穡穢穩穫穭窩窪窮窯窵窶窺竄竅竇竈竊竪競筆筍筧筴箇箋箏箚節範築篋篔篠篤篩篳簀簍簑簞簡簣簫簹簽簾籃籌籔籙籛籜籟籠籤籩籪籬籮籲粵糉糝糞糧糰糲糴糶糹糾紀紂約紅紆紇紈紉紋納紐紓純紕紖紗紘紙級紛紜紝紡紬紮細紱紲紳紵紹紺紼紿絀終絃組絅絆絎結絕絛絝絞絡絢給絨絰統絲絳絶絹綁綃綆綈綉綌綏綐綑經綜綞綠綢綣綫綬維綯綰綱網綳綴綵綸綹綺綻綽綾綿緄緇緊緋緑緒緓緔緗緘緙線緝緞締緡緣緦編緩緬緯緱緲練緶緹緻緼縈縉縊縋縐縑縕縗縛縝縞縟縣縧縫縭縮縱縲縳縴縵縶縷縹總績繃繅繆繒織繕繚繞繡繢繩繪繫繭繮繯繰繳繸繹繼繽繾繿纇纈纊續纍纏纓纔纖纘纜缽罃罈罌罎罰罵罷羅羆羈羋羣羥羨義羶習翫翬翹翽耬耮聖聞聯聰聲聳聵聶職聹聽聾肅脅脈脛脣脩脫脹腎腖腡腦腫腳腸膃膕膚膞膠膩膽膾膿臉臍臏臘臚臟臠臢臥臨臺與興舉舊舖舘艙艤艦艫艱艷芻苧茲荊莊莖莢莧華菴菸萇萊萬萴萵葉葒葤葦葯葷蒐蒓蒔蒕蒞蒼蓀蓆蓋蓮蓯蓴蓽蔔蔘蔞蔣蔥蔦蔭蕁蕆蕎蕒蕓蕕蕘蕢蕩蕪蕭蕷薀薈薊薌薑薔薘薟薦薩薴薵薹薺藍藎藝藥藪藭藴藶藹藺蘀蘄蘆蘇蘊蘋蘚蘞蘢蘭蘺蘿虆處虛虜號虧虯蛺蛻蜆蝕蝟蝦蝨蝸螄螞螢螮螻螿蟄蟈蟎蟣蟬蟯蟲蟶蟻蠁蠅蠆蠍蠐蠑蠔蠟蠣蠨蠱蠶蠻衆衊術衕衚衛衝袞袷裊裏補裝裡製複褌褘褲褳褸褻襇襉襏襖襝襠襤襪襬襯襲襴覈見覎規覓視覘覡覥覦親覬覯覲覷覺覽覿觀觴觶觸訁訂訃計訊訌討訐訒訓訕訖託記訛訝訟訢訣訥訩訪設許訴訶診註証詁詆詎詐詒詔評詖詗詘詛詞詠詡詢詣試詩詫詬詭詮詰話該詳詵詼詿誄誅誆誇誌認誑誒誕誘誚語誠誡誣誤誥誦誨說説誰課誶誹誼誾調諂諄談諉請諍諏諑諒論諗諛諜諝諞諡諢諤諦諧諫諭諮諱諳諶諷諸諺諼諾謀謁謂謄謅謊謎謐謔謖謗謙謚講謝謠謡謨謫謬謭謳謹謾譁證譎譏譖識譙譚譜譟譫譭譯議譴護譸譽譾讀讅變讋讌讎讒讓讕讖讚讜讞谿豈豎豐豔豬豶貍貓貙貝貞貟負財貢貧貨販貪貫責貯貰貲貳貴貶買貸貺費貼貽貿賀賁賂賃賄賅資賈賊賑賒賓賕賙賚賜賞賠賡賢賣賤賦賧質賫賬賭賰賴賵賺賻購賽賾贄贅贇贈贊贋贍贏贐贓贔贖贗贛贜赬趕趙趨趲跡踐踰踴蹌蹕蹟蹠蹣蹤蹺躂躉躊躋躍躎躑躒躓躕躚躡躥躦躪軀車軋軌軍軑軒軔軛軟軤軫軲軸軹軺軻軼軾較輅輇輈載輊輒輓輔輕輛輜輝輞輟輥輦輩輪輬輯輳輸輻輼輾輿轀轂轄轅轆轉轍轎轔轟轡轢轤辦辭辮辯農迴逕這連週進遊運過達違遙遜遞遠遡適遲遶遷選遺遼邁還邇邊邏邐郟郵鄆鄉鄒鄔鄖鄧鄭鄰鄲鄴鄶鄺酇酈醃醖醜醞醟醣醫醬醱釀釁釃釅釋釐釒釓釔釕釗釘釙針釣釤釦釧釩釵釷釹釺釾鈀鈁鈃鈄鈅鈈鈉鈍鈎鈐鈑鈒鈔鈕鈞鈡鈣鈥鈦鈧鈮鈰鈳鈴鈷鈸鈹鈺鈽鈾鈿鉀鉅鉆鉈鉉鉋鉍鉑鉕鉗鉚鉛鉞鉢鉤鉦鉬鉭鉳鉶鉸鉺鉻鉿銀銃銅銍銑銓銖銘銚銛銜銠銣銥銦銨銩銪銫銬銱銳銷銹銻銼鋁鋃鋅鋇鋌鋏鋒鋙鋝鋟鋣鋤鋥鋦鋨鋩鋪鋭鋮鋯鋰鋱鋶鋸鋼錁錄錆錇錈錏錐錒錕錘錙錚錛錟錠錡錢錦錨錩錫錮錯録錳錶錸錼鍀鍁鍃鍅鍆鍇鍈鍊鍋鍍鍔鍘鍚鍛鍠鍤鍥鍩鍬鍰鍵鍶鍺鍼鍾鎂鎄鎇鎊鎌鎔鎖鎘鎚鎛鎡鎢鎣鎦鎧鎩鎪鎬鎭鎮鎰鎲鎳鎵鎶鎸鎿鏃鏇鏈鏌鏍鏐鏑鏗鏘鏜鏝鏞鏟鏡鏢鏤鏨鏰鏵鏷鏹鏺鏽鐃鐋鐐鐒鐓鐔鐘鐙鐝鐠鐥鐦鐧鐨鐫鐮鐯鐲鐳鐵鐶鐸鐺鐿鑄鑊鑌鑑鑒鑔鑕鑞鑠鑣鑥鑭鑰鑱鑲鑷鑹鑼鑽鑾鑿钁钂長門閂閃閆閈閉開閌閎閏閑閒間閔閘閡閣閤閥閨閩閫閬閭閱閲閶閹閻閼閽閾閿闃闆闇闈闊闋闌闍闐闒闓闔闕闖關闞闠闡闢闤闥陘陝陞陣陰陳陸陽隉隊階隕際隨險隯隱隴隸隻雋雖雙雛雜雞離難雲電霑霢霧霽靂靄靆靈靉靚靜靝靦靨鞏鞝鞦鞽韁韃韆韉韋韌韍韓韙韜韝韞韻響頁頂頃項順頇須頊頌頎頏預頑頒頓頗領頜頡頤頦頭頮頰頲頴頷頸頹頻頽顆題額顎顏顒顓顔願顙顛類顢顥顧顫顬顯顰顱顳顴風颭颮颯颱颳颶颸颺颻颼飀飄飆飈飛飠飢飣飥飩飪飫飭飯飱飲飴飼飽飾飿餃餄餅餈餉養餌餎餏餑餒餓餕餖餘餚餛餜餞餡館餬餱餳餵餶餷餺餼餾餿饁饃饅饈饉饊饋饌饑饒饗饜饞饢馬馭馮馱馳馴馹駁駐駑駒駔駕駘駙駛駝駟駡駢駭駰駱駸駿騁騂騅騌騍騎騏騖騙騤騧騫騭騮騰騶騷騸騾驀驁驂驃驄驅驊驌驍驏驕驗驚驛驟驢驤驥驦驪驫骯髏髒體髕髖髮鬆鬍鬚鬢鬥鬧鬨鬩鬮鬱鬹魎魘魚魛魢魨魯魴魷魺鮁鮃鮊鮋鮍鮎鮐鮑鮒鮓鮚鮜鮝鮞鮣鮦鮪鮫鮭鮮鮳鮶鮺鯀鯁鯇鯉鯊鯒鯔鯕鯖鯗鯛鯝鯡鯢鯤鯧鯨鯪鯫鯰鯴鯷鯽鯿鰁鰂鰃鰆鰈鰉鰌鰍鰏鰐鰒鰓鰛鰜鰟鰠鰣鰥鰧鰨鰩鰭鰮鰱鰲鰳鰵鰷鰹鰺鰻鰼鰾鱂鱅鱈鱉鱒鱔鱖鱗鱘鱝鱟鱠鱣鱤鱧鱨鱭鱯鱷鱸鱺鳥鳧鳩鳬鳲鳳鳴鳶鳾鴆鴇鴉鴒鴕鴛鴝鴞鴟鴣鴦鴨鴯鴰鴴鴷鴻鴿鵁鵂鵃鵐鵑鵒鵓鵜鵝鵠鵡鵪鵬鵮鵯鵰鵲鵷鵾鶄鶇鶉鶊鶓鶖鶘鶚鶡鶥鶩鶪鶬鶯鶲鶴鶹鶺鶻鶼鶿鷀鷁鷂鷄鷉鷊鷓鷖鷗鷙鷚鷥鷦鷫鷯鷲鷳鷴鷸鷹鷺鷽鸂鸇鸊鸌鸏鸕鸘鸚鸛鸝鸞鹵鹹鹺鹼鹽麗麥麩麪麫麯麴麵麼麽黃黌點黨黲黴黶黷黽黿鼂鼉鼕鼴齊齋齎齏齒齔齕齗齙齜齟齠齡齣齦齧齪齬齲齶齷龍龎龐龑龔龕龜鿁鿓"

    private const val S_SIMP = "㑔㑇㐹刾㘎㚯㛣㟆㤘㨫㧐擜䀥鿎䌶䌺䌻䌿䌾䍠䎬䙌䜧䞍䦂鿏䥾䦶䦷䯅鲃䲣䲝鳚鳤鹮丢并干乱亘亚伫布占并来仑侣局俣系伣侠伡私伥俩俫仓个们幸伦㑈伟㐽侧侦伪㐷杰伧伞备家佣偬传伛债伤倾偻仅佥侨仆伪侥偾雇价仪俊侬亿侩俭傤傧俦侪尽偿优储俪㑩傩傥俨凶兑儿兖内两册胄幂净冻凛凯别删刭则克刹刬刚剥剐剀创铲划札剧刘刽刿剑㓥剂㔉劲动务勋胜劳势勚劢勋励劝匀匦汇匮区协恤却即厍厕历厌厉厣参叁丛咤吴呐吕呙员呗念问启哑启唡㖞唤丧吃乔单哟呛啬唝吗呜唢哔叹喽啯呕啧尝唛哗唠啸叽哓呒啴恶嘘㖊咝哒哝哕嗳哙喷吨当咛吓哜尝噜啮咽呖咙向亸喾严嘤啭嗫嚣冁呓啰苏嘱囱囵国围园圆图团坝垭采执坚垩垴埚尧报场块茔垲埘涂冢坞埙尘堑垫坠堕坛坟垯墙垦坛垱埙压垒圹垆坛坏垄垅坜坝塆壮壶壸寿够梦伙夹奂奥奁夺奖奋姹妆姗奸娱娄妇娅娲妫㛀媪妈袅妪妩娴娴婳妫媭娆婵娇嫱嫒嬷嫔婴婶娘㛤娈孙学孪宫采寝实宁审写宽宠宝将专寻对导尴届尸屃屉屡层屦属冈峰岘岛峡崃昆岗仑峥岽岚岁㟥嵝崭岖嵚崂峤峣峄峃崄嵘岭屿岳岿峦巅岩巯卺帅师帐带帧帏㡎帼帻帜币帮帱并干几库厕厢厩厦庼荫厨厮庙厂庑废广廪庐厅弑吊弪张强别弹弥弯录汇彟彦雕彨佛后径从徕复征彻恒耻悦悮怅闷凄恶恼恽恻爱惬悫怆恺忾栗态愠惨惭恸惯悫怄怂虑悭庆㥪戚欲忧惫怜凭愦慭惮愤悯怃宪忆恳应怿懔蒙怼懑㤽恹惩懒怀悬忏惧慑恋戆戋戗戬战戯戏户捍抛拼捝挲挟舍扪挨卷扫抡㧏挜挣挂采拣扬换挥搄损摇捣扇揾抢掴掼搂挚抠抟折掺捞挦撑挠㧑挢掸拨抚扑揿挞挝捡拥掳择击挡㧟担据挤抬捣拟摈拧搁掷扩撷摆擞撸㧰扰摅撵拢拦撄搀撺携摄攒挛摊搅揽教敚败叙敌数敛毙敩斓斩断于旗既升时晋昼晕晖旸畅暂晔历昙晓向暧旷昽晒书会胧术东拐栅拐查杆栀枧条枭棁弃棋枨枣栋㭎栈栖梾桠㭏杨枫桢业极矩干杩荣榅桤构枪杠梿椠椁椮桨椢椝桩乐枞梁楼标枢㭤样榝㭴桪朴树桦椫桡桥机椭横檩柽档桧槚检樯梼台槟柠槛柜橹榈栉椟橼栎橱槠栌枥橥榇蘖栊榉樱栏榉权椤栾榄棂钦叹欧欤欢岁历归殁残殒殇㱮殚僵殓殡㱩歼杀壳壳毁殴毵牦毡氇气氢氩氲泛泛污决没冲况溯泄汹浃泾涚凉凄泪渌净凌沦渊涞浅涣减沨涡测浑凑浈涌汤沩准沟温浉涢湿沧灭涤荥汇沪滞渗卤浒浐滚满渔溇沤汉涟渍涨溆渐浆颍泼洁沩㴋潜润浔溃滗涠涩浇涝沄涧渑泽滪泶浍淀㳠浊浓㳡湿泞溁蒙浕济涛㳔滥潍滨溅泺滤澛滢渎㲿泻沈浏濒泸沥潇潆潴泷濑弥潋澜沣滠洒漓滩灏㳕湾滦滟滟灾为乌烃无炼炜烟茕焕烦炀㶽煴荧炝热颎炽烨灯炖烧烫焖营灿毁烛烩㶶熏烬焘烁炉烂争为爷尔床墙牍抵牵荦牦犊牺状狭狈狰犹狲犸呆狱狮奖独狯猃狝狞㺍获猎犷兽獭献猕猡现雕珐珲玮玚琐瑶莹玛玱琏琎玑瑷珰㻅环玙瑸玺璇琼珑璎瓒瓯瓮产产亩毕画异画当畴叠痉酸疴痖疯疡痪瘗疮疟瘆疭瘘瘘疗痨痫瘅愈疠瘪痴痒疖症疬癞癣瘿瘾痈瘫癫发皂皑疱皲皱杯盗盏尽监盘卢荡真眦众困睁睐眍䁖瞒瞆睑蒙眬瞩矫朱硁硖砗砚埼硕砀砜确码䂵硙砖硵碜碛矶硗䃅硚础碍矿砺砾矾砻秘禄祸祯祎祃御禅礼祢祷秃籼税秆䅉棱禀种称谷䅟稣积颖秾穑秽稳获穞窝洼穷窑窎窭窥窜窍窦灶窃竖竞笔笋笕䇲个笺筝札节范筑箧筼筿笃筛筚箦篓蓑箪简篑箫筜签帘篮筹䉤箓篯箨籁笼签笾簖篱箩吁粤粽糁粪粮团粝籴粜纟纠纪纣约红纡纥纨纫纹纳纽纾纯纰纼纱纮纸级纷纭纴纺䌷扎细绂绁绅纻绍绀绋绐绌终弦组䌹绊绗结绝绦绔绞络绚给绒绖统丝绛绝绢绑绡绠绨绣绤绥䌼捆经综缍绿绸绻线绶维绹绾纲网绷缀彩纶绺绮绽绰绫绵绲缁紧绯绿绪绬绱缃缄缂线缉缎缔缗缘缌编缓缅纬缑缈练缏缇致缊萦缙缢缒绉缣缊缞缚缜缟缛县绦缝缡缩纵缧䌸纤缦絷缕缥总绩绷缫缪缯织缮缭绕绣缋绳绘系茧缰缳缲缴䍁绎继缤缱䍀颣缬纩续累缠缨才纤缵缆钵䓨坛罂坛罚骂罢罗罴羁芈群羟羡义膻习玩翚翘翙耧耢圣闻联聪声耸聩聂职聍听聋肃胁脉胫唇修脱胀肾胨脶脑肿脚肠腽腘肤䏝胶腻胆脍脓脸脐膑腊胪脏脔臜卧临台与兴举旧铺馆舱舣舰舻艰艳刍苎兹荆庄茎荚苋华庵烟苌莱万荝莴叶荭荮苇药荤搜莼莳蒀莅苍荪席盖莲苁莼荜卜参蒌蒋葱茑荫荨蒇荞荬芸莸荛蒉荡芜萧蓣蕰荟蓟芗姜蔷荙莶荐萨苧䓓苔荠蓝荩艺药薮䓖蕴苈蔼蔺萚蕲芦苏蕴苹藓蔹茏兰蓠萝蔂处虚虏号亏虬蛱蜕蚬蚀猬虾虱蜗蛳蚂萤䗖蝼螀蛰蝈螨虮蝉蛲虫蛏蚁蚃蝇虿蝎蛴蝾蚝蜡蛎蟏蛊蚕蛮众蔑术同胡卫冲衮夹袅里补装里制复裈袆裤裢褛亵裥裥袯袄裣裆褴袜摆衬袭襕核见觃规觅视觇觋觍觎亲觊觏觐觑觉览觌观觞觯触讠订讣计讯讧讨讦讱训讪讫托记讹讶讼䜣诀讷讻访设许诉诃诊注证诂诋讵诈诒诏评诐诇诎诅词咏诩询诣试诗诧诟诡诠诘话该详诜诙诖诔诛诓夸志认诳诶诞诱诮语诚诫诬误诰诵诲说说谁课谇诽谊訚调谄谆谈诿请诤诹诼谅论谂谀谍谞谝谥诨谔谛谐谏谕咨讳谙谌讽诸谚谖诺谋谒谓誊诌谎谜谧谑谡谤谦谥讲谢谣谣谟谪谬谫讴谨谩哗证谲讥谮识谯谭谱噪谵毁译议谴护诪誉谫读谉变詟䜩雠谗让谰谶赞谠谳溪岂竖丰艳猪豮狸猫䝙贝贞贠负财贡贫货贩贪贯责贮贳赀贰贵贬买贷贶费贴贻贸贺贲赂赁贿赅资贾贼赈赊宾赇赒赉赐赏赔赓贤卖贱赋赕质赍账赌䞐赖赗赚赙购赛赜贽赘赟赠赞赝赡赢赆赃赑赎赝赣赃赪赶赵趋趱迹践逾踊跄跸迹跖蹒踪跷跶趸踌跻跃䟢踯跞踬蹰跹蹑蹿躜躏躯车轧轨军轪轩轫轭软轷轸轱轴轵轺轲轶轼较辂辁辀载轾辄挽辅轻辆辎辉辋辍辊辇辈轮辌辑辏输辐辒辗舆辒毂辖辕辘转辙轿辚轰辔轹轳办辞辫辩农回径这连周进游运过达违遥逊递远溯适迟绕迁选遗辽迈还迩边逻逦郏邮郓乡邹邬郧邓郑邻郸邺郐邝酂郦腌酝丑酝蒏糖医酱酦酿衅酾酽释厘钅钆钇钌钊钉钋针钓钐扣钏钒钗钍钕钎䥺钯钫钘钭钥钚钠钝钩钤钣钑钞钮钧钟钙钬钛钪铌铈钶铃钴钹铍钰钸铀钿钾巨钻铊铉铇铋铂钷钳铆铅钺钵钩钲钼钽锫铏铰铒铬铪银铳铜铚铣铨铢铭铫铦衔铑铷铱铟铵铥铕铯铐铞锐销锈锑锉铝锒锌钡铤铗锋铻锊锓铘锄锃锔锇铓铺锐铖锆锂铽锍锯钢锞录锖锫锩铔锥锕锟锤锱铮锛锬锭锜钱锦锚锠锡锢错录锰表铼镎锝锨锪钫钔锴锳炼锅镀锷铡钖锻锽锸锲锘锹锾键锶锗针钟镁锿镅镑镰镕锁镉锤镈镃钨蓥镏铠铩锼镐镇镇镒镋镍镓鿔镌镎镞旋链镆镙镠镝铿锵镗镘镛铲镜镖镂錾镚铧镤镪䥽锈铙铴镣铹镦镡钟镫镢镨䦅锎锏镄镌镰䦃镯镭铁镮铎铛镱铸镬镔鉴鉴镲锧镴铄镳镥镧钥镵镶镊镩锣钻銮凿镢镋长门闩闪闫闬闭开闶闳闰闲闲间闵闸阂阁合阀闺闽阃阆闾阅阅阊阉阎阏阍阈阌阒板暗闱阔阕阑阇阗阘闿阖阙闯关阚阓阐辟阛闼陉陕升阵阴陈陆阳陧队阶陨际随险陦隐陇隶只隽虽双雏杂鸡离难云电沾霡雾霁雳霭叇灵叆靓静靔腼靥巩绱秋鞒缰鞑千鞯韦韧韨韩韪韬鞲韫韵响页顶顷项顺顸须顼颂颀颃预顽颁顿颇领颌颉颐颏头颒颊颋颕颔颈颓频颓颗题额颚颜颙颛颜愿颡颠类颟颢顾颤颥显颦颅颞颧风飐飑飒台刮飓飔飏飖飕飗飘飙飚飞饣饥饤饦饨饪饫饬饭飧饮饴饲饱饰饳饺饸饼糍饷养饵饹饻饽馁饿馂饾余肴馄馃饯馅馆糊糇饧喂馉馇馎饩馏馊馌馍馒馐馑馓馈馔饥饶飨餍馋馕马驭冯驮驰驯驲驳驻驽驹驵驾骀驸驶驼驷骂骈骇骃骆骎骏骋骍骓骔骒骑骐骛骗骙䯄骞骘骝腾驺骚骟骡蓦骜骖骠骢驱骅骕骁骣骄验惊驿骤驴骧骥骦骊骉肮髅脏体髌髋发松胡须鬓斗闹哄阋阄郁鬶魉魇鱼鱽鱾鲀鲁鲂鱿鲄鲅鲆鲌鲉鲏鲇鲐鲍鲋鲊鲒鲘鲞鲕䲟鲖鲔鲛鲑鲜鲓鲪鲝鲧鲠鲩鲤鲨鲬鲻鲯鲭鲞鲷鲴鲱鲵鲲鲳鲸鲮鲰鲶鲺鳀鲫鳊鳈鲗鳂䲠鲽鳇䲡鳅鲾鳄鳆鳃鳁鳒鳑鳋鲥鳏䲢鳎鳐鳍鳁鲢鳌鳓鳘鲦鲣鲹鳗鳛鳔鳉鳙鳕鳖鳟鳝鳜鳞鲟鲼鲎鲙鳣鳡鳢鲿鲚鳠鳄鲈鲡鸟凫鸠凫鸤凤鸣鸢䴓鸩鸨鸦鸰鸵鸳鸲鸮鸱鸪鸯鸭鸸鸹鸻䴕鸿鸽䴔鸺鸼鹀鹃鹆鹁鹈鹅鹄鹉鹌鹏鹐鹎雕鹊鹓鹍䴖鸫鹑鹒鹋鹙鹕鹗鹖鹛鹜䴗鸧莺鹟鹤鹠鹡鹘鹣鹚鹚鹢鹞鸡䴘鹝鹧鹥鸥鸷鹨鸶鹪鹔鹩鹫鹇鹇鹬鹰鹭鸴㶉鹯䴙鹱鹲鸬鹴鹦鹳鹂鸾卤咸鹾碱盐丽麦麸面面曲曲面么么黄黉点党黪霉黡黩黾鼋鼌鼍冬鼹齐斋赍齑齿龀龁龂龅龇龃龆龄出龈啮龊龉龋腭龌龙厐庞䶮龚龛龟䜤鿒"

    /** 繁→简单字索引（OpenCC TSCharacters 单字表，2965 对，已滤非 BMP 字符）；未收录字符原样保留 */
    private val t2sIndex by lazy {
        buildMap(T_TRAAD.length) { for (i in T_TRAAD.indices) put(T_TRAAD[i], S_SIMP[i]) }
    }

    /** 繁→简（OpenCC 单字表，未收录字符原样保留）；仅对新解析的显示名应用，存量数据不动 */
    fun toSimplified(s: String): String = buildString(s.length) {
        for (ch in s) append(t2sIndex[ch] ?: ch)
    }
}
