package eu.wewox.pagecurl.page

/**
 * ★ 翻页几何探针（2026-09-30 右上角纹块定位用）：lib 模块不依赖 app，经 sink 回调把
 *   绘制帧的真实几何（节点尺寸/纸背位图/透明度/多边形顶点）交给宿主记入诊断日志。
 *   内置 600ms 节流，翻页动画期间约 1.6 条/秒，不影响性能。
 */
object CurlGeomProbe {

    @Volatile var sink: ((String) -> Unit)? = null

    @Volatile private var lastAt = 0L

    fun mark(msg: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastAt < 600L) return
        lastAt = now
        val s = sink ?: return
        try { s(msg) } catch (_: Throwable) {}
    }

    /** 节流通过才构建消息：翻页动画每帧调用本方法，msg 构建含 joinToString，
     *  放在节流前 = 每帧白付 ~200B 字符串成本 */
    fun markLazy(build: () -> String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastAt < 600L) return
        lastAt = now
        val s = sink ?: return
        try { s(build()) } catch (_: Throwable) {}
    }
}
