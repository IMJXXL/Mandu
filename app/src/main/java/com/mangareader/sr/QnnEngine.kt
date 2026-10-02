package com.mangareader.sr

import android.util.Log
import java.io.File

/**
 * QNN/HTP（高通 NPU）引擎入口：JNI 封装 + 设备能力探测。
 *
 * 探测两步走（都来自 Komiho 实测经验，Apache-2.0）：
 *  1. CDSP 节点探测——部分机型有骁龙芯但固件把 CDSP 关了（只剩调试节点 rdbg_cdsp），
 *     QNN 架构探测却仍报出正常代号，NPU 初始化必败（Transport 14001）。所以先看 /dev。
 *  2. QNN 架构探测——非高通设备从 QNN 拿不到 ON_CHIP 设备，报 0；报出的代号即 HTP 代
 *     （69/73/75/79/81 = 骁龙 8+Gen1/8Gen2/8Gen3/8Elite/8EliteGen5），决定装哪代模型包。
 *
 * 线程约定：[architecture] 会 dlopen QNN 运行时，禁止主线程调用（SrCore/面板统一在 IO 协程探测）。
 */
object QnnEngine {

    private const val TAG = "QnnEngine"

    /** 打包了 context 的 HTP 代（模型流水线产出的五代；不在表内的代号一律视为不支持） */
    val SUPPORTED_ARCHS: Set<Int> = setOf(69, 73, 75, 79, 81)

    val nativeLoaded: Boolean by lazy {
        try {
            System.loadLibrary("sr_engine")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "sr_engine not loaded: ${e.message}")
            false
        }
    }

    /** libsr_engine.so 所在目录（QNN Skel 库的 ADSP_LIBRARY_PATH 指向这里） */
    fun libraryDir(context: android.content.Context): String =
        context.applicationInfo.nativeLibraryDir

    /**
     * CDSP 功能节点探测：/dev 下任何带 "cdsp" 且不是调试节点 rdbg_cdsp 的节点；
     * SELinux 不给 readdir 时退化为 stat 已知功能节点名。只 stat/readdir，从不 open。
     */
    @Volatile
    private var cdspAvailable: Boolean? = null
    val isCdspAvailable: Boolean
        get() {
            cdspAvailable?.let { return it }
            val functionalNodes = listOf(
                "/dev/glink_pkt_ctrl_cdsp",
                "/dev/glink_pkt_data_cdsp",
                "/dev/remoteproc-cdsp-md",
                "/dev/fastrpc-cdsp",
                "/dev/fastrpc-cdsp-unsigned",
            )
            val found = File("/dev").listFiles()
                ?.firstOrNull { f -> "cdsp" in f.name && f.name != "rdbg_cdsp" }?.name
                ?: functionalNodes.firstOrNull { File(it).exists() }
            val available = found != null
            Log.d(TAG, if (available) "CDSP available (/dev/$found)" else "CDSP unavailable (no functional node)")
            cdspAvailable = available
            return available
        }

    /** 探测过的 HTP 代；0 = 不可用（未探测/非高通/无 CDSP/运行时加载失败） */
    @Volatile
    private var cachedArch: Int = -1
    val architecture: Int
        get() {
            if (!nativeLoaded) return 0
            if (cachedArch >= 0) return cachedArch
            synchronized(this) {
                if (cachedArch >= 0) return cachedArch
                val arch = try {
                    // ensure_dsp_path 必须先于任何 dlopen(libQnnHtp.so)；空参数走 dladdr 自定位
                    nativeEnsureDspPath(null)
                    if (!nativeIsRuntimeAvailable()) 0 else nativeGetArchitecture()
                } catch (e: Throwable) {
                    Log.w(TAG, "QNN probe failed: ${e.message}")
                    0
                }
                Log.i(TAG, "Detected HTP architecture: v$arch")
                if (arch in SUPPORTED_ARCHS) cachedArch = arch else return 0
            }
            return cachedArch
        }

    /** 本机是否具备 NPU 条件（有 CDSP + QNN 报出受支持的代号）。探测结果缓存，进程内不变。 */
    val isDeviceCapable: Boolean
        get() = isCdspAvailable && architecture in SUPPORTED_ARCHS

    // ── JNI（qnn_jni.cpp；桩实现见同文件的 MIHON_ENABLE_QNN=0 分支）──

    @JvmStatic private external fun nativeEnsureDspPath(libDir: String?)
    @JvmStatic private external fun nativeIsRuntimeAvailable(): Boolean
    @JvmStatic private external fun nativeGetArchitecture(): Int
    @JvmStatic internal external fun nativeInitQnn(contextPath: String, libDir: String?, padding: Int): Boolean
    @JvmStatic internal external fun nativeIsQnnInitialized(): Boolean
    @JvmStatic internal external fun nativeGetQnnScale(): Int
    @JvmStatic internal external fun nativeProcessQnn(bitmap: android.graphics.Bitmap): android.graphics.Bitmap?
    @JvmStatic internal external fun nativeAbortQnn()
    @JvmStatic internal external fun nativeClearAbortQnn()
    @JvmStatic internal external fun nativeShutdownQnn()

    // Kotlin 侧便捷封装（初始化/处理/释放走 QnnProcessor；这里只暴露探测与生命周期）

    fun init(contextPath: String, libDir: String, padding: Int): Boolean =
        nativeLoaded && nativeInitQnn(contextPath, libDir, padding)

    fun isInitialized(): Boolean = nativeLoaded && try { nativeIsQnnInitialized() } catch (_: Throwable) { false }

    fun process(input: android.graphics.Bitmap): android.graphics.Bitmap? = nativeProcessQnn(input)

    fun abort() = runCatching { nativeAbortQnn() }

    fun clearAbort() = runCatching { nativeClearAbortQnn() }

    fun shutdown() = runCatching { nativeShutdownQnn() }

    /** 探测入口：先指 DSP 库路径再取代号。IO 线程调用。 */
    fun probe(libDir: String): Int {
        if (!nativeLoaded) return 0
        return try {
            nativeEnsureDspPath(libDir)
            architecture
        } catch (_: Throwable) {
            0
        }
    }

    /** HTP 代 → 骁龙平台名（UI 提示用；同代号兼容同代其它骁龙，仅展示参考） */
    fun archDisplayName(arch: Int): String = when (arch) {
        69 -> "骁龙 8+ Gen1 / Gen2 级"
        73 -> "骁龙 8 Gen 2 级"
        75 -> "骁龙 8 Gen 3 级"
        79 -> "骁龙 8 Elite 级"
        81 -> "骁龙 8 Elite Gen 5 级"
        else -> "未知"
    }
}
