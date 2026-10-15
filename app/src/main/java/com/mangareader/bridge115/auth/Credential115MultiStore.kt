package com.mangareader.bridge115.auth

import android.content.Context
import splitties.init.appCtx

object Credential115MultiStore {
    private const val PREFS_OFFICIAL = "115_multi_creds_official"
    private const val PREFS_WEB = "115_multi_creds_web"
    private const val PREFIX = "acc_"

    private val prefsOfficial by lazy {
        appCtx.getSharedPreferences(PREFS_OFFICIAL, Context.MODE_PRIVATE)
    }
    private val prefsWeb by lazy {
        appCtx.getSharedPreferences(PREFS_WEB, Context.MODE_PRIVATE)
    }

    fun saveOfficial(accountId: Long, session: Session115Official) {
        val p = PREFIX + accountId + "_"
        prefsOfficial.edit()
            .putString(p + "app_id", CredentialCrypto115.encrypt(session.appId))
            .putString(p + "app_secret", CredentialCrypto115.encrypt(session.appSecret))
            .putString(p + "access_token", CredentialCrypto115.encrypt(session.accessToken))
            .putString(p + "refresh_token", CredentialCrypto115.encrypt(session.refreshToken))
            .putLong(p + "expires_at", session.expiresAt)
            .putString(p + "nickname", session.nickname)
            .putLong(p + "login_at", session.loginAt)
            .apply()
    }

    fun loadOfficial(accountId: Long): Session115Official? {
        val p = PREFIX + accountId + "_"
        val accessToken = CredentialCrypto115.decryptStored(prefsOfficial.getString(p + "access_token", "") ?: "")
        if (accessToken.isEmpty()) return null
        return Session115Official(
            appId = CredentialCrypto115.decryptStored(prefsOfficial.getString(p + "app_id", "") ?: ""),
            appSecret = CredentialCrypto115.decryptStored(prefsOfficial.getString(p + "app_secret", "") ?: ""),
            accessToken = accessToken,
            refreshToken = CredentialCrypto115.decryptStored(prefsOfficial.getString(p + "refresh_token", "") ?: ""),
            expiresAt = prefsOfficial.getLong(p + "expires_at", 0L),
            nickname = prefsOfficial.getString(p + "nickname", "") ?: "",
            loginAt = prefsOfficial.getLong(p + "login_at", 0L)
        )
    }

    fun saveWeb(accountId: Long, session: Session115Web) {
        val p = PREFIX + accountId + "_"
        prefsWeb.edit()
            .putString(p + "cookie", CredentialCrypto115.encrypt(session.cookie))
            .putString(p + "user_id", CredentialCrypto115.encrypt(session.userId))
            .putLong(p + "login_at", session.loginAt)
            .apply()
    }

    fun loadWeb(accountId: Long): Session115Web? {
        val p = PREFIX + accountId + "_"
        val cookie = CredentialCrypto115.decryptStored(prefsWeb.getString(p + "cookie", "") ?: "")
        if (cookie.isEmpty()) return null
        return Session115Web(
            cookie = cookie,
            userId = CredentialCrypto115.decryptStored(prefsWeb.getString(p + "user_id", "") ?: ""),
            loginAt = prefsWeb.getLong(p + "login_at", 0L)
        )
    }

    fun remove(accountId: Long) {
        val p = PREFIX + accountId + "_"
        val officialKeys = listOf("app_id", "app_secret", "access_token", "refresh_token", "expires_at", "nickname", "login_at")
        val webKeys = listOf("cookie", "user_id", "login_at")
        val e1 = prefsOfficial.edit()
        officialKeys.forEach { e1.remove(p + it) }
        e1.apply()
        val e2 = prefsWeb.edit()
        webKeys.forEach { e2.remove(p + it) }
        e2.apply()
    }

    fun isValidOfficial(accountId: Long): Boolean {
        val session = loadOfficial(accountId) ?: return false
        if (session.accessToken.isEmpty() || session.refreshToken.isEmpty()) return false
        return session.expiresAt > System.currentTimeMillis()
    }

    fun isValidWeb(accountId: Long): Boolean {
        val session = loadWeb(accountId) ?: return false
        return session.cookie.isNotEmpty()
    }
}