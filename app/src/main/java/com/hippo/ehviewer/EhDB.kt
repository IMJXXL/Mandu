package com.hippo.ehviewer

import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo

object EhDB {
    fun loadGalleryInfo(gid: Long, token: String): GalleryInfo? = null
    fun putGalleryInfo(gallery: GalleryInfo) {}
    fun putHistoryInfo(info: BaseGalleryInfo) {}
    fun putReadProgress(gallery: GalleryInfo, page: Int) {}
}