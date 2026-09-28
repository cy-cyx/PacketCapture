package com.packetcapture.export

import com.packetcapture.core.*
import com.packetcapture.data.TrafficExporter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DocumentExporterTest {
    @Test fun zipContainsOnlyRequestResponseAndSelfContainedCurlText() = runBlocking {
        val request = """{"message":"\u4f60\u597d","literal":"@file 'quote' ${'$'}(printf unsafe)"}""".toByteArray()
        val response = ByteArrayOutputStream().also {
            GZIPOutputStream(it).use { gzip -> gzip.write("""{"result":"captured response"}""".toByteArray()) }
        }.toByteArray()
        val exchange = HttpExchange(id = "export-fixture", sessionId = "s", connectionId = "c", method = "POST",
            url = "https://example.test/export", protocol = "HTTP/2", status = 200, completion = Completion.COMPLETE,
            requestHeaders = listOf(Header("Content-Type", "application/json"), Header("Cookie", "private-request"),
                Header("Authorization", "Bearer request-token"), Header("Proxy-Authorization", "Basic proxy-token")),
            responseHeaders = listOf(Header("Content-Type", "application/json"), Header("Content-Encoding", "gzip"), Header("Set-Cookie", "private-response")),
            requestBody = BodyRef("request", request.size.toLong(), request.size.toLong()),
            responseBody = BodyRef("response", response.size.toLong(), response.size.toLong()))
        val output = export(exchange, mapOf("request" to request, "response" to response))
        val entries = unzip(output)
        assertEquals(setOf("request.txt", "response.txt", "curl.txt"), entries.keys)
        val requestText = entries.getValue("request.txt").toString(Charsets.UTF_8)
        assertTrue(requestText.contains("你好"))
        assertTrue(requestText.contains("Cookie: private-request"))
        assertTrue(requestText.contains("Authorization: Bearer request-token"))
        assertTrue(requestText.contains("Proxy-Authorization: Basic proxy-token"))
        val responseText = entries.getValue("response.txt").toString(Charsets.UTF_8)
        assertTrue(responseText.contains("HTTP/2 200"))
        assertTrue(responseText.contains("captured response"))
        val allText = entries.values.joinToString { it.toString(Charsets.UTF_8) }
        assertTrue(responseText.contains("Set-Cookie: private-response"))
        assertFalse(allText.contains("[REDACTED]"))
        val script = entries.getValue("curl.txt").toString(Charsets.UTF_8)
        assertTrue(script.contains("--header 'Cookie: private-request'"))
        assertTrue(script.contains("--header 'Authorization: Bearer request-token'"))
        assertTrue(script.contains("--header 'Proxy-Authorization: Basic proxy-token'"))
        assertTrue(script.contains("--compressed"))
        assertTrue(script.contains("--data-raw " + TrafficExporter.quote(request.toString(Charsets.UTF_8))))
        assertFalse(script.contains(".bin"))
        assertFalse(script.contains("--output"))
        assertFalse(script.contains("--dump-header"))
        // 留下不含真实用户数据的导出样例，供桌面端解压和 cURL 运行验证。
        File("build/test-exports/request-response.zip").apply { parentFile?.mkdirs(); writeBytes(output) }
        File("build/test-exports/request-response.expected").writeBytes(request)
        Unit
    }

    @Test fun zipWithoutBodiesStillIncludesRequestAndResponseReports() = runBlocking {
        val exchange = HttpExchange(sessionId = "s", connectionId = "c", method = "GET", url = "https://example.test/empty",
            protocol = "HTTP/1.1", status = 204, completion = Completion.COMPLETE)
        val entries = unzip(export(exchange, emptyMap()))
        assertEquals(setOf("request.txt", "response.txt", "curl.txt"), entries.keys)
        assertTrue(entries.getValue("request.txt").toString(Charsets.UTF_8).contains(exchange.url))
        assertTrue(entries.getValue("response.txt").toString(Charsets.UTF_8).contains("HTTP/1.1 204"))
        assertFalse(entries.getValue("curl.txt").toString(Charsets.UTF_8).contains("--data-"))
    }

    @Test fun binaryAndLargeBodiesAreEmbeddedInTheSameCurlText() = runBlocking {
        val requests = mapOf("binary" to ByteArray(256) { it.toByte() }, "large" to "你好\r\n".repeat(24_000).toByteArray())
        for ((name, request) in requests) {
            val exchange = HttpExchange(id = "export-$name", sessionId = "s", connectionId = "c", method = "POST",
                url = "https://example.test/export", protocol = "HTTP/1.1", status = 204, completion = Completion.COMPLETE,
                requestHeaders = listOf(Header("Content-Type", if (name == "binary") "application/octet-stream" else "text/plain; charset=utf-8")),
                requestBody = BodyRef("request", request.size.toLong(), request.size.toLong()))
            val output = export(exchange, mapOf("request" to request))
            val entries = unzip(output)
            assertEquals(setOf("request.txt", "response.txt", "curl.txt"), entries.keys)
            val command = entries.getValue("curl.txt").toString(Charsets.UTF_8)
            assertTrue(command.startsWith("base64 -d <<'PACKET_CAPTURE_BODY' | curl "))
            assertTrue(command.contains("--data-binary '@-'"))
            assertArrayEquals(request, Base64.getMimeDecoder().decode(command.substringAfter('\n').substringBefore("\nPACKET_CAPTURE_BODY")))
            File("build/test-exports/request-response-$name.zip").apply { parentFile?.mkdirs(); writeBytes(output) }
            File("build/test-exports/request-response-$name.expected").writeBytes(request)
        }
    }

    private suspend fun export(exchange: HttpExchange, content: Map<String, ByteArray>): ByteArray {
        val bodies = object : BodyStore {
            override fun open(sessionId: String, exchangeId: String, part: BodyPart, limit: Long, totalLimit: Long): BodySink = error("not used")
            override suspend fun read(ref: BodyRef, maxBytes: Int) = content.getValue(ref.key)
            override suspend fun preview(ref: BodyRef?, headers: List<Header>): BodyPreview = error("not used")
            override suspend fun deleteSession(sessionId: String) {}
            override suspend fun clearAll() {}
            override suspend fun usedBytes() = 0L
            override suspend fun recoverOrphans(liveSessionIds: Set<String>) {}
        }
        val certificates = object : CertificateManager {
            override suspend fun ensureCertificate(): CertificateInfo = error("not used")
            override suspend fun exportCertificate(output: OutputStream) = error("not used")
        }
        val repository = object : CaptureRepository {
            override fun exchange(id: String) = flowOf(exchange.takeIf { it.id == id })
            override fun sessions(query: String) = flowOf(emptyList<CaptureSession>())
            override fun exchanges(sessionId: String?, filter: ExchangeFilter, limit: Int) = flowOf(emptyList<HttpExchange>())
            override fun connections(sessionId: String?) = flowOf(emptyList<ConnectionRecord>())
            override suspend fun session(id: String): CaptureSession? = null
            override suspend fun connection(id: String): ConnectionRecord? = null
            override suspend fun allExchanges(sessionId: String) = listOf(exchange)
            override suspend fun saveSession(session: CaptureSession) {}
            override suspend fun saveConnection(connection: ConnectionRecord) {}
            override suspend fun saveExchange(exchange: HttpExchange) {}
            override suspend fun deleteSession(id: String) {}
            override suspend fun clearStorage() {}
            override suspend fun recoverInterrupted() {}
        }
        var closed = false
        val output = object : ByteArrayOutputStream() {
            override fun close() { closed = true; super.close() }
        }
        DocumentExporter(repository, bodies, certificates, TrafficExporter(bodies)).write(ExportRequest("curl", exchange.id), output)
        assertFalse("导出不能关闭调用方的输出流", closed)
        return output.toByteArray()
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = ZipInputStream(bytes.inputStream()).use { zip ->
        buildMap {
            while (true) {
                val entry = zip.nextEntry ?: break
                assertNull("ZIP 不应包含重名文件", put(entry.name, zip.readBytes()))
            }
        }
    }
}
