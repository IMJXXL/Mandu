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
            tmpFile.renameTo(apkFile)
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