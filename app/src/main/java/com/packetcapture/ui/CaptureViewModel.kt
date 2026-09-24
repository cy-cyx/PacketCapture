package com.packetcapture.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.packetcapture.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class DetailState(val exchange: HttpExchange? = null, val connection: ConnectionRecord? = null,
    val request: BodyPreview? = null, val response: BodyPreview? = null, val loading: Boolean = false)
data class CaptureUiState(
    val ready: Boolean = false, val capture: CaptureState = CaptureState(), val settings: AppSettings = AppSettings(),
    val requests: List<HttpExchange> = emptyList(), val connections: List<ConnectionRecord> = emptyList(),
    val sessions: List<CaptureSession> = emptyList(), val filter: ExchangeFilter = ExchangeFilter(),
    val selectedSession: String? = null, val apps: List<InstalledApp> = emptyList(),
    val certificate: CertificateInfo? = null, val usedBytes: Long = 0, val detail: DetailState = DetailState(),
    val message: String? = null,
)
data class ExportRequest(val kind: String, val id: String? = null, val rawBody: Boolean = false, val decodeBase64: Boolean = false)

/** 页面状态协调器。只依赖 core 接口；查询在后台执行，列表仅保留最近 200 条，正文按选中项加载。 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModel(
    private val controller: CaptureController, private val repository: CaptureRepository,
    private val bodies: BodyStore, private val preferences: SettingsRepository,
    private val certificates: CertificateManager, private val exports: ExportService,
    private val awaitReady: suspend () -> Unit,
    private val loadApps: suspend () -> List<InstalledApp>,
) : ViewModel() {
    private val mutable = MutableStateFlow(CaptureUiState())
    val state = mutable.asStateFlow()
    private val selection = MutableStateFlow<String?>(null)
    private val filter = MutableStateFlow(ExchangeFilter())
    private val historyQuery = MutableStateFlow("")
    private var detailJob: Job? = null
    var pendingExport: ExportRequest? = null
    init {
        action {
            awaitReady()
            mutable.update { it.copy(ready = true, apps = loadApps(), usedBytes = bodies.usedBytes()) }
        }
        viewModelScope.launch { controller.state.collect { value -> mutable.update { it.copy(capture = value) } } }
        viewModelScope.launch { preferences.settings.collect { value -> mutable.update { it.copy(settings = value) } } }
        viewModelScope.launch { historyQuery.flatMapLatest(repository::sessions).collect { value -> mutable.update { it.copy(sessions = value) } } }
        viewModelScope.launch {
            combine(controller.state.map { it.sessionId }.distinctUntilChanged(), selection, filter) { current, selected, f -> (selected ?: current) to f }
                .flatMapLatest { (id, f) -> if (id == null) flowOf(emptyList()) else repository.exchanges(id, f) }
                .collect { value -> mutable.update { it.copy(requests = value) } }
        }
        viewModelScope.launch {
            combine(controller.state.map { it.sessionId }.distinctUntilChanged(), selection) { current, selected -> selected ?: current }
                .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.connections(id) }
                .collect { value -> mutable.update { it.copy(connections = value) } }
        }
    }
    private fun action(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { message(e.message ?: "操作失败") }
    }
    fun message(text: String?) { mutable.update { it.copy(message = text) } }
    fun start() { if (state.value.ready) { selectSession(null); controller.start(state.value.settings.capture) } }
    fun stop() = controller.stop()
    fun selectSession(id: String?) { selection.value = id; mutable.update { it.copy(selectedSession = id) } }
    fun setFilter(value: ExchangeFilter) { filter.value = value; mutable.update { it.copy(filter = value) } }
    fun searchHistory(text: String) { historyQuery.value = text }
    fun saveSettings(settings: AppSettings) = action { preferences.update(settings) }
    fun refreshStorage() = action { mutable.update { it.copy(usedBytes = bodies.usedBytes()) } }
    fun certificate() = action { val info = certificates.ensureCertificate(); mutable.update { it.copy(certificate = info) } }
    fun delete(id: String) = action {
        repository.deleteSession(id)
        if (selection.value == id) selectSession(null)
        refreshStorage()
    }
    fun showDetail(id: String?) {
        detailJob?.cancel()
        mutable.update { it.copy(detail = DetailState(loading = id != null)) }
        if (id == null) return
        detailJob = viewModelScope.launch {
            repository.exchange(id).collectLatest { exchange ->
                if (exchange == null) { mutable.update { it.copy(detail = DetailState()) }; return@collectLatest }
                mutable.update { it.copy(detail = it.detail.copy(exchange = exchange, loading = true)) }
                try {
                    val connection = repository.connection(exchange.connectionId)
                    val request = bodies.preview(exchange.requestBody, exchange.requestHeaders)
                    val response = bodies.preview(exchange.responseBody, exchange.responseHeaders)
                    mutable.update { it.copy(detail = DetailState(exchange, connection, request, response)) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message("正文读取失败: ${e.message}"); mutable.update { it.copy(detail = it.detail.copy(loading = false)) } }
            }
        }
    }
    /** 调用方拥有并关闭输出流。cURL 连同原始请求体写入 ZIP，防止复制命令后缺失二进制正文。 */
    suspend fun writeExport(request: ExportRequest, output: OutputStream) = withContext(Dispatchers.IO) {
        when (request.kind) {
            "certificate" -> certificates.exportCertificate(output)
            "har" -> {
                val id = request.id ?: state.value.selectedSession ?: state.value.capture.sessionId ?: error("暂无会话可导出")
                exports.har(repository.allExchanges(id), output)
            }
            "request-body", "response-body" -> {
                val exchange = request.id?.let { repository.exchange(it).first() } ?: error("请求不存在")
                val isRequest = request.kind == "request-body"
                val ref = (if (isRequest) exchange.requestBody else exchange.responseBody) ?: error("正文尚未完成保存")
                val headers = if (isRequest) exchange.requestHeaders else exchange.responseHeaders
                val body = bodies.preview(ref, headers)
                val sourceText = if (request.rawBody) body.rawText ?: body.text else body.text
                val text = if (request.decodeBase64) when (val result = BodyBase64Decoder.decode(sourceText)) {
                    is BodyBase64Result.Success -> result.text
                    is BodyBase64Result.Failure -> error(result.message)
                } else sourceText
                val writer = OutputStreamWriter(output, Charsets.UTF_8)
                if (body.limited) writer.write("【正文不完整】${body.note ?: "仅有部分内容可用"}\n\n")
                writer.write(text)
                writer.flush()
            }
            "curl" -> {
                val exchange = request.id?.let { repository.exchange(it).first() } ?: error("请求不存在")
                val bundle = exports.curl(exchange)
                val zip = ZipOutputStream(output)
                zip.putNextEntry(ZipEntry("request.sh")); zip.write((bundle.command + "\n").toByteArray()); zip.closeEntry()
                if (bundle.body != null) { zip.putNextEntry(ZipEntry(bundle.bodyFileName!!)); zip.write(bundle.body); zip.closeEntry() }
                zip.finish(); zip.flush()
            }
            else -> error("未知导出类型")
        }
    }
}
