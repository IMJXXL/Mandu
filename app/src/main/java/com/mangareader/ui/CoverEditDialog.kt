package com.mangareader.ui

import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mangareader.viewer.ArchiveEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 封面编辑全屏对话框（2026-09-26）。
 *
 * 流程：来源选择 → ①系统文件选择器（OpenDocument，用户从文件管理器任意目录找图）或
 * ②本卷内容（dir 树浏览器可下钻子文件夹/压缩包；档案书直出页网格）→ 取景框裁剪
 * （双指缩放+平移，框比例固定为书架封面卡 0.72）→ JPEG 90 落盘
 * covers/custom_{md5}.jpg → onResult("custom:<fileName>") 由调用方写 DB + 清缓存。
 *
 * 安全：所有解码 inSampleSize 钳制（低内存机 2048 / 其余 4096）防 OOM；网格缩略图 32MB
 * LRU；任何失败仅 Toast 不崩溃；远程书内容选择禁用（走相册），文案注明。
 */
private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif", "avif")
private val ARCHIVE_EXTS = setOf("zip", "cbz", "rar", "cbr", "7z", "cb7", "tar", "epub", "pdf", "mobi", "azw", "azw3", "pdb", "prc")

private fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase()
private fun decodeCap(ctx: android.content.Context): Int = try {
    val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as ActivityManager
    if (am.isLowRamDevice) 2048 else 4096
} catch (_: Exception) { 2048 }

private fun decodeBmpCapped(bytes: ByteArray, cap: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var s = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (s * 2) >= cap) s *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (oom: OutOfMemoryError) { System.gc(); null } catch (_: Exception) { null }
}

/** 编辑器阶段：来源选择 / 目录树浏览（dir 书）/ 页网格（档案书）/ 裁剪 */
private sealed class CoverStage {
    object Source : CoverStage()
    data class Browser(val path: String, val title: String) : CoverStage()
    data class Pages(val path: String, val title: String) : CoverStage()
    data class Crop(val bmp: Bitmap) : CoverStage()
}

@Composable
internal fun CoverEditDialog(
    book: com.mangareader.data.Book,
    engine: ArchiveEngine,
    ctx: android.content.Context,
    onResult: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val cap = remember { decodeCap(ctx) }
    val thumbCache = remember { ConcurrentHashMap<String, Bitmap>() }
    var stage by remember { mutableStateOf<CoverStage>(CoverStage.Source) }
    var toastMsg by remember { mutableStateOf<String?>(null) }
    toastMsg?.let { m ->
        LaunchedEffect(m) { android.widget.Toast.makeText(ctx, m, android.widget.Toast.LENGTH_SHORT).show(); toastMsg = null }
    }

    val isLocal = book.source == "local"
    val isTreeBook = book.filePath.startsWith("d:") || book.filePath.startsWith("z:")

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (_: Exception) { null }
                    ?.let { decodeBmpCapped(it, cap) }
            }
            if (bmp != null) stage = CoverStage.Crop(bmp) else toastMsg = "图片读取失败"
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val s = stage) {
                is CoverStage.Source -> Column(Modifier.fillMaxSize().padding(20.dp)) {
                    Spacer(Modifier.height(28.dp))
                    Text("设置封面", fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(book.title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    Spacer(Modifier.height(24.dp))
                    SourceCard("从文件选择", "从文件管理器选择图片，取景框裁剪后设为封面") {
                        pickImage.launch(arrayOf("image/*"))
                    }
                    Spacer(Modifier.height(12.dp))
                    if (isLocal) {
                        SourceCard(
                            if (isTreeBook) "从本卷内容选择（可进入子文件夹/压缩包）" else "从本卷页面选择",
                            "浏览本书内容，任意一张图设为封面",
                        ) {
                            stage = if (isTreeBook) CoverStage.Browser(book.filePath, book.title) else CoverStage.Pages(book.filePath, book.title)
                        }
                    } else {
                        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text(
                                "远程书暂不支持从内容中选择，请使用本地图片",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(14.dp),
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("取消") }
                    Spacer(Modifier.height(12.dp))
                }

                is CoverStage.Browser -> ContentBrowserStage(
                    engine, s.path, s.title, thumbCache,
                    onDescend = { item -> stage = CoverStage.Browser(item.subPath.ifEmpty { composeChildPath(s.path, item) }, item.name) },
                    onPickImage = { item ->
                        scope.launch {
                            val bytes = withContext(Dispatchers.IO) { engine.loadDirItemBytes(item) }
                            val bmp = bytes?.let { decodeBmpCapped(it, cap) }
                            if (bmp != null) stage = CoverStage.Crop(bmp) else toastMsg = "图片读取失败"
                        }
                    },
                    onOpenArchive = { item ->
                        val p = item.subPath.ifEmpty { composeChildPath(s.path, item) }
                        stage = CoverStage.Pages(p, item.name)
                    },
                    onBack = { stage = CoverStage.Source },
                )

                is CoverStage.Pages -> PageGridStage(
                    engine, s.path, s.title, thumbCache, cap,
                    onPick = { meta ->
                        scope.launch {
                            val bmp = withContext(Dispatchers.IO) {
                                val raw = try { engine.loadPageRaw(meta, s.path) } catch (_: Exception) { null }
                                if (raw != null && raw.isNotEmpty()) decodeBmpCapped(raw, cap)
                                else try { engine.loadPage(meta, s.path) } catch (_: Exception) { null }
                            }
                            if (bmp != null) stage = CoverStage.Crop(bmp) else toastMsg = "页面读取失败"
                        }
                    },
                    onBack = { stage = CoverStage.Source },
                )

                is CoverStage.Crop -> CropStage(
                    s.bmp,
                    onConfirm = { cropped ->
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) { engine.saveCustomCoverBitmap(cropped, book.filePath) }
                            if (saved != null) onResult(saved) else toastMsg = "封面保存失败"
                        }
                    },
                    onCancel = { stage = CoverStage.Source },
                )
            }
        }
    }
}

