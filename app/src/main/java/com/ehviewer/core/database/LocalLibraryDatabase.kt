package com.ehviewer.core.database

import androidx.room.RoomDatabase
import com.ehviewer.core.database.dao.LibraryRootDao
import com.ehviewer.core.database.dao.LocalGalleryDao
import com.ehviewer.core.database.dao.SmbSourceDao
import com.ehviewer.core.database.dao.WebDavSourceDao

abstract class LocalLibraryDatabase : RoomDatabase() {
    abstract fun libraryRootDao(): LibraryRootDao
    abstract fun localGalleryDao(): LocalGalleryDao
    abstract fun smbSourceDao(): SmbSourceDao
    abstract fun webDavSourceDao(): WebDavSourceDao
}
