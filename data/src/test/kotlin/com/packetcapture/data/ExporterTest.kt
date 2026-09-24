package com.packetcapture.data
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class ExporterTest {
    @Test fun shellQuotingNeverLeavesApostropheUnescaped() {
        assertEquals("'a'\"'\"'b'", TrafficExporter.quote("a'b"))
        assertEquals("'\$(id)\nend'", TrafficExporter.quote("\$(id)\nend"))
    }
    @Test fun queryPreservesRepeatedKeysAndDecodesValues() {
        val query = TrafficExporter.queryParameters("https://example.test/?x=1&x=2&name=a%20b")
        assertEquals(listOf("1", "2", "a b"), query.map { it.value })
    }
    @Test fun decompressedPreviewIsBounded() {
        val result = FileBodyStore.readLimited(ByteArrayInputStream(ByteArray(10000)), 512)
        assertEquals(512, result.first.size)
        assertTrue(result.second)
    }
}
