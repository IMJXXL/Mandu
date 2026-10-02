package com.mangareader.sr

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * NPU 模型插件扫描器（机制移植自 Komiho NpuModelPluginScanner，Apache-2.0）。
 *
 * 模型包是**纯资产 APK**——无代码无组件，只带 context 二进制和 models.json 清单。
 * 为什么用 APK 而不是 zip：Android 10 起 targetSdk≥29 禁止从应用可写目录加载可执行
 * 代码，插件 APK 走系统安装、context 只是数据文件复制出来喂 QNN，全版本可靠。
 *
 * 发现：Android 11+ 不能任意枚举包，声明 QUERY_ALL_PACKAGES 后按保留前缀过滤；
 * 部分 ROM 的 getInstalledPackages 只返回调用方自己（Android 16 见过），此时按
 * `com.mangareader.model.v<代号>` 逐名探测仍能命中——探测范围扫 v64..v96，
 * 未来新 SoC 不发主程序版也能装新包。
 *
 * 安全：签名必须与主程序一致（同 keystore），第三方无法伪造模型包混进列表。
 * 前向兼容：models.json 带 protocolVersion，比宿主新的整包跳过；新增可选字段忽略。
 */
object NpuModelPluginScanner {

    /** 模型包保留 applicationId 前缀（见 :modelapk 模块） */
    const val MODEL_PACKAGE_PREFIX = "com.mangareader.model."

    /** 逐名探测的代号范围（覆盖今天五代 + 未来新代） */
    private val PROBE_ARCH_CODES = 64..96

    /** manifest <queries> 里声明的对照包：区分"逐名查询可用、列表 API 被拦"和"全被拦" */
    private const val CONTROL_PROBE_PACKAGE = "com.android.settings"

    /** 宿主能理解的清单协议版本。只有语义重设计才升；加可选字段不用升（被忽略） */
    const val SUPPORTED_PROTOCOL_VERSION = 1

    private const val MANIFEST_ASSET = "models.json"
    private const val DEFAULT_ASSET_DIR = "qnn-contexts"

    private const val TAG = "NpuModels"

    /** 扫描一无所获时才写的原因 trace（外部 files dir）；扫描成功即删，绝不当普通文件用 */
    private const val DIAG_FILE = "npu-diag.txt"

    private const val KEY_PROTOCOL_VERSION = "protocolVersion"
    private const val KEY_MODELS = "models"
    private const val KEY_ID = "id"
    private const val KEY_STEM = "stem"
    private const val KEY_SCALE = "scale"
    private const val KEY_PADDING = "padding"
    private const val KEY_ARCHES = "arches"
    private const val KEY_LABEL = "label"
    private const val KEY_ASSET_DIR = "assetDir"

    fun scan(context: Context): List<NpuModelEntry> {
        val pm = context.packageManager
        val diag = StringBuilder()

        // 主校验 = 与宿主同签。部分 OEM ROM 对 v2-only 签名包会返回 null 证书，
        // 此时降级到白名单常量（见 Komiho 2026-09-23 注释），绝不能直接放弃发现。
        val hostSignature = firstSignature(pm, context.packageName)
        diag.append("host signature: ").append(
            if (hostSignature == null) "UNREADABLE (whitelist only)" else "${hostSignature.size} bytes"
        ).append('\n')
        if (hostSignature == null) {
            Log.w(TAG, "host signature unavailable — falling back to cert whitelist")
        }

        // 双通道发现：先枚举，再对预期包名逐个探测
        val names = LinkedHashSet<String>()
        val enumerated = try {
            pm.getInstalledPackages(0)
        } catch (e: Exception) {
            diag.append("package enumeration FAILED: ").append(e).append('\n')
            Log.w(TAG, "package enumeration failed: ${e.message}")
            null
        }
        enumerated?.forEach { info -> info.packageName?.let(names::add) }
        diag.append("installed packages: ").append(enumerated?.size ?: -1).append('\n')

        var probed = 0
        for (arch in PROBE_ARCH_CODES) {
            val pkg = MODEL_PACKAGE_PREFIX + "v" + arch
            if (names.contains(pkg)) continue
            if (runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess) {
                names += pkg
                probed++
            }
        }
        diag.append("control probe ($CONTROL_PROBE_PACKAGE): ")
            .append(runCatching { pm.getPackageInfo(CONTROL_PROBE_PACKAGE, 0) }.isSuccess).append('\n')
        diag.append("name probes: ").append(PROBE_ARCH_CODES.count()).append(" tried, ").append(probed).append(" hit\n")

        val models = mutableListOf<NpuModelEntry>()
        val seenIds = mutableSetOf<String>()
        for (pkg in names) {
            if (!pkg.startsWith(MODEL_PACKAGE_PREFIX)) continue
            diag.append("candidate: ").append(pkg).append('\n')
            val pluginSignature = firstSignature(pm, pkg)
            if (pluginSignature == null || !isTrustedSignature(pluginSignature, hostSignature)) {
                diag.append("  signature: UNTRUSTED -> skipped\n")
                Log.w(TAG, "$pkg signature untrusted — skipped")
                continue
            }
            diag.append("  signature: ok\n")
            val parsed = parsePackage(context, pkg, seenIds, diag)
            diag.append("  parsed models: ").append(parsed.size).append('\n')
            models += parsed
        }

        diag.append("RESULT: ").append(models.size).append(" models\n")
        if (models.isEmpty()) {
            writeDiagnostics(context, diag)
            Log.w(TAG, "no NPU model discovered — trace written to $DIAG_FILE")
        } else {
            clearDiagnostics(context)
        }
        return models
    }

