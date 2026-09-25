package com.mangareader

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.security.MessageDigest

object SignatureVerifier {
    private const val TAG = "SigVerify"
    private const val HASH_PART_A = "CC657F10752C00E528A8727E6A37FCCE"
    /** 完整期望 hash（仅用于诊断文件展示指纹；两段拆分存储防源码全文检索） */
    private const val HASH_PART_B_PUBLIC = "F6D95465BE7D17C1B3BE2B0C59969C22"

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
                degrade(context, "签名列表为空 (signingInfo=${pkgInfo.signingInfo})", null)
                return false
            }

            val cert = signatures[0].toByteArray()
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(cert).joinToString("") { "%02X".format(it) }

            if (hash == HASH_PART_A + hashPartB) {
                isVerified = true
                return true
            }

            degrade(context, "签名hash不匹配", hash)
            return false
        } catch (e: Exception) {
            // ★ 异常路径同样可能是误杀源（如特定安装方式导致 signingInfo 异常），必须留全栈取证
            degrade(context, "校验过程异常: ${e.javaClass.name}: ${e.message}", null, e)
            return false
        }
    }

    private fun degrade(context: Context, reason: String, actualHash: String?, throwable: Throwable? = null) {
        failCount++
        Log.e(TAG, "signature check failed #$failCount: $reason")
        // ★ 取证必须先于硬退出：nativeForceExit 绕过 CrashHandler，本文件是校验失败的唯一
        //   取证渠道（真机无 adb 时的生命线）。1.5s 延迟足够同步写小文件。
        try { writeFailureDiagnostics(context, reason, actualHash, throwable) } catch (_: Throwable) {}
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

    /**
     * 校验失败诊断落盘：双写 filesDir（about 导出按钮可读）+ 公共 Download（API 29+ 经
     * MediaStore，用户文件管理器直接可见，无需 root/adb——绕开 Android/data 限制）。
     * 记录实际/期望签名 hash、失败原因、异常栈、安装来源（哪个应用装的包）。
     */
    private fun writeFailureDiagnostics(context: Context, reason: String, actualHash: String?, throwable: Throwable?) {
        val sb = StringBuilder()
        sb.appendLine("=== 漫读 签名校验失败诊断 ===")
        sb.appendLine("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        sb.appendLine("包名: ${context.packageName}")
        run {
            val pkg = try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (_: Exception) { null }
            if (pkg != null) {
                sb.appendLine("versionName: ${pkg.versionName}")
                sb.appendLine("versionCode: ${pkg.longVersionCode}")
                sb.appendLine("安装时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(pkg.firstInstallTime))}")
                sb.appendLine("更新时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(pkg.lastUpdateTime))}")
            }
            val installer = try {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getInstallerPackageName(context.packageName)
                }
            } catch (_: Exception) { null }
            sb.appendLine("安装来源(installer): $installer  ← 判断是哪个应用装的包")
        }
        sb.appendLine("失败原因: $reason")
        sb.appendLine("实际签名hash: ${actualHash ?: "<不可用>"}")
        sb.appendLine("期望签名hash: $HASH_PART_A$HASH_PART_B_PUBLIC")
        if (throwable != null) {
            sb.appendLine("--- 异常栈 ---")
            sb.appendLine(android.util.Log.getStackTraceString(throwable))
        }
        sb.appendLine("提示: hash 不匹配 = 安装的 APK 与正版签名不同（传输/安装环节被重签或文件被替换）；")
        sb.appendLine("      异常路径 = 可能是特定安装方式/设备环境的误判，请把本文件发回开发者。")
        val text = sb.toString()

        // ① filesDir：关于页导出按钮的来源之一
        runCatching {
            java.io.File(context.filesDir, "sig_check_failed.txt").writeText(text)
        }
        // ② 公共 Download：用户文件管理器直接可见
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "漫读_签名校验失败.txt")
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                }
                val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                }
            } else {
                @Suppress("DEPRECATION")
                val dl = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                dl.mkdirs()
                java.io.File(dl, "漫读_签名校验失败.txt").writeText(text)
            }
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
                degrade(context, "后台定时校验失败(签名hash不匹配或签名列表为空)", null)
            }
        } catch (_: Exception) {}
    }
}
