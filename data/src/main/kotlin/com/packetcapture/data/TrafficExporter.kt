package com.packetcapture.data

import com.google.gson.Gson
import com.google.gson.stream.JsonWriter
import com.packetcapture.core.*
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.URI
import java.net.URLDecoder
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
        val args = mutableListOf("curl", "--request", quote(exchange.method), "--url", quote(exchange.url))
        // shell 参数逐个单引号转义，换行及命令替换字符都只能作为数据。
        headers(exchange.requestHeaders, options).filterNot {
            it.name.startsWith(":") || it.name.lowercase() in setOf("content-length", "transfer-encoding", "connection", "host")
        }.forEach { args += listOf("--header", quote("${it.name}: ${it.value}")) }
        val body = exchange.requestBody?.takeIf { it.savedBytes > 0 }?.let { bodies.read(it) }
        require(body == null || body.size.toLong() == exchange.requestBody?.savedBytes) { "请求正文文件不完整" }
        val filename = if (body != null) "request-${exchange.id}.bin" else null
        if (filename != null) args += listOf("--data-binary", quote("@$filename"))
        CurlExport(args.joinToString(" "), body, filename)
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
