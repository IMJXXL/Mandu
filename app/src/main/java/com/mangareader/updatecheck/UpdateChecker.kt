package com.mangareader.updatecheck

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

object UpdateConstants {
    const val OWNER = "IMJXXL"
    const val REPO = "mandu"
    const val API_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    const val PREF_NAME = "manga_reader"
    const val PREF_AUTO_CHECK = "update_auto_check"
    const val PREF_INTERVAL_DAYS = "update_interval_days"
    const val PREF_LAST_CHECK_TIME = "update_last_check_time"
    const val DOWNLOAD_DIR_NAME = "漫读"
    const val APK_FILENAME_PREFIX = "update"
    const val CONNECT_TIMEOUT = 30000
    const val READ_TIMEOUT = 30000

    val MIRRORS: List<Pair<String, String>> = listOf(
        "" to "官方线路",
        "https://ghfast.top/" to "加速线路1",
        "https://ghproxy.net/" to "加速线路2",
    )
}

@Serializable
data class GithubAsset(
    val name: String = "",
    val size: Long = 0,
    @kotlinx.serialization.SerialName("browser_download_url")
    val downloadUrl: String = "",
)

@Serializable
data class GithubRelease(
    @kotlinx.serialization.SerialName("tag_name")
    val tagName: String = "",
    val name: String = "",
    val body: String = "",
    val assets: List<GithubAsset> = emptyList(),
)

data class UpdateInfo(
    val version: String,
    val changelog: String,
    val downloadUrl: String,
    val apkSize: Long,
)

object UpdateCheckConfig {
    fun isAutoCheckEnabled(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences(UpdateConstants.PREF_NAME, Context.MODE_PRIVATE)
        return sp.getBoolean(UpdateConstants.PREF_AUTO_CHECK, true)
    }

    fun setAutoCheckEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(UpdateConstants.PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(UpdateConstants.PREF_AUTO_CHECK, enabled).apply()
    }

    fun getIntervalDays(ctx: Context): Int {
        val sp = ctx.getSharedPreferences(UpdateConstants.PREF_NAME, Context.MODE_PRIVATE)
        return sp.getInt(UpdateConstants.PREF_INTERVAL_DAYS, 7)
    }

    fun setIntervalDays(ctx: Context, days: Int) {
        ctx.getSharedPreferences(UpdateConstants.PREF_NAME, Context.MODE_PRIVATE)
            .edit().putInt(UpdateConstants.PREF_INTERVAL_DAYS, days.coerceIn(0, 30)).apply()
    }

    fun getLastCheckTime(ctx: Context): Long {
        val sp = ctx.getSharedPreferences(UpdateConstants.PREF_NAME, Context.MODE_PRIVATE)
        return sp.getLong(UpdateConstants.PREF_LAST_CHECK_TIME, 0L)
    }

    fun setLastCheckTime(ctx: Context, time: Long) {
        ctx.getSharedPreferences(UpdateConstants.PREF_NAME, Context.MODE_PRIVATE)
            .edit().putLong(UpdateConstants.PREF_LAST_CHECK_TIME, time).apply()
    }

    fun shouldAutoCheck(ctx: Context): Boolean {
        if (!isAutoCheckEnabled(ctx)) return false
        val interval = getIntervalDays(ctx)
        if (interval == 0) return false
        val now = System.currentTimeMillis()
        val last = getLastCheckTime(ctx)
        return now - last > interval * 24L * 60L * 60L * 1000L
    }
}

object UpdateChecker {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun getCurrentVersion(ctx: Context): String {
        return try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            pi.versionName ?: ""
        } catch (_: PackageManager.NameNotFoundException) {
            ""
        }
    }

    private fun normalizeVersion(v: String): String =
        v.trim().removePrefix("v").removePrefix("V")

    private fun compareVersions(a: String, b: String): Int {
        val num = Regex("\\d+")
        val pa = a.split(".").map { num.find(it)?.value?.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { num.find(it)?.value?.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    suspend fun checkForUpdate(ctx: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(UpdateConstants.API_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = UpdateConstants.CONNECT_TIMEOUT
            conn.readTimeout = UpdateConstants.READ_TIMEOUT
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "MangaReader-UpdateChecker")
            if (conn.responseCode != 200) {
                conn.disconnect()
                return@withContext null
            }
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val release = json.decodeFromString<GithubRelease>(responseText)
            val curVersion = normalizeVersion(getCurrentVersion(ctx))
            val latestVersion = normalizeVersion(release.tagName)
            if (compareVersions(latestVersion, curVersion) <= 0) return@withContext null
            val asset = release.assets.firstOrNull { it.name.endsWith(".apk") }
                ?: return@withContext null
            UpdateInfo(
                version = release.tagName,
                changelog = release.body.ifBlank { release.name },
                downloadUrl = asset.downloadUrl,
                apkSize = asset.size,
            )
        } catch (_: Exception) {
            null
        }
    }
}