package com.mangareader.backup.snapshot

import com.mangareader.backup.format.ReadingProgressDto
import com.mangareader.backup.format.ReadingHistoryDto
import com.mangareader.backup.format.BookmarkDto
import com.mangareader.backup.format.BookshelfItemDto
import com.mangareader.backup.format.TagLinkDto
import kotlinx.serialization.Serializable

@Serializable
data class RescanSnapshot(
    val progress: List<ReadingProgressDto> = emptyList(),
    val history: List<ReadingHistoryDto> = emptyList(),
    val bookmarks: List<BookmarkDto> = emptyList(),
    val shelfItems: List<BookshelfItemDto> = emptyList(),
    val tagLinks: List<TagLinkDto> = emptyList(),
)