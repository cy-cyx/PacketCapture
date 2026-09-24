package com.packetcapture.ui

import com.packetcapture.core.DEFAULT_BODY_LIMIT
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.Base64

internal sealed interface BodyBase64Result {
    data class Success(val text: String) : BodyBase64Result
    data class Failure(val message: String) : BodyBase64Result
}

/** 仅解码一层；先校验字母表和补位，避免 MIME 解码器忽略标点后误解码普通正文。 */
internal object BodyBase64Decoder {
    private const val MAX_BASE64_CHARS = ((DEFAULT_BODY_LIMIT + 2) / 3) * 4
    private const val MAX_INPUT_CHARS = MAX_BASE64_CHARS + ((MAX_BASE64_CHARS - 1) / 76) * 2
    private const val NON_TEXT = "Base64 已解码，但结果不是可显示的 UTF-8 文本。可切回 Base64 查看。"

    fun isBase64(text: String): Boolean = normalized(text) != null

    fun decode(text: String): BodyBase64Result {
        if (text.length > MAX_INPUT_CHARS) return BodyBase64Result.Failure("内容超过 5 MiB Base64 解码上限。")
        val encoded = normalized(text) ?: return BodyBase64Result.Failure("Base64 格式无效或内容不完整，可切回原内容查看。")
        return try {
            val bytes = Base64.getDecoder().decode(encoded)
            if (bytes.size > DEFAULT_BODY_LIMIT) return BodyBase64Result.Failure("内容超过 5 MiB Base64 解码上限。")
            // CharsetDecoder 默认报告非法 UTF-8，不能用替换字符掩盖二进制内容。
            val decoded = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            if (decoded.any { it.isISOControl() && it !in "\r\n\t" }) BodyBase64Result.Failure(NON_TEXT)
            else BodyBase64Result.Success(decoded)
        } catch (_: CharacterCodingException) {
            BodyBase64Result.Failure(NON_TEXT)
        } catch (_: IllegalArgumentException) {
            BodyBase64Result.Failure("Base64 格式无效或内容不完整，可切回原内容查看。")
        }
    }

    private fun normalized(text: String): String? {
        if (text.isEmpty() || text.length > MAX_INPUT_CHARS) return null
        val encoded = StringBuilder(text.length)
        var padding = 0
        var standard = false
        var urlSafe = false
        for (char in text) {
            when (char) {
                ' ', '\t', '\r', '\n' -> continue
                '=' -> if (++padding > 2) return null
                in 'A'..'Z', in 'a'..'z', in '0'..'9', '+', '/', '-', '_' -> {
                    if (padding != 0) return null
                    if (char == '+' || char == '/') standard = true
                    if (char == '-' || char == '_') urlSafe = true
                    if (standard && urlSafe) return null
                }
                else -> return null
            }
            encoded.append(when (char) { '-' -> '+'; '_' -> '/'; else -> char })
        }
        val payloadLength = encoded.length - padding
        if (payloadLength < 2 || payloadLength % 4 == 1 || encoded.length > MAX_BASE64_CHARS) return null
        if (padding != 0 && (encoded.length % 4 != 0 || padding != (4 - payloadLength % 4) % 4)) return null
        return encoded.toString()
    }
}
