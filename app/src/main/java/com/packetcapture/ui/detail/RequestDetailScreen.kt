package com.packetcapture.ui.detail

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.packetcapture.body.*
import com.packetcapture.core.*
import com.packetcapture.export.ExportRequest
import com.packetcapture.ui.components.*
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RequestDetailApp(
    state: RequestDetailUiState,
    back: () -> Unit,
    export: (ExportRequest) -> Unit,
    clearMessage: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); clearMessage() } }
    val exchange = state.detail.exchange
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(title = { Text("请求详情", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
            actions = {
                IconButton(onClick = { exchange?.let { export(ExportRequest("curl", it.id)) } },
                    enabled = exchange != null && exchange.completion != Completion.ACTIVE && exchange.requestBody?.truncated != true) {
                    Icon(Icons.Outlined.FileUpload, "导出请求/响应")
                }
            })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) { DetailScreen(state.detail, export) }
    }
}

private enum class BodyDisplayMode { TEXT, DECODED_BASE64, IMAGE }

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun DetailScreen(detail: DetailState, export: (ExportRequest) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(2) }
    val clipboard = LocalClipboardManager.current
    val exchange = detail.exchange
    if (exchange == null) { Empty(if (detail.loading) "正在加载" else "请求已删除", ""); return }
    var showRawBody by rememberSaveable(exchange.id) { mutableStateOf(false) }
    val selectedBody = if (tab == 1) detail.request else detail.response
    val sourceText = (if (showRawBody) selectedBody?.rawText ?: selectedBody?.text else selectedBody?.text) ?: "正文尚未完成保存"
    var bodyMode by rememberSaveable(exchange.id, tab, sourceText) { mutableStateOf(BodyDisplayMode.TEXT) }
    val imageSelected = bodyMode == BodyDisplayMode.IMAGE && selectedBody?.binary == true
    val base64Selected = bodyMode == BodyDisplayMode.DECODED_BASE64
    val canDecodeBase64 = key(exchange.id, tab, sourceText, selectedBody?.binary) {
        val available by produceState(selectedBody?.binary == true) {
            if (selectedBody != null && !selectedBody.binary && tab in 1..2) {
                value = withContext(Dispatchers.Default) { BodyBase64Decoder.isBase64(sourceText) }
            }
        }
        available
    }
    val base64Result = key(exchange.id, tab, sourceText, base64Selected) {
        val result by produceState<BodyBase64Result?>(null) {
            if (base64Selected) value = withContext(Dispatchers.Default) { BodyBase64Decoder.decode(sourceText) }
        }
        result
    }
    val decodedText = (base64Result as? BodyBase64Result.Success)?.text
    val bodyText = if (base64Selected) decodedText.orEmpty() else sourceText
    val chunks = remember(bodyText) { bodyTextChunks(bodyText) }
    val listState = remember(exchange.id, tab, showRawBody, imageSelected, base64Selected) { LazyListState() }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Badge(exchange.method); Text(runCatching { URI(exchange.url).path }.getOrNull() ?: exchange.url,
                Modifier.weight(1f).padding(start = 12.dp), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Row(Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Badge(exchange.status?.toString() ?: exchange.completion.name); Badge(exchange.protocol)
                Badge(exchange.durationMillis?.let { "$it ms" } ?: "进行中"); Badge(bytes(exchange.responseBody?.observedBytes ?: 0))
            }
        }
        TabRow(tab) { listOf("概览", "请求", "响应", "连接").forEachIndexed { index, label -> Tab(tab == index, onClick = { tab = index }, text = { Text(label) }) } }
        if (detail.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f).testTag("detail-content"), state = listState,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // 完整地址随详情滚动，避免长路径或查询参数撑满固定头部。
            item(key = "request-url") { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("请求地址", fontWeight = FontWeight.Bold)
                SelectionContainer { Muted(exchange.url) }
            } }
            exchange.error?.let { item { Notice(it, true) } }
            when (tab) {
                0 -> item { Panel { KeyValue("请求方法", exchange.method); KeyValue("状态", exchange.completion.name); KeyValue("请求时间", requestTimestamp(exchange.startedAt))
                    KeyValue("响应类型", responseContentType(exchange))
                    KeyValue("应用", exchange.packageName ?: "未知"); KeyValue("HTTP/2 流", exchange.streamId?.toString() ?: "—")
                    KeyValue("请求正文", bodyInfo(exchange.requestBody)); KeyValue("响应正文", bodyInfo(exchange.responseBody)) } }
                1, 2 -> {
                    val request = tab == 1
                    val headers = if (request) exchange.requestHeaders else exchange.responseHeaders
                    val trailers = if (request) exchange.requestTrailers else exchange.responseTrailers
                    val body = if (request) detail.request else detail.response
                    val bodyRef = if (request) exchange.requestBody else exchange.responseBody
                    item { Text(if (request) "请求头" else "响应头", fontWeight = FontWeight.Bold) }
                    item { Panel { if (headers.isEmpty()) Muted("暂无头信息") else headers.forEach { KeyValue(it.name, it.value) } } }
                    if (trailers.isNotEmpty()) item { Panel { Text("尾部字段", fontWeight = FontWeight.Medium); trailers.forEach { KeyValue(it.name, it.value) } } }
                    item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (request) "请求正文" else "响应正文", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        TextButton(onClick = { export(ExportRequest(if (request) "request-body" else "response-body", exchange.id, showRawBody, base64Selected)) },
                            enabled = body != null && bodyRef != null && !detail.loading && (!base64Selected || decodedText != null)) { Text(if (imageSelected) "导出 Base64" else "导出正文") }
                        // 大正文完整显示/导出；剪贴板超出 Binder 安全大小时禁用，不能静默复制前缀。
                        IconButton(onClick = { clipboard.setText(AnnotatedString(bodyText)) },
                            enabled = body != null && bodyRef != null && !detail.loading && (!base64Selected || decodedText != null) && bodyText.length <= 65536) { Icon(Icons.Outlined.ContentCopy, if (imageSelected) "复制 Base64" else "复制正文") }
                    } }
                    if (canDecodeBase64) item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !imageSelected && !base64Selected, onClick = { bodyMode = BodyDisplayMode.TEXT }, label = { Text("Base64") })
                        FilterChip(selected = base64Selected, onClick = { bodyMode = BodyDisplayMode.DECODED_BASE64 }, enabled = !detail.loading,
                            label = { Text("解码 Base64") })
                        if (body?.binary == true) FilterChip(selected = imageSelected, onClick = { bodyMode = BodyDisplayMode.IMAGE }, enabled = !detail.loading,
                            label = { Text("尝试转为图片") })
                    } }
                    if (body?.rawText != null) item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !showRawBody && !base64Selected, onClick = { showRawBody = false; bodyMode = BodyDisplayMode.TEXT }, label = { Text("解析") })
                        FilterChip(selected = showRawBody && !base64Selected, onClick = { showRawBody = true; bodyMode = BodyDisplayMode.TEXT }, label = { Text("原文") })
                    } }
                    if (body?.limited == true) item { Notice("正文不完整：${body.note ?: "仅有部分内容可用"}", true) }
                    if (imageSelected) {
                        item(key = "image-$tab") { BodyImageContent(bodyText) }
                    } else if (base64Selected && decodedText == null) {
                        item { when (val result = base64Result) {
                            is BodyBase64Result.Failure -> Notice(result.message, true)
                            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Muted("正在解码 Base64…")
                            }
                        } }
                    } else {
                        if (body?.limited != true && body != null && bodyRef != null) item { Muted("已显示全部已保存正文 · ${bodyText.length} 字符") }
                        if (base64Selected) item { Muted("已解码 Base64 · UTF-8 文本") }
                        else body?.note?.takeIf { !body.limited }?.let { note -> item { Muted(if (showRawBody && body.rawText != null) "显示解压后的原始文本，保留转义" else note) } }
                        if (bodyText.length > 65536) item { Muted("大正文可滚动查看全部内容，使用“导出正文”保存当前文本") }
                        if (bodyText.isEmpty()) item { Muted(if (body?.limited == true) "没有可显示的正文" else "正文为空") }
                        items(chunks, key = { "body-$tab-${it.start}" }) { chunk ->
                            SelectionContainer { Text(bodyText.substring(chunk.start, chunk.end), Modifier.fillMaxWidth().background(Color(0xFF122936), RoundedCornerShape(10.dp)).padding(14.dp),
                            color = Color(0xFFA6E5ED), fontFamily = FontFamily.Monospace, fontSize = 12.sp) } }
                    }
                }
                3 -> item { Panel { detail.connection?.let {
                    KeyValue("目标", "${it.host ?: it.destinationAddress}:${it.destinationPort}"); KeyValue("原始地址", "${it.sourceAddress}:${it.sourcePort} → ${it.destinationAddress}:${it.destinationPort}")
                    KeyValue("应用 / UID", "${it.packageName ?: "未知"} / ${it.uid ?: "未知"}"); KeyValue("传输协议", it.transport)
                    KeyValue("TLS", it.tlsVersion ?: "—"); KeyValue("解密", if (it.decrypted) "已解密" else "未解密"); KeyValue("备注", it.note ?: "—")
                } ?: Muted("连接信息尚未写入") } }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { export(ExportRequest("curl", exchange.id)) }, Modifier.weight(1f), enabled = exchange.completion != Completion.ACTIVE && exchange.requestBody?.truncated != true) { Text("导出请求/响应") }
            Button(onClick = { export(ExportRequest("har", exchange.sessionId)) }, Modifier.weight(1f)) { Text("导出 HAR") }
        }
    }
}

@Composable private fun BodyImageContent(base64: String) = key(base64) {
    val result by produceState<BodyImageResult?>(null) {
        value = withContext(Dispatchers.Default) { BodyImageDecoder.decode(base64) }
    }
    when (val preview = result) {
        null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Muted("正在尝试解析图片…")
        }
        is BodyImageResult.Failure -> Notice(preview.message, true)
        is BodyImageResult.Success -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Muted("${preview.width} × ${preview.height} · ${preview.mimeType ?: "图片"}")
            if (preview.bitmap.width != preview.width || preview.bitmap.height != preview.height) Muted("大图已缩小预览，原始正文保持不变")
            Muted("显示静态预览；多帧图片仅显示首帧")
            Image(bitmap = preview.bitmap.asImageBitmap(), contentDescription = "正文图片预览", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(320.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(8.dp))
        }
    }
}
