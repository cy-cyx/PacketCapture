package com.packetcapture.capture.proxy

/** 只窥探握手副本，不消费转发字节；支持 ClientHello 跨多个 TLS record，限制最大缓冲。 */
internal object ClientHelloReader {
    data class Result(val complete: Boolean, val host: String? = null, val ech: Boolean = false)
    fun read(bytes: ByteArray): Result {
        return try {
            val handshake = java.io.ByteArrayOutputStream()
            var offset = 0
            while (offset + 5 <= bytes.size) {
                if (bytes[offset].toInt() and 255 != 22) return Result(true)
                val length = u16(bytes, offset + 3)
                if (length > 18432) return Result(true)
                if (offset + 5 + length > bytes.size) return Result(false)
                handshake.write(bytes, offset + 5, length)
                val data = handshake.toByteArray()
                if (data.size >= 4) {
                    if (data[0].toInt() != 1) return Result(true)
                    val size = ((data[1].toInt() and 255) shl 16) or u16(data, 2)
                    if (size > 65532) return Result(true)
                    if (data.size >= size + 4) return parse(data, size + 4)
                }
                offset += 5 + length
            }
            Result(false)
        } catch (_: IndexOutOfBoundsException) { Result(true) }
    }
    private fun parse(b: ByteArray, end: Int): Result {
        var p = 4 + 2 + 32
        p += 1 + (b[p].toInt() and 255)
        p += 2 + u16(b, p)
        p += 1 + (b[p].toInt() and 255)
        if (p == end) return Result(true)
        val extensionsEnd = p + 2 + u16(b, p)
        if (extensionsEnd > end) return Result(true)
        p += 2
        var host: String? = null
        var ech = false
        while (p + 4 <= extensionsEnd) {
            val type = u16(b, p); val size = u16(b, p + 2); p += 4
            if (p + size > extensionsEnd) return Result(true)
            if (type == 0 && size >= 5) {
                var s = p + 2
                while (s + 3 <= p + size) {
                    val nameType = b[s].toInt() and 255
                    val n = u16(b, s + 1); s += 3
                    if (s + n > p + size) return Result(true)
                    if (nameType == 0) host = String(b, s, n, Charsets.US_ASCII).lowercase().trimEnd('.')
                    s += n
                }
            }
            if (type == 0xfe0d) ech = true
            p += size
        }
        return Result(true, host?.takeIf { it.length in 1..253 && it.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' } }, ech)
    }
    private fun u16(b: ByteArray, p: Int) = ((b[p].toInt() and 255) shl 8) or (b[p + 1].toInt() and 255)
}
