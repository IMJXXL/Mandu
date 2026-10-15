package com.mangareader.bridge115.api

open class Exception115(message: String, cause: Throwable? = null) : Exception(message, cause)
class Auth115Exception(message: String) : Exception115(message)
class Api115Exception(val code: Int, message: String) : Exception115(message)
class RateLimit115Exception(message: String) : Exception115(message)
class ApiChanged115Exception(message: String) : Exception115(message)
class WafBlockedException(message: String) : Exception115(message)
class Cipher115Exception(message: String) : Exception115(message)