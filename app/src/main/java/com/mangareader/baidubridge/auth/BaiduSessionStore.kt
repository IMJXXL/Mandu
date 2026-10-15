package com.mangareader.baidubridge.auth

import android.content.Context
import com.mangareader.baidubridge.BaiduConstants

data class BaiduSession(
    val appKey: String,
    val secretKey: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val nickname: String,
    val loginAt: Long
)

class BaiduSessionStore(context: Context) {

    private val prefs = context.getSharedPreferences(BaiduConstants.PREFS_SESSION, Context.MODE_PRIVATE)

    fun save(session: BaiduSession) {
        prefs.edit()
            .putString(KEY_APP_KEY, BaiduCredentialCrypto.encrypt(session.appKey))
            .putString(KEY_SECRET_KEY, BaiduCredentialCrypto.encrypt(session.secretKey))
            .putString(KEY_ACCESS_TOKEN, BaiduCredentialCrypto.encrypt(session.accessToken))
            .putString(KEY_REFRESH_TOKEN, BaiduCredentialCrypto.encrypt(session.refreshToken))
            .putLong(KEY_EXPIRES_AT, session.expiresAt)
            .putString(KEY_NICKNAME, session.nickname)
            .putLong(KEY_LOGIN_AT, session.loginAt)
            .apply()
    }

    fun load(): BaiduSession? {
        val accessToken = BaiduCredentialCrypto.decryptStored(prefs.getString(KEY_ACCESS_TOKEN, "") ?: "")
        if (accessToken.isEmpty()) return null
        return BaiduSession(
            appKey = BaiduCredentialCrypto.decryptStored(prefs.getString(KEY_APP_KEY, "") ?: ""),
            secretKey = BaiduCredentialCrypto.decryptStored(prefs.getString(KEY_SECRET_KEY, "") ?: ""),
            accessToken = accessToken,
            refreshToken = BaiduCredentialCrypto.decryptStored(prefs.getString(KEY_REFRESH_TOKEN, "") ?: ""),
            expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L),
            nickname = prefs.getString(KEY_NICKNAME, "") ?: "",
            loginAt = prefs.getLong(KEY_LOGIN_AT, 0L)
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun isValid(): Boolean {
        val session = load() ?: return false
        if (session.accessToken.isEmpty() || session.refreshToken.isEmpty()) return false
        return true
    }

    fun updateTokens(accessToken: String, refreshToken: String, expiresAt: Long) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, BaiduCredentialCrypto.encrypt(accessToken))
            .putString(KEY_REFRESH_TOKEN, BaiduCredentialCrypto.encrypt(refreshToken))
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .apply()
    }

    fun updateNickname(nickname: String) {
        prefs.edit().putString(KEY_NICKNAME, nickname).apply()
    }

    companion object {
        private const val KEY_APP_KEY = "app_key"
        private const val KEY_SECRET_KEY = "secret_key"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_LOGIN_AT = "login_at"
    }
}