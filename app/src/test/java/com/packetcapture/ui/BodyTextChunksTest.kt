package com.packetcapture.ui

import org.junit.Assert.*
import org.junit.Test

class BodyTextChunksTest {
    @Test fun completeBodySurvivesChunkingIncludingSurrogateAndNewlineBoundaries() {
        val text = "x".repeat(4095) + "😀" + "y".repeat(4094) + "\r\n" + "中文\n".repeat(30000) + "LAST_REQUEST_FIELD"
        val chunks = bodyTextChunks(text)
        assertTrue(chunks.size > 16)
        assertEquals(text, chunks.joinToString("") { text.substring(it.start, it.end) })
        for (chunk in chunks) {
            assertTrue(chunk.end - chunk.start <= 4096)
            assertTrue(text.substring(chunk.start, chunk.end).count { it == '\n' } <= 64)
            assertFalse(text[chunk.end - 1].isHighSurrogate())
            assertFalse(text[chunk.start].isLowSurrogate())
            if (chunk.end < text.length) assertFalse(text[chunk.end - 1] == '\r' && text[chunk.end] == '\n')
        }
    }

    @Test fun newlineHeavyBodyKeepsIndividualTextLayoutsBounded() {
        val text = "\n".repeat(70000)
        val chunks = bodyTextChunks(text)
        assertEquals(text, chunks.joinToString("") { text.substring(it.start, it.end) })
        assertTrue(chunks.all { it.end - it.start <= 64 })
    }
}
