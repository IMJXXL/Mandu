package com.mangareader.util

import com.mangareader.zip.CommonsZipHelper
import java.text.Collator
import java.util.Locale

/**
 * 统一排序工具 - 漫画文件名排序的唯一真相源
 * 规则：路径深度优先 → 自然排序 → Collator(CHINA)中文平局决胜
 */
object ComicSortUtil {

    private val chineseCollator: Collator = Collator.getInstance(Locale.CHINA).apply {
        strength = Collator.PRIMARY
    }

    /**
     * 计算路径深度（斜杠数量）
     */
    fun getPathDepth(path: String): Int {
        var count = 0
        for (c in path) {
            if (c == '/' || c == '\\') count++
        }
        return count
    }

    /**
     * 统一排序比较器：先按路径深度，再按自然排序，最后Collator中文平局决胜
     */
    val fileNameComparator: Comparator<String> = Comparator { a, b ->
        val depthA = getPathDepth(a)
        val depthB = getPathDepth(b)
        if (depthA != depthB) depthA - depthB
        else {
            val naturalResult = CommonsZipHelper.compareNatural(a, b)
            if (naturalResult != 0) naturalResult
            else chineseCollator.compare(a, b)
        }
    }

    /**
     * 对条目名称列表排序，返回排序后的原始索引
     */
    fun sortIndices(names: List<String>): List<Int> {
        return names.mapIndexed { index, name -> Pair(name, index) }
            .sortedWith(Comparator { a, b ->
                val depthA = getPathDepth(a.first)
                val depthB = getPathDepth(b.first)
                if (depthA != depthB) depthA - depthB
                else {
                    val naturalResult = CommonsZipHelper.compareNatural(a.first, b.first)
                    if (naturalResult != 0) naturalResult
                    else chineseCollator.compare(a.first, b.first)
                }
            })
            .map { it.second }
    }
}
