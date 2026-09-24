package com.packetcapture.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.packetcapture.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 纯 UI 操作集合；页面不直接访问 Service、数据库、文件系统或 Android 权限。 */
data class CaptureActions(
    val start: () -> Unit, val stop: () -> Unit, val filter: (ExchangeFilter) -> Unit,
    val settings: (AppSettings) -> Unit, val selectSession: (String?) -> Unit,
    val detail: (String?) -> Unit, val delete: (String) -> Unit, val historySearch: (String) -> Unit,
    val certificate: () -> Unit, val export: (ExportRequest) -> Unit, val refreshStorage: () -> Unit,
    val clearMessage: () -> Unit,
)
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CaptureApp(state: CaptureUiState, actions: CaptureActions) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var appPicker by remember { mutableStateOf(false) }
    var bypass by remember { mutableStateOf(false) }
    var guide by remember { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { detailId?.let(actions.detail) }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); actions.clearMessage() } }
    BackHandler(detailId != null) { detailId = null; actions.detail(null) }
    LaunchedEffect(page) { if (page == 2) { actions.refreshStorage(); actions.certificate() } }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(title = { Text(if (detailId != null) "请求详情" else listOf("抓包", "历史记录", "设置")[page], fontWeight = FontWeight.Bold) },
            navigationIcon = { if (detailId != null) IconButton(onClick = { detailId = null; actions.detail(null) }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
            actions = {
                if (detailId != null) IconButton(onClick = { actions.export(ExportRequest("curl", detailId)) }) { Icon(Icons.Outlined.FileUpload, "导出 cURL") }
                else if (page == 0) IconButton(onClick = { page = 2 }) { Icon(Icons.Outlined.Tune, "抓包设置") }
            })
    }, bottomBar = {
        if (detailId == null) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            listOf("抓包" to Icons.Outlined.ShowChart, "历史" to Icons.Outlined.History, "设置" to Icons.Outlined.Settings).forEachIndexed { index, item ->
                NavigationBarItem(selected = page == index, onClick = { page = index }, icon = { Icon(item.second, item.first) }, label = { Text(item.first) })
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (detailId != null) DetailScreen(state.detail, actions.export)
            else when (page) {
                0 -> CaptureScreen(state, actions, { appPicker = true }, { page = 2 }, { id -> detailId = id; actions.detail(id) })
                1 -> HistoryScreen(state.sessions, actions.historySearch, { id -> actions.selectSession(id); page = 0 },
                    { id -> deleteId = id }, { id -> actions.export(ExportRequest("har", id)) })
                2 -> SettingsScreen(state, actions, { appPicker = true }, { bypass = true }, { guide = true })
            }
            if (!state.ready) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    if (appPicker) AppPicker(state.apps, state.settings.capture.packages, onDismiss = { appPicker = false }) { packages ->
        actions.settings(state.settings.copy(capture = state.settings.capture.copy(packages = packages))); appPicker = false
    }
    if (bypass) {
        var text by remember { mutableStateOf(state.settings.capture.bypassDomains.joinToString("\n")) }
        AlertDialog(onDismissRequest = { bypass = false }, title = { Text("HTTPS 透传域名") },
            text = { Column { Text("每行一个域名，支持 *.example.com。保存后在下一次启动生效，已有请求不会重放。")
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().height(160.dp), placeholder = { Text("api.example.com") }) } },
            confirmButton = { TextButton(onClick = {
                actions.settings(state.settings.copy(capture = state.settings.capture.copy(bypassDomains = text.lines().map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet())))
                bypass = false
            }) { Text("保存") } }, dismissButton = { TextButton(onClick = { bypass = false }) { Text("取消") } })
    }
    if (guide) AlertDialog(onDismissRequest = { guide = false }, title = { Text("测试应用证书配置") }, text = {
        SelectionContainer { Text("1. 生成并导出此设备的公开 CA 证书。\n\n2. 在自有测试应用的 debug 构建中配置 network_security_config，信任导出的 CA；也可使用独立测试客户端的“导入抓包 CA”。\n\n3. 选择应用，开启 HTTPS 解密后开始抓包。\n\n证书固定、未信任 CA、没有 SNI 或 ECH 的连接可能无法解密。HTTP/3 与其他 UDP 流量只转发。上游证书仍正常校验。") }
    }, confirmButton = { TextButton(onClick = { guide = false }) { Text("知道了") } })
    deleteId?.let { id -> AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("删除这次抓包？") },
        text = { Text("会同时删除该会话的请求、连接和正文文件。") },
        confirmButton = { TextButton(onClick = { actions.delete(id); deleteId = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text("取消") } }) }
}

