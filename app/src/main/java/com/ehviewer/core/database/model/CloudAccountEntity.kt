package com.ehviewer.core.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "CLOUD_ACCOUNTS")
data class CloudAccountEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "ID")
    val id: Long = 0,

    @ColumnInfo(name = "PAN_TYPE")
    val panType: String,

    @ColumnInfo(name = "DISPLAY_NAME")
    val displayName: String,

    @ColumnInfo(name = "IS_ACTIVE")
    val isActive: Boolean = false,

    @ColumnInfo(name = "LOGIN_STATUS")
    val loginStatus: String = "已登录",

    @ColumnInfo(name = "MODE_115", defaultValue = "")
    val mode115: String = "",

    @ColumnInfo(name = "ADDED_AT")
    val addedAt: Long,

    @ColumnInfo(name = "LAST_OK_AT")
    val lastOkAt: Long? = null,

    @ColumnInfo(name = "LAST_ERROR")
    val lastError: String? = null,
)