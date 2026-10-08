package com.mangareader.wifitransfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.text.format.Formatter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.mangareader.R
import io.ktor.http.ContentType
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date

class WifiTransferService : Service() {

    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "uncaught in scope", e)
        isRunning = false
        serverUrl = null
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var folderUri: Uri? = null

    companion object {
        private const val TAG = "WifiTransfer"
        private const val CHANNEL_ID = "wifi_transfer"
        private const val NOTI_ID = 9003
        const val PORT = 8000
        const val ACTION_START = "com.mangareader.wifitransfer.START"
        const val ACTION_STOP = "com.mangareader.wifitransfer.STOP"
        const val EXTRA_FOLDER_URI = "folder_uri"

        @Volatile var isRunning = false; private set
        @Volatile var serverUrl: String? = null; private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopServer(); stopSelf(); return START_NOT_STICKY }
            ACTION_START -> {
                val uriStr = intent.getStringExtra(EXTRA_FOLDER_URI) ?: return START_NOT_STICKY
                folderUri = Uri.parse(uriStr)
                startForegroundSafe(buildNotification("正在启动…"))
                startServer()
            }
        }
        return START_NOT_STICKY
    }

    private fun startServer() {
        scope.launch {
            try {
                server?.stop(500, 1000)
                val treeUri = folderUri ?: return@launch
                val baseDir = DocumentFile.fromTreeUri(this@WifiTransferService, treeUri) ?: return@launch
                val ctx = applicationContext

                val srv = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                    routing {
                        get("/") {
                            val html = ctx.assets.open("wifi_transfer/index.html").bufferedReader().use { it.readText() }
                            call.respondText(html, ContentType.Text.Html)
                        }
                        get("/assets/style.css") {
                            val css = ctx.assets.open("wifi_transfer/style.css").bufferedReader().use { it.readText() }
                            call.respondText(css, ContentType.Text.CSS)
                        }
                        get("/assets/script.js") {
                            val js = ctx.assets.open("wifi_transfer/script.js").bufferedReader().use { it.readText() }
                            call.respondText(js, ContentType.Text.JavaScript)
                        }
                        get("/icon") {
                            val bmp = BitmapFactory.decodeResource(ctx.resources, R.mipmap.ic_launcher)
                            if (bmp != null) {
                                val baos = ByteArrayOutputStream()
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
                                bmp.recycle()
                                call.respondBytes(baos.toByteArray(), ContentType.Image.PNG)
                            } else {
                                call.respond(HttpStatusCode.NotFound)
                            }
                        }
                        get("/donation-qr") {
                            val resId = R.drawable.donation_qr
                            val uri = Uri.parse("android.resource://${ctx.packageName}/$resId")
                            val input = ctx.contentResolver.openInputStream(uri)
                            if (input != null) {
                                val bytes = withContext(Dispatchers.IO) { input.use { it.readBytes() } }
                                call.respondBytes(bytes, ContentType.Image.JPEG)
                            } else {
                                call.respond(HttpStatusCode.NotFound)
                            }
                        }
                        route("/api") {
                            get("/files") {
                                val path = call.request.queryParameters["path"] ?: ""
                                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm")
                                val dir = navigateToPath(baseDir, path) ?: return@get call.respond(HttpStatusCode.NotFound)
                                val files = dir.listFiles()
                                    .filter { it.canRead() }
                                    .sortedWith(compareByDescending<DocumentFile> { it.isDirectory }.thenBy { it.name })
                                    .map { f ->
                                        FileInfo(
                                            name = f.name ?: "unknown",
                                            size = if (f.isDirectory) 0 else f.length(),
                                            formattedSize = if (f.isDirectory) "-" else formatBytes(f.length()),
                                            lastModified = fmt.format(Date(f.lastModified())),
                                            type = f.type ?: "application/octet-stream",
                                            isDirectory = f.isDirectory
                                        )
                                    }
                                call.respond(FileListResponse(files))
                            }
                            get("/download/{name}") {
                                val rawName = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                                val fileName = URLDecoder.decode(rawName, "UTF-8")
                                val parts = fileName.split("/")
                                val parentPath = parts.dropLast(1).joinToString("/")
                                val parentDir = navigateToPath(baseDir, parentPath) ?: return@get call.respond(HttpStatusCode.NotFound)
                                val target = parentDir.findFile(parts.last()) ?: return@get call.respond(HttpStatusCode.NotFound)
                                val input = ctx.contentResolver.openInputStream(target.uri) ?: return@get call.respond(HttpStatusCode.NotFound)
                                val mime = ContentType.parse(target.type ?: "application/octet-stream")
                                call.respond(object : OutgoingContent.WriteChannelContent() {
                                    override val contentType = mime
                                    override val contentLength = target.length()
                                    override val headers = headersOf(
                                        HttpHeaders.ContentDisposition,
                                        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, fileName).toString()
                                    )
                                    override suspend fun writeTo(channel: ByteWriteChannel) {
                                        withContext(Dispatchers.IO) {
                                            input.use { stream ->
                                                val buf = ByteArray(256 * 1024)
                                                while (true) {
                                                    val n = stream.read(buf)
                                                    if (n == -1) break
                                                    channel.writeFully(buf, 0, n)
                                                }
                                            }
                                        }
                                    }
                                })
                            }
                            post("/upload") {
                                val multipart = call.receiveMultipart()
                                var uploaded = 0
                                var failed = 0
                                multipart.forEachPart { part ->
                                    if (part is PartData.FileItem) {
                                        val originalName = part.originalFileName ?: "uploaded_file"
                                        val safeName = sanitizeFileName(originalName)
                                        try {
                                            Log.i(TAG, "upload start: $safeName")
                                            val input = part.provider().toInputStream()
                                            writeToDocumentTree(baseDir, safeName) { out ->
                                                input.use { i ->
                                                    val buf = ByteArray(64 * 1024)
                                                    while (true) {
                                                        val n = i.read(buf)
                                                        if (n == -1) break
                                                        out.write(buf, 0, n)
                                                    }
                                                }
                                            }
                                            uploaded++
                                            Log.i(TAG, "upload done: $safeName")
                                        } catch (e: Exception) {
                                            Log.e(TAG, "upload failed: $safeName", e)
                                            failed++
                                        }
                                    }
                                    part.dispose()
                                }
                                call.respondText("{\"uploaded\":$uploaded,\"failed\":$failed}", ContentType.Application.Json)
                            }
                            post("/delete") {
                                val body = call.receiveText()
                                val parsed = Json { ignoreUnknownKeys = true }.decodeFromString(DeleteRequest.serializer(), body)
                                val parts = parsed.filename.split("/")
                                val parentPath = parts.dropLast(1).joinToString("/")
                                val parentDir = navigateToPath(baseDir, parentPath)
                                val target = parentDir?.findFile(parts.last())
                                if (target != null && target.delete()) {
                                    call.respond(DeleteResponse(true))
                                } else {
                                    call.respond(DeleteResponse(false, "文件不存在或无法删除"))
                                }
                            }
                        }
                    }
                }
                server = srv

                isRunning = true
                val ip = getIpAddress()
                serverUrl = "http://$ip:$PORT"
                startForegroundSafe(buildNotification("服务运行中 · $serverUrl"))
                Log.i(TAG, "server started at $serverUrl")

                srv.start(wait = true)

                isRunning = false
                serverUrl = null
            } catch (e: Exception) {
                Log.e(TAG, "startServer failed", e)
                isRunning = false
                serverUrl = null
                stopSelf()
            }
        }
    }

    private fun stopServer() {
        scope.launch {
            server?.stop(500, 1000)
            server = null
            isRunning = false
            serverUrl = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            Log.i(TAG, "server stopped")
        }
    }

    @Serializable
    data class FileInfo(val name: String, val size: Long, val formattedSize: String, val lastModified: String, val type: String, val isDirectory: Boolean = false)

    @Serializable
    data class FileListResponse(val files: List<FileInfo>)

    @Serializable
    data class DeleteRequest(val filename: String)

    @Serializable
    data class DeleteResponse(val success: Boolean, val error: String? = null)

    private fun sanitizeFileName(name: String): String =
        name.split("/")
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/")
            .ifEmpty { "unnamed_file" }

    private fun navigateToPath(base: DocumentFile, path: String): DocumentFile? {
        if (path.isEmpty()) return base
        var current = base
        for (part in path.split("/")) {
            if (part.isBlank()) continue
            current = current.findFile(part) ?: return null
        }
        return current
    }

    private fun writeToDocumentTree(baseDir: DocumentFile, path: String, write: (OutputStream) -> Unit) {
        val parts = path.split("/")
        var current = baseDir
        for (i in 0 until parts.size - 1) {
            val dirName = parts[i]
            var next = current.findFile(dirName)
            if (next == null || !next.isDirectory) {
                next = current.createDirectory(dirName) ?: throw IOException("无法创建目录: $dirName")
            }
            current = next
        }
        val fileName = parts.last()
        val existing = current.findFile(fileName)
        if (existing != null) existing.delete()
        val newFile = current.createFile("application/octet-stream", fileName) ?: throw IOException("无法创建文件: $fileName")
        contentResolver.openOutputStream(newFile.uri)?.use { out -> write(out) } ?: throw IOException("无法打开输出流")
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var u = 0
        var v = bytes.toDouble()
        while (v >= 1024 && u < units.size - 1) { v /= 1024; u++ }
        return "%.1f %s".format(v, units[u])
    }

    @Suppress("DEPRECATION")
    private fun getIpAddress(): String {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiIp = wm?.let { Formatter.formatIpAddress(it.connectionInfo.ipAddress) }
            if (wifiIp != null && wifiIp != "0.0.0.0") return wifiIp
        } catch (_: Exception) {}
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is InetAddress && addr.hostAddress?.contains(':') == false) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        } catch (_: Exception) {}
        return "0.0.0.0"
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "WiFi 传书", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, WifiTransferService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("WiFi 传书")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止", stopIntent).build())
            .build()
    }

    private fun startForegroundSafe(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTI_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTI_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        isRunning = false
        serverUrl = null
        super.onDestroy()
    }
}