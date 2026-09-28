package com.packetcapture.data

import com.google.gson.Gson
import com.google.gson.stream.JsonWriter
import com.packetcapture.core.*
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.time.Instant
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 导出只消费领域模型；未知时序使用 HAR 的 -1，不从总耗时伪造 DNS/TLS 时间。 */
class TrafficExporter(private val bodies: BodyStore) : ExportService {
    private val gson = Gson()
    private val sensitive = setOf("authorization", "proxy-authorization", "cookie", "set-cookie")
    private fun headers(input: List<Header>, options: ExportOptions) = input.map {
        if (options.redactCredentials && it.name.lowercase() in sensitive) it.copy(value = "[REDACTED]") else it
    }
    override suspend fun har(exchanges: List<HttpExchange>, output: OutputStream, options: ExportOptions) = withContext(Dispatchers.IO) {
        // 逐条写 JSON；不把整个会话的所有响应体及 Base64 同时装入内存。
        val writer = JsonWriter(OutputStreamWriter(output, Charsets.UTF_8))
        writer.beginObject().name("log").beginObject().name("version").value("1.2")
            .name("creator").beginObject().name("name").value("Packet Capture").name("version").value("1.0.0").endObject()
            .name("entries").beginArray()
        for (exchange in exchanges) {
            val reqBody = exchange.requestBody
            val respBody = exchange.responseBody
            val request = linkedMapOf<String, Any?>(
                "method" to exchange.method, "url" to exchange.url, "httpVersion" to exchange.protocol,
                "cookies" to emptyList<Any>(), "headers" to headers(exchange.requestHeaders, options),
                "queryString" to queryParameters(exchange.url), "headersSize" to -1,
                "bodySize" to (reqBody?.observedBytes ?: 0),
                "_body" to reqBody, "_trailers" to headers(exchange.requestTrailers, options))
            if (reqBody != null && reqBody.savedBytes > 0) {
                val content = bodies.read(reqBody)
                request["postData"] = mapOf("mimeType" to (exchange.requestHeaders.firstHeader("content-type") ?: "application/octet-stream"),
                    "text" to String(content, Charsets.UTF_8), "_base64" to Base64.getEncoder().encodeToString(content),
                    "_truncated" to reqBody.truncated)
            }
            val content = linkedMapOf<String, Any?>("size" to (respBody?.observedBytes ?: 0),
                "mimeType" to (exchange.responseHeaders.firstHeader("content-type") ?: "application/octet-stream"))
            if (respBody != null) {
                val raw = bodies.read(respBody)
                val encoding = exchange.responseHeaders.firstHeader("content-encoding")?.trim()?.lowercase()
                val decoded = runCatching { when (encoding) {
                    "gzip" -> GZIPInputStream(raw.inputStream()).use { FileBodyStore.readLimited(it, DEFAULT_BODY_LIMIT.toInt()) }
                    "deflate" -> InflaterInputStream(raw.inputStream()).use { FileBodyStore.readLimited(it, DEFAULT_BODY_LIMIT.toInt()) }
                    else -> raw to false
                } }
                val (body, limited) = decoded.getOrElse { byteArrayOf() to true }
                // HAR content 表示解码后的实体；原始压缩字节单独保留，避免导入工具把 gzip 当正文显示。
                content["text"] = Base64.getEncoder().encodeToString(body)
                content["size"] = body.size
                content["encoding"] = "base64"
                content["_contentEncoding"] = encoding
                if (encoding != null) content["_rawBase64"] = Base64.getEncoder().encodeToString(raw)
                content["_truncated"] = respBody.truncated || limited
                content["_decodeError"] = decoded.exceptionOrNull()?.message
            }
            val response = mapOf("status" to (exchange.status ?: 0), "statusText" to "", "httpVersion" to exchange.protocol,
                "cookies" to emptyList<Any>(), "headers" to headers(exchange.responseHeaders, options),
                "content" to content, "redirectURL" to exchange.responseHeaders.firstHeader("location").orEmpty(),
                "headersSize" to -1, "bodySize" to (respBody?.observedBytes ?: 0),
                "_body" to respBody, "_trailers" to headers(exchange.responseTrailers, options))
            val total = exchange.durationMillis?.toDouble() ?: 0.0
            val waiting = exchange.responseStartedAt?.let { (it - exchange.startedAt).coerceAtLeast(0).toDouble() } ?: total
            val entry = mapOf("startedDateTime" to Instant.ofEpochMilli(exchange.startedAt).toString(),
                "time" to total, "request" to request, "response" to response, "cache" to emptyMap<String, String>(),
                "timings" to mapOf("blocked" to -1, "dns" to -1, "connect" to -1, "ssl" to -1, "send" to 0,
                    "wait" to waiting, "receive" to (total - waiting).coerceAtLeast(0.0)),
                // 即使一字节也没存下，也必须保留正文完整性；不能仅依赖 postData 是否存在。
                "_completion" to exchange.completion.name, "_error" to exchange.error,
                "_connectionId" to exchange.connectionId, "_streamId" to exchange.streamId)
            gson.toJson(entry, Map::class.java, writer)
        }
        writer.endArray().endObject().endObject()
        writer.flush()
    }
    override suspend fun curl(exchange: HttpExchange, options: ExportOptions): CurlExport = withContext(Dispatchers.IO) {
        require(exchange.completion != Completion.ACTIVE) { "请求仍在进行中" }
        require(exchange.requestBody?.truncated != true) { "请求正文不完整，无法生成可重放的 cURL" }
        val args = mutableListOf("curl", "--compressed", "--request", quote(exchange.method), "--url", quote(exchange.url))
        // shell 参数逐个单引号转义，换行及命令替换字符都只能作为数据。
        headers(exchange.requestHeaders, options).filterNot {
            // 由 --compressed 按本机 cURL 的解码能力协商，避免照搬客户端的 br 等编码后无法解压。
            it.name.startsWith(":") || it.name.lowercase() in setOf("content-length", "transfer-encoding", "connection", "host", "accept-encoding")
        }.forEach { args += listOf("--header", quote("${it.name}: ${it.value}")) }
        val body = exchange.requestBody?.takeIf { it.savedBytes > 0 }?.let { bodies.read(it) }
        require(body == null || body.size.toLong() == exchange.requestBody?.savedBytes) { "请求正文文件不完整" }
        val inlineBody = body?.takeIf { it.size <= 16 * 1024 && 0.toByte() !in it }?.let {
            runCatching { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(it)).toString() }.getOrNull()
        }
        if (inlineBody != null) args += listOf("--data-raw", quote(inlineBody))
        else if (body != null) args += listOf("--data-binary", quote("@-"))
        val command = args.joinToString(" ")
        // NUL / 非 UTF-8 字节不能放进 shell 参数；大正文也可能超出参数长度上限。
        // Base64 随同一段命令内嵌，经标准输入还原，无临时文件、命令替换或末尾换行丢失。
        val selfContainedCommand = if (body != null && inlineBody == null) {
            val encoded = Base64.getMimeEncoder(76, byteArrayOf(10)).encodeToString(body)
            "base64 -d <<'PACKET_CAPTURE_BODY' | $command\n$encoded\nPACKET_CAPTURE_BODY"
        } else command
        val responseBody = exchange.responseBody?.takeIf { it.savedBytes > 0 }?.let { bodies.read(it) }
        CurlExport(selfContainedCommand,
            requestText = messageText("${exchange.method} ${exchange.url} ${exchange.protocol}",
                exchange.requestHeaders, exchange.requestTrailers, exchange.requestBody, body, options),
            responseText = buildString {
                append("【采集状态】${exchange.completion.name}\n")
                exchange.error?.let { append("【错误】$it\n") }
                append(messageText(exchange.status?.let { "${exchange.protocol} $it" } ?: "（未收到响应状态）",
                    exchange.responseHeaders, exchange.responseTrailers, exchange.responseBody, responseBody, options))
            })
    }

    /** 文本用于阅读；cURL 单独内嵌原始请求字节，解压、JSON 格式化和 Base64 不影响重放。 */
    private fun messageText(startLine: String, inputHeaders: List<Header>, trailers: List<Header>,
        ref: BodyRef?, raw: ByteArray?, options: ExportOptions): String = buildString {
        append(startLine).append('\n')
        headers(inputHeaders, options).forEach { append("${it.name}: ${it.value}\n") }
        if (trailers.isNotEmpty()) {
            append("\n【尾部字段】\n")
            headers(trailers, options).forEach { append("${it.name}: ${it.value}\n") }
        }
        append('\n')
        if (ref == null) {
            append("（未保存正文或正文为空）\n")
        } else {
            val bytes = raw ?: byteArrayOf()
            val missingBytes = bytes.size.toLong() != ref.savedBytes
            val incomplete = ref.truncated || missingBytes || ref.savedBytes < ref.observedBytes
            val reason = ref.reason ?: if (missingBytes) "已保存的正文文件不完整" else if (incomplete) "采集时正文未完整保存" else null
            val preview = if (bytes.isEmpty()) BodyPreview("", false, incomplete, reason)
                else BodyDecoder.decode(bytes.inputStream(), inputHeaders, incomplete, reason)
            append("【正文大小】采集 ${ref.observedBytes} 字节，已保存 ${bytes.size} 字节\n")
            if (preview.limited) append("【正文不完整】${preview.note ?: "仅有部分内容可用"}\n")
            else preview.note?.let { append("【正文说明】$it\n") }
            if (preview.text.isEmpty() && preview.limited && bytes.isNotEmpty()) {
                append("【正文（原始字节 Base64）】\n")
                append(Base64.getEncoder().encodeToString(bytes)).append('\n')
            } else {
                append(if (preview.binary) "【正文（Base64）】\n" else "【正文（阅读视图）】\n")
                append(preview.text).append('\n')
            }
        }
    }
    companion object {
        fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
        fun queryParameters(url: String): List<Header> = runCatching {
            URI(url).rawQuery?.split("&")?.map {
                val parts = it.split("=", limit = 2)
                Header(URLDecoder.decode(parts[0], "UTF-8"), URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8"))
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }
}
