package com.mangareader.sr

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * 超分运行时 UI 状态（会话级，不落盘）：作用域默认"关闭"，每本书每次进阅读器都要手动开启；
 * 「始终开启」(SCOPE_ALWAYS) 是唯一例外——全局持久，落盘恢复。
 * 已批量离线超分过的内容不走这里——产物阅读时自动生效（见 SrPipeline.getCached）。
 */
object SrUiState {

    /** 超分状态节拍器：调度开始/结束/出结果时 +1，状态指示器读它实现实时刷新 */
    val srTick = androidx.compose.runtime.mutableIntStateOf(0)
    fun bumpSrTick() { srTick.intValue++ }

    /** 超分作用域：0=关闭 1=仅当前页 2=当前整本 3=始终开启（全局+持久） */
    val scope = mutableIntStateOf(0)

    /** 前后对比模式是否打开（由阅读器底栏"对比"按钮控制） */
    val compareActive = mutableStateOf(false)

    /** 正在超分运算的页（Compose 快照，自动重组相关 UI） */
    val computingPages = mutableSetOf<Int>()

    /** 当前阅读的书（面板显示用） */
    @Volatile
    var currentBookId: Long = -1L

    @Volatile
    var currentBookTitle: String = ""

    const val SCOPE_OFF = 0
    const val SCOPE_PAGE = 1
    const val SCOPE_BOOK = 2
    /** ★ 始终开启（2026-10-11 用户需求）：影响全局+持久记忆——所有书一直生效、进书/切书
     *  不重置、重启自动恢复（落盘 sr_scope_always，见 SrSettings/SrCore.init），直到手动关闭 */
    const val SCOPE_ALWAYS = 3

    fun resetForBook(bookId: Long, bookTitle: String) {
        // ★ SCOPE_ALWAYS 不随进书/切书重置；其余作用域维持"每次进书默认关闭"的既定语义
        if (scope.intValue != SCOPE_ALWAYS) scope.intValue = SCOPE_OFF
        compareActive.value = false
        currentBookId = bookId
        currentBookTitle = bookTitle
    }
}
