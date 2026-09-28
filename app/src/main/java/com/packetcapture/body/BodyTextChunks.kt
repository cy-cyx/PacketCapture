package com.packetcapture.body

internal data class BodyTextChunk(val start: Int, val end: Int)

/** 只保存索引，LazyColumn 按可见范围取文本，避免一次排版数 MiB 的单个 Text。 */
internal fun bodyTextChunks(text: String): List<BodyTextChunk> = buildList {
    var start = 0
    while (start < text.length) {
        var end = minOf(start + 4096, text.length)
        var lines = 0
        for (index in start until end) {
            if (text[index] == '\n' && ++lines == 64) { end = index + 1; break }
        }
        if (end < text.length) {
            // 搜索仅限当前块；长单行正文不能每块都从头扫描，否则退化成 O(n²)。
            var newline = end - 1
            while (newline >= start + 2048 && text[newline] != '\n') newline--
            if (newline >= start + 2048) end = newline + 1
            if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            if (text[end - 1] == '\r' && text[end] == '\n') end--
        }
        add(BodyTextChunk(start, end))
        start = end
    }
}
