package com.mangareader.util

/**
 * 漫画文件名智能解析（2026-09-30，规则移植自 chaptr 的核心思路——仅规则，非代码）：
 * `[汉化组] 进击的巨人 v03 c042 (Digital).cbz` → title=进击的巨人 volume=3 chapter=42.0
 *
 * 设计约束：
 * - 解析永远有兜底：识别不出结构时返回原文（去扩展名），绝不比旧行为差
 * - displayTitle 用于 Book.title 入库/显示：干净书名 + " v03"/" c42" 短缀 + 繁转简
 * - 原始文件名不在界面出现（Book.filePath 天然保留，长按/详情可查）
 */
object BookTitleParser {

    data class Parsed(
        val title: String,
        val volume: Int? = null,
        val volumeDec: Int? = null,
        val volumeEnd: Int? = null,
        val chapter: Double? = null,
    )

    private val COMIC_EXTS = setOf("zip", "cbz", "rar", "cbr", "7z", "cb7", "tar", "cbt", "pdf", "epub", "mobi", "azw3", "txt")

    /** 前导组名括号链：[..] (..) {..} 【..】 （..） */
    private val LEADING_BRACKETS = Regex("""^(\s*(\[[^\]]*\]|\([^)]*\)|\{[^}]*\}|【[^】]*】|（[^）]*）))+\s*""")

    /** 卷号（按优先序尝试） */
    private val VOL_PATTERNS = listOf(
        Regex("""(?i)\bv(?:ol(?:ume)?)?[\.\s]*((\d{1,4})(?:\.(\d))?)((?:\s*[-~]\s*v?\.?\s*(\d{1,4}))?)\b"""),
        Regex("""第\s*(\d{1,4})(?:\.(\d))?\s*[卷册巻]"""),
        Regex("""(?:(\d{1,4})\s*[-~]\s*)?(\d{1,4})(?:\.(\d))?\s*[卷册巻](?=\s|$)"""),
        Regex("""(\d{1,4})\s*장(?=\s|$)"""),
    )

    /** 章号 */
    private val CH_PATTERNS = listOf(
        Regex("""(?i)\b(?:chapter|chp|ch|c)[\.\s]*(\d{1,4})(?:\.(\d))?(?:\s*v\d+)?\b(?!\d)"""),
        Regex("""第\s*(\d{1,4})(?:\.(\d))?\s*[话話回]"""),
        Regex("""(\d{1,4})(?:\.(\d))?\s*[话話回](?=\s|$)"""),
    )

    /** 尾部括号链（来源/版式标记等）：(Digital) [无修正] 【汉化】 */
    private val TRAILING_BRACKETS = Regex("""(\s*(\[[^\]]*\]|\([^)]*\)|\{[^}]*\}|【[^】]*】|（[^）]*）))+\s*$""")

    /** 尾部标点 */
    private val TRAILING_PUNCT = Regex("""[\s\-._,#:·、]+$""")

