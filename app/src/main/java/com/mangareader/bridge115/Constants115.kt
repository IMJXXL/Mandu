package com.mangareader.bridge115

object Constants115 {
    const val SCHEME = "115:"
    const val KEYSTORE_ALIAS = "115Cred"
    const val PREFS_SESSION_OFFICIAL = "115_session_official"
    const val PREFS_SESSION_WEB = "115_session_web"
    const val PREFS_PROGRESS = "115_progress"
    const val PREFS_MODE = "115_mode"
    const val ENCRYPT_PREFIX = "enc1:"
    const val CACHE_DIR = "115"
    const val DEFAULT_CACHE_LIMIT = 500L * 1024 * 1024
    const val LOG_TAG = "Bridge115"

    const val API_BASE_PRO = "https://proapi.115.com"
    const val API_BASE_PASSPORT = "https://passportapi.115.com"
    const val API_BASE_WEB = "https://webapi.115.com"
    const val API_BASE_WEB_ALT = "https://web.api.115.com"
    const val API_BASE_APS = "https://aps.115.com"
    const val API_BASE_QRCODE = "https://qrcodeapi.115.com"

    const val UA_OFFICIAL = "RCH/0.3"
    val UA_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    const val MODE_OFFICIAL = "official"
    const val MODE_WEB = "web"

    const val OFFICIAL_MIN_INTERVAL_MS = 666L
    const val OFFICIAL_MAX_INTERVAL_MS = 1332L
    const val WEB_MIN_INTERVAL_MS = 1000L
    const val WEB_MAX_INTERVAL_MS = 2000L

    const val PAGE_SIZE = 200
    const val QR_TIMEOUT_MS = 120_000L
    const val QR_POLL_INTERVAL_MS = 2_000L
    const val WAF_COOLDOWN_MS = 60_000L
    const val TOKEN_REFRESH_AHEAD_MS = 300_000L

    const val DOWNURL_TTL_MS = 300_000L
    const val DOWNURL_MAX_ENTRIES = 512
}