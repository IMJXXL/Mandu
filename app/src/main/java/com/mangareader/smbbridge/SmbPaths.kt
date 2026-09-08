package com.mangareader.smbbridge

import com.hippo.ehviewer.library.ZipAsDirListing

/**
 * SMB 路径解析（design.md 2.2.1 / spec 6.2）。
 *
 * - 书籍 filePath：`smb:{serverId}:{share}/{relativePath}`（relativePath 为**前缀空间**路径，
 *   即扫描/浏览所用的相对路径；成员后缀 `a.zip:inner/1.png` 保留原样）
 * - 封面 coverPath：`cv:smb:{serverId}:{share}/{relativePath}`
 *
 * **gateway remote 语义**（关键）：SmbGateway.listDirectory / SmbArchiveByteSource /
 * withOpenFile 内部经 resolveLocation 自动拼接 source.pathPrefix，并按 fixed-share 与否决定
 * share 取自实体还是路径首段。因此：
 * - source.share 非空（fixed-share）→ gateway remote = [SmbBookRef.rel]（去 share 段）
 * - source.share 为空（server-root 多共享）→ gateway remote = [SmbBookRef.fullRel]（首段即真实 share）
 */
data class SmbBookRef(
    val serverId: Long,
    /** `id:` 后第一段（spec 的 {share} 段；server-root 源时即真实 share 名） */
    val share: String,
    /** 去 {share} 段后的剩余路径（fixed-share 源的 gateway remote） */
    val rel: String,
    /** `id:` 后的完整路径（server-root 源的 gateway remote；缓存键用它保稳定） */
    val fullRel: String,
    /** 外层文件扩展名（小写，无点；文件夹书为空串；成员后缀不影响） */
    val ext: String,
)

object SmbPaths {
    const val SCHEME = "smb:"
    const val COVER_SCHEME = "cv:smb:"

    /** 解析 `smb:{id}:{share}/{rel}`；非法返回 null */
    fun parseBookPath(filePath: String): SmbBookRef? = parse(filePath.removePrefix(SCHEME))

    /** 解析 `cv:smb:{id}:{share}/{rel}`；非法返回 null */
    fun parseCoverPath(coverPath: String): SmbBookRef? = parse(coverPath.removePrefix(COVER_SCHEME))

    /**
     * 反向构造书籍 filePath。[relInPrefixSpace] 为扫描/浏览空间路径；
     * [share] 为空（server-root 源）时直接用 [relInPrefixSpace]（其首段即真实 share）。
     */
    fun bookPath(serverId: Long, share: String, relInPrefixSpace: String): String =
        "$SCHEME$serverId:${composeBody(share, relInPrefixSpace)}"

    /** 反向构造封面 coverPath（组合规则同 [bookPath]） */
    fun coverPath(serverId: Long, share: String, relInPrefixSpace: String): String =
        "$COVER_SCHEME$serverId:${composeBody(share, relInPrefixSpace)}"

    private fun composeBody(share: String, relInPrefixSpace: String): String {
        val s = share.trim('/')
        val r = relInPrefixSpace.trim('/')
        return if (s.isEmpty()) r else "$s/$r"
    }

    private fun parse(body: String): SmbBookRef? {
        val idPart = body.substringBefore(':').toLongOrNull() ?: return null
        val fullRel = body.substringAfter(':', "").trim('/')
        if (fullRel.isEmpty() || '/' !in fullRel) return null
        val share = fullRel.substringBefore('/')
        val rel = fullRel.substringAfter('/')
        if (rel.isEmpty()) return null
        // 外层文件名：先剥成员后缀（`books/a.zip:inner/page.png` → `books/a.zip`）再取基名
        // zip-as-dir 路径（`books/a.zip/inner/page.png`）用 zip 文件的扩展名，不是成员的
        val pathBeforeMember = fullRel.substringBefore(':')
        val zipSplit = ZipAsDirListing.splitZipBrowsePath(pathBeforeMember)
        val outerName = if (zipSplit != null && zipSplit.second.isNotEmpty()) {
            zipSplit.first.substringAfterLast('/')
        } else {
            pathBeforeMember.substringAfterLast('/')
        }
        val ext = outerName.substringAfterLast('.', "").lowercase()
        return SmbBookRef(serverId = idPart, share = share, rel = rel, fullRel = fullRel, ext = ext)
    }
}
