package com.mangareader.baidubridge.api

open class BaiduException(message: String, cause: Throwable? = null) : Exception(message, cause)
class BaiduAuthException(message: String) : BaiduException(message)
class BaiduApiException(val code: Int, message: String) : BaiduException(message)
class BaiduRateLimitException(message: String) : BaiduException(message)
class BaiduApiChangedException(message: String) : BaiduException(message)