    /**
     * 解析文件名（可含扩展名；目录名同样适用）。
     * volume 范围文件（v01-09）volume=1、volumeEnd=9。
     */
    fun parse(fileName: String): Parsed {
        var s = stripExt(fileName).trim()
        if (s.isEmpty()) return Parsed("")

        var group: String? = null
        println("BTP step0 s=$s")
        // 前导组名括号链（记录组名但不显示）
        LEADING_BRACKETS.find(s)?.let {
            if (it.range.first == 0 && it.value.length < s.length) {
                group = it.value
                s = s.substring(it.value.length).trim()
            }
        }

        println("BTP step1 afterLead s=$s group=$group")
        // ★ 章号先检测（章标记常带修订号 "c042 V2"——先吞掉，避免修订 V2 被卷模式误当卷号）
        var chapter: Double? = null
        var chRange: IntRange? = null
        for (pat in CH_PATTERNS) {
            val m = pat.find(s) ?: continue
            val whole = m.groupValues[1].toIntOrNull() ?: continue
            val dec = m.groups[2]?.value?.toIntOrNull()
            chapter = whole + (dec?.let { it / 10.0 } ?: 0.0)
            chRange = m.range
            break
        }

        // 卷号在"章打码"串上检测（章区间替换等长空格——卷不会命中章区间/修订号）
        var volume: Int? = null; var volumeDec: Int? = null; var volumeEnd: Int? = null; var volRange: IntRange? = null
        val masked = if (chRange != null) s.replaceRange(chRange, " ".repeat(chRange.last - chRange.first + 1)) else s
        for ((pi, pat) in VOL_PATTERNS.withIndex()) {
            val mm = pat.find(masked)
            println("BTP volTry pi=$pi pat=<" + pat.pattern + "> on=<" + masked + "> => " + (mm?.value ?: "null"))
            val m = mm ?: continue
            // 模式 0 整数在 group2（group1 含小数点）；范围前缀模式整数在 group2；其余在 group1
            val intPart = when (pi) { 0 -> m.groupValues[2]; 2 -> m.groupValues[2]; else -> m.groupValues[1] }
            println("BTP intPart=<$intPart> g1=<${m.groupValues.getOrNull(1)}> g2=<${m.groupValues.getOrNull(2)}> g3=<${m.groupValues.getOrNull(3)}>")
            volume = intPart.toIntOrNull() ?: continue
            println("BTP volAssigned pi=$pi intPart=$intPart volume=$volume")
            volumeDec = when (pi) { 0 -> m.groupValues.getOrNull(3); 2 -> m.groupValues.getOrNull(3); else -> m.groupValues.getOrNull(2) }?.toIntOrNull()
            // volumeEnd 仅真范围模式有值（pi0 的范围尾组 / pi2 的范围前缀组；第N卷无范围）
            volumeEnd = when (pi) {
                0 -> m.groupValues.getOrNull(5)?.toIntOrNull()
                2 -> m.groupValues[1].takeIf { it.isNotEmpty() }?.toIntOrNull()
                else -> null
            }
            // 防倒退范围（chaptr 规则：v356-1 → 356，不是范围）
            if (volumeEnd != null && volumeEnd < volume) volumeEnd = null
            volRange = m.range
            break
        }

        println("BTP step2 vol=$volume volEnd=$volumeEnd ch=$chapter cut candidates vol=$volRange ch=$chRange")
        // 标题切片：到第一个标记之前；无标记=整段；标记在串首 → 空串走兜底回退原名
        val cutStart = listOfNotNull(volRange, chRange).minOfOrNull { it.first }
        var title = when {
            cutStart == null -> s
            cutStart > 0 -> s.substring(0, cutStart)
            else -> ""
        }
        title = TRAILING_BRACKETS.replace(title, " ")
        title = title.trim().trimEnd('-', '_', ',', '#', ':', '·', '、').trim()
        // 点号保留（Fate.Zero / Re:Zero 类名有意义），只归一空格
        title = title.replace(Regex("[ _-]+"), " ").replace(Regex("\\s{2,}"), " ").trim()

        // 全数字标题（纯卷文件 "03.cbz"）→ 不当书名，回退原名段
        if (title.isEmpty() || title.matches(Regex("\\d+(\\.\\d)?"))) {
            title = stripExt(fileName).trim()
        }

        // ★ 裸数字回退（chaptr 规则：需有前置元数据上下文——组名括号出现过才认）：
        //   "Kaiju No. 8 036" → 书名 "Kaiju No. 8"、章 36
        if (volume == null && chapter == null && group != null) {
            val m = Regex("^(.*?\\S)\\s+(\\d{1,4})$").find(title)
            if (m != null && m.groupValues[1].contains(Regex("\\d"))) {
                title = m.groupValues[1]
                chapter = m.groupValues[2].toDouble()
            }
        }

        println("BTP final title=<$title> vol=$volume volDec=$volumeDec end=$volumeEnd ch=$chapter group=$group")
        return Parsed(title, volume, volumeDec, volumeEnd, chapter)
    }

