package com.mangareader.sr

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * 超分运行时 UI 状态（会话级，不落盘）：作用域默认"关闭"，每本书每次进阅读器都要手动开启。
 * 已批量离线超分过的内容不走这里——产物阅读时自动生效（见 SrPipeline.getCached）。
 */
object SrUiState {

    /** 超分状态节拍器：调度开始/结束/出结果时 +1，状态指示器读它实现实时刷新 */
    val srTick = androidx.compose.runtime.mutableIntStateOf(0)
    fun bumpSrTick() { srTick.intValue++ }

    /** 超分作用域：0=关闭 1=仅当前页 2=当前整本 */
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

    fun resetForBook(bookId: Long, bookTitle: String) {
        scope.intValue = SCOPE_OFF
        compareActive.value = false
        currentBookId = bookId
        currentBookTitle = bookTitle
    }
}
