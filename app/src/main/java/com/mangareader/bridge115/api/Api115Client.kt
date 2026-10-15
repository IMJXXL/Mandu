package com.mangareader.bridge115.api

import com.mangareader.bridge115.Constants115
import com.mangareader.bridge115.auth.Login115Flow
import com.mangareader.bridge115.auth.SessionStore115
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

class Api115Client(
    val httpClient: HttpClient = HttpClient115.client(),
    val rateLimiter: RateLimiter115 = RateLimiter115(
        minIntervalMs = Constants115.OFFICIAL_MIN_INTERVAL_MS,
        maxIntervalMs = Constants115.OFFICIAL_MAX_INTERVAL_MS
    ),
    private val sessionStore: SessionStore115? = null,
    private val loginFlow: Login115Flow? = null
) {
    private fun accessToken(): String {
        val session = sessionStore?.loadOfficial() ?: throw Auth115Exception("登录已失效，请重新授权")
        return session.accessToken
    }

    private suspend fun ensureAccessToken(): String {
        return loginFlow?.ensureOfficialToken() ?: accessToken()
    }

    suspend fun list(cid: String, offset: Int = 0): List<FileEntry115> {
        val token = ensureAccessToken()
        rateLimiter.acquire()
        val response = httpClient.get("https://proapi.115.com/open/ufile/files") {
            header("Authorization", token)
            header("User-Agent", Constants115.UA_OFFICIAL)
            parameter("cid", cid)
            parameter("limit", Constants115.PAGE_SIZE.toString())
            parameter("offset", offset.toString())
        }
        val json = response.body<JsonObject>()
        val state = json["state"]?.jsonPrimitive?.contentOrNull
        val code = json["code"]?.jsonPrimitive?.intOrNull ?: 0
        if (code == 99 || (40100..40200).contains(code) || (401000..402000).contains(code)) {
            val newToken = loginFlow?.refreshOfficial()?.accessToken
            if (newToken != null) {
                return listRetry(cid, newToken, offset)
            }
            throw Auth115Exception("登录已失效，请重新授权")
        }
        if (state == "false" || code != 0) {
            val msg = json["message"]?.jsonPrimitive?.contentOrNull ?: "未知错误"
            throw Api115Exception(code, msg)
        }
        val data = json["data"]?.jsonObject ?: return emptyList()
        val listArr = data["list"] as? JsonArray ?: return emptyList()
        return listArr.map { it.jsonObject.toFileEntry(cid) }
    }

    private suspend fun listRetry(cid: String, token: String, offset: Int = 0): List<FileEntry115> {
        rateLimiter.acquire()
        val response = httpClient.get("https://proapi.115.com/open/ufile/files") {
            header("Authorization", token)
            header("User-Agent", Constants115.UA_OFFICIAL)
            parameter("cid", cid)
            parameter("limit", Constants115.PAGE_SIZE.toString())
            parameter("offset", offset.toString())
        }
        val json = response.body<JsonObject>()
        val data = json["data"]?.jsonObject ?: return emptyList()
        val listArr = data["list"] as? JsonArray ?: return emptyList()
        return listArr.map { it.jsonObject.toFileEntry(cid) }
    }

    suspend fun downurl(pickCode: String): DownloadInfo115 {
        val token = ensureAccessToken()
        rateLimiter.acquire()
        val response = httpClient.get("https://proapi.115.com/open/ufile/downurl") {
            header("Authorization", token)
            header("User-Agent", Constants115.UA_OFFICIAL)
            parameter("pick_code", pickCode)
        }
        val json = response.body<JsonObject>()
        val code = json["code"]?.jsonPrimitive?.intOrNull ?: 0
        if (code == 99 || (40100..40200).contains(code) || (401000..402000).contains(code)) {
            val newToken = loginFlow?.refreshOfficial()?.accessToken
            if (newToken != null) {
                return downurlRetry(pickCode, newToken)
            }
            throw Auth115Exception("登录已失效，请重新授权")
        }
        val data = json["data"]?.jsonObject
            ?: throw Api115Exception(code, "下载链接数据为空")
        val url = data["url"]?.jsonPrimitive?.contentOrNull
            ?: data.values.firstOrNull()?.jsonPrimitive?.contentOrNull
            ?: throw ApiChanged115Exception("下载链接格式可能已变更")
        val size = data["size"]?.jsonPrimitive?.longOrNull ?: 0L
        return DownloadInfo115(url, size)
    }

    private suspend fun downurlRetry(pickCode: String, token: String): DownloadInfo115 {
        rateLimiter.acquire()
        val response = httpClient.get("https://proapi.115.com/open/ufile/downurl") {
            header("Authorization", token)
            header("User-Agent", Constants115.UA_OFFICIAL)
            parameter("pick_code", pickCode)
        }
        val json = response.body<JsonObject>()
        val data = json["data"]?.jsonObject
            ?: throw Api115Exception(0, "下载链接数据为空")
        val url = data["url"]?.jsonPrimitive?.contentOrNull
            ?: data.values.firstOrNull()?.jsonPrimitive?.contentOrNull
            ?: throw ApiChanged115Exception("下载链接格式可能已变更")
        val size = data["size"]?.jsonPrimitive?.longOrNull ?: 0L
        return DownloadInfo115(url, size)
    }

    suspend fun getAccountInfo(): String? {
        return try {
            val token = ensureAccessToken()
            rateLimiter.acquire()
            val response = httpClient.get("https://proapi.115.com/open/user/info") {
                header("Authorization", token)
                header("User-Agent", Constants115.UA_OFFICIAL)
            }
            val json = response.body<JsonObject>()
            json["data"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            null
        }
    }

    private fun JsonObject.toFileEntry(cid: String): FileEntry115 {
        val fidVal = this["fid"]?.jsonPrimitive?.contentOrNull ?: ""
        val isDirVal = (this["fc"]?.jsonPrimitive?.contentOrNull ?: "0") != "0"
        return FileEntry115(
            fid = fidVal,
            pickCode = this["pc"]?.jsonPrimitive?.contentOrNull ?: "",
            name = this["fn"]?.jsonPrimitive?.contentOrNull ?: "",
            isDir = isDirVal,
            size = this["fs"]?.jsonPrimitive?.longOrNull ?: 0L,
            mtime = this["upt"]?.jsonPrimitive?.longOrNull ?: 0L,
            cid = if (isDirVal) fidVal else cid
        )
    }
}