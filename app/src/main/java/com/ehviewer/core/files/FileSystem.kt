package com.ehviewer.core.files

import okio.FileSystem
import splitties.init.appCtx

val SystemFileSystem: FileSystem
    get() = PlatformSystemFileSystem

@PublishedApi
internal val PlatformSystemFileSystem = AndroidFileSystem(appCtx)

