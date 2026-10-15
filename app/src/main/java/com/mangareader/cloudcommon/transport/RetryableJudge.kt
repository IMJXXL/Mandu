package com.mangareader.cloudcommon.transport

import com.hippo.ehviewer.library.RemoteRangeNotSupportedException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

object RetryableJudge {

    fun isRetryable(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            when (cur) {
                is RemoteRangeNotSupportedException -> return false
                is FileNotFoundException -> return false
                is SocketException,
                is SocketTimeoutException,
                is ConnectException,
                is UnknownHostException,
                is IOException,
                -> return true
            }
            val msg = cur.message.orEmpty()
            if (msg.contains("timeout", ignoreCase = true) ||
                msg.contains("Broken pipe", ignoreCase = true) ||
                msg.contains("Connection reset", ignoreCase = true) ||
                msg.contains("Connection refused", ignoreCase = true) ||
                msg.contains("Software caused connection abort", ignoreCase = true)
            ) {
                return true
            }
            val code = Regex("""\b([45]\d\d)\b""").find(msg)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (code != null) {
                if (code in 400..499) return false
                if (code in 500..599) return true
            }
            cur = cur.cause
        }
        return false
    }
}