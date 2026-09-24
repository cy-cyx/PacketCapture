package com.packetcapture.data

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.packetcapture.core.BodyPreview
import com.packetcapture.core.DEFAULT_BODY_LIMIT
import com.packetcapture.core.Header
import com.packetcapture.core.firstHeader
import java.io.InputStream
import java.io.StringReader
import java.io.Writer
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/** 只解释已保存的实体，不改变转发/导出原始字节；解压和格式化均有独立内存上限。 */
internal object BodyDecoder {
    private const val MAX_FORMATTED_CHARS = 20 * 1024 * 1024

    fun decode(input: InputStream, headers: List<Header>, truncated: Boolean = false, reason: String? = null): BodyPreview {
        val notes = mutableListOf<String>()
        if (truncated) notes += reason ?: "采集时正文未完整保存"
        val encodings = headers.filter { it.name.equals("content-encoding", true) }
            .flatMap { it.value.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() && it != "identity" }
        return try {
            val unsupported = encodings.any { it !in setOf("gzip", "x-gzip", "deflate") }
            var stream = input
            if (!unsupported) for (encoding in encodings.asReversed()) {
                stream = when (encoding) {
                    "gzip", "x-gzip" -> GZIPInputStream(stream)
                    else -> InflaterInputStream(stream)
                }
            }
            val (bytes, limited) = stream.use { FileBodyStore.readLimited(it, DEFAULT_BODY_LIMIT.toInt()) }
            if (limited) notes += "解码后超过 5 MiB，仅显示前 5 MiB"
            val rendered = if (unsupported) {
                notes += "暂不支持 Content-Encoding: ${encodings.joinToString(", ")}，显示原始字节的 Base64"
                Rendered(base64(bytes), true)
            } else {
                if (encodings.isNotEmpty()) notes += "已解压 ${encodings.joinToString(", ")}"
                render(bytes, headers.firstHeader("content-type").orEmpty())
            }
            rendered.note?.let(notes::add)
            BodyPreview(rendered.text, rendered.binary, limited || truncated,
                notes.takeIf { it.isNotEmpty() }?.joinToString("；"), rendered.rawText)
        } catch (e: Exception) {
            notes += "正文无法解码：${e.message ?: e.javaClass.simpleName}，可导出 HAR 查看已保存的原始字节"
            BodyPreview("", false, true, notes.joinToString("；"))
        }
    }

    private data class Rendered(val text: String, val binary: Boolean = false, val note: String? = null,
        val rawText: String? = null)

    private fun render(bytes: ByteArray, contentType: String, inheritedCharset: Charset = Charsets.UTF_8, depth: Int = 0): Rendered {
        if (bytes.isEmpty()) return Rendered("")
        val type = contentType.substringBefore(';').trim().lowercase()
        val charset = parameter(contentType, "charset")?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: inheritedCharset
        if (type.startsWith("multipart/") && depth < 8) {
            return runCatching { multipart(bytes, contentType, charset, depth) }
                .getOrElse { Rendered(String(bytes, charset), note = "multipart 无法完整解析，显示原文：${it.message}") }
        }
        val textual = type.startsWith("text/") || type.contains("json") || type.contains("xml") ||
            type in setOf("application/x-www-form-urlencoded", "application/javascript", "application/graphql") ||
            (type.isEmpty() && bytes.take(512).none { it == 0.toByte() })
        if (!textual) return Rendered(base64(bytes), true, "二进制内容，完整已保存字节以 Base64 显示")
        val text = String(bytes, charset)
        return when {
            type.contains("json") ->
                runCatching { readableJson(text) }
                    .getOrElse { Rendered(text, note = "JSON 无法格式化，显示原文：${it.message}") }
            type == "application/x-www-form-urlencoded" ->
                runCatching {
                    val formatted = LimitedWriter()
                    var count = 0
                    var jsonField = false
                    // 不转换成 Map：重复字段、空值和原始顺序必须保留。
                    for (entry in text.splitToSequence('&')) {
                        if (count++ > 0) formatted.write("\n\n")
                        val name = URLDecoder.decode(entry.substringBefore('='), charset.name())
                        val value = URLDecoder.decode(entry.substringAfter('=', ""), charset.name())
                        val parsed = tryReadableJson(value)
                        if (parsed != null) jsonField = true
                        formatted.write("$name = ${parsed?.text ?: value}")
                    }
                    Rendered(formatted.toString(), note = "表单已 URL 解码，保留重复字段与顺序" +
                        if (jsonField) "；JSON 字段已解码转义（阅读视图）" else "", rawText = text)
                }.getOrElse { Rendered(text, note = "表单无法完整解析，显示原文：${it.message}") }
            else -> tryReadableJson(text) ?: Rendered(text)
        }
    }

    private fun tryReadableJson(text: String): Rendered? {
        if (text.trimStart().firstOrNull() !in listOf('{', '[', '"')) return null
        return runCatching { readableJson(text) }.getOrNull()
    }

    private fun readableJson(text: String): Rendered {
        val formatted = formatJson(text)
        val readable = unescapeJsonForDisplay(formatted)
        return Rendered(readable, note = "JSON 已格式化并解码转义（阅读视图）", rawText = text.takeIf { it != readable })
    }

