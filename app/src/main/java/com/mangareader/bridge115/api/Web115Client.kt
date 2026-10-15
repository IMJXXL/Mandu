package com.mangareader.bridge115.api

import com.mangareader.bridge115.Constants115
import com.mangareader.bridge115.auth.SessionStore115
import com.mangareader.bridge115.crypto.M115Cipher
import com.mangareader.bridge115.crypto.WafCooldown
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.ConcurrentHashMap

class Web115Client(
    val httpClient: HttpClient = HttpClient115.client(),
    val rateLimiter: RateLimiter115 = RateLimiter115(
        minIntervalMs = Constants115.WEB_MIN_INTERVAL_MS,
        maxIntervalMs = Constants115.WEB_MAX_INTERVAL_MS
    ),
    private val sessionStore: SessionStore115? = null,
    private val wafCooldown: WafCooldown = WafCooldown()
) {
    private data class CachedDownurl(val url: String, val size: Long, val cachedAt: Long)
    private val downurlCache = ConcurrentHashMap<String, CachedDownurl>()

    private fun cookieHeader(): String {
        return sessionStore?.loadWeb()?.cookie ?: ""
    }

    private val webListUrls = listOf(
        "https://webapi.115.com/files",
        "https://web.api.115.com/files",
        "https://aps.115.com/natsort/files.php"
    )

    suspend fun list(cid: String, offset: Int = 0): List<FileEntry115> {
        wafCooldown.ensureAllowed()
        var saw405Count = 0
        for (url in webListUrls) {
            try {
                rateLimiter.acquire()
                val response = httpClient.get(url) {
                    header("Cookie", cookieHeader())
                    header("User-Agent", Constants115.UA_WEB)
                    parameter("cid", cid)
                    parameter("limit", Constants115.PAGE_SIZE.toString())
                    parameter("offset", offset.toString())
                    parameter("show_dir", "1")
                }
                when (response.status.value) {
                    405 -> {
                        saw405Count++
                        continue
                    }
                    401, 403 -> {
                        sessionStore?.clearWeb()
                        throw Auth115Exception("Cookie 过期，请重新扫码")
                    }
                }
                val json = response.body<JsonObject>()
                val errno = json["errno"]?.jsonPrimitive?.intOrNull ?: 0
                when (errno) {
                    990001, 40101032, 40101033 -> {
                        sessionStore?.clearWeb()
                        throw Auth115Exception("Cookie 过期，请重新扫码")
                    }
                    990002 -> {
                        rateLimiter.suspendFor(30)
                        throw RateLimit115Exception("请求过于频繁，请稍后再试")
                    }
                    990004 -> throw Api115Exception(errno, "账号被风控")
                }
                val pathArr = json["path"] as? JsonArray
                val listArr = json["list"] as? JsonArray ?: return emptyList()
                return listArr.map { it.jsonObject.toWebFileEntry(cid) }
            } catch (e: com.mangareader.bridge115.api.WafBlockedException) {
                throw e
            } catch (e: Auth115Exception) {
                throw e
            } catch (e: RateLimit115Exception) {
                throw e
            } catch (_: Exception) {
                continue
            }
        }
        if (saw405Count == webListUrls.size) {
            wafCooldown.markBlocked()
            throw WafBlockedException("触发风控，正在冷却...")
        }
        throw Api115Exception(-1, "所有域名均不可用")
    }

    suspend fun downurl(pickCode: String): DownloadInfo115 {
        val cached = downurlCache[pickCode]
        if (cached != null && System.currentTimeMillis() - cached.cachedAt < Constants115.DOWNURL_TTL_MS) {
            return DownloadInfo115(cached.url, cached.size)
        }
        if (downurlCache.size > Constants115.DOWNURL_MAX_ENTRIES) {
            downurlCache.clear()
        }

        wafCooldown.ensureAllowed()
        rateLimiter.acquire()
        val t = System.currentTimeMillis()
        val json = """{"pick_code":"$pickCode"}"""
        val encoded = M115Cipher.encode(json)

        val response = httpClient.post("https://proapi.115.com/app/chrome/downurl?t=$t") {
            header("Cookie", cookieHeader())
            header("User-Agent", Constants115.UA_WEB)
            header("Referer", "https://proapi.115.com")
            parameter("data", encoded)
        }
        when (response.status.value) {
            405 -> {
                wafCooldown.markBlocked()
                throw WafBlockedException("触发风控，正在冷却...")
            }
            403 -> throw Auth115Exception("签名失效，请重试")
        }
        val respJson = response.body<JsonObject>()
        val errno = respJson["errno"]?.jsonPrimitive?.intOrNull ?: 0
        when (errno) {
            990001, 40101032, 40101033 -> {
                sessionStore?.clearWeb()
                throw Auth115Exception("Cookie 过期，请重新扫码")
            }
            990002 -> {
                rateLimiter.suspendFor(30)
                throw RateLimit115Exception("请求过于频繁，请稍后再试")
            }
        }
        val dataStr = respJson["data"]?.jsonPrimitive?.contentOrNull
            ?: throw Api115Exception(errno, "下载链接数据为空")
        val decoded = M115Cipher.decode(dataStr)
        val decodedStr = String(decoded, Charsets.UTF_8)
        val decodedJson = kotlinx.serialization.json.Json.parseToJsonElement(decodedStr).jsonObject
        val url = decodedJson["url"]?.jsonPrimitive?.contentOrNull
            ?: decodedJson.entries.firstOrNull()?.value?.jsonPrimitive?.contentOrNull
            ?: throw ApiChanged115Exception("下载链接格式可能已变更")
        val size = decodedJson["size"]?.jsonPrimitive?.longOrNull ?: 0L

        downurlCache[pickCode] = CachedDownurl(url, size, System.currentTimeMillis())
        return DownloadInfo115(url, size)
    }

    suspend fun check(): Boolean {
        return try {
            list("0")
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun JsonObject.toWebFileEntry(cid: String): FileEntry115 {
        return FileEntry115(
            fid = this["fid"]?.jsonPrimitive?.contentOrNull ?: "",
            pickCode = this["pc"]?.jsonPrimitive?.contentOrNull ?: "",
            name = this["n"]?.jsonPrimitive?.contentOrNull ?: "",
            isDir = (this["cid"]?.jsonPrimitive?.contentOrNull ?: "").isNotEmpty(),
            size = this["s"]?.jsonPrimitive?.longOrNull ?: 0L,
            mtime = this["upt"]?.jsonPrimitive?.longOrNull ?: 0L,
            cid = this["cid"]?.jsonPrimitive?.contentOrNull ?: cid
        )
    }
}