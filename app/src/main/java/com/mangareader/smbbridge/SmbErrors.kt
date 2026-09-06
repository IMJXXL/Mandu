package com.mangareader.smbbridge

/**
 * 连接类错误的中文翻译层（2026-09-06 用户要求）：
 * 把 smbj/ktor/网络栈的英文异常翻译成"原因 + 怎么办"。技术细节仍完整写入日志。
 * 规则：取异常因果链的根因，按异常类名与消息关键词归类。
 */
object SmbErrors {

    private fun rootOf(t: Throwable): Throwable {
        var r = t
        var guard = 0
        while (r.cause != null && r.cause !== r && guard++ < 8) r = r.cause!!
        return r
    }

    /** SMB 连接/枚举/扫描错误 → 中文 */
    fun humanizeSmb(t: Throwable): String {
        val root = rootOf(t)
        val cls = root.javaClass.simpleName + "/" + t.javaClass.simpleName
        val msg = ((root.message ?: "") + " " + (t.message ?: ""))
        val low = msg.lowercase()
        return when {
            "UnknownHost" in cls || "unable to resolve host" in low -> "无法解析主机名：请检查地址/IP 是否填写正确"
            "ConnectException" in cls || "refused" in low -> "连接被拒绝：请检查 IP 与端口（SMB 默认 445）"
            "SocketTimeout" in cls || "timeout" in low || "timed out" in low -> "连接超时：请确认服务器已开机在线，且手机与其在同一网络"
            "NoRoute" in cls || "unreachable" in low -> "无法访问服务器：请确认手机与服务器在同一局域网"
            "NetworkUnreachable" in cls -> "手机网络不可用：请检查 Wi-Fi / 流量"
            "LOGON_FAILURE" in low || "logon failure" in low -> "认证失败：用户名或密码错误"
            "ACCESS_DENIED" in low || "access is denied" in low || "access denied" in low -> "访问被拒绝：该账号权限不足"
            "BAD_NETWORK_NAME" in low || "bad network name" in low -> "共享不存在：共享名不正确或已被移除"
            "share name required" in low -> "需要指定共享：该服务器不支持自动枚举共享，请编辑书源直接填写共享名"
            "445" in msg && ("refused" in low || "timeout" in low) -> "端口 445 连接失败：服务器可能未开启 SMB 共享，或被防火墙拦截"
            "negotiation" in low || "protocol" in low && "fail" in low -> "协议协商失败：服务器可能仅开启 SMB1，本应用需要 SMB2/SMB3"
            "需要密码" in msg -> "该归档需要密码"
            "Broken pipe" in low || "reset" in low -> "连接被中断：网络波动或服务器主动断开，请重试"
            else -> "连接失败：${(root.message ?: t.message ?: "未知错误").take(120)}"
        }
    }

    /** WebDAV 连接/枚举/扫描错误 → 中文 */
    fun humanizeWebDav(t: Throwable): String {
        val root = rootOf(t)
        val cls = root.javaClass.simpleName + "/" + t.javaClass.simpleName
        val msg = ((root.message ?: "") + " " + (t.message ?: ""))
        val low = msg.lowercase()
        return when {
            "SSL" in cls || "ssl" in low || "trust anchor" in low || "certificate" in low || "handshake" in low ->
                "HTTPS 证书校验失败：自签名/私有证书请在 WebDAV 设置开启「允许自签名/不安全证书」"
            "UnknownHost" in cls || "unable to resolve host" in low -> "无法解析域名：请检查服务器地址是否正确"
            "ConnectException" in cls || "refused" in low -> "连接被拒绝：请检查地址与端口"
            "SocketTimeout" in cls || "timeout" in low || "timed out" in low -> "连接超时：请确认服务器在线，且手机与其在同一网络"
            "NoRoute" in cls || "unreachable" in low -> "无法访问服务器：请确认手机与服务器在同一局域网"
            "401" in msg || "unauthorized" in low || "凭据" in low -> "认证失败：用户名或密码错误"
            "403" in msg || "forbidden" in low -> "访问被拒绝：账号权限不足或路径受限"
            "404" in msg || "not found" in low -> "路径不存在：请检查服务器地址与起始路径"
            "405" in msg || "method not allowed" in low -> "该服务器不支持 WebDAV（PROPFIND 被拒绝）"
            "Broken pipe" in low || "reset" in low -> "连接被中断：网络波动或服务器主动断开，请重试"
            else -> "连接失败：${(root.message ?: t.message ?: "未知错误").take(120)}"
        }
    }
}
