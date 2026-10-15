package com.mangareader.bridge115.reader

data class PathInfo115(
    val mode: String,
    val pickCode: String,
    val name: String,
    val ext: String,
)

object Paths115 {
    const val SCHEME = "115:"

    fun parsePath(filePath: String): PathInfo115? {
        val body = filePath.removePrefix(SCHEME)
        val mode = body.substringBefore(':', "").trim()
        if (mode.isEmpty()) return null
        val rest = body.substringAfter(':', "").trim()
        if (rest.isEmpty()) return null
        val pickCode = rest.substringBefore(':', "").trim()
        if (pickCode.isEmpty()) return null
        val name = rest.substringAfter(':', "").trim()
        if (name.isEmpty()) return null
        val ext = name.substringAfterLast('.', "").lowercase()
        return PathInfo115(mode = mode, pickCode = pickCode, name = name, ext = ext)
    }

    fun buildPath(mode: String, pickCode: String, name: String): String = "$SCHEME$mode:$pickCode:$name"
}