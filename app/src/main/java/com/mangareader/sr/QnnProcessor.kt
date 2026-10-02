package com.mangareader.sr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

/**
 * QNN/HTP（高通 NPU）处理器：实现与 ncnn 引擎相同的 [SuperResolutionProcessor] 接口，
 * 批量/实时/预热三条管线零改动获得 NPU 能力。
 *
 * 生命周期由 SuperResolutionManager 串行驱动（同一时刻只有一个 processor 在位），
 * QNN 后端是进程级单例（qnn_backend 内部状态），init/shutdown 即加载/卸载 context。
 * 瓦片循环在 native（256 输入瓦片、按 [NpuModelEntry.padding] 缝合）， abort 在瓦片边界生效。
 *
 * 黑白优化与 ncnn 路径同语义：NPU 产出 2x 图后再叠加 SrBwProcessor（彩色页调用方已置空 bwConfig）。
 */
class QnnProcessor(
    override val model: SRModel,
    private val entry: NpuModelEntry,
    private val contextPath: String,
) : SuperResolutionProcessor {

    private val libDir: String = QnnEngine.libraryDir(SrCore.appContext)

    override val isReady: Boolean
        get() = QnnEngine.isInitialized()

    override suspend fun initialize(modelPath: String, gpuid: Int) {
        // contextPath 形参由 Manager 传 ncnn 目录；NPU 用构造时传入的 context 路径
        val ok = QnnEngine.init(contextPath, libDir, entry.padding)
        Log.i(TAG, "QNN init ${entry.stem} ok=$ok padding=${entry.padding}")
        if (!ok) {
            // 中性打点（对照表 enq）：s=初始化失败阶段码，0=插件 context 提取失败、1-9=native 各阶段
            val stage = QnnEngine.lastInitStage()
            val status = QnnEngine.lastInitStatus()
            runCatching {
                com.mangareader.DiagTracer.markStatic(
                    "enhance: npu load s=$stage c=$status"
                )
            }
            // DSP 传输层被拒（14001）＝厂商封锁 CDSP 无签名域，重试无意义——记住结论供面板明示
            if (stage == 7 && status == 14001L) {
                runCatching { SrCore.settings.npuDeviceBlock = true }
            }
            throw IllegalStateException("QNN init failed for ${entry.stem}")
        }
    }

    override fun process(
        input: Bitmap,
        scale: Int,
        denoiseLevel: DenoiseLevel,
        denoiseStrength: Float,
        bwConfig: MangaBWPostProcessConfig?,
    ): Bitmap {
        if (!isReady) return input
        if (input.isRecycled) return input
        // native 只收 RGBA_8888（qnn_jni 防御性再查一次）
        val argb = if (input.config == Bitmap.Config.ARGB_8888) input
        else input.copy(Bitmap.Config.ARGB_8888, false) ?: return input

        val upscaled = QnnEngine.clearAbort().let { QnnEngine.process(argb) } ?: return input

        // 黑白优化逐页叠加（与 ncnn nativeProcess 内的 bw 分支同参语义）
        if (bwConfig != null && bwConfig.grayLevels >= 2 && !upscaled.isRecycled) {
            return SrBwProcessor.process(upscaled, bwConfig.grayLevels, bwConfig.densityCorrection) ?: upscaled
        }
        return upscaled
    }

    override fun abort() {
        QnnEngine.abort()
    }

    override fun release() {
        // QNN 后端是单例：卸载 context 即释放；下次 NPU 初始化再加载
        QnnEngine.shutdown()
    }

    companion object {
        private const val TAG = "QnnProcessor"

        /**
         * 工厂：按当前档位+降噪+设备代号解析插件 context 并构造处理器；
         * null = 该组合无 NPU 模型（调用方回落 GPU/CPU）。
         */
        fun create(context: Context, model: SRModel, denoise: DenoiseLevel, arch: Int): QnnProcessor? {
            if (!QnnEngine.nativeLoaded) return null
            val entry = NpuModelRegistry.resolve(model, denoise, arch) ?: return null
            val path = NpuModelRegistry.prepareContextFile(context, entry, arch)
            if (path == null) {
                // s=0：还没到 native，插件 context 资产提取失败（对照表 enq）
                runCatching { com.mangareader.DiagTracer.markStatic("enhance: npu load s=0") }
                return null
            }
            return QnnProcessor(model, entry, path)
        }
    }
}
