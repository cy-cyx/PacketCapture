package com.packetcapture.capture.proxy

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

class Socks5UdpTest {
    @Test fun fragmentedHandshakeWaitsForFullReply() {
        var ready: InetSocketAddress? = null
        val channel = EmbeddedChannel(UdpAssociateHandler({ ready = it }, {}))
        assertBytes(byteArrayOf(5, 1, 0), channel.readOutbound())
        channel.writeInbound(Unpooled.wrappedBuffer(byteArrayOf(5)))
        assertNull(ready); assertNull(channel.readOutbound<Any>())
        channel.writeInbound(Unpooled.wrappedBuffer(byteArrayOf(0)))
        assertBytes(Socks5Udp.associate, channel.readOutbound())
        val reply = byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0x2a, 0x38)
        reply.forEachIndexed { index, value ->
            channel.writeInbound(Unpooled.wrappedBuffer(byteArrayOf(value)))
            if (index != reply.lastIndex) assertNull(ready)
        }
        assertEquals(InetSocketAddress("127.0.0.1", 10808), ready)
        channel.finishAndReleaseAll()
    }
    @Test fun authenticationAndUnsupportedUdpAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { Socks5Udp.checkGreeting(5, 2) }
        assertThrows(IllegalArgumentException::class.java) { Socks5Udp.checkGreeting(4, 0) }
        val reply = Unpooled.wrappedBuffer(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0))
        try { assertThrows(IllegalArgumentException::class.java) { Socks5Udp.readAssociateReply(reply) } } finally { reply.release() }
    }
    @Test fun datagramsPreserveIpv4Ipv6AndBinaryPayloads() {
        for (host in listOf("8.8.8.8", "2001:4860:4860::8888")) {
            val target = InetSocketAddress(host, 53)
            val bytes = ByteArray(4096) { it.toByte() }
            val encoded = Socks5Udp.encode(target, bytes)
            try {
                assertArrayEquals(bytes, Socks5Udp.decode(encoded, target))
                assertNull(Socks5Udp.decode(encoded, InetSocketAddress(host, 54)))
                encoded.setByte(2, 1); assertNull(Socks5Udp.decode(encoded, target))
            } finally { encoded.release() }
        }
    }
    @Test fun truncatedRepliesAndNonLoopbackRelayAreRejected() {
        val target = InetSocketAddress("1.1.1.1", 53)
        val message = Socks5Udp.encode(target, byteArrayOf())
        try {
            for (length in 0 until message.readableBytes()) assertNull(Socks5Udp.decode(message.slice(0, length), target))
        } finally { message.release() }
        val reply = Unpooled.wrappedBuffer(byteArrayOf(5, 0, 0, 1, 8, 8, 8, 8, 0, 53))
        try { assertThrows(IllegalArgumentException::class.java) { Socks5Udp.readAssociateReply(reply) } } finally { reply.release() }
    }
    private fun assertBytes(expected: ByteArray, actual: ByteBuf) {
        try { assertArrayEquals(expected, ByteArray(actual.readableBytes()).also { actual.readBytes(it) }) } finally { actual.release() }
    }
}
