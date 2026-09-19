p = r'app/src/main/java/com/mangareader/smbbridge/SmbCoverLoader.kt'
src = open(p, 'rb').read().decode('utf-8')

BS = chr(92)  # backslash

# 1) imports
if 'import com.hippo.ehviewer.library.isTarArchiveFileName' not in src:
    old_imp = 'import com.hippo.ehviewer.library.isDocumentFileName\n'
    new_imp = ('import com.hippo.ehviewer.library.isDocumentFileName\n'
               'import com.hippo.ehviewer.library.isTarArchiveFileName\n'
               'import com.hippo.ehviewer.library.isZipArchiveFileName\n')
    assert old_imp in src, 'imp'
    src = src.replace(old_imp, new_imp, 1)

# 2) internal dispatch passes wantRaw
old_call = '            loadCoverByArchiveCache(ref) ?: loadCoverByTransientSession(ref)\n'
new_call = '            loadCoverByArchiveCache(ref, wantRaw) ?: loadCoverByTransientSession(ref, wantRaw)\n'
assert old_call in src, 'call'
src = src.replace(old_call, new_call, 1)

# 3) archive branch: signature
old_arc_sig = ('    private suspend fun loadCoverByArchiveCache(ref: SmbBookRef): Pair<ByteArray, String>? {\n'
               '        if (ref.ext.isEmpty()) return null\n')
new_arc_sig = ('    private suspend fun loadCoverByArchiveCache(ref: SmbBookRef, wantRaw: Boolean): Pair<ByteArray, String>? {\n'
               '        if (ref.ext.isEmpty()) return null\n')
assert old_arc_sig in src, 'arc sig'
src = src.replace(old_arc_sig, new_arc_sig, 1)

# 4) archive branch: anim gate before diskHit (anchor without backslashes)
old_gate = ('        val diskHit = ArchiveCoverCache.tryDiskCover(cacheKey)\n'
            '        if (diskHit != null) return readJpegBytes(diskHit)\n')
new_gate = (
    "        // ★ 动图首页分流（wantRaw=true）：副档动图 → 原始字节（Coil 平台解码按请求尺寸\n"
    "        //   播放）；探测确认静图 → 标记后走既有 JPEG 物化路径。每封面至多探测一次。\n"
    "        if (wantRaw && !com.mangareader.viewer.CoverAnimRawCache.isStatic(cacheKey)) {\n"
    "            com.mangareader.viewer.CoverAnimRawCache.readRaw(cacheKey)\n"
    "                ?.let { return Pair(it, com.mangareader.viewer.CoverAnimRawCache.mimeOf(it)) }\n"
    "            val probeable = isZipArchiveFileName(name) || isTarArchiveFileName(name) || isSolidArchiveFileName(name)\n"
    "            if (probeable) {\n"
    "                fetchFirstPageRaw(ref)?.let { raw ->\n"
    "                    if (com.mangareader.viewer.ArchiveEngine.isAnimatedBytes(raw)) {\n"
    "                        com.mangareader.viewer.CoverAnimRawCache.writeRaw(cacheKey, raw)\n"
    "                        return Pair(raw, com.mangareader.viewer.CoverAnimRawCache.mimeOf(raw))\n"
    "                    }\n"
    "                    com.mangareader.viewer.CoverAnimRawCache.markStatic(cacheKey)\n"
    "                }\n"
    "            } else {\n"
    "                com.mangareader.viewer.CoverAnimRawCache.markStatic(cacheKey)\n"
    "            }\n"
    "        }\n\n"
    + old_gate)
assert old_gate in src, 'gate'
src = src.replace(old_gate, new_gate, 1)

# 5) transient branch: signature + anim gate
old_tr = ('    private suspend fun loadCoverByTransientSession(ref: SmbBookRef): Pair<ByteArray, String>? {\n'
          '        val remoteFile = ref.fullRel.substringBefore(\':\')\n'
          '        val cached = SmbCache.thumbCachePath(ref.serverId, remoteFile)\n')
new_tr = ('    private suspend fun loadCoverByTransientSession(ref: SmbBookRef, wantRaw: Boolean): Pair<ByteArray, String>? {\n'
          '        val remoteFile = ref.fullRel.substringBefore(\':\')\n'
          '        // ★ 动图首页分流（wantRaw=true，文件夹书）：同归档分支\n'
          '        val animKey = "smb:$' + '{ref.serverId}:$remoteFile"\n'
          '        if (wantRaw && !com.mangareader.viewer.CoverAnimRawCache.isStatic(animKey)) {\n'
          '            com.mangareader.viewer.CoverAnimRawCache.readRaw(animKey)\n'
          '                ?.let { return Pair(it, com.mangareader.viewer.CoverAnimRawCache.mimeOf(it)) }\n'
          '            fetchFirstPageRaw(ref)?.let { raw ->\n'
          '                if (com.mangareader.viewer.ArchiveEngine.isAnimatedBytes(raw)) {\n'
          '                    com.mangareader.viewer.CoverAnimRawCache.writeRaw(animKey, raw)\n'
          '                    return Pair(raw, com.mangareader.viewer.CoverAnimRawCache.mimeOf(raw))\n'
          '                }\n'
          '                com.mangareader.viewer.CoverAnimRawCache.markStatic(animKey)\n'
          '            }\n'
          '        }\n'
          '        val cached = SmbCache.thumbCachePath(ref.serverId, remoteFile)\n')
assert old_tr in src, 'tr'
src = src.replace(old_tr, new_tr, 1)

# 6) fetchFirstPageRaw helper before readJpegBytes
old_helper = '    private fun readJpegBytes(path: Path): Pair<ByteArray, String>? {'
new_helper = (
    '    /**\n'
    '     * 临时会话取第一页原始字节（动图探测共用）；失败返回 null 不标记，下次重试。\n'
    '     */\n'
    '    private suspend fun fetchFirstPageRaw(ref: SmbBookRef): ByteArray? = try {\n'
    '        SmbCache.withBrowseThumbFetchSlot {\n'
    '            SmbBookRouter.openTransient(ref) { _, adapter -> adapter.loadPageRaw(0) }\n'
    '        }\n'
    '    } catch (e: CancellationException) {\n'
    '        throw e\n'
    '    } catch (_: Throwable) {\n'
    '        null\n'
    '    }\n\n'
    + old_helper)
assert old_helper in src, 'helper'
src = src.replace(old_helper, new_helper, 1)

open(p, 'wb').write(src.encode('utf-8'))
print('SmbCoverLoader patched')
