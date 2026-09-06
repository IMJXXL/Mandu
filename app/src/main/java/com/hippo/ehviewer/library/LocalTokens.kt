package com.hippo.ehviewer.library

/**
 * Extracted from reference project `LocalHistory.kt` (the rest of that file depends on
 * deleted UI/model code and was intentionally not copied). Keeps the token constants and
 * the pure path helper used by `ArchivePageLoader` / `LocalGalleryInfo` / `FolderGalleryIndex`
 * / `ZipAsDirListing`. See `smbbridge/MODIFICATIONS.md`.
 */

/** Library gallery (scanned). Click → reader. */
const val LOCAL_GALLERY_TOKEN = "local"

/** Browse SAF folder path link. Click → FolderBrowser at path (dir listing, not reader). */
const val LOCAL_BROWSE_TOKEN = "local_browse"

/** Browse SMB folder path link. Click → SmbBrowser at path. */
const val SMB_BROWSE_TOKEN = "smb_browse"

/** Browse WebDAV folder path link. Click → WebDavBrowser at path. */
const val WEBDAV_BROWSE_TOKEN = "webdav_browse"

/**
 * Browse **folder gallery** (image dir). Click → reader; back → parent dir.
 * Same gid scheme as reader progress (`stableGalleryId(rootId, rel)` or `zip:$histRel`).
 */
const val LOCAL_FOLDER_TOKEN = "local_folder"

/** SMB folder gallery. Click → reader; back → parent share path. */
const val SMB_FOLDER_TOKEN = "smb_folder"

/** WebDAV folder gallery. Click → reader; back → parent path. */
const val WEBDAV_FOLDER_TOKEN = "webdav_folder"

/** Local archive file path. Click → archive reader. */
const val LOCAL_ARCHIVE_TOKEN = "local_archive"

/** SMB streamable archive (zip/cbz/tar/cbt). Click → stream reader. */
const val SMB_ARCHIVE_TOKEN = "smb_archive"

/** WebDAV streamable archive. Click → stream reader. */
const val WEBDAV_ARCHIVE_TOKEN = "webdav_archive"

/**
 * Local non-dir external file (video, document, loose image, …). Click → open/play;
 * returns to History (no reader back-stack). Path in [GalleryInfo.uploader].
 */
const val LOCAL_FILE_TOKEN = "local_file"

/** SMB non-dir external file. Click → open/play. */
const val SMB_FILE_TOKEN = "smb_file"

/** WebDAV non-dir external file. Click → open/play. */
const val WEBDAV_FILE_TOKEN = "webdav_file"

/** Parent directory of a remote archive path (`a/b/c.zip` → `a/b`; `c.zip` → `""`). */
fun parentRelativeOfFile(remotePath: String): String {
    val rel = remotePath.trim('/').let { if (it == ".") "" else it }
    if (rel.isEmpty() || !rel.contains('/')) return ""
    return rel.substringBeforeLast('/')
}
