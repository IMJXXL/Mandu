package com.mangareader.viewer

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ★ 页表索引后台预建（2026-09-30，用户提议"扫描后后台做好索引"）：
 *   a: 压缩书（mobi/epub/zip…）与 d: 文件夹书的页表索引原本都在"第一次打开"时才建——
 *   慢 SD（Boyue 实测）上 a: 重扫整包要 25s。预热让第一次打开就秒开。
 *
 *   触发：① App 启动后台维护 ② 每次扫描/重扫入库完成。
 *   节流：单飞（running 标志）+ 每本之间 2.5s 间隔 + 单轮上限 60 本，
 *   只处理本地 a:/d: 且索引未落盘的书；listPages 自带磁盘索引写入，直接复用。
 */
object PageIndexWarmer {

    @Volatile private var running = false

    fun kick(ctx: Context, dao: com.mangareader.data.MangaDao) {
        if (running) return
        running = true
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val engine = ArchiveEngine(ctx.applicationContext)
                val all = try { dao.getAllBooksList() } catch (_: Exception) { emptyList() }
                val targets = all.map { it.filePath }
                    .filter { it.startsWith("a:") || it.startsWith("d:") }
                    .filter { !engine.pageIndexCached(it) }
                    .take(60)
                if (targets.isEmpty()) return@launch
                DiagMark.mark("pageIndex warm begin n=${targets.size}")
                var ok = 0
                targets.forEach { p ->
                    try {
                        val pages = engine.listPages(p)
                        if (pages.isNotEmpty()) ok++
                    } catch (_: Throwable) {}
                    delay(2500L)  // 慢 SD 上留喘息，不与用户操作抢 IO
                }
                DiagMark.mark("pageIndex warm done ok=$ok/${targets.size}")
            } catch (_: Throwable) {
            } finally {
                running = false
            }
        }
    }

    /** 打点走 DiagTracer（中性化短码纪律），单独包一层避免 viewer 层直接依赖 */
    private object DiagMark {
        fun mark(msg: String) {
            try { com.mangareader.DiagTracer.markStatic(msg) } catch (_: Throwable) {}
        }
    }
}
