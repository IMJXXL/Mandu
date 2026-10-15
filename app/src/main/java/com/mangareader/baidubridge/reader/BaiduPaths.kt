package com.mangareader.baidubridge.reader

data class BaiduPathInfo(
    val path: String,
    val name: String,
    val ext: String,
)

object BaiduPaths {
    const val SCHEME = "baidu:"

    fun parsePath(filePath: String): BaiduPathInfo? {
        val body = filePath.removePrefix(SCHEME)
        val path = body.substringBeforeLast(':', "").trim()
        if (path.isEmpty()) return null
        val namePart = body.substringAfterLast(':', "").trim()
        if (namePart.isEmpty()) return null
        val ext = namePart.substringAfterLast('.', "").lowercase()
        return BaiduPathInfo(path = path, name = namePart, ext = ext)
    }

    fun buildPath(path: String, name: String): String = "$SCHEME$path:$name"
}