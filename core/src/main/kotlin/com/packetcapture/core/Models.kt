package com.packetcapture.core

import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
const val DEFAULT_BODY_LIMIT = 5L * 1024 * 1024
const val DEFAULT_STORAGE_LIMIT = 500L * 1024 * 1024

enum class CapturePhase { IDLE, STARTING, CAPTURING, STOPPING, FAILED }
enum class Completion { ACTIVE, COMPLETE, FAILED, INTERRUPTED }
enum class BodyPart { REQUEST, RESPONSE }
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 只连接同一设备的 SOCKS5 服务；该应用必须在抓包白名单之外。 */
data class UpstreamProxy(val enabled: Boolean = false, val port: Int = 10808) {
    init { require(port in 1..65535) { "代理端口必须在 1–65535 之间" } }
    val host: String get() = "127.0.0.1"
}

data class CaptureConfig(
    val packages: Set<String> = emptySet(),
    val decryptHttps: Boolean = true,
    val bypassDomains: Set<String> = emptySet(),
    val bodyLimit: Long = DEFAULT_BODY_LIMIT,
    val storageLimit: Long = DEFAULT_STORAGE_LIMIT,
    val upstreamProxy: UpstreamProxy = UpstreamProxy(),
)
data class AppSettings(val capture: CaptureConfig = CaptureConfig(), val theme: ThemeMode = ThemeMode.SYSTEM)
data class CaptureState(
    val phase: CapturePhase = CapturePhase.IDLE,
    val sessionId: String? = null,
    val startedAt: Long? = null,
    val uploadedBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val error: String? = null,
    val recordingWarning: String? = null,
    val upstreamProxy: UpstreamProxy = UpstreamProxy(),
)
data class CaptureSession(
    val id: String = newId(),
    val title: String,
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
    val packages: List<String>,
    val completion: Completion = Completion.ACTIVE,
    val uploadedBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val requestCount: Int = 0,
)
data class ConnectionRecord(
    val id: String,
    val sessionId: String,
    val sourceAddress: String,
    val sourcePort: Int,
    val destinationAddress: String,
    val destinationPort: Int,
    val transport: String,
    val uid: Int? = null,
    val packageName: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
    val host: String? = null,
    val tlsVersion: String? = null,
    val decrypted: Boolean = false,
    val note: String? = null,
    val completion: Completion = Completion.ACTIVE,
)
data class Header(val name: String, val value: String)
data class BodyRef(
    val key: String,
    val observedBytes: Long = 0,
    val savedBytes: Long = 0,
    val truncated: Boolean = false,
    val reason: String? = null,
)
data class HttpExchange(
    val id: String = newId(),
    val sessionId: String,
    val connectionId: String,
    val streamId: Int? = null,
    val packageName: String? = null,
    val method: String,
    val url: String,
    val protocol: String,
    val startedAt: Long = System.currentTimeMillis(),
    val responseStartedAt: Long? = null,
    val endedAt: Long? = null,
    val status: Int? = null,
    val requestHeaders: List<Header> = emptyList(),
    val responseHeaders: List<Header> = emptyList(),
    val requestTrailers: List<Header> = emptyList(),
    val responseTrailers: List<Header> = emptyList(),
    val requestBody: BodyRef? = null,
    val responseBody: BodyRef? = null,
    val completion: Completion = Completion.ACTIVE,
    val error: String? = null,
) {
    val durationMillis: Long? get() = endedAt?.let { it - startedAt }
}
data class ExchangeFilter(
    val query: String = "",
    val method: String? = null,
    val errorsOnly: Boolean = false,
    val packageName: String? = null,
    val status: Int? = null,
)
data class CertificateInfo(val subject: String, val fingerprint: String, val expiresAt: Long)
data class InstalledApp(val packageName: String, val label: String)
/** text 用于默认阅读视图；rawText 保留解压后的原始文本，供核对转义和原始 JSON 语法。 */
data class BodyPreview(val text: String, val binary: Boolean, val limited: Boolean, val note: String? = null,
    val rawText: String? = null)
data class ExportOptions(val redactCredentials: Boolean = false)
data class CurlExport(
    val command: String,
    val requestText: String = "",
    val responseText: String = "",
)
