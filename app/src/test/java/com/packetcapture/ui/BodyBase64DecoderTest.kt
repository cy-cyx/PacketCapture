package com.packetcapture.ui

import com.packetcapture.core.DEFAULT_BODY_LIMIT
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class BodyBase64DecoderTest {
    @Test fun standardWrappedAndUrlSafeBase64PreserveUnicodeAndWhitespace() {
        val text = "你好，Base64 😀???\n下一行\t完成\r\n".repeat(20)
        val bytes = text.toByteArray()
        val encodings = listOf(
            Base64.getEncoder().encodeToString(bytes),
            Base64.getEncoder().withoutPadding().encodeToString(bytes),
            Base64.getMimeEncoder(76, byteArrayOf(10)).encodeToString(bytes),
            Base64.getMimeEncoder().encodeToString(bytes),
            Base64.getUrlEncoder().encodeToString(bytes),
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
        )
        for (encoded in encodings) {
            assertTrue(BodyBase64Decoder.isBase64(encoded))
            assertEquals(BodyBase64Result.Success(text), BodyBase64Decoder.decode(" \t$encoded\r\n"))
        }
    }

    @Test fun malformedAlphabetPaddingAndEmptyInputAreRejected() {
        for (text in listOf("", " \r\n\t", "A", "AAAAA", "=", "==", "====", "SGVsbG8===", "Zg=", "Zg==a", "Z=g=",
            "SGVsbG8!", "SGVsbG8_+A==", "{\"data\":\"SGVsbG8=\"}", "SGVs\u00a0bG8=")) {
            assertFalse(text, BodyBase64Decoder.isBase64(text))
            assertTrue(text, BodyBase64Decoder.decode(text) is BodyBase64Result.Failure)
        }
    }

    @Test fun binaryOrMalformedUtf8IsNotSilentlyDisplayedAsReplacementCharacters() {
        for (bytes in listOf(byteArrayOf(0, 1, 2, 3), byteArrayOf(-1), byteArrayOf(-61, 40), "text\u007f".toByteArray())) {
            val encoded = Base64.getEncoder().encodeToString(bytes)
            assertTrue(BodyBase64Decoder.isBase64(encoded))
            val result = BodyBase64Decoder.decode(encoded) as BodyBase64Result.Failure
            assertTrue(result.message.contains("不是可显示的 UTF-8 文本"))
        }
    }

    @Test fun decodingIsOnlyOneLayerAndKeepsJsonEscapesAndLongTextTail() {
        val nested = Base64.getEncoder().encodeToString("你好".toByteArray())
        assertEquals(BodyBase64Result.Success(nested), BodyBase64Decoder.decode(Base64.getEncoder().encodeToString(nested.toByteArray())))
        val text = """{"message":"\u4f60\nnext","value":"${"中".repeat(100000)}","tail":"END_OF_BASE64"}"""
        val result = BodyBase64Decoder.decode(Base64.getEncoder().encodeToString(text.toByteArray()))
        assertEquals(BodyBase64Result.Success(text), result)
    }

    @Test fun mimeWrappedFiveMiBBoundaryIsAcceptedAndOversizeIsRejected() {
        val bytes = ByteArray(DEFAULT_BODY_LIMIT.toInt()) { 65 }
        val encoded = Base64.getMimeEncoder().encodeToString(bytes)
        assertTrue(BodyBase64Decoder.isBase64(encoded))
        val result = BodyBase64Decoder.decode(encoded) as BodyBase64Result.Success
        assertEquals(bytes.size, result.text.length)
        assertTrue(result.text.all { it == 'A' })
        assertTrue(BodyBase64Decoder.decode(Base64.getEncoder().encodeToString(bytes.copyOf(bytes.size + 1))) is BodyBase64Result.Failure)
        assertTrue(BodyBase64Decoder.decode(encoded + "AAAA") is BodyBase64Result.Failure)
    }
}
