package com.mangareader.backup.format

import kotlinx.serialization.Serializable

sealed class BackupError {
    object FileCorrupted : BackupError()
    object ChecksumMismatch : BackupError()
    data class VersionIncompatible(val msg: String) : BackupError()
    object DirectoryNotWritable : BackupError()
    object DataReadFailed : BackupError()
    object WriteInterrupted : BackupError()
    object ConnectionFailed : BackupError()
    object RemoteDirNotFound : BackupError()
    object TransferInterrupted : BackupError()
    object RemoteStorageFull : BackupError()
    object NoWebDavConnection : BackupError()
    object ForeignKeyViolation : BackupError()
    object RestoreInterrupted : BackupError()
    object NoContentSelected : BackupError()
}

enum class BackupCategory { PROGRESS, SETTINGS, SHELF, CONNECTIONS }

@Serializable
data class BackupContentSelection(
    val progress: Boolean = true,
    val settings: Boolean = true,
    val shelf: Boolean = true,
    val connections: Boolean = true,
)