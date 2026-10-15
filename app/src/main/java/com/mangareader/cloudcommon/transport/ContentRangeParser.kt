package com.mangareader.cloudcommon.transport

object ContentRangeParser {

    fun parseStart(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val s = header.trim()
        if (!s.startsWith("bytes", ignoreCase = true)) return null
        val afterUnit = s.substring(5).trimStart()
        if (afterUnit.startsWith("*")) return null
        val dash = afterUnit.indexOf('-')
        if (dash <= 0) return null
        return afterUnit.substring(0, dash).trim().toLongOrNull()
    }

    fun parseTotal(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val slash = header.lastIndexOf('/')
        if (slash < 0 || slash >= header.length - 1) return null
        val total = header.substring(slash + 1).trim()
        if (total == "*") return null
        return total.toLongOrNull()?.takeIf { it > 0L }
    }
}