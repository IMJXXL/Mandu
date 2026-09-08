package com.mangareader.webdavbridge

import com.hippo.ehviewer.library.ZipAsDirListing

/**
 * WebDAV 路径解析（design.md 2.2.1 / spec 6.2）。
 *
 * - 书籍 filePath：`webdav:{serverId}:{relativePath}`（无 share 段；relativePath 相对 baseUrl+pathPrefix）
 * - 封面 coverPath：`cv:webdav:{serverId}:{relativePath}`
 */
data class WebDavBookRef(
    val serverId: Long,
    /** 相对共享根的远程路径（可能带 `a.zip:member` 后缀） */
    val remotePath: String,
    /** 外层文件的扩展名（小写，无点；文件夹书为空串） */
    val ext: String,
)

object WebDavPaths {
    const val SCHEME = "webdav:"
    const val COVER_SCHEME = "cv:webdav:"

    fun parseBookPath(filePath: String): WebDavBookRef? = parse(filePath.removePrefix(SCHEME))

    fun parseCoverPath(coverPath: String): WebDavBookRef? = parse(coverPath.removePrefix(COVER_SCHEME))

    fun bookPath(serverId: Long, remotePath: String): String =
        "$SCHEME$serverId:${remotePath.trim('/')}"

    fun coverPath(serverId: Long, remotePath: String): String =
        "$COVER_SCHEME$serverId:${remotePath.trim('/')}"

    private fun parse(body: String): WebDavBookRef? {
        val idPart = body.substringBefore(':').toLongOrNull() ?: return null
        val rel = body.substringAfter(':', "").trim('/')
        if (rel.isEmpty()) return null
        // zip-as-dir 路径（`dir/file.zip/inner/page.png`）→ ext 用 zip 文件的扩展名
        val pathBeforeMember = rel.substringBefore(':')
        val zipSplit = ZipAsDirListing.splitZipBrowsePath(pathBeforeMember)
        val fileName = if (zipSplit != null && zipSplit.second.isNotEmpty()) {
            zipSplit.first.substringAfterLast('/')
        } else {
            pathBeforeMember.substringAfterLast('/')
        }
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return WebDavBookRef(serverId = idPart, remotePath = rel, ext = ext)
    }
}
