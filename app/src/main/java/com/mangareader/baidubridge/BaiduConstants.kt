package com.mangareader.baidubridge

object BaiduConstants {
    const val SCHEME = "baidu:"
    const val KEYSTORE_ALIAS = "baiduCred"
    const val PREFS_SESSION = "baidu_session"
    const val PREFS_PROGRESS = "baidu_progress"
    const val ENCRYPT_PREFIX = "enc1:"
    const val CACHE_DIR = "baidu"
    const val DEFAULT_CACHE_LIMIT = 500L * 1024 * 1024
    const val LOG_TAG = "BaiduBridge"

    const val API_BASE_OAUTH = "https://openapi.baidu.com"
    const val API_BASE_PAN = "https://pan.baidu.com"
    const val API_AUTHORIZE = "$API_BASE_OAUTH/oauth/2.0/authorize"
    const val API_TOKEN = "$API_BASE_OAUTH/oauth/2.0/token"
    const val API_FILE = "$API_BASE_PAN/rest/2.0/xpan/file"
    const val API_MULTIMEDIA = "$API_BASE_PAN/rest/2.0/xpan/multimedia"

    const val BAIDU_UA = "pan.baidu.com"
    const val REDIRECT_URI = "oob"
    const val SCOPE = "basic,netdisk"

    const val SESSION_VALID_DAYS = 30L
    const val PAGE_SIZE = 200
    const val TOKEN_REFRESH_AHEAD_MS = 300_000L
}