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
    private class MemoryBodies(private val bytes: ByteArray) : BodyStore {
        override fun open(sessionId: String, exchangeId: String, part: BodyPart, limit: Long, totalLimit: Long): BodySink = error("not used")
        override suspend fun read(ref: BodyRef, maxBytes: Int) = bytes
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
        assertArrayEquals(bytes, exported.body); assertFalse(exported.command.contains("private")); assertTrue(exported.command.contains("--data-binary"))
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
}