/** 树内子路径合成：优先 Item.subPath（列表期的权威书式路径），z: 树回退 openFolder 同款拼接 */
private fun composeChildPath(parent: String, item: ArchiveEngine.Item): String {
    if (item.subPath.isNotEmpty()) return item.subPath
    if (parent.startsWith("z:")) {
        val inner = parent.removePrefix("z:")
        val sepIdx = inner.indexOf("//")
        if (sepIdx >= 0) {
            var archivePath = inner.substring(0, sepIdx)
            if (!archivePath.startsWith("a:")) archivePath = "a:$archivePath"
            val base = inner.substring(sepIdx + 2)
            val nb = if (base.isEmpty()) "" else if (base.endsWith("/")) base else "$base/"
            return "z:${archivePath}//${nb}${item.name}/"
        }
    }
    return "$parent/${item.name}"
}

@Composable
private fun SourceCard(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickableNoRipple(onClick),
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(androidx.compose.material.icons.Icons.Default.PhotoLibrary, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))

@Composable
private fun ContentBrowserStage(
    engine: ArchiveEngine,
    path: String,
    title: String,
    thumbCache: ConcurrentHashMap<String, Bitmap>,
    onDescend: (ArchiveEngine.Item) -> Unit,
    onPickImage: (ArchiveEngine.Item) -> Unit,
    onOpenArchive: (ArchiveEngine.Item) -> Unit,
    onBack: () -> Unit,
) {
    var items by remember(path) { mutableStateOf<List<ArchiveEngine.Item>?>(null) }
    LaunchedEffect(path) {
        items = withContext(Dispatchers.IO) { try { engine.children(path) } catch (_: Exception) { emptyList() } }
    }
    Column(Modifier.fillMaxSize()) {
        StageTopBar(title = title.ifEmpty { "选择图片" }, onBack = onBack)
        val list = items
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("加载中…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("没有可用的图片", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.subPath.ifEmpty { it.name } }) { item ->
                    val isDir = item.dir
                    val ext = extOf(item.name)
                    val isImg = ext in IMAGE_EXTS
                    val isArc = ext in ARCHIVE_EXTS
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickableNoRipple {
                        when {
                            isDir -> onDescend(item)
                            isImg -> onPickImage(item)
                            isArc -> onOpenArchive(item)
                            else -> onPickImage(item)
                        }
                    }) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                            when {
                                isDir -> Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                                isImg -> ItemThumbBmp(engine, item, thumbCache)
                                else -> Text(ext.uppercase(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(item.name, fontSize = 11.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemThumbBmp(engine: ArchiveEngine, item: ArchiveEngine.Item, cache: ConcurrentHashMap<String, Bitmap>) {
    val key = item.subPath.ifEmpty { item.doc }
    var bmp by remember(key) { mutableStateOf<Bitmap?>(cache[key]) }
    LaunchedEffect(key) {
        if (bmp == null) {
            val t = withContext(Dispatchers.IO) {
                cache[key]?.let { return@withContext it }
                val bytes = engine.loadDirItemBytes(item) ?: return@withContext null
                engine.decodeThumbnail(bytes, 256)?.also { cache[key] = it }
            }
            if (bmp == null && t != null) bmp = t
        }
    }
    bmp?.let { Image(it.asImageBitmap(), null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
}

@Composable
private fun PageGridStage(
    engine: ArchiveEngine,
    path: String,
    title: String,
    thumbCache: ConcurrentHashMap<String, Bitmap>,
    cap: Int,
    onPick: (ArchiveEngine.PageMeta) -> Unit,
    onBack: () -> Unit,
) {
    var metas by remember(path) { mutableStateOf<List<ArchiveEngine.PageMeta>?>(null) }
    LaunchedEffect(path) {
        metas = withContext(Dispatchers.IO) { try { engine.listPages(path) } catch (_: Exception) { emptyList() } }
    }
    Column(Modifier.fillMaxSize()) {
        StageTopBar(title = title.ifEmpty { "选择页面" }, onBack = onBack)
        val list = metas
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("加载中…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("没有可用页面", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { "${it.localIdx}|${it.index}" }) { meta ->
                    var bmp by remember(meta.index) { mutableStateOf<Bitmap?>(thumbCache["p:$path|${meta.index}"]) }
                    LaunchedEffect(meta.index) {
                        if (bmp == null) {
                            val t = withContext(Dispatchers.IO) {
                                thumbCache["p:$path|${meta.index}"]?.let { return@withContext it }
                                val raw = try { engine.loadPageRaw(meta, path) } catch (_: Exception) { null }
                                val full = if (raw != null && raw.isNotEmpty()) decodeBmpCapped(raw, cap) else try { engine.loadPage(meta, path) } catch (_: Exception) { null }
                                full?.let { big ->
                                    val w = if (big.width > 256) 256 else big.width
                                    val h = big.height * w / big.width
                                    try { Bitmap.createScaledBitmap(big, w, h.coerceAtLeast(1), true) } catch (_: Exception) { big }
                                }
                            }
                            t?.let { thumbCache["p:$path|${meta.index}"] = it; bmp = it }
                        }
                    }
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).clickableNoRipple { onPick(meta) }, contentAlignment = Alignment.Center) {
                        bmp?.let { Image(it.asImageBitmap(), null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            ?: Text("${meta.index + 1}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun StageTopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        Text(title, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f))
    }
}

/** 书架封面卡宽高比（BookCard aspectRatio(0.72f)）：取景框固定同比例，裁出即满卡无二次裁切 */
private const val COVER_FRAME_RATIO = 0.72f

/**
 * 取景框裁剪：双指缩放+平移，框比例固定 0.72（与书架封面卡一致）。
 * 确认 = 框内区域映射回源位图裁剪，输出长边 ≤1280（显示端 ~500px，4 倍冗余）。
 */
@Composable
private fun CropStage(src: Bitmap, onConfirm: (Bitmap) -> Unit, onCancel: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var confirming by remember { mutableStateOf(false) }
    var initFit by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { v ->
        viewSize = v
        // 首次得知视图尺寸：预缩放到恰好覆盖取景框（横图/方图初始也满框，否则映射裁剪区会缺内容）
        if (!initFit && v.width > 0 && v.height > 0) {
            initFit = true
            val fit = fitRect(src, v)
            val frame = frameRect(Size(v.width.toFloat(), v.height.toFloat()), COVER_FRAME_RATIO)
            if (fit.width > 0f && fit.height > 0f) scale = maxOf(1f, frame.width / fit.width, frame.height / fit.height)
        }
    }) {
        // 源图 Fit 居中显示，graphicsLayer 以视图中心为原点缩放/平移
        Image(
            src.asImageBitmap(), null,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
            },
            contentScale = ContentScale.Fit,
        )
        // 取景框遮罩 + 手势
        Canvas(Modifier.fillMaxSize().pointerInput(viewSize) {
            detectTransformGestures { _, pan, zoom, _ ->
                val v = viewSize
                if (v.width > 0 && v.height > 0) {
                    val fit = fitRect(src, v)
                    val frame = frameRect(Size(v.width.toFloat(), v.height.toFloat()), COVER_FRAME_RATIO)
                    val ns = (scale * zoom).coerceIn(1f, 12f)
                    val nx = offset.x + pan.x
                    val ny = offset.y + pan.y
                    val halfW = fit.width / 2f * ns
                    val halfH = fit.height / 2f * ns
                    val maxX = maxOf(0f, halfW - frame.width / 2f)
                    val maxY = maxOf(0f, halfH - frame.height / 2f)
                    scale = ns
                    offset = Offset(nx.coerceIn(-maxX, maxX), ny.coerceIn(-maxY, maxY))
                }
            }
        }) {
            val v = size
            if (v.width > 0 && v.height > 0) {
                val frame = frameRect(v, COVER_FRAME_RATIO)
                // 四周压暗
                val dim = Color.Black.copy(alpha = 0.6f)
                drawRect(dim, Offset(0f, 0f), Size(v.width, frame.top))
                drawRect(dim, Offset(0f, frame.bottom), Size(v.width, v.height - frame.bottom))
                drawRect(dim, Offset(0f, frame.top), Size(frame.left, frame.height))
                drawRect(dim, Offset(frame.right, frame.top), Size(v.width - frame.right, frame.height))
                // 白框 + 三分线
                drawRect(Color.White, frame.topLeft, frame.size, style = Stroke(2.dp.toPx()))
                val c1 = frame.left + frame.width / 3f
                val c2 = frame.left + frame.width * 2f / 3f
                drawLine(Color.White.copy(alpha = 0.4f), Offset(c1, frame.top), Offset(c1, frame.bottom), 1.dp.toPx())
                drawLine(Color.White.copy(alpha = 0.4f), Offset(c2, frame.top), Offset(c2, frame.bottom), 1.dp.toPx())
                val r1 = frame.top + frame.height / 3f
                val r2 = frame.top + frame.height * 2f / 3f
                drawLine(Color.White.copy(alpha = 0.4f), Offset(frame.left, r1), Offset(frame.right, r1), 1.dp.toPx())
                drawLine(Color.White.copy(alpha = 0.4f), Offset(frame.left, r2), Offset(frame.right, r2), 1.dp.toPx())
            }
        }
        // 顶部工具条
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = {
                    if (confirming) return@IconButton
                    confirming = true
                    val v = viewSize
                    if (v.width <= 0) { confirming = false; return@IconButton }
                    val frame = frameRect(Size(v.width.toFloat(), v.height.toFloat()), COVER_FRAME_RATIO)
                    val fit = fitRect(src, v)
                    // 视图坐标 → 源位图坐标
                    val sx = (frame.left - fit.left) / scale / fit.width * src.width
                    val sy = (frame.top - fit.top) / scale / fit.height * src.height
                    val sw = frame.width / scale / fit.width * src.width
                    val sh = frame.height / scale / fit.height * src.height
                    val x = sx.toInt().coerceIn(0, src.width - 1)
                    val y = sy.toInt().coerceIn(0, src.height - 1)
                    val w = sw.toInt().coerceIn(1, src.width - x)
                    val h = sh.toInt().coerceIn(1, src.height - y)
                    val cropped = try { Bitmap.createBitmap(src, x, y, w, h) } catch (oom: OutOfMemoryError) { System.gc(); null }
                    if (cropped == null) { confirming = false; return@IconButton }
                    val out = if (maxOf(cropped.width, cropped.height) > 1280) {
                        val tw = if (cropped.width >= cropped.height) 1280 else cropped.width * 1280 / cropped.height
                        val th = if (cropped.height > cropped.width) 1280 else cropped.height * 1280 / cropped.width
                        try { Bitmap.createScaledBitmap(cropped, tw, th, true) } catch (_: Exception) { cropped }
                    } else cropped
                    onConfirm(out)
                },
            ) { Icon(Icons.Default.Check, "确认", tint = Color.White) }
        }
    }
}

/** 源图在视图内 Fit 显示的矩形 */
private fun fitRect(src: Bitmap, view: IntSize): androidx.compose.ui.geometry.Rect {
    val vw = view.width.toFloat()
    val vh = view.height.toFloat()
    if (vw <= 0f || vh <= 0f || src.width <= 0 || src.height <= 0) return androidx.compose.ui.geometry.Rect(0f, 0f, 0f, 0f)
    val s = minOf(vw / src.width, vh / src.height)
    val w = src.width * s
    val h = src.height * s
    val l = (vw - w) / 2f
    val t = (vh - h) / 2f
    return androidx.compose.ui.geometry.Rect(l, t, l + w, t + h)
}

/** 视图内居中的取景框（比例 w/h，留 24dp 边距） */
private fun frameRect(view: Size, ratio: Float): androidx.compose.ui.geometry.Rect {
    val margin = 24f * 2.5f // ≈24dp@2.5x，够用即可（框只用于构图参照）
    val availW = (view.width - margin).coerceAtLeast(50f)
    val availH = (view.height - margin).coerceAtLeast(50f)
    var w = availW
    var h = w / ratio
    if (h > availH) { h = availH; w = h * ratio }
    val l = (view.width - w) / 2f
    val t = (view.height - h) / 2f
    return androidx.compose.ui.geometry.Rect(l, t, l + w, t + h)
}