    private fun writeDiagnostics(context: Context, diag: StringBuilder) {
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: return
            File(dir, DIAG_FILE).writeText(diag.toString())
        }
    }

    private fun clearDiagnostics(context: Context) {
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: return
            File(dir, DIAG_FILE).delete()
        }
    }

    /** 可信 = 带宿主证书，或 SHA-256 在白名单里（覆盖宿主证书读不出来的 ROM） */
    private fun isTrustedSignature(plugin: ByteArray, host: ByteArray?): Boolean {
        if (host != null && plugin.contentEquals(host)) return true
        return try {
            val hex = MessageDigest.getInstance("SHA-256")
                .digest(plugin)
                .joinToString(":") { "%02X".format(it) }
            hex in ALLOWED_PLUGIN_CERT_SHA256
        } catch (_: Exception) {
            false
        }
    }

    /** 主程序签名证书的 SHA-256（manga-reader.jks；模型包必须同签） */
    private val ALLOWED_PLUGIN_CERT_SHA256 = setOf(
        "CC:65:7F:10:75:2C:00:E5:28:A8:72:7E:6A:37:FC:CE:F6:D9:54:65:BE:7D:17:C1:B3:BE:2B:0C:59:96:9C:22",
    )

    private fun parsePackage(
        context: Context,
        pkg: String,
        seenIds: MutableSet<String>,
        diag: StringBuilder,
    ): List<NpuModelEntry> {
        return try {
            val pluginContext = context.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY)
            val manifest = pluginContext.assets.open(MANIFEST_ASSET).bufferedReader().use { it.readText() }
            val root = JSONObject(manifest)

            val protocol = root.optInt(KEY_PROTOCOL_VERSION, 1)
            diag.append("  models.json protocol=").append(protocol).append('\n')
            if (protocol > SUPPORTED_PROTOCOL_VERSION) {
                diag.append("  protocol newer than supported -> skipped\n")
                Log.w(TAG, "$pkg manifest protocol v$protocol > supported — update host; skipped")
                return emptyList()
            }

            val entries = root.optJSONArray(KEY_MODELS)
            if (entries == null) {
                diag.append("  no models array\n")
                return emptyList()
            }
            val result = mutableListOf<NpuModelEntry>()
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                entryOrNull(pkg, entry, diag)?.let { model ->
                    if (seenIds.add(model.id)) result += model
                    else Log.w(TAG, "duplicate model id ${model.id} — skipped")
                }
            }
            result
        } catch (e: Exception) {
            diag.append("  models.json FAILED -> ").append(e).append('\n')
            Log.w(TAG, "failed to read $pkg — skipped: ${e.message}")
            emptyList()
        }
    }

    private fun entryOrNull(pkg: String, entry: JSONObject, diag: StringBuilder): NpuModelEntry? {
        val id = entry.optString(KEY_ID).trim()
        val stem = entry.optString(KEY_STEM).trim()
        val label = entry.optString(KEY_LABEL).trim()
        val padding = entry.optInt(KEY_PADDING, -1)
        val scale = entry.optInt(KEY_SCALE, 2)
        val assetDir = entry.optString(KEY_ASSET_DIR).trim().ifEmpty { DEFAULT_ASSET_DIR }
        val arches = parseArches(entry)

        if (id.isEmpty() || stem.isEmpty() || label.isEmpty() || padding <= 0 || arches.isEmpty()) {
            diag.append("  entry '").append(id).append("' rejected\n")
            Log.w(TAG, "incomplete entry in $pkg — skipped")
            return null
        }
        return NpuModelEntry(
            id = id,
            stem = stem,
            label = label,
            scale = if (scale > 0) scale else 2,
            padding = padding,
            arches = arches,
            assetDir = assetDir,
            sourcePackage = pkg,
        )
    }

    /** arches 兼容数字与字符串两种形态（打包脚本经 os.environ 写出的一定是字符串） */
    private fun parseArches(entry: JSONObject): List<Int> {
        val array = entry.optJSONArray(KEY_ARCHES) ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            when (val raw = array.opt(i)) {
                is Int -> raw
                is Number -> raw.toInt()
                is String -> raw.trim().toIntOrNull()
                else -> null
            }?.takeIf { it > 0 }
        }
    }

    private fun firstSignature(pm: PackageManager, pkg: String): ByteArray? = try {
        val info: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
        }
        val sigs = info.signingInfo?.apkContentsSigners ?: info.signatures
        sigs?.firstOrNull()?.toByteArray()
    } catch (_: Exception) {
        null
    }
}
