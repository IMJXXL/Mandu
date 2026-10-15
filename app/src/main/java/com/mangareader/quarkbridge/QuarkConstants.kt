package com.mangareader.quarkbridge

object QuarkConstants {
    const val SCHEME = "quark:"
    const val KEYSTORE_ALIAS = "quarkCred"
    const val PREFS_SESSION = "quark_session"
    const val PREFS_PROGRESS = "quark_progress"
    const val ENCRYPT_PREFIX = "enc1:"
    const val CACHE_DIR = "quark"
    const val DEFAULT_CACHE_LIMIT = 500L * 1024 * 1024
    const val LOG_TAG = "QuarkBridge"

    const val API_BASE_PC = "https://drive-pc.quark.cn"
    const val API_BASE_PAN = "https://pan.quark.cn"

    const val QUARK_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/3.14.2 Chrome/112.0.5615.165 Electron/24.1.3.8 Safari/537.36 Channel/pckk_other_ch"

    const val SESSION_VALID_DAYS = 7L
    const val PAGE_SIZE = 50
    const val QR_POLL_INTERVAL_MS = 2000L
    const val QR_TIMEOUT_MS = 120_000L
}