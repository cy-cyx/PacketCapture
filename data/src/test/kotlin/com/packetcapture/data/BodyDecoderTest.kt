package com.packetcapture.data

import com.google.gson.JsonParser
import com.packetcapture.core.DEFAULT_BODY_LIMIT
import com.packetcapture.core.Header
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

class BodyDecoderTest {
    private fun decode(text: String, type: String) = BodyDecoder.decode(text.byteInputStream(), listOf(Header("Content-Type", type)))

    @Test fun jsonAboveOldLimitsIsFormattedWithoutLosingTailNumbersOrDuplicateKeys() {
        val raw = "{\"text\":\"${"中".repeat(100000)}\",\"n\":900719925474099312345,\"same\":1,\"same\":2,\"tail\":\"END_OF_REQUEST\"}"
        val result = decode(raw, "Application/JSON; Charset=\"UTF-8\"")
        assertTrue(result.text.startsWith("{\n  \"text\": "))
        assertTrue(result.text.contains("900719925474099312345"))
        assertEquals(2, Regex("\"same\"").findAll(result.text).count())
        assertEquals("END_OF_REQUEST", JsonParser.parseString(result.text).asJsonObject["tail"].asString)
        assertFalse(result.limited)
    }

    @Test fun stackedCompressionIsDecodedInReverseOrder() {
        val text = "{\"marker\":\"${"decoded".repeat(12000)}-END\"}"
        val gzip = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()
        val compressed = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(gzip) } }.toByteArray()
        val result = BodyDecoder.decode(compressed.inputStream(), listOf(Header("Content-Encoding", "gzip, deflate"), Header("Content-Type", "application/json")))
        assertEquals(JsonParser.parseString(text), JsonParser.parseString(result.text))
        assertFalse(result.limited)
        assertTrue(result.note!!.contains("已解压"))
    }

    @Test fun formPreservesRepeatedAndEmptyFieldsAndDecodesCharset() {
        val result = decode("name=%D6%D0%CE%C4&name=a%2Bb+c&empty=&bare&token=a%3Db", "application/x-www-form-urlencoded; charset = GBK")
        assertEquals("name = 中文\n\nname = a+b c\n\nempty = \n\nbare = \n\ntoken = a=b", result.text)
        assertFalse(result.binary)
        assertFalse(result.limited)
    }

    @Test fun multipartPreservesLongTextAndBinaryFileAndIgnoresBoundaryPrefixesInContent() {
        val long = "字段".repeat(35000) + "\r\n--test-boundary-not-a-delimiter\r\nEND_OF_FIELD"
        val binary = byteArrayOf(0, 1, 2, -1, 13, 10)
        val bytes = ("--test-boundary\r\nContent-Disposition: form-data; name=\"字段\"\r\n\r\n$long\r\n" +
            "--test-boundary\r\nContent-Disposition: form-data; name=\"upload\"; filename=\"中文.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n").toByteArray() +
            binary + "\r\n--test-boundary--\r\n".toByteArray()
        val result = BodyDecoder.decode(bytes.inputStream(), listOf(Header("Content-Type", "multipart/form-data; boundary=\"test-boundary\"")))
        assertTrue(result.text.contains("[1] 字段"))
        assertTrue(result.text.contains(long))
        assertTrue(result.text.contains("文件名：中文.bin"))
        assertTrue(result.text.endsWith(Base64.getEncoder().encodeToString(binary)))
        assertTrue(result.note!!.contains("共 2 个字段/文件"))
        assertFalse(result.limited)
    }

    @Test fun binaryContentBeyondOldFourKiBPreviewIsFullyRecoverable() {
        val bytes = ByteArray(90000) { (it % 256).toByte() }
        val result = BodyDecoder.decode(bytes.inputStream(), listOf(Header("Content-Type", "application/octet-stream")))
        assertArrayEquals(bytes, Base64.getMimeDecoder().decode(result.text))
        assertTrue(result.binary)
        assertFalse(result.limited)
    }

    @Test fun invalidJsonAndFormFallBackToOriginalWithoutDroppingContent() {
        for ((text, type) in listOf("{\"incomplete\":" to "application/json", "bad=%ZZ&next=ok" to "application/x-www-form-urlencoded")) {
            val result = decode(text, type)
            assertEquals(text, result.text)
            assertTrue(result.note!!.contains("原文"))
        }
        val nested = "[".repeat(129) + "0" + "]".repeat(129)
        assertEquals(nested, decode(nested, "application/json").text)
    }

    @Test fun incompleteStorageAndDecompressionLimitAreExplicit() {
        val incomplete = BodyDecoder.decode("prefix".byteInputStream(), listOf(Header("Content-Type", "text/plain")), true, "达到正文保存上限")
        assertTrue(incomplete.limited)
        assertEquals("prefix", incomplete.text)
        assertTrue(incomplete.note!!.contains("保存上限"))
        val raw = ByteArray(DEFAULT_BODY_LIMIT.toInt() + 1) { 'X'.code.toByte() }
        val compressed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(raw) } }.toByteArray()
        val large = BodyDecoder.decode(compressed.inputStream(), listOf(Header("Content-Type", "text/plain"), Header("Content-Encoding", "gzip")))
        assertTrue(large.limited)
        assertEquals(DEFAULT_BODY_LIMIT.toInt(), large.text.length)
        assertTrue(large.note!!.contains("解码后超过"))
    }

    @Test fun unsupportedEncodingDoesNotPretendCompressedBytesAreText() {
        val bytes = byteArrayOf(1, 2, 3, -1)
        val result = BodyDecoder.decode(bytes.inputStream(), listOf(Header("Content-Encoding", "unknown"), Header("Content-Type", "application/json")))
        assertTrue(result.binary)
        assertArrayEquals(bytes, Base64.getMimeDecoder().decode(result.text))
        assertTrue(result.note!!.contains("暂不支持"))
    }

    @Test fun jsonEscapesAreReadableByDefaultAndOriginalTextRemainsAvailable() {
        val raw = """{"message":"\u4f60\u597d\nnext\t\"quoted\"","url":"https:\/\/example.test","emoji":"\ud83d\ude00"}"""
        val result = decode(raw, "application/json")
        assertTrue(result.text.contains("你好\nnext\t\"quoted\""))
        assertTrue(result.text.contains("https://example.test"))
        assertTrue(result.text.contains("😀"))
        assertEquals(raw, result.rawText)
        assertTrue(result.note!!.contains("解码转义"))
        assertFalse(result.limited)
    }

    @Test fun escapedBackslashesAreDecodedOnlyOnceSoPathsAndLiteralSequencesSurvive() {
        val raw = """{"path":"C:\\new\\test","literal":"\\u4e2d\\n","control":"\u0000\b\f"}"""
        val result = decode(raw, "application/json")
        assertTrue(result.text.contains("C:\\new\\test"))
        assertTrue(result.text.contains("\\u4e2d\\n"))
        assertTrue(result.text.contains("\\u0000\\b\\f"))
        assertFalse(result.text.contains("C:\new"))
        assertEquals(raw, result.rawText)
    }

    @Test fun plainTextIsNotBlindlyUnescapedButJsonInTextFieldsIsRecognized() {
        val plain = "C:\\new\\test literal \\n and \\u4e2d"
        val result = decode(plain, "text/plain")
        assertEquals(plain, result.text)
        assertNull(result.rawText)
        val json = decode("""{"field":"first\nsecond"}""", "text/plain")
        assertTrue(json.text.contains("first\nsecond"))
        assertNotNull(json.rawText)
    }

    @Test fun jsonInUrlEncodedFormIsUnescapedAfterPercentDecoding() {
        val raw = "payload=%7B%22message%22%3A%22%5Cu4e2d%5Cnnext%22%7D&name=a%2Bb+c"
        val result = decode(raw, "application/x-www-form-urlencoded")
        assertTrue(result.text.contains("中\nnext"))
        assertTrue(result.text.contains("name = a+b c"))
        assertEquals(raw, result.rawText)
        assertTrue(result.note!!.contains("JSON 字段"))
    }
}
