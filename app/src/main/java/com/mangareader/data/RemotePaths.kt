package com.mangareader.data

/**
 * ★ 远程书路径身份归一（2026-10-06 拆分缓存/进度"时灵时不灵"工单）。
 *
 * 日志实锤（漫读全部日志_1006-153624）：同一本书多次打开，恢复进度在 8/14/23/28/34 之间
 * 跳变（save 每页都存、读回的却是更早会话的值）——同书存在多个 path 字符串身份
 * （书架 filePath / 浏览器 item.doc / 切卷 subPath / 继续阅读 jumpPath 之间的斜杠与
 * 前缀形式差异），而探测缓存键 md5(path) 与进度键 path 直接吃这个字符串 → 每种身份
 * 一份缓存一份进度，交替被读到 → "第二次进能拆，划一划切回又不能拆"。
 *
 * 归一规则（只动远程两族，本地路径原样返回零影响）：
 * - smb:{id}:{share}/{rel} → parse 后用 fullRel 重组（trim 首尾斜杠）
 * - webdav:{id}:{rel}      → parse 后用 remotePath 重组（trim 首尾斜杠）
 * - 全串连续斜杠压缩为单斜杠（含首尾 trim；member 后缀 a.zip:inner/x.png 不受影响）
 * - 解析失败/非远程路径 → 原样返回（宽容：烂串永不至于打不开书，只是退回旧行为）
 *
 * 接入点：PageSizeCacheStore 读写入口（探测缓存单点统一）+ Reader 进度读写（读归一
 * 优先、裸键兜底渐进迁移，写恒归一）。
 */
object RemotePaths {

    fun normalizeBookKey(p: String): String {
        if (p.isEmpty()) return p
        val parsed = when {
            p.startsWith("smb:") -> com.mangareader.smbbridge.SmbPaths.parseBookPath(p)
                ?.let { "smb:${it.serverId}:${it.fullRel}" }
            p.startsWith("webdav:") -> com.mangareader.webdavbridge.WebDavPaths.parseBookPath(p)
                ?.let { "webdav:${it.serverId}:${it.remotePath}" }
            else -> null
        } ?: return p
        return collapseSlashes(parsed)
    }

    /** 连续斜杠压单 + 去首尾；member 后缀（`a.zip:inner/x.png`）同样受益，语义无损 */
    fun collapseSlashes(p: String): String =
        p.replace(Regex("/+"), "/").trim('/')
}
