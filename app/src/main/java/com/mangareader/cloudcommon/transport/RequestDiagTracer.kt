package com.mangareader.cloudcommon.transport

import android.util.Log

class RequestDiagTracer(private val tag: String) {

    fun begin(): Long = System.currentTimeMillis()

    fun end(start: Long, hit: Boolean, retry: Int) {
        val elapsed = System.currentTimeMillis() - start
        Log.d(tag, "elapsed=${elapsed}ms hit=$hit retry=$retry")
    }

    fun log(msg: String) {
        Log.d(tag, msg)
    }
}