package com.mangareader.bridge115.auth

import com.mangareader.bridge115.Constants115
import com.mangareader.bridge115.api.Auth115Exception
import com.mangareader.bridge115.api.HttpClient115
import com.mangareader.bridge115.api.RateLimiter115
import com.mangareader.bridge115.api.TokenPair115
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import android.util.Base64

data class QrPayload115(
    val uid: String,
    val time: Long,
    val sign: String,
    val qrcode: String
)

data class QrPollResult115(
    val status: Int,
    val accessToken: String?,
    val refreshToken: String?
)

class Login115Flow(
    private val sessionStore: SessionStore115,
    private val officialRateLimiter: RateLimiter115 = RateLimiter115(
        minIntervalMs = Constants115.OFFICIAL_MIN_INTERVAL_MS,
        maxIntervalMs = Constants115.OFFICIAL_MAX_INTERVAL_MS
    ),
    private val webRateLimiter: RateLimiter115 = RateLimiter115(
        minIntervalMs = Constants115.WEB_MIN_INTERVAL_MS,
        maxIntervalMs = Constants115.WEB_MAX_INTERVAL_MS
    )
) {
    private val httpClient = HttpClient115.client()
    private val verifiers = ConcurrentHashMap<String, String>()

    private fun genCodeVerifier(): String {
        val nanos = System.nanoTime().toString().toByteArray()
        val threadId = Thread.currentThread().id.toString().toByteArray()
        val hasher = MessageDigest.getInstance("SHA-256")
        hasher.update(nanos)
        hasher.update(threadId)
        return hasher.digest().joinToString("") { "%02x".format(it) }
    }

    private fun codeChallenge(verifier: String): String {
        val hasher = MessageDigest.getInstance("SHA-256")
        hasher.update(verifier.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hasher.digest(), Base64.NO_WRAP)
    }

    suspend fun qrStartOfficial(appId: String): QrPayload115 {
        val verifier = genCodeVerifier()
        val challenge = codeChallenge(verifier)
        officialRateLimiter.acquire()
        val response = httpClient.post("https://passportapi.115.com/open/authDeviceCode") {
            parameter("client_id", appId)
            parameter("code_challenge", challenge)
            parameter("code_challenge_method", "sha256")
        }
        val json = response.body<JsonObject>()
        val code = json["code"]?.jsonPrimitive?.intOrNull ?: -1
        if (code != 0) {
            val msg = json["message"]?.jsonPrimitive?.contentOrNull ?: "未知"
            throw Auth115Exception("获取二维码失败: $code $msg")
        }
        val data = json["data"]?.jsonObject
            ?: throw Auth115Exception("设备码响应缺少 data")
        val uid = data["uid"]?.jsonPrimitive?.contentOrNull
            ?: throw Auth115Exception("设备码响应缺少 uid")
        val qrcode = data["qrcode"]?.jsonPrimitive?.contentOrNull ?: ""
        val time = data["time"]?.jsonPrimitive?.longOrNull ?: 0L
        val sign = data["sign"]?.jsonPrimitive?.contentOrNull ?: ""
        verifiers[uid] = verifier
        return QrPayload115(uid, time, sign, qrcode)
    }

    suspend fun qrPollOfficial(uid: String, time: Long, sign: String): QrPollResult115 {
        officialRateLimiter.acquire()
        val response = httpClient.get("https://qrcodeapi.115.com/get/status/") {
            parameter("uid", uid)
            parameter("time", time.toString())
            parameter("sign", sign)
        }
        val json = response.body<JsonObject>()
        val status = json["data"]?.jsonObject?.get("status")?.jsonPrimitive?.intOrNull ?: 0
        if (status != 2) {
            return QrPollResult115(status, null, null)
        }
        val verifier = verifiers.remove(uid)
            ?: throw Auth115Exception("扫码会话已过期，请重新获取二维码")
        officialRateLimiter.acquire()
        val tokenResp = httpClient.post("https://passportapi.115.com/open/deviceCodeToToken") {
            parameter("uid", uid)
            parameter("code_verifier", verifier)
        }
        val tokenJson = tokenResp.body<JsonObject>()
        val tokenCode = tokenJson["code"]?.jsonPrimitive?.intOrNull ?: -1
        if (tokenCode != 0) {
            val msg = tokenJson["message"]?.jsonPrimitive?.contentOrNull ?: "未知"
            throw Auth115Exception("换取 token 失败: $tokenCode $msg")
        }
        val tokenData = tokenJson["data"]?.jsonObject
            ?: throw Auth115Exception("token 响应缺少 data")
        val accessToken = tokenData["access_token"]?.jsonPrimitive?.contentOrNull
            ?: throw Auth115Exception("token 响应缺少 access_token")
        val refreshToken = tokenData["refresh_token"]?.jsonPrimitive?.contentOrNull
            ?: throw Auth115Exception("token 响应缺少 refresh_token")
        return QrPollResult115(2, accessToken, refreshToken)
    }

    suspend fun refreshOfficial(): TokenPair115 {
        val session = sessionStore.loadOfficial()
            ?: throw Auth115Exception("登录已失效，请重新授权")
        officialRateLimiter.acquire()
        val response = httpClient.post("https://passportapi.115.com/open/refreshToken") {
            parameter("refresh_token", session.refreshToken)
        }
        val json = response.body<JsonObject>()
        val code = json["code"]?.jsonPrimitive?.intOrNull ?: -1
        if (code != 0) {
            val msg = json["message"]?.jsonPrimitive?.contentOrNull ?: "未知"
            sessionStore.clearOfficial()
            throw Auth115Exception("刷新 token 失败: $code $msg")
        }
        val data = json["data"]?.jsonObject
            ?: throw Auth115Exception("刷新响应缺少 data")
        val accessToken = data["access_token"]?.jsonPrimitive?.contentOrNull
            ?: throw Auth115Exception("刷新响应缺少 access_token")
        val refreshToken = data["refresh_token"]?.jsonPrimitive?.contentOrNull
            ?: throw Auth115Exception("刷新响应缺少 refresh_token")
        val expiresAt = System.currentTimeMillis() + 7 * 24 * 3600 * 1000L
        sessionStore.updateOfficialTokens(accessToken, refreshToken, expiresAt)
        return TokenPair115(accessToken, refreshToken, 7 * 24 * 3600L)
    }

    suspend fun ensureOfficialToken(): String {
        val session = sessionStore.loadOfficial()
            ?: throw Auth115Exception("登录已失效，请重新授权")
        val now = System.currentTimeMillis()
        if (now + Constants115.TOKEN_REFRESH_AHEAD_MS < session.expiresAt) {
            return session.accessToken
        }
        return refreshOfficial().accessToken
    }

    suspend fun webQrStart(): QrPayload115 {
        webRateLimiter.acquire()
        val response = httpClient.get("https://qrcodeapi.115.com/api/1.0/web/1.0/token/")
        val json = response.body<JsonObject>()
        val code = json["code"]?.jsonPrimitive?.intOrNull ?: -1
        if (code != 0) {
            val msg = json["message"]?.jsonPrimitive?.contentOrNull ?: "未知"
            throw Auth115Exception("获取二维码失败: $code $msg")
        }
        val data = json["data"]?.jsonObject
            ?: throw Auth115Exception("二维码响应缺少 data")
        val uid = data["uid"]?.jsonPrimitive?.contentOrNull
            ?: throw Auth115Exception("二维码响应缺少 uid")
        val qrcode = data["qrcode"]?.jsonPrimitive?.contentOrNull ?: ""
        val time = data["time"]?.jsonPrimitive?.longOrNull ?: 0L
        val sign = data["sign"]?.jsonPrimitive?.contentOrNull ?: ""
        return QrPayload115(uid, time, sign, qrcode)
    }

    suspend fun webQrPoll(uid: String, time: Long, sign: String): Int {
        webRateLimiter.acquire()
        val response = httpClient.get("https://qrcodeapi.115.com/get/status/") {
            parameter("uid", uid)
            parameter("time", time.toString())
            parameter("sign", sign)
        }
        val json = response.body<JsonObject>()
        return json["data"]?.jsonObject?.get("status")?.jsonPrimitive?.intOrNull ?: 0
    }

    suspend fun webQrCookie(uid: String, app: String): String {
        if (app !in WEB_QR_APPS) {
            throw Auth115Exception("不支持的 115 扫码设备: $app")
        }
        webRateLimiter.acquire()
        val url = "https://passportapi.115.com/app/1.0/$app/1.0/login/qrcode/"
        val response = httpClient.post(url) {
            parameter("app", app)
            parameter("account", uid)
        }
        val json = response.body<JsonObject>()
        val code = json["code"]?.jsonPrimitive?.intOrNull ?: -1
        if (code != 0) {
            val msg = json["message"]?.jsonPrimitive?.contentOrNull ?: "未知"
            throw Auth115Exception("换取 Cookie 失败: $code $msg")
        }
        val cookieObj = json["data"]?.jsonObject?.get("cookie")?.jsonObject
            ?: throw Auth115Exception("Cookie 响应缺少 data.cookie")
        val parts = cookieObj.entries.map { (k, v) ->
            "$k=${v.jsonPrimitive.contentOrNull ?: ""}"
        }
        if (parts.isEmpty()) {
            throw Auth115Exception("登录失败，请重新扫码")
        }
        val cookie = parts.joinToString("; ")
        val userId = extractUserId(cookie)
        sessionStore.saveWeb(Session115Web(
            cookie = cookie,
            userId = userId,
            loginAt = System.currentTimeMillis()
        ))
        return cookie
    }

    private fun extractUserId(cookie: String): String {
        val match = Regex("UID=([^;]+)").find(cookie)
        return match?.groupValues?.get(1) ?: ""
    }

    companion object {
        val WEB_QR_APPS = listOf("web", "android", "ios", "tv", "alipaymini", "wechatmini", "qandroid")
    }
}