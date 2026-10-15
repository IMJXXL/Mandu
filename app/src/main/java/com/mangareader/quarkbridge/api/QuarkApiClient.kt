package com.mangareader.quarkbridge.api

import com.mangareader.quarkbridge.QuarkConstants
import com.mangareader.quarkbridge.auth.QuarkCredentials
import com.mangareader.quarkbridge.auth.QuarkSessionStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull


data class QuarkFileEntry(
    val fid: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val updatedAt: Long,
    val pdirFid: String,
    val fileType: Int
)

data class QuarkDirResult(
    val items: List<QuarkFileEntry>,
    val hasMore: Boolean,
    val total: Int
)

data class QuarkDownloadInfo(
    val url: String,
    val tempCookie: String
)

data class QuarkAccountInfo(
    val nickname: String
)

class QuarkApiClient(
    val httpClient: HttpClient = QuarkHttpClient.client(),
    val rateLimiter: QuarkRateLimiter = QuarkRateLimiter(),
    private val sessionStore: QuarkSessionStore? = null
) {
    private fun cookieHeader(): String {
        val creds = sessionStore?.load() ?: return ""
        return creds.cookie
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyDeviceParams() {
        parameter("pr", "ucpro")
        parameter("fr", "pc")
    }

    private fun refreshPuusCookie(response: HttpResponse) {
        val setCookies = response.headers.getAll("Set-Cookie") ?: return
        for (setCookie in setCookies) {
            val pair = setCookie.substringBefore(";")
            val eqIdx = pair.indexOf('=')
            if (eqIdx > 0) {
                val name = pair.substring(0, eqIdx).trim()
                if (name == "__puus") {
                    val value = pair.substring(eqIdx + 1).trim()
                    if (value.isNotEmpty()) {
                    val store = sessionStore ?: return
                    val creds = store.load() ?: return
                    val updated = upsertCookieValue(creds.cookie, "__puus", value)
                    if (updated != creds.cookie) {
                        store.updateCookie(updated)
                    }
                        return
                    }
                }
            }
        }
    }

    private fun upsertCookieValue(cookie: String, name: String, value: String): String {
        val parts = cookie.split("; ").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        val idx = parts.indexOfFirst { it.startsWith("$name=") }
        if (idx >= 0) parts[idx] = "$name=$value" else parts.add("$name=$value")
        return parts.joinToString("; ")
    }

    private fun checkResponseCode(code: Int, msg: String) {
        if (code == 0) return
        when (code) {
            31001, 32001 -> throw QuarkAuthException("登录已过期")
            429, 32010, 32011 -> {
                rateLimiter.suspendFor(30)
                throw QuarkRateLimitException("访问被限制，请稍后再试")
            }
        }
        if (code < 0) throw QuarkApiChangedException("服务接口可能已变更")
        throw QuarkApiException(code, msg)
    }

    suspend fun listDir(pdirFid: String, page: Int, size: Int = QuarkConstants.PAGE_SIZE): QuarkDirResult {
        rateLimiter.acquire()
        val response = httpClient.get("${QuarkConstants.API_BASE_PC}/1/clouddrive/file/sort") {
            header("Cookie", cookieHeader())
            header("User-Agent", QuarkConstants.QUARK_UA)
            header("Referer", QuarkConstants.QUARK_REFERER)
            parameter("pdir_fid", pdirFid)
            parameter("_page", page.toString())
            parameter("_size", size.toString())
            parameter("_sort", "file_type:asc,updated_at:desc")
            parameter("fetch_risk_file_name", "1")
            applyDeviceParams()
        }
        refreshPuusCookie(response)
        val json = response.body<JsonObject>()
        val code = json["code"]?.jsonPrimitive?.intOrNull ?: -1
        val msg = json["message"]?.jsonPrimitive?.contentOrNull ?: "未知错误"
        checkResponseCode(code, msg)
        val data = json["data"]?.jsonObject ?: return QuarkDirResult(emptyList(), false, 0)
        val list = data["list"] as? JsonArray ?: JsonArray(emptyList())
        val items = list.map { it.jsonObject.toFileEntry() }
        val hasMore = data["has_more"]?.jsonPrimitive?.intOrNull == 1
        val total = data["total"]?.jsonPrimitive?.intOrNull ?: items.size
        return QuarkDirResult(items, hasMore, total)
    }

    suspend fun getDownloadUrl(fid: String): QuarkDownloadInfo {
        rateLimiter.acquire()
        val response = httpClient.post("${QuarkConstants.API_BASE_PC}/1/clouddrive/file/download") {
            header("Cookie", cookieHeader())
            header("User-Agent", QuarkConstants.QUARK_UA)
            header("Referer", QuarkConstants.QUARK_REFERER)
            setBody("""{"fids":["$fid"]}""")
            applyDeviceParams()
        }
        refreshPuusCookie(response)
        val json = response.body<JsonObject>()
        val code = json["code"]?.jsonPrimitive?.intOrNull ?: -1
        val msg = json["message"]?.jsonPrimitive?.contentOrNull ?: "未知错误"
        checkResponseCode(code, msg)
        val data = json["data"]?.jsonObject ?: throw QuarkApiException(code, "下载链接数据为空")
        val url = data["download_url"]?.jsonPrimitive?.contentOrNull
            ?: (data["list"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("download_url")?.jsonPrimitive?.contentOrNull
            ?: throw QuarkApiChangedException("下载链接格式可能已变更")
        val tempCookie = response.headers.getAll("Set-Cookie")?.joinToString("; ") { it.substringBefore(";") } ?: ""
        return QuarkDownloadInfo(url, tempCookie)
    }

    suspend fun getAccountInfo(): QuarkAccountInfo? {
        rateLimiter.acquire()
        return try {
            val response = httpClient.get("${QuarkConstants.API_BASE_PAN}/account/info") {
                header("Cookie", cookieHeader())
                header("User-Agent", QuarkConstants.QUARK_UA)
                header("Referer", QuarkConstants.QUARK_REFERER)
                applyDeviceParams()
            }
            refreshPuusCookie(response)
            val json = response.body<JsonObject>()
            val code = json["code"]?.jsonPrimitive?.intOrNull ?: -1
            if (code != 0) return null
            val data = json["data"]?.jsonObject ?: return null
            QuarkAccountInfo(data["nickname"]?.jsonPrimitive?.contentOrNull ?: "")
        } catch (e: Exception) {
            null
        }
    }

    private fun JsonObject.toFileEntry(): QuarkFileEntry {
        return QuarkFileEntry(
            fid = this["fid"]?.jsonPrimitive?.contentOrNull ?: "",
            name = this["file_name"]?.jsonPrimitive?.contentOrNull ?: "",
            isDir = (this["file_type"]?.jsonPrimitive?.intOrNull ?: 0) == 0,
            size = this["size"]?.jsonPrimitive?.longOrNull ?: 0L,
            updatedAt = this["updated_at"]?.jsonPrimitive?.longOrNull ?: 0L,
            pdirFid = this["pdir_fid"]?.jsonPrimitive?.contentOrNull ?: "",
            fileType = this["file_type"]?.jsonPrimitive?.intOrNull ?: 0
        )
    }
}