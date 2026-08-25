package com.mangareader

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {
    
    companion object {
        private const val TAG = "CrashHandler"
        private const val CRASH_DIR = "crash_logs"
    }
    
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    
    fun init() {
        Thread.setDefaultUncaughtExceptionHandler(this)
        Log.d(TAG, "CrashHandler initialized")
    }
    
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            saveCrashLog(thread, throwable)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash log", e)
        }
        
        defaultHandler?.uncaughtException(thread, throwable)
    }
    
    private fun saveCrashLog(thread: Thread, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val crashDir = File(context.getExternalFilesDir(null), CRASH_DIR)
        if (!crashDir.exists()) crashDir.mkdirs()
        
        val crashFile = File(crashDir, "crash_$timestamp.txt")
        
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        
        pw.println("=== 漫读 崩溃日志 ===")
        pw.println("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        pw.println("线程: ${thread.name}")
        pw.println()
        
        pw.println("--- 设备信息 ---")
        pw.println("品牌: ${Build.BRAND}")
        pw.println("型号: ${Build.MODEL}")
        pw.println("Android版本: ${Build.VERSION.RELEASE}")
        pw.println("SDK版本: ${Build.VERSION.SDK_INT}")
        pw.println("ABIS: ${Build.SUPPORTED_ABIS.joinToString()}")
        pw.println()
        
        pw.println("--- 崩溃堆栈 ---")
        throwable.printStackTrace(pw)
        pw.println()
        
        // 如果有原因链，也打印出来
        var cause = throwable.cause
        while (cause != null) {
            pw.println("--- Caused by ---")
            cause.printStackTrace(pw)
            cause = cause.cause
        }
        
        crashFile.writeText(sw.toString())
        
        Log.e(TAG, "Crash log saved to: ${crashFile.absolutePath}")
    }
    
    fun getLatestCrashLog(): String? {
        val crashDir = File(context.getExternalFilesDir(null), CRASH_DIR)
        if (!crashDir.exists()) return null
        
        val crashFiles = crashDir.listFiles()?.filter { it.name.startsWith("crash_") && it.name.endsWith(".txt") }
            ?.sortedByDescending { it.name }
        
        return crashFiles?.firstOrNull()?.readText()
    }
    
    fun getAllCrashLogs(): List<Pair<String, String>> {
        val crashDir = File(context.getExternalFilesDir(null), CRASH_DIR)
        if (!crashDir.exists()) return emptyList()
        
        return crashDir.listFiles()
            ?.filter { it.name.startsWith("crash_") && it.name.endsWith(".txt") }
            ?.sortedByDescending { it.name }
            ?.map { it.name to it.readText() }
            ?: emptyList()
    }
    
    fun clearOldCrashLogs(keepDays: Int = 7) {
        val crashDir = File(context.getExternalFilesDir(null), CRASH_DIR)
        if (!crashDir.exists()) return
        
        val cutoff = System.currentTimeMillis() - (keepDays * 24 * 60 * 60 * 1000L)
        crashDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
    }
}
