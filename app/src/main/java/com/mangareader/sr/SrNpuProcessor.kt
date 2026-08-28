package com.mangareader.sr

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import java.io.File

/**
 * QNN NPU 后端（骁龙8+ 及以上）。
 * 不满足条件时全部安全降级，面板 UI 按 status 显示原因。
 */
object SrNpuProcessor {

    enum class Status { READY, UNSUPPORTED_OS, UNSUPPORTED_SOC, NO_CONTEXT, RUNTIME_MISSING }

    private const val TAG = "SrNpu"

    /** 用户主动开关（false = 即使可用也不用；跟随 Vulkan/CPU 后备） */
    @Volatile
    var userEnabled: Boolean = true

    @Volatile
    private var initialized = false

    @Volatile
    var usable = false
        private set

    @Volatile
    var status: Status = Status.UNSUPPORTED_OS
        private set

    fun describeSupportedSoC(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                Build.SOC_MODEL.ifEmpty { "未知" }
            } catch (_: Throwable) {
                "未知"
            }
        } else "未知"
    }

    fun check(context: Context): Boolean {
        // 上次 NPU 推理崩溃过 → 永久停用（直到用户清线）
        if (SrCore.isNpuBlacklisted()) return false
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    evaluate(context)
                    initialized = true
                }
            }
        }
        return usable && userEnabled
    }

    private fun evaluate(context: Context) {
        usable = false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            status = Status.UNSUPPORTED_OS
            return
        }
        val socOk = try {
            Build.SOC_MODEL.uppercase() in SUPPORTED_SOCS
        } catch (_: Throwable) {
            false
        }
        if (!socOk) {
            status = Status.UNSUPPORTED_SOC
            return
        }
        // QNN 只内置了 arm64-v8a 的原生符号；32位设备直接判不可用（否则 external fun 会 UnsatisfiedLinkError）
        val is64 = try {
            (Build.SUPPORTED_64_BIT_ABIS?.isNotEmpty() == true) ||
                Build.CPU_ABI.contains("64")
        } catch (_: Throwable) { false }
        if (!is64) {
            status = Status.RUNTIME_MISSING
            return
        }
        if (!RealESRGANProcessor.nativeLibraryLoaded) {
            status = Status.RUNTIME_MISSING
            return
        }
        val runtimeOk = try {
            nativeProbe()
        } catch (_: Throwable) {
            false
        }
        if (!runtimeOk) {
            status = Status.RUNTIME_MISSING
            return
        }
        if (!contextFor("realcugan-2x-conservative").exists()) {
            status = Status.NO_CONTEXT
            return
        }
        status = Status.READY
        usable = true
    }

    fun statusText(): String = when (status) {
        Status.READY -> "本机芯片满足（${describeSupportedSoC()}），NPU 加速可用"
        Status.UNSUPPORTED_OS -> "需要 Android 12 及以上（本机系统过低）"
        Status.UNSUPPORTED_SOC -> "需要骁龙8+ Gen1 及更新芯片（本机 ${describeSupportedSoC()} 不支持）"
        Status.NO_CONTEXT -> "NPU 模型图包缺失"
        Status.RUNTIME_MISSING -> "NPU 运行时未初始化"
    }

    fun contextFor(modelKey: String): File {
        // 模型 key（下划线式） ↔ assets 文件名（横杠式）
        val fileKey = modelKey.replace('_', '-')
        val soc = describeSupportedSoC()
        return File(contextDir(), "$fileKey.$soc.bin")
    }

    private fun contextDir(): File =
        File(SrCore.appContext.filesDir, "npu").also { it.mkdirs() }

    /** 把 assets/npu/ 下的 .bin 解包到 filesDir/npu（幂等） */
    fun ensureContextsExtracted(context: Context) {
        try {
            val outDir = contextDir()
            val stamp = File(outDir, ".v1")
            if (stamp.exists()) return
            context.assets.list("npu")?.forEach { name ->
                if (name.endsWith(".bin")) {
                    context.assets.open("npu/$name").use { input ->
                        File(outDir, name).outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            stamp.writeText("1")
        } catch (_: Exception) {
        }
    }

    /** NPU 运行时 so 解包到真实文件路径（QNN FastRPC 需要真实文件，不能从几乎）
     *  返回可用的 lib 目录路径；null = 不可用 */
    fun ensureRuntimeLibsExtracted(context: Context): String? {
        return try {
            val outDir = File(context.filesDir, "npu/lib").also { it.mkdirs() }
            val stamp = File(outDir, ".v2")
            if (stamp.exists()) return outDir.absolutePath
            val soNames = listOf(
                "libQnnHtp.so", "libQnnSystem.so", "libQnnHtpV69Stub.so",
                "libQnnHtpV69Skel.so", "libCalculator_skel.so",
            )
            soNames.forEach { name ->
                context.assets.open("npu_runtime/$name").use { input ->
                    File(outDir, name).outputStream().use { output -> input.copyTo(output) }
                }
            }
            stamp.writeText("1")
            outDir.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "NPU runtime libs extract failed", e)
            null
        }
    }

    /** 开机时评估可用性 */
    fun warmUp(context: Context) {
        ensureContextsExtracted(context)
        synchronized(this) {
            initialized = false
            usable = false
            status = Status.UNSUPPORTED_OS
            check(context)
        }
    }

    private var activeModelKey: String? = null

    /** 激活 NPU。modelKey 变化时自动重载 context（幂等） */
    fun activate(context: Context, modelKey: String = "realcugan-2x-conservative"): Boolean {
        if (!check(context)) return false
        return try {
            if (activeModelKey == modelKey) return true
            // 运行时 so(deployment)：先确保解包，只按文件找
            val libDir = ensureRuntimeLibsExtracted(context) ?: return false
            val ctxFile = contextFor(modelKey)
            if (!ctxFile.exists()) {
                Log.w(TAG, "no context for $modelKey, fallback default")
                val fallback = contextFor("realcugan-2x-conservative")
                if (!fallback.exists()) return false
                val ok2 = nativeInit(fallback.absolutePath, 16, libDir)
                if (ok2) activeModelKey = "realcugan-2x-conservative"
                ok2
            } else {
                val ok = nativeInit(ctxFile.absolutePath, 16, libDir)
                if (ok) activeModelKey = modelKey
                if (!ok) Log.e(TAG, "nativeInit failed: ${ctxFile.absolutePath}")
                ok
            }
        } catch (e: Throwable) {
            Log.e(TAG, "activate failed", e)
            false
        }
    }

    fun process(bitmap: Bitmap): Bitmap? {
        return try {
            if (!usable) null else nativeProcess(bitmap)
        } catch (_: Throwable) {
            null
        }
    }

    fun release() {
        try {
            nativeRelease()
        } catch (_: Throwable) {
        }
    }

    private val SUPPORTED_SOCS = setOf("SM8475", "SM8550", "SM8650", "SM8750", "SM8850")

    private external fun nativeProbe(): Boolean
    private external fun nativeInit(contextPath: String, padding: Int, libDir: String): Boolean
    private external fun nativeProcess(bitmap: Bitmap): Bitmap?
    private external fun nativeRelease()
}
