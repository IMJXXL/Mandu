package com.mangareader.baidubridge.api

import com.mangareader.baidubridge.BaiduConstants
import com.mangareader.baidubridge.auth.BaiduLoginFlow
import com.mangareader.baidubridge.auth.BaiduSessionStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

class BaiduApiClient(
    val httpClient: HttpClient = BaiduHttpClient.client(),
    val rateLimiter: BaiduRateLimiter = BaiduRateLimiter(),
    private val sessionStore: BaiduSessionStore? = null,
    private val loginFlow: BaiduLoginFlow? = null
) {
    private fun accessToken(): String {
        val session = sessionStore?.load() ?: throw BaiduAuthException("登录已失效，请重新授权")
        return session.accessToken
    }

    private suspend fun ensureAccessToken(): String {
        return loginFlow?.ensureToken() ?: accessToken()
    }

    private fun checkErrno(errno: Int, msg: String) {
        when (errno) {
            0 -> return
            -6, 110, 31045 -> throw BaiduAuthException("登录已失效，请重新授权")
            31066 -> {
                rateLimiter.suspendFor(30)
                throw BaiduRateLimitException("请求过于频繁，请稍后再试")
            }
            31119, 31329 -> throw BaiduApiException(errno, "账号风控，请稍后再试")
            -9 -> throw BaiduApiException(errno, "文件不存在")
            else -> throw BaiduApiException(errno, msg)
        }
    }

    suspend fun listDir(dir: String, start: Int = 0, limit: Int = BaiduConstants.PAGE_SIZE): List<BaiduFileEntry> {
        val token = ensureAccessToken()
        rateLimiter.acquire()
        val response = httpClient.get(BaiduConstants.API_FILE) {
            parameter("method", "list")
            parameter("access_token", token)
            parameter("dir", dir)
            parameter("order", "time")
            parameter("desc", "1")
            parameter("start", start.toString())
            parameter("limit", limit.toString())
            parameter("web", "1")
            header("User-Agent", BaiduConstants.BAIDU_UA)
        }
        val json = response.body<JsonObject>()
        val errno = json["errno"]?.jsonPrimitive?.intOrNull ?: -1
        if (errno == -6 || errno == 110 || errno == 31045) {
            val newToken = loginFlow?.refresh()?.accessToken
            if (newToken != null) {
                return listDirRetry(dir, start, limit, newToken)
            }
            throw BaiduAuthException("登录已失效，请重新授权")
        }
        val msg = json["errmsg"]?.jsonPrimitive?.contentOrNull ?: "未知错误"
        checkErrno(errno, msg)
        val list = json["list"] as? JsonArray ?: return emptyList()
        val entries = list.map { it.jsonObject.toFileEntry() }
        return entries.sortedWith(compareByDescending<BaiduFileEntry> { it.isDir }.thenByDescending { it.mtime })
    }

    private suspend fun listDirRetry(dir: String, start: Int, limit: Int, token: String): List<BaiduFileEntry> {
        rateLimiter.acquire()
        val response = httpClient.get(BaiduConstants.API_FILE) {
            parameter("method", "list")
            parameter("access_token", token)
            parameter("dir", dir)
            parameter("order", "time")
            parameter("desc", "1")
            parameter("start", start.toString())
            parameter("limit", limit.toString())
            parameter("web", "1")
            header("User-Agent", BaiduConstants.BAIDU_UA)
        }
        val json = response.body<JsonObject>()
        val errno = json["errno"]?.jsonPrimitive?.intOrNull ?: -1
        val msg = json["errmsg"]?.jsonPrimitive?.contentOrNull ?: "未知错误"
        checkErrno(errno, msg)
        val list = json["list"] as? JsonArray ?: return emptyList()
        return list.map { it.jsonObject.toFileEntry() }
            .sortedWith(compareByDescending<BaiduFileEntry> { it.isDir }.thenByDescending { it.mtime })
    }

    suspend fun fsIdOfPath(path: String): Long {
        val parentDir = path.substringBeforeLast('/', "/")
        val targetName = path.substringAfterLast('/')
        var start = 0
        while (true) {
            val entries = listDir(parentDir, start)
            if (entries.isEmpty()) break
            val match = entries.find { it.name == targetName }
            if (match != null) return match.fsId
            start += BaiduConstants.PAGE_SIZE
        }
        throw BaiduApiException(-9, "文件不存在: $path")
    }

    suspend fun dlink(path: String): BaiduDownloadInfo {
        val token = ensureAccessToken()
        rateLimiter.acquire()
        val response = httpClient.get(BaiduConstants.API_MULTIMEDIA) {
            parameter("method", "filemetas")
            parameter("access_token", token)
            parameter("path", path)
            parameter("dlink", "1")
            header("User-Agent", BaiduConstants.BAIDU_UA)
        }
        val json = response.body<JsonObject>()
        val errno = json["errno"]?.jsonPrimitive?.intOrNull ?: -1
        if (errno == -6 || errno == 110 || errno == 31045) {
            val newToken = loginFlow?.refresh()?.accessToken
            if (newToken != null) {
                return dlinkRetry(path, newToken)
            }
            throw BaiduAuthException("登录已失效，请重新授权")
        }
        val msg = json["errmsg"]?.jsonPrimitive?.contentOrNull ?: "未知错误"
        checkErrno(errno, msg)
        val list = json["list"] as? JsonArray
            ?: throw BaiduApiException(errno, "下载链接数据为空")
        val item = list.firstOrNull()?.jsonObject
            ?: throw BaiduApiException(errno, "下载链接数据为空")
        val dlink = item["dlink"]?.jsonPrimitive?.contentOrNull
            ?: throw BaiduApiChangedException("下载链接格式可能已变更")
        val size = item["size"]?.jsonPrimitive?.longOrNull ?: 0L
        return BaiduDownloadInfo(dlinkWithAccessToken(dlink, token), size)
    }

    private suspend fun dlinkRetry(path: String, token: String): BaiduDownloadInfo {
        rateLimiter.acquire()
        val response = httpClient.get(BaiduConstants.API_MULTIMEDIA) {
            parameter("method", "filemetas")
            parameter("access_token", token)
            parameter("path", path)
            parameter("dlink", "1")
            header("User-Agent", BaiduConstants.BAIDU_UA)
        }
        val json = response.body<JsonObject>()
        val errno = json["errno"]?.jsonPrimitive?.intOrNull ?: -1
        val msg = json["errmsg"]?.jsonPrimitive?.contentOrNull ?: "未知错误"
        checkErrno(errno, msg)
        val list = json["list"] as? JsonArray
            ?: throw BaiduApiException(errno, "下载链接数据为空")
        val item = list.firstOrNull()?.jsonObject
            ?: throw BaiduApiException(errno, "下载链接数据为空")
        val dlink = item["dlink"]?.jsonPrimitive?.contentOrNull
            ?: throw BaiduApiChangedException("下载链接格式可能已变更")
        val size = item["size"]?.jsonPrimitive?.longOrNull ?: 0L
        return BaiduDownloadInfo(dlinkWithAccessToken(dlink, token), size)
    }

    suspend fun getAccountInfo(): BaiduAccountInfo? {
        return try {
            val token = ensureAccessToken()
            rateLimiter.acquire()
            val response = httpClient.get("https://pan.baidu.com/rest/2.0/xpan/nas") {
                parameter("method", "uinfo")
                parameter("access_token", token)
                header("User-Agent", BaiduConstants.BAIDU_UA)
            }
            val json = response.body<JsonObject>()
            val errno = json["errno"]?.jsonPrimitive?.intOrNull ?: -1
            if (errno != 0) return null
            BaiduAccountInfo(json["baidu_name"]?.jsonPrimitive?.contentOrNull ?: "")
        } catch (e: Exception) {
            null
        }
    }

    private fun dlinkWithAccessToken(dlink: String, token: String): String {
        val separator = if (dlink.contains("?")) "&" else "?"
        return if (dlink.contains("access_token=")) {
            dlink.replace(Regex("access_token=[^&]*"), "access_token=$token")
        } else {
            "$dlink${separator}access_token=$token"
        }
    }

    private fun JsonObject.toFileEntry(): BaiduFileEntry {
        return BaiduFileEntry(
            fsId = this["fs_id"]?.jsonPrimitive?.longOrNull ?: 0L,
            path = this["path"]?.jsonPrimitive?.contentOrNull ?: "",
            name = this["server_filename"]?.jsonPrimitive?.contentOrNull
                ?: this["filename"]?.jsonPrimitive?.contentOrNull ?: "",
            isDir = (this["isdir"]?.jsonPrimitive?.intOrNull ?: 0) == 1,
            size = this["size"]?.jsonPrimitive?.longOrNull ?: 0L,
            mtime = this["local_mtime"]?.jsonPrimitive?.longOrNull
                ?: this["server_mtime"]?.jsonPrimitive?.longOrNull ?: 0L
        )
    }
}