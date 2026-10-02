package com.mangareader.sr

import android.content.Context
import android.util.Log
import java.io.File

/**
 * NPU 模型插件数据面：插件条目 + 注册表 + 档位映射 + context 文件落盘。
 *
 * 模型包 = 纯资产 APK（无代码无组件，com.mangareader.model.* 前缀、与主程序同签），
 * 内含 assets/<assetDir>/<stem>.v<arch>.bin（每 HTP 代一个 context）+ assets/models.json 清单。
 * 发现逻辑在 [NpuModelPluginScanner]；这里只管消费。
 */
data class NpuModelEntry(
    val id: String,
    val stem: String,
    val label: String,
    val scale: Int,
    /** 瓦片接缝像素：输入瓦片边界被网络感受野吃掉的部分（打包工具从 ONNX 推导，勿手改） */
    val padding: Int,
    val arches: List<Int>,
    val assetDir: String,
    val sourcePackage: String,
)

object NpuModelRegistry {

    private const val TAG = "NpuModels"

    @Volatile
    private var entries: List<NpuModelEntry> = emptyList()

    @Volatile
    private var scanned = false

    /** 进程内扫一次；设置面板 resume 时调 [refresh] 重扫（装/卸插件立刻可见） */
    fun ensureScanned(context: Context) {
        if (scanned) return
        synchronized(this) {
            if (scanned) return
            entries = NpuModelPluginScanner.scan(context.applicationContext)
            scanned = true
            Log.i(TAG, "scanned ${entries.size} NPU models")
        }
    }

    fun refresh(context: Context): List<NpuModelEntry> = synchronized(this) {
        entries = NpuModelPluginScanner.scan(context.applicationContext)
        scanned = true
        entries
    }

    fun all(): List<NpuModelEntry> = entries

    /** 当前设备代可用的条目 */
    fun forDevice(arch: Int): List<NpuModelEntry> = entries.filter { arch in it.arches }

    /** 本机是否已装可用模型包（任一条目覆盖当前代号） */
    fun hasPackageFor(arch: Int): Boolean = forDevice(arch).isNotEmpty()

    // ── 档位映射：SRModel + 降噪强度 → context stem（与打包脚本命名严格一致）──
    // 现有档位里 NPU 化的两个：CUGAN 2x（按降噪强度三变体）+ 动漫V3 2x（无降噪输入）。
    // waifu2x_1x / 4x 大屏暂无 NPU 版；快速优化/无损放大是纯算法档，与 NPU 无关。

    fun supports(model: SRModel, denoise: DenoiseLevel, arch: Int): Boolean =
        stemFor(model, denoise)?.let { stem ->
            entries.any { it.stem == stem && arch in it.arches }
        } == true

    fun stemFor(model: SRModel, denoise: DenoiseLevel): String? = when (model.key) {
        "realcugan_2x_conservative" -> when (denoise) {
            DenoiseLevel.LIGHT -> "realcugan-se-2x-denoise1x"
            DenoiseLevel.STRONG -> "realcugan-se-2x-denoise3x"
            DenoiseLevel.OFF -> "realcugan-se-2x-conservative"
        }
        "animevideov3_2x" -> "animevideov3-2x"
        else -> null
    }

    /** 解析出可用的 NPU 条目；null = 该档位+强度+代号组合没有模型包 */
    fun resolve(model: SRModel, denoise: DenoiseLevel, arch: Int): NpuModelEntry? {
        val stem = stemFor(model, denoise) ?: return null
        return entries.firstOrNull { it.stem == stem && arch in it.arches }
    }

    /**
     * 把插件的 context 资产原子复制到 cacheDir/npu-contexts/（与 ncnn 模型解包同款：
     * .tmp 写完再 rename，读侧永远只见完整文件），返回绝对路径供 QnnEngine.init。
     */
    fun prepareContextFile(context: Context, entry: NpuModelEntry, arch: Int): String? = try {
        val pluginContext = context.createPackageContext(entry.sourcePackage, android.content.Context.CONTEXT_IGNORE_SECURITY)
        val fileName = "${entry.stem}.v$arch.bin"
        val dir = File(context.cacheDir, "npu-contexts")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) null
        else {
            val out = File(dir, fileName)
            val assetPath = "${entry.assetDir}/$fileName"
            val tmp = File(dir, "$fileName.tmp")
            pluginContext.assets.open(assetPath).use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            if (!tmp.renameTo(out)) {
                // rename 被拒（极端文件系统）退回直接覆盖写，至少可用
                pluginContext.assets.open(assetPath).use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            }
            tmp.delete()
            out.absolutePath
        }
    } catch (e: Exception) {
        Log.w(TAG, "prepare context failed: ${entry.stem} v$arch: ${e.message}")
        null
    }

    /** 清掉落盘的 context 缓存（插件升级/换包后由版本戳机制自动重提，一般无需手动） */
    fun clearContextCache(context: Context) {
        File(context.cacheDir, "npu-contexts").deleteRecursively()
    }
}
