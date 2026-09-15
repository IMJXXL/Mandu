package com.mangareader.backup.format

import kotlinx.serialization.Serializable

@Serializable
data class BackupPayload(
    val formatVersion: Int = BACKUP_FORMAT_VERSION,
    val appVersion: String = "",
    val dbVersion: Int = 0,
    val createdAt: Long = 0,
    var checksum: String = "",
    val progress: ProgressPayload? = null,
    val settings: SettingsPayload? = null,
    val shelf: ShelfPayload? = null,
    val connections: ConnectionsPayload? = null,
)

@Serializable
data class ProgressPayload(
    val readingProgress: List<ReadingProgressDto> = emptyList(),
    val readingHistory: List<ReadingHistoryDto> = emptyList(),
    val bookmarks: List<BookmarkDto> = emptyList(),
    val mangaBooksAggregate: List<BookAggregateDto> = emptyList(),
)

@Serializable
data class SettingsPayload(
    val globalSettings: Map<String, String> = emptyMap(),
    val namedPresets: Map<String, String> = emptyMap(),
    val perBookOverrides: Map<String, String> = emptyMap(),
)

@Serializable
data class ShelfPayload(
    val bookshelves: List<BookshelfDto> = emptyList(),
    val bookshelfItems: List<BookshelfItemDto> = emptyList(),
    val tags: List<TagDto> = emptyList(),
    val tagLinks: List<TagLinkDto> = emptyList(),
    val mangaBooksShelfFlags: List<BookShelfFlagsDto> = emptyList(),
)

@Serializable
data class ConnectionsPayload(
    val smbSources: List<SmbSourceDto> = emptyList(),
    val smbPasswords: Map<String, String> = emptyMap(),
    val webdavSources: List<WebDavSourceDto> = emptyList(),
    val webdavPasswords: Map<String, String> = emptyMap(),
    val komgaConnectionRaw: String? = null,
    val komgaActiveConnectionId: String? = null,
    val libraryRoots: List<LibraryRootDto> = emptyList(),
    val importedFolders: List<ImportedFolderDto> = emptyList(),
)

const val BACKUP_FORMAT_VERSION = 1
const val DEFAULT_REMOTE_BACKUP_DIR = "/MangaReaderBackups"
const val DEFAULT_LOCAL_RETENTION_COUNT = 7