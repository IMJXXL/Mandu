package com.mangareader.sr

import android.content.Context

/**
 * NPU 传输层实测（用户在面板主动点「测试 NPU」触发，IO 线程调用）：
 * 用当前设备代的任一插件 context 真实加载一次 QNN——这是唯一能探出「厂商封锁 CDSP」的
 * 办法：CPU 侧架构探测成功 ≠ DSP 会话能建立（MIUI 部分机型封锁无签名域，传输层 14001）。
 *
 * 结果三态写入 SrSettings：PASS→npuTestOk=true（解锁 NPU 档）；
 * BLOCKED→npuDeviceBlock=true（面板明示系统限制）；FAILED→仅提示失败原因。
 */
object NpuTransportProbe {

    enum class Result { PASS, BLOCKED, FAILED, NO_MODEL, BUSY }

    fun run(context: Context): Result {
        // 礼让正在进行的 NPU 推理（native 层虽有 g_lock 兜底，这里主动避让，
        // 免得探测按钮卡在"正在实测"等长任务跑完）
        var waited = 0
        while (SrCore.inferenceInFlight && waited < 5000) {
            Thread.sleep(100)
            waited += 100
        }
        if (SrCore.inferenceInFlight) return Result.BUSY
        val arch = QnnEngine.architecture
        if (arch <= 0) return Result.FAILED
        val entry = NpuModelRegistry.forDevice(arch).firstOrNull() ?: return Result.NO_MODEL
        val path = NpuModelRegistry.prepareContextFile(context, entry, arch)
            ?: return Result.FAILED
        val ok = QnnEngine.init(path, QnnEngine.libraryDir(context), entry.padding)
        if (ok) {
            // 试水成功即卸载，恢复干净状态；正式使用时路由会完整加载
            QnnEngine.shutdown()
            SrCore.settings.npuTestOk = true
            SrCore.settings.npuDeviceBlock = false
            return Result.PASS
        }
        if (QnnEngine.lastInitStage() == 7 && QnnEngine.lastInitStatus() == 14001L) {
            SrCore.settings.npuDeviceBlock = true
            SrCore.settings.npuTestOk = false
            return Result.BLOCKED
        }
        return Result.FAILED
    }
}
