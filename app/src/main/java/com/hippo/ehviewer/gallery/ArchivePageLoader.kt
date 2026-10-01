/*
 * Copyright 2023-2024 Tarsin Norbin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.gallery

import arrow.autoCloseScope
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.Settings.archivePasswdFront
import com.hippo.ehviewer.Settings.archivePasswds
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.byteBufferSource
import com.hippo.ehviewer.jni.closeArchive
import com.hippo.ehviewer.jni.extractToByteBuffer
import com.hippo.ehviewer.jni.extractToFd
import com.hippo.ehviewer.jni.getArchiveFilename
import com.hippo.ehviewer.jni.getExtension
import com.hippo.ehviewer.jni.needPassword
import com.hippo.ehviewer.jni.openArchive
import com.hippo.ehviewer.jni.providePassword
import com.hippo.ehviewer.jni.releaseByteBuffer
import com.hippo.ehviewer.library.ArchiveAccess
import com.hippo.ehviewer.library.ArchiveCoverCache
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.isZipArchiveFileName
import com.hippo.ehviewer.util.displayName
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import moe.tarsin.kt.install
import okio.Path

typealias PasswdInvalidator = (String) -> Boolean
typealias PasswdProvider = suspend (PasswdInvalidator) -> String

/**
 * ★ 记忆密码自动尝试（0930）：先试最近命中密码（front，"中的放前边"），再遍历整个列表，
 *   逐个交给 native 验证（providePassword = 设密码 + 重建探测会话）。命中返回 true——
 *   native passwd 已就位，当前打开流程可直接继续取内容。未加密包不会走到这里，零开销。
 *   三个加载器（本地/Solid/流式）与封面提取共用本函数，记忆库同一份（Settings.archivePasswds）。
 */
fun archivePasswdTryKnown(): Boolean {
    val front = archivePasswdFront
    // front 必须仍在库内才试：用户经编辑器删除的密码不允许继续被自动尝试
    if (front.isNotEmpty() && front in archivePasswds && providePassword(front)) return true
    val hit = archivePasswds.firstOrNull { it.isNotEmpty() && it != front && providePassword(it) } ?: return false
    archivePasswdFront = hit
    return true
}

/** Library metadata must finish even if the reader destination is replaced. */
// Public inline [useArchivePageLoader] inlines at call sites; property must be @PublishedApi.
@PublishedApi
internal val archiveMetadataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

suspend inline fun <T> useArchivePageLoader(
    file: Path,
    info: GalleryInfo? = null,
    startPage: Int = 0,
    hasAds: Boolean = false,
    memberPrefix: String = "",
    imageNames: List<String> = emptyList(),
    crossinline passwdProvider: PasswdProvider,
    crossinline block: suspend (PageLoader) -> T,
) = ArchiveAccess.withArchive {
    autoCloseScope {
        coroutineScope {
            val pfd = install(file.openFileDescriptor("r"))
            // sortEntries: EH downloads with real gallery info keep pack order; local / zip sort by name.
            // Must not key only on info==null 鈥?local archives now pass GalleryInfo for read progress.
            val sortEntries = info == null ||
                isZipArchiveFileName(file.name) ||
                info.token == com.hippo.ehviewer.library.LOCAL_GALLERY_TOKEN ||
                info.token == com.hippo.ehviewer.library.LOCAL_ARCHIVE_TOKEN ||
                info.token == com.hippo.ehviewer.library.LOCAL_FOLDER_TOKEN ||
                info.token == com.hippo.ehviewer.library.SMB_ARCHIVE_TOKEN ||
                info.token == com.hippo.ehviewer.library.WEBDAV_ARCHIVE_TOKEN
            val opened = install(
                { openArchive(pfd.fd, pfd.statSize, sortEntries) },
                { _, _ -> closeArchive() },
            )
            check(opened > 0) { "Archive have no content!" }
            if (needPassword() && !archivePasswdTryKnown()) {
                val pwd = passwdProvider(::providePassword)
                archivePasswds += pwd
                archivePasswdFront = pwd
            }
            val indexMap = if (imageNames.isEmpty() && memberPrefix.isEmpty()) {
                null
            } else {
                val names = List(opened) { getArchiveFilename(it) }
                nativeIndicesForZipFolder(memberPrefix, imageNames, names)
                    .takeIf { it.isNotEmpty() }
            }
            val size = indexMap?.size ?: opened
            check(size > 0) { "Archive have no content!" }
            val nat: (Int) -> Int = { index -> indexMap?.getOrNull(index) ?: index }
            // Serialize JNI extract; libarchive is process-global and not MT-safe.
            val extractLock = Any()
            val pathStr = file.toString()
            val f = File(pathStr)
            val mtime = f.takeIf { it.isFile }?.lastModified() ?: 0L
            val len = f.takeIf { it.isFile }?.length() ?: 0L
            val loader = install(
                object : PageLoader(this, info, startPage, size, hasAds, decodeImages = false) {
                    override val title by lazy {
                        if (info != null) {
                            info.title ?: ""
                        } else {
                            // Full filename incl. extension (archive/pdf, not folder).
                            file.displayName.ifEmpty { file.name }
                        }
                    }

                    override fun getImageExtension(index: Int) = getExtension(nat(index))

                    override fun savePage(index: Int, file: Path) = runCatching {
                        file.openFileDescriptor("w").use {
                            synchronized(extractLock) {
                                extractToFd(nat(index), it.fd)
                            }
                        }
                    }.getOrElse {
                        logcat(it)
                        false
                    }

                    override fun openSource(index: Int): ImageSource {
                        val nativeIndex = nat(index)
                        val native = synchronized(extractLock) {
                            extractToByteBuffer(nativeIndex)
                        }
                        if (native != null) {
                            check(native.isDirect)
                            // Heap copy so sibling closeArchive / cancel cannot UAF Coil on
                            // DefaultDispatcher (mmap or native malloc still in flight).
                            val bytes = ByteArray(native.remaining())
                            native.duplicate().get(bytes)
                            releaseByteBuffer(native)
                            return byteBufferSource(ByteBuffer.wrap(bytes)) {}
                        }
                        // 鈽?fallback: libarchive extractToByteBuffer 澶辫触锛堟煇浜?ZIP entry 鍘嬬缉鏂规硶涓嶆敮鎸侊級锛?                        //   鐢?java.util.zip.ZipFile 鎸?entry name 闅忔満鎻愬彇锛堟敮鎸?ZIP64/deflate/store 绛夛級
                        val entryName = getArchiveFilename(nativeIndex)
                        val fallbackBytes = try {
                            java.util.zip.ZipFile(f).use { zf ->
                                val entry = zf.getEntry(entryName)
                                if (entry != null) zf.getInputStream(entry).use { it.readBytes() } else null
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("ArchivePageLoader", "ZipFile fallback failed for $entryName: ${e.message}")
                            null
                        }
                        if (fallbackBytes != null) {
                            android.util.Log.i("ArchivePageLoader", "ZipFile fallback OK for $entryName size=${fallbackBytes.size}")
                            return byteBufferSource(ByteBuffer.wrap(fallbackBytes)) {}
                        }
                        throw IllegalStateException("Extract archive content $index (name=$entryName) failed!")
                    }

                    override fun prefetchPages(pages: List<Int>, bounds: IntRange) = Unit

                    override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) {
                        notifySourceReady(index, orgImg)
                    }
                },
            )
            // Page count (and any existing cover) independent of cover encode path.
            // DAO COALESCE keeps a null cover from wiping a prior thumb path.
            archiveMetadataScope.launch {
                updateLocalArchiveLibraryCover(
                    pathStr = pathStr,
                    mtime = mtime,
                    len = len,
                    pageCount = size,
                    info = info,
                )
            }
            block(loader)
        }
    }
}

