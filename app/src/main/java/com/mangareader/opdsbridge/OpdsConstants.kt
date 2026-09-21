package com.mangareader.opdsbridge

object OpdsConstants {
    const val SCHEME = "opds:"
    const val COVER_SCHEME = "cv:opds:"
    const val SHARED_PREFS_NAME = "opds_connection"
    const val KEYSTORE_ALIAS = "opdsCred"
    const val ENCRYPT_PREFIX = "enc1:"
    const val LOG_TAG = "OpdsBridge"
    const val CACHE_DIR_NAME = "opds"

    const val CONNECT_TIMEOUT_MS = 10_000L
    const val SOCKET_TIMEOUT_MS = 30_000L
    const val REQUEST_TIMEOUT_MS = 60_000L

    val STREAMING_FORMATS = setOf("mobi", "epub", "azw3", "kf8", "kfx")

    val ACQUISITION_PRIORITY = listOf(
        "application/vnd.comicbook+zip",
        "application/x-cbz",
        "application/zip",
        "application/vnd.rar",
        "application/x-rar",
        "application/x-7z-compressed",
        "application/epub+zip",
        "application/pdf",
        "application/x-mobipocket-ebook",
        "application/x-mobi",
        "application*azw3",
        "application/x-kf8",
        "application/x-kfx",
    )

    const val LARGE_FILE_WARN_BYTES = 200L * 1024 * 1024
    const val COVER_LRU_CAPACITY = 512
}