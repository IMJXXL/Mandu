package com.mangareader.native.curl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * ★ 翻页特效一期：仿真卷页渲染器（形变数学为净室重建）。
 *
 * 要点（逆向实锤 → 本文实现）：
 * - 26×26 顶点 / 25×25 单元 / 3750 索引，一次构建复用（同一拓扑）
 * - shader 原文照搬：vertex 带 aShade 逐顶点明暗，fragment 只乘亮度（见 VERT/FRAG）
 * - 进度语义：curlProgress ∈ [25.0, -3.125]；25=平铺、-3.125=翻完
 *   归一化 t = (25 - progress) / 26.25 ∈ [0,1]
 * - 手势映射与动画参数在 CortexPagerView 中（26.25 行程、250/300ms 动画），本类只渲染
 *
 * 净室重建说明：jadx 未反出原 onDrawFrame 逐行（经 dexdump 确认其每帧仅 1 次 glDrawElements
 * + ≤3 次纹理绑定的量级）。本实现用标准圆柱卷页模型 + cos 明暗，锚点用逆向的三组进度值校验。
 *
 * 线程模型：一切 GL 调用只发生在 GL 线程（onSurfaceCreated/Changed/DrawFrame）。
 * 位图上传走 [uploadQueue]，由 onDrawFrame 开头排水——跨线程零共享状态。
 */
class CurlRenderer : GLSurfaceView.Renderer {

    enum class Slot { LEFT, FRONT, RIGHT }

    /** 正在卷曲的槽位；null = 静止平铺 FRONT。
     *  语义：向前翻（下一页）时卷起 FRONT、底下露出 nextSlot（LTR=RIGHT/RTL=LEFT）；
     *  向后翻时卷起 prevSlot（上一页）盖在 FRONT 上。 */
    @Volatile var curlingSlot: Slot? = null
    @Volatile var progress: Float = PROGRESS_FLAT
    @Volatile var rtl: Boolean = false
    @Volatile var bgColor: FloatArray = floatArrayOf(0.067f, 0.067f, 0.067f, 1f)

    /** FRONT 位图纵横比（宽/高）；surface 重建/换书时由视图层更新 */
    @Volatile var pageAspect: Float = 0.7f

    private val uploadQueue = ConcurrentLinkedQueue<Pair<Slot, Bitmap>>()
    /** 轮转中被挤出的孤儿纹理 id——GL 删除只能上 GL 线程（context 线程私有），onDrawFrame 排水 */
    private val pendingDeletes = ConcurrentLinkedQueue<Int>()
    /** 主线程（rotate/upload 入队）与 GL 线程（draw/drain）并发读写——必须并发容器 */
    private val textures = java.util.concurrent.ConcurrentHashMap<Slot, Int>()
    private var viewW = 1
    private var viewH = 1

    private var prog = 0
    private var aPos = 0; private var aUV = 0; private var aShade = 0
    private var uMVP = 0; private var uTex = 0
    private val mvp = FloatArray(16)

    // 26×26 顶点网格：每顶点 6 float（x,y,z,u,v,shade）
    private val vertexArr = FloatArray(26 * 26 * 6)
    private val vertexBuffer: FloatBuffer
    private val indexBuffer: ShortBuffer
    private var dirtyMesh = true
    private var lastT = -1f
    private var lastCurlKey = -2 // progress+slot 组合签名，变化才重建网格

    companion object {
        const val PROGRESS_FLAT = 25.0f      // 平铺（原值）
        const val PROGRESS_FULL = -3.125f    // 翻完（原值）
        const val PROGRESS_SPAN = 26.25f     // 平铺→翻完整行程（原值 25-(-1.25)）

        // ★ Shader 原文照搬自逆向提取（Q2/C0554j onSurfaceCreated 内嵌字符串）
        private const val VERT = "uniform mat4 uMVP;\n" +
                "attribute vec3 aPos;\nattribute vec2 aUV;\nattribute float aShade;\n" +
                "varying vec2 vUV;\nvarying float vShade;\n" +
                "void main() {\n    gl_Position = uMVP * vec4(aPos, 1.0);\n    vUV = aUV;\n    vShade = aShade;\n}"
        private const val FRAG = "precision mediump float;\nuniform sampler2D uTex;\n" +
                "varying vec2 vUV;\nvarying float vShade;\n" +
                "void main() {\n    vec4 c = texture2D(uTex, vUV);\n    gl_FragColor = vec4(c.rgb * vShade, 1.0);\n}"
    }

