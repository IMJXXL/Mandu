package com.mangareader.baidubridge.api

data class BaiduFileEntry(
    val fsId: Long,
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long
)

data class BaiduDownloadInfo(
    val url: String,
    val size: Long
)

data class BaiduTokenPair(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long
)

data class BaiduAccountInfo(
    val nickname: String
)