package com.packetcapture.export

import com.packetcapture.body.BodyBase64Decoder
import com.packetcapture.body.BodyBase64Result
import com.packetcapture.core.*
import java.io.OutputStream
import java.io.OutputStreamWriter
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