/**
 * Map zip-as-dir gallery pages onto mmap archive indices.
 * [imageNames] are basenames (or relative names) from the CD listing; [nativeNames]
 * are libarchive paths. Prefer exact prefix, then same-depth basename (folder-name
 * encoding may differ), then basename anywhere.
 */
@PublishedApi
internal fun nativeIndicesForZipFolder(
    innerRel: String,
    imageNames: List<String>,
    nativeNames: List<String>,
): IntArray {
    if (nativeNames.isEmpty()) return IntArray(0)
    val prefix = innerRel.replace('\\', '/').trim('/')
    if (imageNames.isEmpty() && prefix.isEmpty()) return IntArray(nativeNames.size) { it }
    val allow = LinkedHashSet(
        imageNames.map { it.replace('\\', '/').substringAfterLast('/') },
    )
    val expectedSlashes = if (prefix.isEmpty()) 0 else prefix.count { it == '/' } + 1
    fun norm(n: String) = n.replace('\\', '/').trimStart('/')
    fun base(n: String) = norm(n).substringAfterLast('/')
    fun slashes(n: String) = norm(n).count { it == '/' }

    fun allowed(n: String) = allow.isEmpty() || base(n) in allow || norm(n) in allow
    val exact = if (prefix.isEmpty()) {
        nativeNames.mapIndexedNotNull { i, n ->
            val path = norm(n)
            if ('/' !in path && allowed(n)) i else null
        }
    } else {
        val p = "$prefix/"
        nativeNames.mapIndexedNotNull { i, n ->
            val path = norm(n)
            if (path.startsWith(p) && '/' !in path.removePrefix(p) && allowed(n)) i else null
        }
    }
    if (allow.isEmpty() && exact.isNotEmpty()) return exact.toIntArray()
    if (exact.size == imageNames.size) return exact.toIntArray()

    val atDepth = nativeNames.mapIndexedNotNull { i, n ->
        if (slashes(n) == expectedSlashes && allowed(n)) i else null
    }
    if (allow.isEmpty() && atDepth.isNotEmpty()) return atDepth.toIntArray()
    if (atDepth.size == imageNames.size) return atDepth.toIntArray()
    if (exact.isNotEmpty()) return exact.toIntArray()
    if (atDepth.isNotEmpty()) return atDepth.toIntArray()

    val byBase = nativeNames.mapIndexedNotNull { i, n -> if (allowed(n)) i else null }
    return if (byBase.isNotEmpty()) byBase.toIntArray() else IntArray(nativeNames.size) { it }
}

suspend fun updateLocalArchiveLibraryCover(
    pathStr: String,
    mtime: Long,
    len: Long,
    pageCount: Int,
    info: GalleryInfo?,
    coverStr: String? = null,
) {
    // Called either from the reader for the initial count or from the application-owned
    // cover encoder for the final path. The latter must survive reader navigation.
    try {
        val resolved = coverStr
            ?: ArchiveCoverCache.resolveCoverDest(pathStr, mtime, len)
                .takeIf { ArchiveCoverCache.isCachedOnDisk(it) }
                ?.toString()
        val gid = info?.gid
        withIOContext {
            if (gid != null && gid != 0L) {
                LocalLibrary.updateGalleryPageAndCover(gid, pageCount, resolved)
            } else {
                LocalLibrary.updateGalleryPageAndCoverByContentPath(pathStr, pageCount, resolved)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logcat("ArchiveCover", e)
    }
}
