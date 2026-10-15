package com.mangareader.bridge115.auth

import android.content.Context
import com.mangareader.bridge115.Constants115

data class Session115Official(
    val appId: String,
    val appSecret: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val nickname: String,
    val loginAt: Long
)

data class Session115Web(
    val cookie: String,
    val userId: String,
    val loginAt: Long
)

class SessionStore115(context: Context) {

    private val prefsOfficial = context.getSharedPreferences(Constants115.PREFS_SESSION_OFFICIAL, Context.MODE_PRIVATE)
    private val prefsWeb = context.getSharedPreferences(Constants115.PREFS_SESSION_WEB, Context.MODE_PRIVATE)

    fun saveOfficial(session: Session115Official) {
        prefsOfficial.edit()
            .putString(KEY_APP_ID, CredentialCrypto115.encrypt(session.appId))
            .putString(KEY_APP_SECRET, CredentialCrypto115.encrypt(session.appSecret))
            .putString(KEY_ACCESS_TOKEN, CredentialCrypto115.encrypt(session.accessToken))
            .putString(KEY_REFRESH_TOKEN, CredentialCrypto115.encrypt(session.refreshToken))
            .putLong(KEY_EXPIRES_AT, session.expiresAt)
            .putString(KEY_NICKNAME, session.nickname)
            .putLong(KEY_LOGIN_AT, session.loginAt)
            .apply()
    }

    fun loadOfficial(): Session115Official? {
        val accessToken = CredentialCrypto115.decryptStored(prefsOfficial.getString(KEY_ACCESS_TOKEN, "") ?: "")
        if (accessToken.isEmpty()) return null
        return Session115Official(
            appId = CredentialCrypto115.decryptStored(prefsOfficial.getString(KEY_APP_ID, "") ?: ""),
            appSecret = CredentialCrypto115.decryptStored(prefsOfficial.getString(KEY_APP_SECRET, "") ?: ""),
            accessToken = accessToken,
            refreshToken = CredentialCrypto115.decryptStored(prefsOfficial.getString(KEY_REFRESH_TOKEN, "") ?: ""),
            expiresAt = prefsOfficial.getLong(KEY_EXPIRES_AT, 0L),
            nickname = prefsOfficial.getString(KEY_NICKNAME, "") ?: "",
            loginAt = prefsOfficial.getLong(KEY_LOGIN_AT, 0L)
        )
    }

    fun clearOfficial() {
        prefsOfficial.edit().clear().apply()
    }

    fun isValidOfficial(): Boolean {
        val session = loadOfficial() ?: return false
        if (session.accessToken.isEmpty() || session.refreshToken.isEmpty()) return false
        return session.expiresAt > System.currentTimeMillis()
    }

    fun updateOfficialTokens(accessToken: String, refreshToken: String, expiresAt: Long) {
        prefsOfficial.edit()
            .putString(KEY_ACCESS_TOKEN, CredentialCrypto115.encrypt(accessToken))
            .putString(KEY_REFRESH_TOKEN, CredentialCrypto115.encrypt(refreshToken))
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .apply()
    }

    fun updateOfficialNickname(nickname: String) {
        prefsOfficial.edit().putString(KEY_NICKNAME, nickname).apply()
    }

    fun saveWeb(session: Session115Web) {
        prefsWeb.edit()
            .putString(KEY_COOKIE, CredentialCrypto115.encrypt(session.cookie))
            .putString(KEY_USER_ID, CredentialCrypto115.encrypt(session.userId))
            .putLong(KEY_LOGIN_AT, session.loginAt)
            .apply()
    }

    fun loadWeb(): Session115Web? {
        val cookie = CredentialCrypto115.decryptStored(prefsWeb.getString(KEY_COOKIE, "") ?: "")
        if (cookie.isEmpty()) return null
        return Session115Web(
            cookie = cookie,
            userId = CredentialCrypto115.decryptStored(prefsWeb.getString(KEY_USER_ID, "") ?: ""),
            loginAt = prefsWeb.getLong(KEY_LOGIN_AT, 0L)
        )
    }

    fun clearWeb() {
        prefsWeb.edit().clear().apply()
    }

    fun isValidWeb(): Boolean {
        val session = loadWeb() ?: return false
        return session.cookie.isNotEmpty()
    }

    fun updateWebCookie(cookie: String) {
        prefsWeb.edit().putString(KEY_COOKIE, CredentialCrypto115.encrypt(cookie)).apply()
    }

    companion object {
        private const val KEY_APP_ID = "app_id"
        private const val KEY_APP_SECRET = "app_secret"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_LOGIN_AT = "login_at"
        private const val KEY_COOKIE = "cookie"
        private const val KEY_USER_ID = "user_id"
    }
}