package com.mangareader.quarkbridge.reader

data class QuarkPathInfo(
    val fid: String,
    val name: String,
    val ext: String,
)

object QuarkPaths {
    const val SCHEME = "quark:"

    fun parsePath(filePath: String): QuarkPathInfo? {
        val body = filePath.removePrefix(SCHEME)
        val fid = body.substringBefore(':', "").trim()
        if (fid.isEmpty()) return null
        val namePart = body.substringAfter(':', "").trim()
        if (namePart.isEmpty()) return null
        val ext = namePart.substringAfterLast('.', "").lowercase()
        return QuarkPathInfo(fid = fid, name = namePart, ext = ext)
    }

    fun buildPath(fid: String, name: String): String = "$SCHEME$fid:$name"
}