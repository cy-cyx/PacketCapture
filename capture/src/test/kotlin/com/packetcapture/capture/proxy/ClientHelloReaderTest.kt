package com.packetcapture.capture.proxy

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class ClientHelloReaderTest {
    private fun hello(host: String, ech: Boolean = false): ByteArray {
        val extension = ByteArrayOutputStream().also { stream -> DataOutputStream(stream).apply {
            writeShort(0); writeShort(host.length + 5); writeShort(host.length + 3); writeByte(0); writeShort(host.length); writeBytes(host)
            if (ech) { writeShort(0xfe0d); writeShort(1); writeByte(0) }
        } }.toByteArray()
        val body = ByteArrayOutputStream().also { stream -> DataOutputStream(stream).apply {
            writeShort(0x303); write(ByteArray(32)); writeByte(0); writeShort(2); writeShort(0x1301); writeByte(1); writeByte(0); writeShort(extension.size); write(extension)
        } }.toByteArray()
        return byteArrayOf(1,0,(body.size shr 8).toByte(),body.size.toByte()) + body
    }
    private fun record(bytes: ByteArray) = byteArrayOf(22,3,3,(bytes.size shr 8).toByte(),bytes.size.toByte()) + bytes
    @Test fun fragmentedRecordsResolveSniOnlyWhenComplete() {
        val h = hello("Example.TEST")
        val bytes = record(h.copyOfRange(0,30)) + record(h.copyOfRange(30,h.size))
        for (size in 0 until bytes.size) assertFalse("$size", ClientHelloReader.read(bytes.copyOf(size)).complete)
        assertEquals("example.test", ClientHelloReader.read(bytes).host)
    }
    @Test fun encryptedClientHelloIsFlaggedForPassthrough() {
        assertTrue(ClientHelloReader.read(record(hello("cover.test", true))).ech)
    }
    @Test fun malformedLengthDoesNotThrowOrReturnUntrustedHostname() {
        val invalid = record(byteArrayOf(1,0,0,2,3,3))
        assertNull(ClientHelloReader.read(invalid).host)
        assertTrue(ClientHelloReader.read(byteArrayOf(23,3,3,0,0)).complete)
    }
}
