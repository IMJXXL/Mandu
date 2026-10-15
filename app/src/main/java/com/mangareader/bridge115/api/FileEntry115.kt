package com.mangareader.bridge115.api

data class FileEntry115(
    val fid: String,
    val pickCode: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
    val cid: String
)

data class DownloadInfo115(
    val url: String,
    val size: Long
)

data class TokenPair115(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long
)