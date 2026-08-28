package com.mangareader.sr

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

object VulkanHelper {

    fun isVulkanSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val pm = context.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION) ||
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
    }

    /** 版本信息文本（关于页展示用），在 native 库不可用时给出原因。 */
    fun describe(context: Context): String {
        val feature = isVulkanSupported(context)
        val gpus = gpuCount
        val device = if (gpus > 0) getDeviceInfo(0) else "不可用"
        return "Vulkan 特性: ${if (feature) "支持" else "不支持"} / GPU 数: $gpus / 设备: $device"
    }

    val gpuCount: Int by lazy {
        try {
            if (!RealESRGANProcessor.nativeLibraryLoaded) return@lazy 0
            nativeGetGpuCount()
        } catch (e: Throwable) {
            Log.e("SrVulkan", "nativeGetGpuCount failed: ${e.message}")
            0
        }
    }

    fun getDeviceInfo(gpuid: Int): String {
        return try {
            nativeGetDeviceInfo(gpuid)
        } catch (_: Throwable) {
            "Unknown"
        }
    }

    private external fun nativeGetGpuCount(): Int
    private external fun nativeGetDeviceInfo(gpuid: Int): String
}
