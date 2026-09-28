package com.packetcapture.ui.home

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.packetcapture.core.*
import com.packetcapture.export.ExportRequest
import com.packetcapture.ui.components.*
import java.net.URI

@Composable internal fun CaptureScreen(state: CaptureUiState, actions: CaptureActions, apps: () -> Unit, settings: () -> Unit, detail: (String) -> Unit) {
    var connections by rememberSaveable { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.capture.phase) { while (state.capture.phase == CapturePhase.CAPTURING) { elapsed = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    val running = state.capture.phase in setOf(CapturePhase.CAPTURING, CapturePhase.STARTING, CapturePhase.STOPPING)
    val currentSession = state.sessions.firstOrNull { it.id == (state.selectedSession ?: state.capture.sessionId) }
    LazyColumn(Modifier.fillMaxSize().testTag("capture-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).background(if (state.capture.phase == CapturePhase.CAPTURING) Color(0xFF19A353) else MaterialTheme.colorScheme.outline, RoundedCornerShape(50)))
                    Text(when (state.capture.phase) { CapturePhase.CAPTURING -> "正在抓包"; CapturePhase.STARTING -> "正在启动"; CapturePhase.STOPPING -> "正在停止"; CapturePhase.FAILED -> "抓包已中断"; else -> "准备就绪" },
                        Modifier.weight(1f).padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
                    if (running) FilledTonalButton(onClick = actions.stop, enabled = state.capture.phase != CapturePhase.STOPPING,
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFFFFE6E8), contentColor = Color(0xFFCD233D))) {
                        Icon(Icons.Outlined.Stop, null, Modifier.size(16.dp)); Text("停止")
                    } else Button(onClick = actions.start, enabled = state.ready && state.settings.capture.packages.isNotEmpty()) { Text("开始抓包") }
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Row(Modifier.fillMaxWidth().clickable(onClick = apps).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Android, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        val names = state.settings.capture.packages.map { pkg -> state.apps.firstOrNull { it.packageName == pkg }?.label ?: pkg }
                        Text(names.joinToString("、").ifEmpty { "选择目标应用" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Muted("已选择 ${names.size} 个应用${if (running) " · 修改下次生效" else ""}")
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                if (state.selectedSession == null) {
                    val saved = state.settings.capture.upstreamProxy
                    val active = if (running) state.capture.upstreamProxy else saved
                    Row(Modifier.fillMaxWidth().testTag("capture-route").clickable(onClick = settings).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AltRoute, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(if (active.enabled) "通过代理转发" else "直接连接", fontWeight = FontWeight.Medium)
                            Muted(if (active.enabled) "SOCKS5 · ${active.host}:${active.port}" else "抓包后直接访问目标服务器")
                            if (running && (saved.enabled != active.enabled || saved.enabled && saved.port != active.port))
                                Muted("下次抓包：" + if (saved.enabled) "SOCKS5 · ${saved.host}:${saved.port}" else "直接连接")
                        }
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "代理设置")
                    }
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    Metric("${currentSession?.requestCount ?: 0}", "请求")
                    Metric(bytes(currentSession?.let { it.uploadedBytes + it.downloadedBytes } ?: 0), "流量")
                    val seconds = currentSession?.let { ((it.endedAt ?: elapsed) - it.startedAt).coerceAtLeast(0) / 1000 } ?: 0
                    Metric("%02d:%02d".format(seconds / 60, seconds % 60), "时长")
                }
            }
        }
        state.capture.error?.let { item { Notice(it, true) } }
        if (state.selectedSession != null) item {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("历史会话 · ${currentSession?.title.orEmpty()}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { actions.selectSession(null) }) { Text("返回实时") } }
        }
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SearchField(state.filter.query, { actions.filter(state.filter.copy(query = it)) }, "搜索域名、路径或状态码") }
            IconButton(onClick = { advanced = true }) { Icon(Icons.Outlined.Tune, "筛选请求") }
        } }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("全部", "GET", "POST", "错误", "连接").forEach { label ->
                    val selected = if (label == "连接") connections else !connections && when (label) {
                        "全部" -> state.filter.method == null && !state.filter.errorsOnly
                        "错误" -> state.filter.errorsOnly
                        else -> state.filter.method == label
                    }
                    FilterChip(selected, onClick = {
                        connections = label == "连接"
                        if (!connections) actions.filter(state.filter.copy(method = label.takeIf { it in setOf("GET", "POST") }, errorsOnly = label == "错误"))
                    }, label = { Text(label) }, shape = RoundedCornerShape(24.dp), border = null,
                        colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary))
                }
            }
        }
        if (connections) {
            if (state.connections.isEmpty()) item { Empty("暂无连接", "开始抓包后，在选中的测试应用中发起网络请求。") }
            items(state.connections.filter { "${it.host} ${it.destinationAddress} ${it.note}".contains(state.filter.query, true) }, key = { it.id }) { c ->
                Panel { Text("${c.transport}  ${c.host ?: c.destinationAddress}:${c.destinationPort}", fontWeight = FontWeight.Medium)
                    Muted(c.packageName ?: "应用归属未知"); Muted(c.note ?: if (c.decrypted) "${c.tlsVersion} · 已解密" else "连接已建立") }
            }
        } else {
            if (state.requests.isEmpty()) item { Empty("暂无请求", "选择应用并开始抓包。若 HTTPS 无法解密，可在“连接”中查看原因。") }
            items(state.requests, key = { it.id }) { item -> RequestRow(item) { detail(item.id) } }
        }
        item { Muted("仅显示最近 200 条匹配记录 · 完整会话可导出 HAR", Modifier.fillMaxWidth().padding(vertical = 8.dp)) }
        if (currentSession != null) item { OutlinedButton(onClick = { actions.export(ExportRequest("har", currentSession.id)) }, Modifier.fillMaxWidth()) { Text("导出当前会话 HAR") } }
    }
    if (advanced) {
        var method by remember { mutableStateOf(state.filter.method.orEmpty()) }
        var status by remember { mutableStateOf(state.filter.status?.toString().orEmpty()) }
        var app by remember { mutableStateOf(state.filter.packageName.orEmpty()) }
        AlertDialog(onDismissRequest = { advanced = false }, title = { Text("筛选请求") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(method, { method = it.uppercase() }, label = { Text("方法，如 GET / PUT，留空为全部") }, singleLine = true)
                OutlinedTextField(status, { status = it.filter(Char::isDigit).take(3) }, label = { Text("HTTP 状态码，留空为全部") }, singleLine = true)
                OutlinedTextField(app, { app = it }, label = { Text("应用包名，留空为全部") }, singleLine = true)
            }
        }, confirmButton = { TextButton(onClick = {
            actions.filter(state.filter.copy(method = method.trim().ifEmpty { null }, status = status.toIntOrNull(), packageName = app.trim().ifEmpty { null }))
            advanced = false
        }) { Text("应用") } }, dismissButton = { TextButton(onClick = { actions.filter(ExchangeFilter()); advanced = false }) { Text("重置") } })
    }
}

@Composable private fun RequestRow(item: HttpExchange, onClick: () -> Unit) {
    val uri = runCatching { URI(item.url) }.getOrNull()
    Column(Modifier.fillMaxWidth().testTag("request-${item.id}").clickable(onClick = onClick).padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Badge(item.method)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(uri?.rawPath?.ifEmpty { "/" } ?: item.url, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
                Muted(uri?.host ?: item.url, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(item.status?.toString() ?: if (item.completion == Completion.ACTIVE) "…" else "失败",
                    color = if ((item.status ?: 0) >= 400 || item.error != null) MaterialTheme.colorScheme.error else Color(0xFF14944B), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Muted(item.durationMillis?.let { "$it ms" } ?: "进行中")
            }
        }
        Column(Modifier.padding(top = 6.dp)) {
            Muted("请求时间：${requestTimestamp(item.startedAt)}")
            Muted("响应类型：${responseContentType(item)}")
        }
    }
    HorizontalDivider(Modifier.padding(top = 6.dp))
}
