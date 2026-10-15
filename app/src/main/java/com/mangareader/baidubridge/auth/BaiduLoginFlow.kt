package com.mangareader.baidubridge.auth

import com.mangareader.baidubridge.BaiduConstants
import com.mangareader.baidubridge.api.BaiduAuthException
import com.mangareader.baidubridge.api.BaiduHttpClient
import com.mangareader.baidubridge.api.BaiduRateLimiter
import com.mangareader.baidubridge.api.BaiduTokenPair
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder

class BaiduLoginFlow(
    private val sessionStore: BaiduSessionStore,
    private val rateLimiter: BaiduRateLimiter = BaiduRateLimiter()
) {
    private val httpClient = BaiduHttpClient.client()

    fun buildAuthUrl(appKey: String): String {
        val params = mapOf(
            "response_type" to "code",
            "client_id" to appKey,
            "redirect_uri" to BaiduConstants.REDIRECT_URI,
            "scope" to BaiduConstants.SCOPE,
            "display" to "popup"
        )
        val query = params.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        return "${BaiduConstants.API_AUTHORIZE}?$query"
    }

    suspend fun exchangeCode(appKey: String, secret: String, code: String): BaiduTokenPair {
        rateLimiter.acquire()
        val response = httpClient.post(BaiduConstants.API_TOKEN) {
            parameter("grant_type", "authorization_code")
            parameter("code", code)
            parameter("client_id", appKey)
            parameter("client_secret", secret)
            parameter("redirect_uri", BaiduConstants.REDIRECT_URI)
        }
        val json = response.body<JsonObject>()
        val error = json["error"]?.jsonPrimitive?.contentOrNull
        if (error != null) {
            val desc = json["error_description"]?.jsonPrimitive?.contentOrNull ?: error
            throw BaiduAuthException(
                if (error == "invalid_grant") "授权码无效或已过期"
                else if (error == "invalid_client") "App Key 或 Secret Key 错误"
                else desc
            )
        }
        val accessToken = json["access_token"]?.jsonPrimitive?.contentOrNull
            ?: throw BaiduAuthException("未获取到 access_token")
        val refreshToken = json["refresh_token"]?.jsonPrimitive?.contentOrNull
            ?: throw BaiduAuthException("未获取到 refresh_token")
        val expiresIn = json["expires_in"]?.jsonPrimitive?.longOrNull ?: 2592000L

        val expiresAt = System.currentTimeMillis() + expiresIn * 1000
        sessionStore.save(BaiduSession(
            appKey = appKey,
            secretKey = secret,
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAt = expiresAt,
            nickname = "",
            loginAt = System.currentTimeMillis()
        ))
        return BaiduTokenPair(accessToken, refreshToken, expiresIn)
    }

    suspend fun refresh(): BaiduTokenPair {
        val session = sessionStore.load()
            ?: throw BaiduAuthException("登录已失效，请重新授权")
        rateLimiter.acquire()
        val response = httpClient.post(BaiduConstants.API_TOKEN) {
            parameter("grant_type", "refresh_token")
            parameter("refresh_token", session.refreshToken)
            parameter("client_id", session.appKey)
            parameter("client_secret", session.secretKey)
        }
        val json = response.body<JsonObject>()
        val error = json["error"]?.jsonPrimitive?.contentOrNull
        if (error != null) {
            sessionStore.clear()
            throw BaiduAuthException("登录已失效，请重新授权")
        }
        val accessToken = json["access_token"]?.jsonPrimitive?.contentOrNull
            ?: throw BaiduAuthException("刷新 token 失败")
        val refreshToken = json["refresh_token"]?.jsonPrimitive?.contentOrNull
            ?: session.refreshToken
        val expiresIn = json["expires_in"]?.jsonPrimitive?.longOrNull ?: 2592000L
        val expiresAt = System.currentTimeMillis() + expiresIn * 1000

        sessionStore.updateTokens(accessToken, refreshToken, expiresAt)
        return BaiduTokenPair(accessToken, refreshToken, expiresIn)
    }

    suspend fun ensureToken(): String {
        val session = sessionStore.load()
            ?: throw BaiduAuthException("登录已失效，请重新授权")
        val now = System.currentTimeMillis()
        if (now + BaiduConstants.TOKEN_REFRESH_AHEAD_MS < session.expiresAt) {
            return session.accessToken
        }
        return refresh().accessToken
    }
}