    /** 只处理已校验 JSON 的一层字符串转义；不反复替换，避免把字面量 \\n 或文件路径变成换行。 */
    private fun unescapeJsonForDisplay(json: String): String {
        if ('\\' !in json) return json
        val output = StringBuilder(json.length)
        var quoted = false
        var index = 0
        while (index < json.length) {
            val char = json[index++]
            if (char == '"') quoted = !quoted
            if (char != '\\' || !quoted) { output.append(char); continue }
            when (val escape = json[index++]) {
                '"', '\\', '/' -> output.append(escape)
                'n' -> output.append('\n')
                'r' -> output.append('\r')
                't' -> output.append('\t')
                'u' -> {
                    val code = json.substring(index, index + 4).toInt(16)
                    // 非排版控制字符保留可见表示，不在阅读视图中悄悄消失。
                    if (code < 0x20 && code !in listOf(9, 10, 13)) output.append("\\u").append(json, index, index + 4)
                    else output.append(code.toChar())
                    index += 4
                }
                else -> output.append('\\').append(escape)
            }
        }
        return output.toString()
    }

    /** 流式格式化保留重复 JSON 键及数字的原始精度，不构建大对象树。 */
    private fun formatJson(text: String): String {
        val output = LimitedWriter()
        JsonReader(StringReader(text)).use { reader ->
            reader.strictness = Strictness.STRICT
            JsonWriter(output).use { writer ->
                writer.setIndent("  ")
                writer.isHtmlSafe = false
                writer.serializeNulls = true
                var depth = 0
                while (true) {
                    when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> { check(++depth <= 128) { "嵌套超过 128 层" }; reader.beginObject(); writer.beginObject() }
                        JsonToken.END_OBJECT -> { reader.endObject(); writer.endObject(); depth-- }
                        JsonToken.BEGIN_ARRAY -> { check(++depth <= 128) { "嵌套超过 128 层" }; reader.beginArray(); writer.beginArray() }
                        JsonToken.END_ARRAY -> { reader.endArray(); writer.endArray(); depth-- }
                        JsonToken.NAME -> writer.name(reader.nextName())
                        JsonToken.STRING -> writer.value(reader.nextString())
                        JsonToken.NUMBER -> writer.jsonValue(reader.nextString())
                        JsonToken.BOOLEAN -> writer.value(reader.nextBoolean())
                        JsonToken.NULL -> { reader.nextNull(); writer.nullValue() }
                        JsonToken.END_DOCUMENT -> break
                    }
                }
            }
        }
        return output.toString()
    }

    private fun multipart(bytes: ByteArray, contentType: String, charset: Charset, depth: Int): Rendered {
        val boundary = parameter(contentType, "boundary") ?: error("缺少 boundary")
        require(boundary.isNotEmpty() && boundary.length <= 70 && boundary.none { it == '\r' || it == '\n' }) { "boundary 无效" }
        // Latin-1 保持每个字符与原字节一一对应，文件字段不会在边界扫描时被 UTF-8 替换。
        val raw = String(bytes, Charsets.ISO_8859_1)
        val delimiter = Regex("(?:\\A|\\r\\n)--${Regex.escape(boundary)}(--)?[ \\t]*(?:\\r\\n|\\z)")
        var previous = delimiter.find(raw) ?: error("找不到 boundary")
        val output = LimitedWriter()
        var count = 0
        val notes = mutableSetOf<String>()
        while (previous.groupValues[1] != "--") {
            val next = delimiter.find(raw, previous.range.last + 1) ?: error("缺少结束 boundary")
            val part = raw.substring(previous.range.last + 1, next.range.first)
            val headerEnd = part.indexOf("\r\n\r\n")
            require(headerEnd >= 0) { "字段头不完整" }
            val headerText = String(part.substring(0, headerEnd).toByteArray(Charsets.ISO_8859_1), charset)
            val headers = headerText.split("\r\n").mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon < 1) null else Header(line.substring(0, colon).trim(), line.substring(colon + 1).trim())
            }
            val disposition = headers.firstHeader("content-disposition").orEmpty()
            val name = parameter(disposition, "name") ?: "未命名字段"
            val filename = parameter(disposition, "filename")
            val type = headers.firstHeader("content-type") ?: if (filename == null) "text/plain" else "application/octet-stream"
            val content = part.substring(headerEnd + 4).toByteArray(Charsets.ISO_8859_1)
            val rendered = render(content, type, charset, depth + 1)
            rendered.note?.let(notes::add)
            if (count > 0) output.write("\n\n")
            output.write("[${++count}] $name\n")
            filename?.let { output.write("文件名：$it\n") }
            output.write("Content-Type: $type\n\n")
            output.write(rendered.text)
            previous = next
        }
        return Rendered(output.toString(), note = (listOf("multipart 已解析，共 $count 个字段/文件") + notes).joinToString("；"))
    }

    private fun parameter(header: String, name: String): String? =
        Regex("(?:^|;)\\s*${Regex.escape(name)}\\s*=\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|([^;\\s]*))", RegexOption.IGNORE_CASE)
            .find(header)?.let { match ->
                if (match.groups[1] != null) match.groupValues[1].replace(Regex("\\\\(.)"), "$1") else match.groupValues[2]
            }

    private fun base64(bytes: ByteArray) = Base64.getMimeEncoder(76, byteArrayOf(10)).encodeToString(bytes)

    private class LimitedWriter : Writer() {
        private val buffer = StringBuilder()
        override fun write(chars: CharArray, offset: Int, length: Int) {
            require(length <= MAX_FORMATTED_CHARS - buffer.length) { "格式化内容超过 20 Mi 字符" }
            buffer.append(chars, offset, length)
        }
        override fun flush() {}
        override fun close() {}
        override fun toString() = buffer.toString()
    }
}
