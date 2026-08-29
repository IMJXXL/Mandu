package com.mangareader.sr

import android.content.Context
import android.util.Log
import java.io.File

/** 内置模型解包器：assets/models/<dir> → filesDir/models/<dir>（带版本戳，assets 更新后自动重提）。 */
class ModelManager private constructor(
    private val context: Context,
) {
    companion object {
        private const val TAG = "SrModels"
        private const val MODEL_CACHE_VERSION = 1

        @Volatile
        private var instance: ModelManager? = null

        fun getInstance(context: Context): ModelManager {
            return instance ?: synchronized(this) {
                instance ?: ModelManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val modelsDir by lazy {
        File(context.filesDir, "models").also { it.mkdirs() }
    }

    fun getModelDir(model: SRModel): File = File(modelsDir, model.modelDirName)

    fun isModelReady(model: SRModel): Boolean {
        if (!model.isBuiltIn) return false
        val dir = getModelDir(model)
        val paramFile = File(dir, "${model.modelDirName}.param")
        val binFile = File(dir, "${model.modelDirName}.bin")
        val stamp = File(dir, ".v$MODEL_CACHE_VERSION")
        return paramFile.exists() && binFile.exists() && stamp.exists()
    }

    fun ensureModelsExtracted() {
        SRModel.entries.filter { it.isBuiltIn }.forEach { model ->
            // 快速优化档无模型文件，跳过解包
            if (model.modelType == "algorithm") return@forEach
            // 基础目录 + 全部降噪变体目录逐个解包
            model.assetDirs().forEach { dirName ->
                val modelDir = File(modelsDir, dirName)
                val stamp = File(modelDir, ".v$MODEL_CACHE_VERSION")
                if (!stamp.exists()) {
                    modelDir.mkdirs()
                    copyModelAssetsDir(dirName, modelDir)
                    try { stamp.writeText("1") } catch (_: Exception) {}
                }
            }
        }
    }

    private fun copyModelAssetsDir(dirName: String, modelDir: File) {
        try {
            context.assets.list("models/$dirName")?.forEach { filename ->
                context.assets.open("models/$dirName/$filename").use { input ->
                    File(modelDir, filename).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            Log.i(TAG, "Model extracted: $dirName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract model: $dirName", e)
        }
    }

    private fun copyModelAssets(model: SRModel) {
        val modelDir = getModelDir(model)
        try {
            context.assets.list("models/${model.modelDirName}")?.forEach { filename ->
                context.assets.open("models/${model.modelDirName}/$filename").use { input ->
                    File(modelDir, filename).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            Log.i(TAG, "Model extracted: ${model.modelDirName}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract model: ${model.modelDirName}", e)
        }
    }
}
