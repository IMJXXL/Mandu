package com.mangareader.komgabridge.api

open class KomgaException(message: String, cause: Throwable? = null) : Exception(message, cause)
class KomgaAuthException(message: String) : KomgaException(message)