package com.mangareader.native

import android.graphics.Bitmap

/**
 * ★ SMB/WebDAV 页面供给抽象（smb_embed_lv119/design.md 2.2.2 "页面供给抽象"选项）。
 *
 * 阅读视图在 [PagerReaderView]/[WebtoonReaderView] 持有非 null 的供给时，
 * 页面解析改走远程会话（参考项目 PageLoader 体系），完全绕开 ArchiveEngine
 * （ArchiveEngine 对 smb:/webdav: 路径有 require 守卫，本地行为零改动）。
 *
 * - [loadPageRaw] 供给原始字节：PagerReaderView 拿到后仍走自身 Coil3 解码 +
 *   动图识别 / 裁边 / 超分 / SSIV 全管线。
 * - [loadBitmap] 供给解码位图：WebtoonReaderView 直接消费（内部 BitmapFactory 解码）。
 *
 * 实现方：com.mangareader.smbbridge.SmbPageAdapter / com.mangareader.webdavbridge.WebDavPageAdapter。
 */
interface RemotePageSupply {
    /** 页数（与阅读器 pageList 一致） */
    val pageCount: Int

    /** 页数增长回调列表；supply 在 loadPageRaw 中检测到 loader.size 增长时逐个调用 */
    val onPageCountGrow: MutableList<(newCount: Int) -> Unit>

    /** 原始页面字节；null = 该页暂不可用（阅读器按加载失败占位处理，不回退 ArchiveEngine） */
    suspend fun loadPageRaw(index: Int): ByteArray?

    /** 解码后的位图；null = 该页暂不可用 */
    suspend fun loadBitmap(index: Int): Bitmap?

    /** 释放远程会话（close loader/source）；阅读器 release 时调用 */
    suspend fun close()
}
