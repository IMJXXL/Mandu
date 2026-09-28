package com.mangareader.backup.format

import kotlinx.serialization.Serializable

@Serializable
data class ReadingProgressDto(
    val id: Long = 0,
    val bookId: Long,
    val path: String,
    val lastPage: Int,
    val completed: Boolean,
    val updatedAt: Long,
    val animDialogChoice: Int = 0,
    val totalPages: Int = 0,
)

@Serializable
data class ReadingHistoryDto(
    val id: Long = 0,
    val bookId: Long,
    val pagesRead: Int,
    val timestamp: Long,
    val scrollX: Int = 0,
    val scrollY: Int = 0,
    val zoomLevel: Float = 1f,
)

@Serializable
data class BookmarkDto(
    val id: Long = 0,
    val bookId: Long,
    val chapterPath: String,
    val page: Int,
    val createdAt: Long,
    val note: String? = null,
)

@Serializable
data class BookAggregateDto(
    val id: Long,
    val readPages: Int,
    val lastReadAt: Long?,
    val filePath: String = "",
)

@Serializable
data class BookShelfFlagsDto(
    val id: Long,
    val isFavorite: Boolean,
    val isHidden: Boolean,
)

@Serializable
data class BookshelfDto(
    val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val sortOrder: Int = 0,
)

@Serializable
data class BookshelfItemDto(
    val id: Long = 0,
    val bookshelfId: Long,
    val bookId: Long,
)

@Serializable
data class TagDto(
    val id: Long = 0,
    val name: String,
    val color: Long = 0xFF7C8CF8,
)

@Serializable
data class TagLinkDto(
    val id: Long = 0,
    val tagId: Long,
    val bookId: Long,
    val source: Int = 1,  // 0=XML自动 1=手动；旧备份无此字段默认按手动（spec.md §五）
)

@Serializable
data class SmbSourceDto(
    val id: Long,
    val displayName: String,
    val host: String,
    val port: Int = 445,
    val easyTierHost: String? = null,
    val share: String,
    val pathPrefix: String? = null,
    val username: String? = null,
    val domain: String? = null,
    val addedAt: Long = 0,
    val lastOkAt: Long = 0,
    val lastError: String? = null,
)

@Serializable
data class WebDavSourceDto(
    val id: Long,
    val displayName: String,
    val baseUrl: String,
    val easyTierHost: String? = null,
    val pathPrefix: String? = null,
    val username: String? = null,
    val addedAt: Long = 0,
    val lastOkAt: Long = 0,
    val lastError: String? = null,
)

@Serializable
data class LibraryRootDto(
    val id: Long,
    val treeUri: String,
    val displayName: String,
    val addedAt: Long,
    val role: Int,
    val accessMode: Int,
)

@Serializable
data class ImportedFolderDto(
    val id: Long = 0,
    val treeUri: String,
    val displayName: String,
    val lastScanAt: Long = 0,
)