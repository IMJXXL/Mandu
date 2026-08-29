package com.mangareader.sr

object NativeLibraryStatus {

    val isRealESRGANAvailable: Boolean by lazy {
        RealESRGANProcessor.nativeLibraryLoaded
    }

    fun isModelAvailable(model: SRModel): Boolean {
        return if (model.requiresVulkan) isRealESRGANAvailable else true
    }

    fun getAvailableModels(): List<SRModel> = SRModel.entries.filter { isModelAvailable(it) }

    fun getFirstAvailableModel(): SRModel =
        getAvailableModels().firstOrNull() ?: SRModel.QUICK_OPTIMIZE
}
