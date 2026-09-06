package com.mangareader.webdavbridge

import com.hippo.ehviewer.library.RemoteArchiveOpen
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WebDAV RANGE 不支持降级（design.md 2.2.2 WebDavRangeFallbackHandler / spec 5.9）。
 *
 * 服务器对 Range GET 返回 200 完整文件时（RemoteRangeNotSupportedException）：
 * 整档下载到 WebDavCache → 切换为本地 useArchivePageLoader 继续（对上层透明）。
 *
 * 简化决策（v1，记入 MODIFICATIONS.md/tasks.md）：
 * - 提示对话框（silentFallbackPref=false 时弹提示）延后：漫读无全局对话框宿主可挂，
 *   v1 始终静默降级（等价 silentFallbackPref=true 默认值，design 2.1.3.8 扩展点 6 保留）。
 * - 降级后用本地 useArchivePageLoader 打开（与 SolidExtract 失败回退同构），
 *   不引入 FileArchiveByteSource 二次适配。
 */
object WebDavRangeFallbackHandler {

    /** "不再提示直接下载"偏好（v1 恒为 true=静默；持久化开关留作扩展点） */
    @Volatile
    var silentFallbackPref: Boolean = true

    /**
     * 整档下载降级。
     * @return 本地缓存文件路径；null = 下载失败/空间不足（保留部分下载，可断点续传）
     */
    suspend fun handleRangeNotSupported(
        serverId: Long,
        remoteRelativeFile: String,
    ): String? = withContext(Dispatchers.IO) {
        val source = WebDavRepository.load(serverId) ?: return@withContext null
        val password = try { WebDavPasswordStore.get(source.id) } catch (_: Throwable) { "" }
        try {
            val local = RemoteArchiveOpen.ensureWebDavArchive(
                source = source,
                password = password,
                remoteRelativeFile = remoteRelativeFile,
                allowLarge = true,
            )
            local.path.toString()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "range fallback download failed: ${t.message}")
            null
        }
    }

    private const val TAG = "WebDavRangeFallback"
}
