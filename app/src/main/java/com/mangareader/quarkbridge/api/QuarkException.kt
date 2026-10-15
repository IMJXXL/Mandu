package com.mangareader.quarkbridge.api

open class QuarkException(message: String, cause: Throwable? = null) : Exception(message, cause)
class QuarkAuthException(message: String) : QuarkException(message)
class QuarkApiException(val code: Int, message: String) : QuarkException(message)
class QuarkRateLimitException(message: String) : QuarkException(message)
class QuarkApiChangedException(message: String) : QuarkException(message)