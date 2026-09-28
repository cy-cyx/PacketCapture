package com.packetcapture.data

import com.google.gson.JsonParser
import com.packetcapture.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

class ExportContentTest {
    private class MemoryBodies(private val bytes: ByteArray = byteArrayOf(), private val byKey: Map<String, ByteArray> = emptyMap()) : BodyStore {
        override fun open(sessionId: String, exchangeId: String, part: BodyPart, limit: Long, totalLimit: Long): BodySink = error("not used")
        override suspend fun read(ref: BodyRef, maxBytes: Int) = byKey[ref.key] ?: bytes
        override suspend fun preview(ref: BodyRef?, headers: List<Header>) = error("not used")
        override suspend fun deleteSession(sessionId: String) {}
        override suspend fun usedBytes() = 0L
        override suspend fun recoverOrphans(liveSessionIds: Set<String>) {}
    }
    @Test fun harPreservesRepeatedHeadersRedactsSecretsAndDecodesGzip() = runBlocking {
        val raw = ByteArrayOutputStream().also { GZIPOutputStream(it).use { gzip -> gzip.write("compressed-body".toByteArray()) } }.toByteArray()
        val exchange = HttpExchange(sessionId = "s", connectionId = "c", method = "GET", url = "https://example.test/", protocol = "HTTP/2",
            completion = Completion.COMPLETE, status = 200, requestHeaders = listOf(Header("Authorization", "secret"), Header("X-Multi", "a"), Header("X-Multi", "b")),
            responseHeaders = listOf(Header("Set-Cookie", "private"), Header("Content-Encoding", "gzip")), responseBody = BodyRef("body", raw.size.toLong(), raw.size.toLong()))
        val out = ByteArrayOutputStream(); TrafficExporter(MemoryBodies(raw)).har(listOf(exchange), out)
        val json = out.toString("UTF-8"); assertFalse(json.contains("secret")); assertFalse(json.contains("private"))
        val entry = JsonParser.parseString(json).asJsonObject["log"].asJsonObject["entries"].asJsonArray[0].asJsonObject
        assertEquals(3, entry["request"].asJsonObject["headers"].asJsonArray.size())
        assertEquals("compressed-body", String(Base64.getDecoder().decode(entry["response"].asJsonObject["content"].asJsonObject["text"].asString)))
    }
    @Test fun curlRefusesTruncatedBodyAndKeepsBinaryBytes() = runBlocking {
        val bytes = byteArrayOf(0,1,-1,10)
        val base = HttpExchange(sessionId = "s", connectionId = "c", method = "POST", url = "https://example.test/", protocol = "HTTP/1.1", completion = Completion.COMPLETE,
            requestHeaders = listOf(Header("Cookie", "private")), requestBody = BodyRef("body", 4, 4))
        val exporter = TrafficExporter(MemoryBodies(bytes)); val exported = exporter.curl(base)
        val encoded = exported.command.substringAfter('\n').substringBefore("\nPACKET_CAPTURE_BODY")
        assertArrayEquals(bytes, Base64.getMimeDecoder().decode(encoded))
        assertFalse(exported.command.contains("private")); assertTrue(exported.command.contains("--data-binary '@-'"))
        assertFalse(exported.command.contains(".bin"))
        try { exporter.curl(base.copy(requestBody = base.requestBody!!.copy(truncated = true))); fail("截断请求不应导出") } catch (_: IllegalArgumentException) { }
    }
    @Test fun harMarksZeroSavedRequestAndRedactsTrailers() = runBlocking {
        val exchange = HttpExchange(sessionId = "s", connectionId = "c", streamId = 17, method = "POST", url = "https://example.test/",
            protocol = "HTTP/2", completion = Completion.COMPLETE, requestBody = BodyRef("body", 42, 0, true, "queue full"),
            responseTrailers = listOf(Header("Set-Cookie", "trailer-secret"), Header("X-Checksum", "abc")))
        val out = ByteArrayOutputStream(); TrafficExporter(MemoryBodies(byteArrayOf())).har(listOf(exchange), out)
        val json = out.toString("UTF-8"); assertFalse(json.contains("trailer-secret"))
        val entry = JsonParser.parseString(json).asJsonObject["log"].asJsonObject["entries"].asJsonArray[0].asJsonObject
        assertTrue(entry["request"].asJsonObject["_body"].asJsonObject["truncated"].asBoolean)
        assertEquals(42, entry["request"].asJsonObject["bodySize"].asInt)
        assertEquals(17, entry["_streamId"].asInt)
    }
    @Test fun curlExportsBothMessagesWithDecodedResponseAndInlineRequest() = runBlocking {
        val request = """{"query":"hello"}""".toByteArray()
        val response = ByteArrayOutputStream().also {
            GZIPOutputStream(it).use { gzip -> gzip.write("""{"message":"\u4f60\u597d"}""".toByteArray()) }
        }.toByteArray()
        val exchange = HttpExchange(sessionId = "s", connectionId = "c", method = "POST", url = "https://example.test/search?q=1", protocol = "HTTP/2",
            status = 201, completion = Completion.COMPLETE,
            requestHeaders = listOf(Header("Authorization", "request-secret"), Header("Accept-Encoding", "gzip, br"), Header("Content-Type", "application/json")),
            responseHeaders = listOf(Header("Content-Type", "application/json"), Header("Content-Encoding", "gzip"),
                Header("Set-Cookie", "response-secret"), Header("X-Multi", "first"), Header("X-Multi", "second")),
            requestTrailers = listOf(Header("Cookie", "request-trailer-secret")),
            responseTrailers = listOf(Header("Set-Cookie", "response-trailer-secret"), Header("X-Checksum", "abc")),
            requestBody = BodyRef("request", request.size.toLong(), request.size.toLong()),
            responseBody = BodyRef("response", response.size.toLong(), response.size.toLong()))
        val exporter = TrafficExporter(MemoryBodies(byKey = mapOf("request" to request, "response" to response)))
        val bundle = exporter.curl(exchange)
        assertTrue(bundle.command.contains("--data-raw '{\"query\":\"hello\"}'"))
        assertTrue(bundle.requestText.contains("POST ${exchange.url} HTTP/2"))
        assertTrue(bundle.requestText.contains("hello"))
        assertTrue(bundle.requestText.contains("Accept-Encoding: gzip, br"))
        assertTrue(bundle.responseText.contains("HTTP/2 201"))
        assertTrue(bundle.responseText.contains("你好"))
        assertTrue(bundle.responseText.contains("X-Multi: first\nX-Multi: second"))
        assertTrue(bundle.responseText.contains("X-Checksum: abc"))
        assertFalse((bundle.command + bundle.requestText + bundle.responseText).contains("secret"))
        assertTrue(bundle.command.contains("--compressed"))
        assertFalse(bundle.command.contains("--output"))
        assertFalse(bundle.command.contains("--dump-header"))
        assertFalse(bundle.command.contains("Accept-Encoding:"))
        val unredacted = exporter.curl(exchange, ExportOptions(redactCredentials = false))
        assertTrue(unredacted.requestText.contains("request-secret"))
        assertTrue(unredacted.responseText.contains("response-secret"))
    }
    @Test fun curlKeepsPartialBinaryResponseAndExplainsMissingContent() = runBlocking {
        val bytes = byteArrayOf(0, -1, 10)
        val exchange = HttpExchange(sessionId = "s", connectionId = "c", method = "GET", url = "https://example.test/file", protocol = "HTTP/1.1",
            completion = Completion.COMPLETE, status = 200, responseHeaders = listOf(Header("Content-Type", "application/octet-stream")),
            responseBody = BodyRef("response", 10, 3, truncated = true, reason = "达到正文保存上限"))
        val bundle = TrafficExporter(MemoryBodies(bytes)).curl(exchange)
        assertFalse(bundle.command.contains("--data-"))
        assertTrue(bundle.responseText.contains("【正文不完整】"))
        assertTrue(bundle.responseText.contains("达到正文保存上限"))
        assertTrue(bundle.responseText.contains("【正文（Base64）】"))
        assertTrue(bundle.responseText.contains(Base64.getEncoder().encodeToString(bytes)))
        val missing = TrafficExporter(MemoryBodies(bytes)).curl(exchange.copy(responseBody = BodyRef("response", 10, 10)))
        assertTrue(missing.responseText.contains("已保存的正文文件不完整"))
    }
    @Test fun curlHandlesEmptyHeadAndFailedResponses() = runBlocking {
        val exchange = HttpExchange(sessionId = "s", connectionId = "c", method = "HEAD", url = "https://example.test/", protocol = "HTTP/1.1",
            completion = Completion.COMPLETE, status = 200, responseHeaders = listOf(Header("Content-Encoding", "gzip")),
            responseBody = BodyRef("response"))
        val exporter = TrafficExporter(MemoryBodies())
        val empty = exporter.curl(exchange)
        assertFalse(empty.responseText.contains("正文不完整"))
        assertFalse(empty.responseText.contains("无法解码"))
        val failed = exporter.curl(exchange.copy(status = null, completion = Completion.FAILED, error = "connection reset", responseBody = null))
        assertTrue(failed.responseText.contains("FAILED"))
        assertTrue(failed.responseText.contains("connection reset"))
        assertTrue(failed.responseText.contains("未收到响应状态"))
    }
    @Test fun curlUsesLiteralTextForAtSignQuotesAndTrailingNewlines() = runBlocking {
        val body = "@not-a-file\n'quoted' \$(printf unsafe)\r\n你好\n\n".toByteArray()
        val exchange = HttpExchange(sessionId = "s", connectionId = "c", method = "POST", url = "https://example.test/", protocol = "HTTP/1.1",
            completion = Completion.COMPLETE, requestBody = BodyRef("request", body.size.toLong(), body.size.toLong()))
        val command = TrafficExporter(MemoryBodies(body)).curl(exchange).command
        assertTrue(command.contains("--data-raw '@not-a-file"))
        assertTrue(command.contains("'\"'\"'quoted'\"'\"'"))
        assertTrue(command.endsWith("你好\n\n'"))
    }
    @Test fun curlRetainsUndecodableResponseBytesWithinResponseText() = runBlocking {
        val raw = byteArrayOf(31, -117, 0, 0)
        val exchange = HttpExchange(sessionId = "s", connectionId = "c", method = "GET", url = "https://example.test/", protocol = "HTTP/1.1",
            completion = Completion.COMPLETE, status = 200, responseHeaders = listOf(Header("Content-Encoding", "gzip")),
            responseBody = BodyRef("response", raw.size.toLong(), raw.size.toLong()))
        val report = TrafficExporter(MemoryBodies(raw)).curl(exchange).responseText
        assertTrue(report.contains("正文无法解码"))
        assertTrue(report.contains("【正文（原始字节 Base64）】"))
        assertTrue(report.contains(Base64.getEncoder().encodeToString(raw)))
    }
}
