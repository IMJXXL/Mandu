package com.mangareader.updatecheck

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ApkDownloader {
    fun getDownloadDir(ctx: Context): File {
        return if (android.os.Environment.isExternalStorageManager()) {
            File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), UpdateConstants.DOWNLOAD_DIR_NAME)
        } else {
            File(ctx.getExternalFilesDir(null), UpdateConstants.DOWNLOAD_DIR_NAME)
        }.apply { mkdirs() }
    }

    fun getApkFile(ctx: Context, version: String): File {
        val safeVer = version.replace(Regex("[^a-zA-Z0-9._-]"), "")
        return File(getDownloadDir(ctx), "${UpdateConstants.APK_FILENAME_PREFIX}_${safeVer}.apk")
    }

    private fun cleanOldApks(dir: File, keepFile: File) {
        try {
            dir.listFiles()?.forEach { f ->
                if (f != keepFile && (f.name.endsWith(".apk") || f.name.endsWith(".tmp"))) {
                    f.delete()
                }
            }
        } catch (_: Exception) {}
    }

    private fun isZipFile(file: File): Boolean = try {
        val header = ByteArray(4)
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.readFully(header)
        }
        header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
            header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
    } catch (_: Exception) {
        false
    }

    /**
     * ★ 下载包与当前应用签名比对：不一致/解析失败一律 false。
     * 防线：镜像侧或公共 Downloads/漫读/ 被塞假包时，不同包名的 APK 会被系统当"新应用"装成功——
     * 同签名校验是我们自己的最后一道闸（系统安装器只拒"换签名的同包名"，管不了不同包名）。
     */
    fun verifyOwnSignature(ctx: Context, apkFile: File): Boolean = try {
        val pm = ctx.packageManager
        val newSigs: List<List<Byte>> = if (android.os.Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageArchiveInfo(apkFile.absolutePath, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                ?: return false
            info.signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() } ?: return false
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageArchiveInfo(apkFile.absolutePath, android.content.pm.PackageManager.GET_SIGNATURES)
                ?: return false
            @Suppress("DEPRECATION")
            info.signatures?.map { it.toByteArray().toList() } ?: return false
        }
        val curSigs: List<List<Byte>> = if (android.os.Build.VERSION.SDK_INT >= 28) {
            val cur = pm.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            cur.signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() } ?: return false
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_SIGNATURES)
                .signatures?.map { it.toByteArray().toList() } ?: return false
        }
        newSigs.toSet() == curSigs.toSet()
    } catch (_: Throwable) {
        false
    }

    fun downloadInBackground(ctx: Context, info: UpdateInfo) {
        val appCtx = ctx.applicationContext
        (appCtx as? com.mangareader.App)?.appScope?.launch(Dispatchers.IO) {
            val file = download(info.downloadUrl, appCtx, info.version, info.apkSize) {}
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    appCtx,
                    if (file != null) "更新包下载完成，打开检查更新即可安装" else "更新包下载失败",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    suspend fun download(
        url: String,
        ctx: Context,
        version: String,
        expectSize: Long = -1L,
        mirror: String = "",
        onProgress: (Float) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        try {
            val apkFile = getApkFile(ctx, version)
            if (apkFile.exists() && apkFile.length() > 0 &&
                (expectSize <= 0 || apkFile.length() == expectSize)
            ) {
                // 复用缓存也要过签名校验：公共目录里同尺寸的假包会被 size 判定放进来
                if (!verifyOwnSignature(ctx, apkFile)) {
                    android.util.Log.e("ApkDownloader", "cached apk signature MISMATCH, deleted")
                    apkFile.delete()
                    return@withContext null
                }
                android.util.Log.i("ApkDownloader", "reuse cached apk ${apkFile.length()} bytes")
                onProgress(1f)
                return@withContext apkFile
            }
            cleanOldApks(apkFile.parentFile, apkFile)
            val actualUrl = if (mirror.isNotEmpty()) "$mirror$url" else url
            android.util.Log.i("ApkDownloader", "download start url=$actualUrl")
            val tmpFile = File(apkFile.parentFile, "${apkFile.name}.tmp")
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            val request = okhttp3.Request.Builder().url(actualUrl)
                .header("User-Agent", "MangaReader-UpdateChecker")
                .build()
            val response = client.newCall(request).execute()
            android.util.Log.i("ApkDownloader", "response code=${response.code} len=${response.body?.contentLength()}")
            if (!response.isSuccessful) {
                response.close()
                return@withContext null
            }
            val total = response.body?.contentLength() ?: -1L
            var downloaded = 0L
            tmpFile.outputStream().use { out ->
                response.body?.byteStream()?.use { input ->
                    val buf = ByteArray(8192)
                    while (true) {
                        kotlinx.coroutines.yield()
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) onProgress(downloaded.toFloat() / total)
                    }
                }
            }
            response.close()
            if (apkFile.exists()) apkFile.delete()
            if (!tmpFile.renameTo(apkFile)) {
                android.util.Log.e("ApkDownloader", "rename FAILED, tmp=${tmpFile.length()} bytes")
                try { tmpFile.delete() } catch (_: Exception) {}
                return@withContext null
            }
            if (apkFile.length() < 4 || !isZipFile(apkFile)) {
                android.util.Log.e("ApkDownloader", "downloaded file is not a valid ZIP/APK, size=${apkFile.length()}")
                apkFile.delete()
                return@withContext null
            }
            // 下载后签名校验：镜像侧篡改/截断在此拦截（不同包名的假包系统安装器拦不住）
            if (!verifyOwnSignature(ctx, apkFile)) {
                android.util.Log.e("ApkDownloader", "downloaded apk signature MISMATCH, deleted")
                apkFile.delete()
                return@withContext null
            }
            onProgress(1f)
            android.util.Log.i("ApkDownloader", "download done ${apkFile.length()} bytes")
            apkFile
        } catch (e: Exception) {
            android.util.Log.e("ApkDownloader", "download FAIL", e)
            null
        }
    }
}

object ApkInstaller {
    fun install(ctx: Context, apkFile: File): Boolean {
        return try {
            // 汇聚点强制校验：所有安装路径（弹窗/后台下载/主界面）都必须过签名这道闸
            if (!apkFile.exists() || !ApkDownloader.verifyOwnSignature(ctx, apkFile)) {
                try { apkFile.delete() } catch (_: Exception) {}
                return false
            }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}

object PreUpdateBackup {
    suspend fun backup(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val backupDir = File(ctx.getExternalFilesDir(null), "pre_update_backup")
            backupDir.mkdirs()
            val backupFile = File(backupDir, "settings_${System.currentTimeMillis()}.json")
            val payload = com.mangareader.backup.export.SettingsExporter.export(ctx)
            val json = kotlinx.serialization.json.Json.encodeToString(
                com.mangareader.backup.format.SettingsPayload.serializer(), payload
            )
            backupFile.writeText(json, Charsets.UTF_8)
            true
        } catch (_: Exception) {
            false
        }
    }
}