package com.packetcapture.capture.proxy

import io.netty.handler.codec.http.DefaultHttpHeaders
import org.junit.Assert.*
import org.junit.Test

class RecordedHeadersTest {
    @Test fun h2AdapterMetadataIsExcludedWithoutLosingRepeatedHeadersOrMutatingForwardedMessage() {
        val headers = DefaultHttpHeaders().add("x-http2-stream-id", "17")
            .add("transfer-encoding", "chunked").add("set-cookie", "a=1").add("set-cookie", "b=2")
        assertEquals(listOf("a=1", "b=2"), recordableHeaders(headers, "HTTP/2").map { it.value })
        assertEquals(4, headers.size())
        assertEquals(4, recordableHeaders(headers, "HTTP/1.1").size)
    }
}