@Composable private fun CaptureScreen(state: CaptureUiState, actions: CaptureActions, apps: () -> Unit, settings: () -> Unit, detail: (String) -> Unit) {
    var connections by rememberSaveable { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.capture.phase) { while (state.capture.phase == CapturePhase.CAPTURING) { elapsed = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    val running = state.capture.phase in setOf(CapturePhase.CAPTURING, CapturePhase.STARTING, CapturePhase.STOPPING)
    val currentSession = state.sessions.firstOrNull { it.id == (state.selectedSession ?: state.capture.sessionId) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        state.capture.recordingWarning?.let { item { Notice(it) } }
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
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
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
    HorizontalDivider(Modifier.padding(top = 6.dp))
}

@Composable private fun HistoryScreen(sessions: List<CaptureSession>, search: (String) -> Unit, select: (String) -> Unit, delete: (String) -> Unit, export: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SearchField(query, { query = it; search(it) }, "搜索会话") }
        item { Muted("最近 ${sessions.size} 次抓包") }
        if (sessions.isEmpty()) item { Empty("还没有历史记录", "完成的抓包会保存在此设备，重启后仍可查看。") }
        items(sessions, key = { it.id }) { session ->
            Panel {
                Row(Modifier.fillMaxWidth().clickable { select(session.id) }, verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.Description, null, Modifier.size(30.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(session.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        Muted(date(session.startedAt)); Spacer(Modifier.height(6.dp))
                        Muted("${session.requestCount} 个请求 · ${bytes(session.uploadedBytes + session.downloadedBytes)}")
                    }
                    Badge(when (session.completion) { Completion.ACTIVE -> "进行中"; Completion.COMPLETE -> "已结束"; else -> "已中断" })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { export(session.id) }) { Text("导出") }
                    TextButton(onClick = { delete(session.id) }, enabled = session.completion != Completion.ACTIVE) { Text("删除") }
                }
            }
        }
        item { Muted("记录仅保存在此设备", Modifier.padding(vertical = 16.dp)) }
    }
}

@Composable private fun SettingsScreen(state: CaptureUiState, actions: CaptureActions, apps: () -> Unit, bypass: () -> Unit, guide: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WorkspacePremium, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.padding(start = 12.dp)) { Text("HTTPS 证书", fontWeight = FontWeight.Bold); Muted(if (state.certificate == null) "生成后配置测试应用信任" else "已生成 · 仅导出公开证书") }
            }
            state.certificate?.let { Spacer(Modifier.height(8.dp)); Muted("SHA-256 ${it.fingerprint}"); Muted("有效期至 ${date(it.expiresAt)}") }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { actions.certificate(); actions.export(ExportRequest("certificate")) }, Modifier.weight(1f)) { Text("导出证书") }
                OutlinedButton(onClick = guide, Modifier.weight(1f)) { Text("配置指引") }
            }
        } }
        item { Muted("抓包"); Spacer(Modifier.height(8.dp)); Panel {
            SettingRow(Icons.Outlined.Apps, "目标应用", "已选 ${state.settings.capture.packages.size} 个", apps)
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, null); Text("HTTPS 解密", Modifier.weight(1f).padding(start = 12.dp))
                Switch(state.settings.capture.decryptHttps, { actions.settings(state.settings.copy(capture = state.settings.capture.copy(decryptHttps = it))) })
            }
            HorizontalDivider(); SettingRow(Icons.Outlined.Language, "透传域名", "${state.settings.capture.bypassDomains.size} 条", bypass)
            Muted("抓包设置在下一次启动生效")
        } }
        item { ProxySettings(state.settings.capture.upstreamProxy) { proxy ->
            actions.settings(state.settings.copy(capture = state.settings.capture.copy(upstreamProxy = proxy)))
        } }
        item { Muted("存储"); Spacer(Modifier.height(8.dp)); Panel {
            SettingRow(Icons.Outlined.Description, "每份正文上限", "5 MiB")
            HorizontalDivider(); SettingRow(Icons.Outlined.Storage, "总存储额度", "500 MiB")
            HorizontalDivider(); SettingRow(Icons.Outlined.PieChart, "已用空间", bytes(state.usedBytes))
            LinearProgressIndicator(progress = { (state.usedBytes.toFloat() / DEFAULT_STORAGE_LIMIT).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp)); Muted("达到限额后保留已保存的正文前缀，网络转发继续。")
        } }
        item { Muted("通用"); Spacer(Modifier.height(8.dp)); Panel {
            SettingRow(Icons.Outlined.Palette, "外观", when (state.settings.theme) { ThemeMode.SYSTEM -> "跟随系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" }) {
                val next = ThemeMode.entries[(state.settings.theme.ordinal + 1) % ThemeMode.entries.size]
                actions.settings(state.settings.copy(theme = next))
            }
            HorizontalDivider(); SettingRow(Icons.Outlined.Info, "本地抓包 Demo", "v1.0.0")
            Muted("所有运行组件位于手机内 · 导出默认隐藏敏感请求头")
        } }
    }
}

