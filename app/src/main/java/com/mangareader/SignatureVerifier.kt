package com.mangareader

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.security.MessageDigest

object SignatureVerifier {
    private const val TAG = "SigVerify"
    private const val HASH_PART_A = "CC657F10752C00E528A8727E6A37FCCE"

    @Volatile var isVerified = false
        private set
    private var failCount = 0

    fun verify(context: Context, hashPartB: String): Boolean {
        try {
            val pkgInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(
                    context.packageName, PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName, PackageManager.GET_SIGNATURES
                )
            }

            val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pkgInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.signatures
            }

            if (signatures.isNullOrEmpty()) {
                degrade(context)
                return false
            }

            val cert = signatures[0].toByteArray()
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(cert).joinToString("") { "%02X".format(it) }

            if (hash == HASH_PART_A + hashPartB) {
                isVerified = true
                return true
            }

            degrade(context)
            return false
        } catch (e: Exception) {
            degrade(context)
            return false
        }
    }

    private fun degrade(context: Context) {
        failCount++
        Log.e(TAG, "signature check failed #$failCount")
        if (failCount == 1) {
            // 第一次失败：清缓存，用户看到空白页
            try {
                val dir = context.filesDir
                java.io.File(dir, "covers").deleteRecursively()
                java.io.File(dir, "page_cache").deleteRecursively()
            } catch (_: Exception) {}
        }
        // 延迟崩溃
        try {
            Thread.sleep(1500L * failCount)
            com.mangareader.native.NativeEngine.nativeForceExit(1)
        } catch (_: Throwable) {
            Runtime.getRuntime().exit(1)
        }
    }

    /** 供后台定时校验调用 */
    fun periodicCheck(context: Context, hashPartB: String) {
        if (!isVerified) return
        try {
            val pkgInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(
                    context.packageName, PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName, PackageManager.GET_SIGNATURES
                )
            }
            val sigs = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pkgInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.signatures
            }
            if (sigs.isNullOrEmpty() || sigs[0].toByteArray().let {
                    MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02X".format(b) }
                } != HASH_PART_A + hashPartB) {
                isVerified = false
                degrade(context)
            }
        } catch (_: Exception) {}
    }
}
