package com.packetcapture.export

import com.packetcapture.body.BodyBase64Decoder
import com.packetcapture.body.BodyBase64Result
import com.packetcapture.core.*
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class ExportRequest(val kind: String, val id: String? = null, val rawBody: Boolean = false, val decodeBase64: Boolean = false)

/** 无页面状态的导出服务；调用方显式传入请求或会话 ID。 */
class DocumentExporter(
    private val repository: CaptureRepository,
    private val bodies: BodyStore,
    private val certificates: CertificateManager,
    private val exports: ExportService,
) {
    /** 文件名使用导出记录所属的应用，不受首页当前选中应用影响。 */
    suspend fun fileName(request: ExportRequest, appLabel: (String) -> String): String = withContext(Dispatchers.IO) {
        if (request.kind == "certificate") return@withContext "packet-capture-ca.cer"
        val (prefix, extension) = when (request.kind) {
            "har" -> "capture" to "har"
            "curl" -> "request-response" to "zip"
            "request-body" -> "request-body" to "txt"
            "response-body" -> "response-body" to "txt"
            else -> error("未知导出类型")
        }
        val packages = if (request.kind == "har") {
            val id = request.id ?: error("暂无会话可导出")
            repository.session(id)?.packages ?: error("会话不存在")
        } else {
            val exchange = request.id?.let { repository.exchange(it).first() } ?: error("请求不存在")
            val packageName = exchange.packageName?.takeIf { it.isNotBlank() }
                ?: repository.connection(exchange.connectionId)?.packageName?.takeIf { it.isNotBlank() }
                // 旧版 Android 无法识别归属时，仅单应用会话可以确定来源。
                ?: repository.session(exchange.sessionId)?.packages?.singleOrNull()
            listOfNotNull(packageName)
        }
        val apps = packages.filter { it.isNotBlank() }.distinct().sorted().joinToString("+") { packageName ->
            appLabel(packageName).ifBlank { packageName }
                .replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]"), "-")
                .trim(' ', '.', '-').ifBlank { packageName }
        }.ifBlank { "未知应用" }
        // 为前缀、时间戳及扩展名留出空间，按 UTF-8 字节限制长度，保留完整中文和 Emoji。
        val shortApps = buildString {
            var bytes = 0
            for (codePoint in apps.codePoints().toArray()) {
                val character = String(Character.toChars(codePoint))
                bytes += character.toByteArray(Charsets.UTF_8).size
                if (bytes > 180) { append("…"); break }
                append(character)
            }
        }
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT))
        "${prefix}_${shortApps}_${timestamp}.${extension}"
    }

    /** 调用方拥有并关闭输出流。ZIP 只包含请求、响应和无外部正文依赖的 cURL 文本。 */
    suspend fun write(request: ExportRequest, output: OutputStream) = withContext(Dispatchers.IO) {
        when (request.kind) {
            "certificate" -> certificates.exportCertificate(output)
            "har" -> {
                val id = request.id ?: error("暂无会话可导出")
                exports.har(repository.allExchanges(id), output)
            }
            "request-body", "response-body" -> {
                val exchange = request.id?.let { repository.exchange(it).first() } ?: error("请求不存在")
                val isRequest = request.kind == "request-body"
                val ref = (if (isRequest) exchange.requestBody else exchange.responseBody) ?: error("正文尚未完成保存")
                val headers = if (isRequest) exchange.requestHeaders else exchange.responseHeaders
                val body = bodies.preview(ref, headers)
                val sourceText = if (request.rawBody) body.rawText ?: body.text else body.text
                val text = if (request.decodeBase64) when (val result = BodyBase64Decoder.decode(sourceText)) {
                    is BodyBase64Result.Success -> result.text
                    is BodyBase64Result.Failure -> error(result.message)
                } else sourceText
                val writer = OutputStreamWriter(output, Charsets.UTF_8)
                if (body.limited) writer.write("【正文不完整】${body.note ?: "仅有部分内容可用"}\n\n")
                writer.write(text)
                writer.flush()
            }
            "curl" -> {
                val exchange = request.id?.let { repository.exchange(it).first() } ?: error("请求不存在")
                val bundle = exports.curl(exchange)
                val zip = ZipOutputStream(output)
                zip.putNextEntry(ZipEntry("request.txt")); zip.write(bundle.requestText.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                zip.putNextEntry(ZipEntry("response.txt")); zip.write(bundle.responseText.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                zip.putNextEntry(ZipEntry("curl.txt")); zip.write((bundle.command + "\n").toByteArray(Charsets.UTF_8)); zip.closeEntry()
                zip.finish(); zip.flush()
            }
            else -> error("未知导出类型")
        }
    }
}
