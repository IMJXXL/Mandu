package com.ehviewer.core.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "WEBDAV_SOURCES")
data class WebDavSourceEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "ID")
    val id: Long = 0,

    @ColumnInfo(name = "DISPLAY_NAME")
    val displayName: String,

    /**
     * Base WebDAV URL ending with `/`, e.g.
     * `https://cloud.example/remote.php/dav/files/user/`.
     */
    @ColumnInfo(name = "BASE_URL")
    val baseUrl: String,

    /**
     * Optional EasyTier virtual IP/hostname swapped into [baseUrl] only while EasyTier
     * is connected. Empty = always use [baseUrl] host. Cache keys stay on [baseUrl].
     */
    @ColumnInfo(name = "EASYTIER_HOST", defaultValue = "")
    val easytierHost: String = "",

    /** Optional subpath under [baseUrl], no leading slash. */
    @ColumnInfo(name = "PATH_PREFIX")
    val pathPrefix: String = "",

    @ColumnInfo(name = "USERNAME")
    val username: String = "",

    @ColumnInfo(name = "ADDED_AT")
    val addedAt: Long,

    @ColumnInfo(name = "LAST_OK_AT")
    val lastOkAt: Long? = null,

    @ColumnInfo(name = "LAST_ERROR")
    val lastError: String? = null,

    /**
     * 不主动加载封面（防服务器限流/封控）：true 时浏览与书架不发任何封面网络请求，
     * 仅显示已落盘缓存（阅读打开时自动落盘）；阅读不受影响。默认 false。
     */
    @ColumnInfo(name = "NO_COVER_FETCH", defaultValue = "0")
    val noCoverFetch: Boolean = false,
)
