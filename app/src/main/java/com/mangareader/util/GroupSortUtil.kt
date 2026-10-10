package com.mangareader.util

import com.mangareader.zip.CommonsZipHelper

object GroupSortUtil {

    fun classifyGroup(simplifiedName: String, config: GroupSortConfig): Int {
        for (i in config.groups.indices) {
            val group = config.groups[i]
            for (kw in group.keywords) {
                if (kw.isNotEmpty() && simplifiedName.contains(kw, ignoreCase = true)) return i
            }
        }
        if (simplifiedName.isNotEmpty() && simplifiedName.last().isDigit()) {
            val chapterIdx = config.groups.indexOfFirst { it.name == "章节" }
            if (chapterIdx >= 0) return chapterIdx
        }
        return config.groups.size
    }

    fun <T> sortItems(
        items: List<T>,
        config: GroupSortConfig,
        getName: (T) -> String,
        isDir: (T) -> Boolean,
    ): List<T> {
        if (!config.enabled) {
            return items.sortedWith(
                compareByDescending<T> { isDir(it) }
                    .thenComparing(getName) { a, b -> CommonsZipHelper.compareNatural(a, b) }
            )
        }
        val groupCache = HashMap<String, Int>(items.size)
        for (item in items) {
            val name = getName(item)
            if (name !in groupCache) {
                groupCache[name] = classifyGroup(BookTitleParser.toSimplified(name), config)
            }
        }
        return items.sortedWith { a, b ->
            if (config.folderFirst) {
                val dA = isDir(a); val dB = isDir(b)
                if (dA && !dB) return@sortedWith -1
                if (!dA && dB) return@sortedWith 1
            }
            val gA = groupCache[getName(a)]!!
            val gB = groupCache[getName(b)]!!
            if (gA != gB) gA - gB
            else CommonsZipHelper.compareNatural(getName(a), getName(b))
        }
    }
}