@Composable internal fun ProxySettings(proxy: UpstreamProxy, save: (UpstreamProxy) -> Unit) {
    var portText by rememberSaveable(proxy.port) { mutableStateOf(proxy.port.toString()) }
    val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
    val invalid = proxy.enabled && port == null
    Muted("上游代理")
    Spacer(Modifier.height(8.dp))
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AltRoute, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("通过代理转发", fontWeight = FontWeight.SemiBold)
                Muted(if (proxy.enabled) "抓包后交给本机代理" else "关闭时直接连接")
            }
            Switch(proxy.enabled, { save(proxy.copy(enabled = it)) }, Modifier.testTag("proxy-enabled").semantics { contentDescription = "通过代理转发" })
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SettingRow(Icons.Outlined.Hub, "代理协议", "SOCKS5")
        SettingRow(Icons.Outlined.PhoneAndroid, "本机地址", proxy.host)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(portText, { portText = it }, Modifier.weight(1f).testTag("proxy-port"), enabled = proxy.enabled,
                label = { Text("代理端口") }, singleLine = true, isError = invalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Button(onClick = { port?.let { save(proxy.copy(port = it)) } },
                enabled = proxy.enabled && port != null && port != proxy.port, modifier = Modifier.testTag("proxy-save")) { Text("保存") }
        }
        Spacer(Modifier.height(6.dp))
        if (invalid) Text("请输入 1–65535 之间的端口", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        else Muted(if (port != proxy.port) "端口修改后需保存" else "已保存端口 ${proxy.port} · 关闭代理会保留端口")
        Spacer(Modifier.height(12.dp))
        Muted("设置在下一次开始抓包时生效")
        Spacer(Modifier.height(8.dp))
        Muted("请先将 v2rayNG 切换为“仅代理”并启动，端口需保持一致。目标应用中不要选择 v2rayNG。代理不可用时会报错，不会自动直连。")
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
                0 -> item { Panel { KeyValue("请求方法", exchange.method); KeyValue("状态", exchange.completion.name); KeyValue("开始时间", date(exchange.startedAt))
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
            OutlinedButton(onClick = { export(ExportRequest("curl", exchange.id)) }, Modifier.weight(1f), enabled = exchange.completion != Completion.ACTIVE && exchange.requestBody?.truncated != true) { Text("导出 cURL") }
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

@Composable private fun AppPicker(apps: List<InstalledApp>, selected: Set<String>, onDismiss: () -> Unit, save: (Set<String>) -> Unit) {
    var chosen by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择测试应用") }, text = {
        Column { SearchField(query, { query = it }, "应用名称或包名")
            LazyColumn(Modifier.heightIn(max = 360.dp)) { items(apps.filter { (it.label + it.packageName).contains(query, true) }, key = { it.packageName }) { app ->
                Row(Modifier.fillMaxWidth().clickable { chosen = if (app.packageName in chosen) chosen - app.packageName else chosen + app.packageName }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(app.packageName in chosen, null)
                    Column(Modifier.padding(start = 8.dp)) { Text(app.label); Muted(app.packageName) }
                }
            } }
            Muted("仅所选应用经过 VPN；未选择的应用直接联网。")
        }
    }, confirmButton = { TextButton(onClick = { save(chosen) }) { Text("确定（${chosen.size}）") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(14.dp), content = content)
    }
}
@Composable private fun Muted(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(text, modifier, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}
@Composable private fun Metric(value: String, label: String) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value, fontWeight = FontWeight.Bold); Muted(label) } }
@Composable private fun Badge(text: String) { Text(text, Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
    color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium) }
@Composable private fun SearchField(text: String, change: (String) -> Unit, placeholder: String) {
    OutlinedTextField(text, change, Modifier.fillMaxWidth(), placeholder = { Text(placeholder, fontSize = 13.sp) }, singleLine = true,
        leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = RoundedCornerShape(16.dp))
}
@Composable private fun Empty(title: String, message: String) { Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Icon(Icons.Outlined.TravelExplore, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(12.dp))
    Text(title, fontWeight = FontWeight.Medium); Muted(message, Modifier.padding(16.dp))
} }
@Composable private fun Notice(message: String, error: Boolean = false) { Text(message, Modifier.fillMaxWidth().background(if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(12.dp), fontSize = 12.sp) }
@Composable private fun SettingRow(icon: ImageVector, title: String, value: String, click: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (click == null) Modifier else Modifier.clickable(onClick = click)).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp)); Text(title, Modifier.weight(1f).padding(start = 12.dp), fontSize = 14.sp); Muted(value)
        if (click != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp))
    }
}
@Composable private fun KeyValue(key: String, value: String) { Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
    Muted(key, Modifier.weight(0.4f)); SelectionContainer(Modifier.weight(0.6f)) { Text(value, fontSize = 12.sp) }
} }
private fun bodyInfo(body: BodyRef?) = body?.let { "${bytes(it.savedBytes)} / ${bytes(it.observedBytes)}${if (it.truncated) " · 截断：${it.reason}" else ""}" } ?: "等待保存"
private fun date(time: Long) = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))
private fun bytes(value: Long): String = when { value < 1024 -> "$value B"; value < 1024 * 1024 -> "%.1f KB".format(value / 1024.0); else -> "%.1f MiB".format(value / 1048576.0) }