    init {
        // 索引一次构建：25×25 单元 × 6（与原实现 identical）
        val idx = ShortArray(3750)
        var p = 0
        for (i in 0 until 25) {
            for (j in 0 until 25) {
                val s = (i * 26 + j).toShort()
                idx[p++] = s; idx[p++] = (s + 1).toShort(); idx[p++] = (s + 26).toShort()
                idx[p++] = (s + 1).toShort(); idx[p++] = (s + 27).toShort(); idx[p++] = (s + 26).toShort()
            }
        }
        indexBuffer = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            .put(idx).also { it.position(0) }
        vertexBuffer = ByteBuffer.allocateDirect(vertexArr.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }

    /** 任意线程调用：位图入队，GL 线程下一帧上传 */
    fun upload(slot: Slot, bmp: Bitmap) = uploadQueue.add(slot to bmp)

    fun dropAllTextures() {
        uploadQueue.clear()
        textures.entries.forEach { it.setValue(0) }
    }

    /** 向前翻完成（主线程调用）：FRONT←旧nextSlot；旧FRONT→prevSlot；旧prevSlot 纹理入删除队列；nextSlot 置 0 待补载 */
    fun rotateForward() {
        val nextS = if (rtl) Slot.LEFT else Slot.RIGHT
        val prevS = if (rtl) Slot.RIGHT else Slot.LEFT
        val front = textures[Slot.FRONT] ?: 0
        val next = textures[nextS] ?: 0
        val oldPrev = textures[prevS] ?: 0
        if (oldPrev != 0) pendingDeletes.add(oldPrev)
        textures[Slot.FRONT] = next
        textures[prevS] = front
        textures[nextS] = 0
    }

    /** 向后翻完成（主线程调用）：FRONT←旧prevSlot；旧FRONT→nextSlot；旧nextSlot 纹理入删除队列；prevSlot 置 0 待补载 */
    fun rotateBackward() {
        val nextS = if (rtl) Slot.LEFT else Slot.RIGHT
        val prevS = if (rtl) Slot.RIGHT else Slot.LEFT
        val front = textures[Slot.FRONT] ?: 0
        val prev = textures[prevS] ?: 0
        val oldNext = textures[nextS] ?: 0
        if (oldNext != 0) pendingDeletes.add(oldNext)
        textures[Slot.FRONT] = prev
        textures[nextS] = front
        textures[prevS] = 0
    }

    /** 当前页是否已有纹理（视图层 isCurrentPageReady 的数据源） */
    fun hasFrontTexture(): Boolean = (textures[Slot.FRONT] ?: 0) != 0 || staticFrontPending()

    private fun staticFrontPending(): Boolean = uploadQueue.any { it.first == Slot.FRONT }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        prog = buildProgram()
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        aUV = GLES20.glGetAttribLocation(prog, "aUV")
        aShade = GLES20.glGetAttribLocation(prog, "aShade")
        uMVP = GLES20.glGetUniformLocation(prog, "uMVP")
        uTex = GLES20.glGetUniformLocation(prog, "uTex")
        textures.clear() // context 丢失后 id 无效，由视图层重新 upload 三槽
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        dirtyMesh = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width.coerceAtLeast(1); viewH = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewW, viewH)
        // 45° FOV 透视 + 相机后移，页高精确填满视图高（fit-center 由页纵横比控宽）
        val aspect = viewW.toFloat() / viewH
        val proj = FloatArray(16); val model = FloatArray(16)
        Matrix.perspectiveM(proj, 0, 45f, aspect, 0.5f, 20f)
        Matrix.setIdentityM(model, 0)
        // 相机在 z=-2.2 处：半视野高 tan(22.5°)*2.2
        Matrix.translateM(model, 0, 0f, 0f, -2.2f)
        Matrix.multiplyMM(mvp, 0, proj, 0, model, 0)
        dirtyMesh = true
    }

    override fun onDrawFrame(gl: GL10?) {
        drainUploads()
        drainDeletes()
        GLES20.glClearColor(bgColor[0], bgColor[1], bgColor[2], 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val front = textures[Slot.FRONT] ?: 0
        if (front == 0) return
        GLES20.glUseProgram(prog)
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        val slot = curlingSlot
        val t = ((PROGRESS_FLAT - progress) / PROGRESS_SPAN).coerceIn(0f, 1f)
        if (slot == null || t <= 0f || t >= 1f) {
            // 静止/翻页两端态：画 FRONT 平面
            val flat = if (slot == Slot.LEFT) textures[Slot.LEFT] ?: front else front
            rebuildMesh(-1f, false)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, flat)
            drawMesh()
            return
        }
        // 卷曲进行中：先画被揭开的底层页（平），再画卷曲页
        val nextTex = textures[if (rtl) Slot.LEFT else Slot.RIGHT] ?: 0
        val (under, curl, mirror) = when (slot) {
            Slot.FRONT -> Triple(nextTex, front, rtl)                                  // 向前翻：卷走当前页露出下一页
            Slot.LEFT -> Triple(front, textures[Slot.LEFT] ?: front, !rtl)            // 向后翻：卷起上一页盖在当前页上
            else -> Triple(0, front, rtl)
        }
        if (under != 0) {
            rebuildMesh(-1f, false)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, under)
            drawMesh()
        }
        if (curl != 0) {
            rebuildMesh(t, mirror)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curl)
            drawMesh()
        }
    }

    private fun drainDeletes() {
        while (true) {
            val id = pendingDeletes.poll() ?: return
            GLES20.glDeleteTextures(1, intArrayOf(id), 0)
        }
    }

    /**
     * 净室重建：圆柱卷页形变。
     * t<=0：整页平面。0<t<1：折缝 x_c 从右缘外侧扫向左缘外侧；x>x_c 的顶点绕 (x_c, r) 圆柱卷起，
     * φ>π 部分摊平折回左侧（书页已翻面），该段 UV 水平镜像（背面把纸当镜像看）。
     * 明暗：卷筒外侧 1.0 → 内侧最低 0.45。
     */
    private fun rebuildMesh(t: Float, mirror: Boolean) {
        val key = (if (t <= 0f) 0 else (t * 1000).toInt() + 1) * if (mirror) -1 else 1
        if (key == lastCurlKey) return
        lastCurlKey = key
        val pageH = 2f * tan(Math.toRadians(22.5)).toFloat() * 2.2f   // 视野高度内页高（世界坐标）
        val pageW = pageH * pageAspect
        val halfW = pageW / 2f
        val halfH = pageH / 2f
        val cX = pageW * (1.08f - 1.16f * t) - halfW                    // 折缝：右缘外 → 左缘外
        val r = (pageW * (0.32f - 0.27f * t)).coerceAtLeast(pageW * 0.05f)
        var v = 0
        for (i in 0..25) {           // 行：y 从上到下
            val y = halfH - i * (pageH / 25f)
            for (j in 0..25) {       // 列：x 从左到右
                var x = -halfW + j * (pageW / 25f)
                var z = 0f
                var shade = 1f
                var u = j / 25f
                if (t > 0f && x > cX) {
                    val phi = (x - cX) / r
                    if (phi <= PI.toFloat()) {
                        x = cX + r * sin(phi)
                        z = r * (1f - cos(phi))
                        shade = 1f - 0.55f * sin((phi * 0.5f).coerceIn(0f, PI.toFloat()))
                    } else {
                        // 已完全翻面部分：摊平在 φ=π 平面左侧回收
                        x = cX - ((x - cX) - PI.toFloat() * r)
                        z = 2f * r
                        shade = 0.45f
                        u = 1f - u    // 背面镜像
                    }
                }
                if (mirror) x = -x
                vertexArr[v++] = x
                vertexArr[v++] = y
                vertexArr[v++] = z
                vertexArr[v++] = if (mirror) 1f - u else u
                vertexArr[v++] = 1f - (i / 25f)
                vertexArr[v++] = shade
            }
        }
        vertexBuffer.put(vertexArr).position(0)
    }

    private fun drawMesh() {
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aUV)
        GLES20.glEnableVertexAttribArray(aShade)
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 24, vertexBuffer)
        vertexBuffer.position(3)
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 24, vertexBuffer)
        vertexBuffer.position(5)
        GLES20.glVertexAttribPointer(aShade, 1, GLES20.GL_FLOAT, false, 24, vertexBuffer)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 3750, GLES20.GL_UNSIGNED_SHORT, indexBuffer)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUV)
        GLES20.glDisableVertexAttribArray(aShade)
    }

    private fun drainUploads() {
        while (true) {
            val (slot, bmp) = uploadQueue.poll() ?: return
            if (bmp.isRecycled) continue
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            textures[slot]?.takeIf { it != 0 }?.let { old -> GLES20.glDeleteTextures(1, intArrayOf(old), 0) }
            textures[slot] = ids[0]
        }
    }

    fun clearTexturesGl() {
        val dels = textures.values.filter { it != 0 }.toIntArray()
        if (dels.isNotEmpty()) GLES20.glDeleteTextures(dels.size, dels, 0)
        textures.clear()
    }

    private fun compile(type: Int, src: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, src)
        GLES20.glCompileShader(id)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("curl shader compile failed: " + GLES20.glGetShaderInfoLog(id))
        return id
    }

    private fun buildProgram(): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERT)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAG)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs)
        return p
    }
}
