package com.mangareader.opdsbridge.api

open class OpdsException(message: String, cause: Throwable? = null) : Exception(message, cause)
class OpdsAuthException(message: String) : OpdsException(message)
class OpdsParseException(message: String, cause: Throwable? = null) : OpdsException(message, cause)
class OpdsNetworkException(message: String, cause: Throwable? = null) : OpdsException(message, cause)