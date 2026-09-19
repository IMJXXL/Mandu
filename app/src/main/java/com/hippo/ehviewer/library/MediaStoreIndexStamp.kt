package com.hippo.ehviewer.library

import com.hippo.ehviewer.Settings

data class MediaStoreIndexStamp(
    val generation: Long,
    val count: Int,
    val maxDateModifiedSecs: Long,
    val idXor: Long,
) {
    fun generationUnchanged(currentGeneration: Long): Boolean = generation > 0L && currentGeneration > 0L && generation == currentGeneration

    fun sameImages(other: MediaStoreIndexStamp): Boolean = count == other.count &&
        maxDateModifiedSecs == other.maxDateModifiedSecs &&
        idXor == other.idXor

    fun encode(rootId: Long): String = "$rootId$SEP$generation$SEP$count$SEP$maxDateModifiedSecs$SEP$idXor"

    companion object {
        const val GENERATION_UNKNOWN = -1L
        private const val SEP = ':'

        fun parse(encoded: String): Pair<Long, MediaStoreIndexStamp>? {
            val parts = encoded.split(SEP)
            if (parts.size != 5) return null
            val rootId = parts[0].toLongOrNull() ?: return null
            val generation = parts[1].toLongOrNull() ?: return null
            val count = parts[2].toIntOrNull() ?: return null
            val maxDate = parts[3].toLongOrNull() ?: return null
            val idXor = parts[4].toLongOrNull() ?: return null
            return rootId to MediaStoreIndexStamp(generation, count, maxDate, idXor)
        }

        fun shouldSkip(
            stored: MediaStoreIndexStamp?,
            current: MediaStoreIndexStamp,
            hasGalleries: Boolean,
        ): Boolean {
            if (!hasGalleries || stored == null) return false
            if (stored.generationUnchanged(current.generation)) return true
            return stored.sameImages(current)
        }

        fun load(rootId: Long): MediaStoreIndexStamp? {
            val prefix = "$rootId$SEP"
            val raw = Settings.libraryMediaStoreStamps.value.firstOrNull { it.startsWith(prefix) }
                ?: return null
            val parsed = parse(raw) ?: return null
            return parsed.second.takeIf { parsed.first == rootId }
        }

        fun remember(rootId: Long, stamp: MediaStoreIndexStamp) {
            val prefix = "$rootId$SEP"
            val without = Settings.libraryMediaStoreStamps.value.filterNot { it.startsWith(prefix) }.toSet()
            Settings.libraryMediaStoreStamps.value = without + stamp.encode(rootId)
        }

        fun clear(rootId: Long) {
            val prefix = "$rootId$SEP"
            val next = Settings.libraryMediaStoreStamps.value.filterNot { it.startsWith(prefix) }.toSet()
            if (next.size != Settings.libraryMediaStoreStamps.value.size) {
                Settings.libraryMediaStoreStamps.value = next
            }
        }
    }
}