    /**
     * 书架显示名：干净书名（繁转简）+ 卷/章短缀（v01 / c42 风格，范围 v01-09）。
     * 解析失败回退原名（去扩展名），与旧行为一致。
     */
    fun displayTitle(fileName: String): String {
        val base = stripExt(fileName).trim()
        return try {
            val p = parse(fileName)
            if (p.title.isBlank()) return toSimplified(base)
            val sb = StringBuilder(toSimplified(p.title))
            when {
                p.volume != null -> {
                    sb.append(" v").append("%02d".format(p.volume))
                    p.volumeDec?.let { sb.append(".").append(it) }
                    p.volumeEnd?.let { sb.append("-").append("%02d".format(it)) }
                }
                p.chapter != null -> {
                    sb.append(" c")
                    val whole = p.chapter.toInt()
                    val dec = p.chapter - whole
                    sb.append("%02d".format(whole))
                    if (dec > 1e-6) sb.append((".${(dec * 10).toInt()}"))
                }
            }
            sb.toString().trim()
        } catch (_: Throwable) {
            toSimplified(base)
        }
    }

    private fun stripExt(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return name
        return if (name.substring(dot + 1).lowercase() in COMIC_EXTS) name.substring(0, dot) else name
    }

    // ===== 繁转简（常用单字映射；冷僻字保持原样——映射错误比不转更糟，只收 100% 确定字） =====
    private val T2S = buildMap {
        val pairs = listOf(
            "們" to "们", "進" to "进", "擊" to "击", "書" to "书", "畫" to "画", "劇" to "剧", "傳" to "传", "說" to "说", "語" to "语", "話" to "话",
            "詩" to "诗", "誰" to "谁", "請" to "请", "調" to "调", "謝" to "谢", "談" to "谈", "識" to "识", "證" to "证",
            "訪" to "访", "設" to "设", "訓" to "训", "議" to "议", "認" to "认", "變" to "变", "讓" to "让", "靈" to "灵",
            "愛" to "爱", "無" to "无", "雙" to "双", "劍" to "剑", "龍" to "龙", "鳳" to "凤", "國" to "国", "會" to "会",
            "來" to "来", "時" to "时", "間" to "间", "門" to "门", "馬" to "马", "鳥" to "鸟", "點" to "点", "頭" to "头",
            "髮" to "发", "電" to "电", "術" to "术", "處" to "处", "裡" to "里", "後" to "后", "臺" to "台", "灣" to "湾",
            "歷" to "历", "豐" to "丰", "縣" to "县", "藝" to "艺", "藥" to "药", "蘭" to "兰", "廳" to "厅", "氣" to "气",
            "漢" to "汉", "錄" to "录", "體" to "体", "貨" to "货", "購" to "购", "貴" to "贵", "質" to "质", "賽" to "赛",
            "車" to "车", "軍" to "军", "輪" to "轮", "軟" to "软", "載" to "载", "輕" to "轻", "較" to "较", "軌" to "轨",
            "麗" to "丽", "義" to "义", "樂" to "乐", "機" to "机", "構" to "构", "標" to "标", "橋" to "桥", "檔" to "档",
            "檢" to "检", "欄" to "栏", "歡" to "欢", "殘" to "残", "燈" to "灯", "獸" to "兽", "獵" to "猎", "貓" to "猫",
            "豬" to "猪", "雞" to "鸡", "鴨" to "鸭", "鷹" to "鹰", "槍" to "枪", "彈" to "弹", "戰" to "战", "敵" to "敌",
            "劃" to "划", "鏡" to "镜", "鐵" to "铁", "銀" to "银", "錢" to "钱", "鑽" to "钻", "環" to "环",
            "禮" to "礼", "祕" to "秘", "屬" to "属", "歲" to "岁", "萬" to "万", "億" to "亿", "圓" to "圆",
            "園" to "园", "圖" to "图", "團" to "团", "圍" to "围", "園" to "园", "壓" to "压", "縮" to "缩", "擊" to "击",
            "報" to "报", "場" to "场", "壞" to "坏", "塊" to "块", "聲" to "声", "殼" to "壳", "夢" to "梦",
            "婦" to "妇", "寶" to "宝", "實" to "实", "寫" to "写", "寬" to "宽", "廢" to "废", "強" to "强",
            "歸" to "归", "當" to "当", "錯" to "错", "鍵" to "键", "鎖" to "锁", "鎮" to "镇", "鏈" to "链", "關" to "关",
            "難" to "难", "雲" to "云", "電" to "电", "靜" to "静", "頁" to "页", "項" to "项", "順" to "顺", "須" to "须",
            "顏" to "颜", "願" to "愿", "風" to "风", "飛" to "飞", "養" to "养", "飲" to "饮", "館" to "馆",
            "馬" to "马", "馳" to "驰", "驚" to "惊", "魚" to "鱼", "鮮" to "鲜", "鳴" to "鸣", "黃" to "黄",
            "齊" to "齐", "龜" to "龟", "劑" to "剂", "區" to "区", "醫" to "医", "華" to "华", "協" to "协",
            "單" to "单", "嚴" to "严", "喬" to "乔", "喚" to "唤", "圓" to "圆", "壽" to "寿", "奮" to "奋", "婁" to "娄",
            "學" to "学", "憶" to "忆", "懷" to "怀", "應" to "应", "戲" to "戏", "擁" to "拥", "據" to "据", "擇" to "择",
            "攔" to "拦", "敗" to "败", "斷" to "断", "舊" to "旧", "晝" to "昼", "曆" to "历", "書" to "书",
            "東" to "东", "棄" to "弃", "榮" to "荣", "樂" to "乐", "櫻" to "樱", "歐" to "欧", "歸" to "归", "毀" to "毁",
            "淚" to "泪", "淨" to "净", "準" to "准", "溝" to "沟", "滅" to "灭", "滿" to "满", "潛" to "潜",
            "營" to "营", "爺" to "爷", "獨" to "独", "瑪" to "玛", "異" to "异", "發" to "发", "盜" to "盗",
            "眾" to "众", "確" to "确", "離" to "离", "稱" to "称", "穩" to "稳", "窮" to "穷", "競" to "竞",
            "筆" to "笔", "簡" to "简", "類" to "类", "級" to "级", "緒" to "绪", "紅" to "红", "約" to "约", "級" to "级",
            "純" to "纯", "細" to "细", "織" to "织", "續" to "续", "蘇" to "苏", "術" to "术", "裝" to "装",
            "製" to "制", "複" to "复", "親" to "亲", "覺" to "觉", "觀" to "观", "觸" to "触", "記" to "记", "訪" to "访",
            "許" to "许", "詠" to "咏", "試" to "试", "話" to "话", "誘" to "诱", "護" to "护", "豐" to "丰", "買" to "买",
            "費" to "费", "賭" to "赌", "跡" to "迹", "轉" to "转", "農" to "农", "邊" to "边", "鄉" to "乡", "醜" to "丑",
            "鋼" to "钢", "錶" to "表", "開" to "开", "陽" to "阳", "雙" to "双", "雖" to "虽", "餘" to "余",
            "館" to "馆", "鬥" to "斗", "鹹" to "咸", "麥" to "麦", "齣" to "出", "龐" to "庞",
        )
        for ((t, sc) in pairs) if (t.length == 1) put(t[0], sc[0])
    }

    /** 繁→简（逐常用字映射，未收录字符原样保留）；仅对新解析的显示名应用，存量数据不动 */
    fun toSimplified(s: String): String = buildString(s.length) {
        for (ch in s) append(T2S[ch] ?: ch)
    }
}
