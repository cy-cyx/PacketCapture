package com.packetcapture.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.packetcapture.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class CaptureUiState(
    val ready: Boolean = false, val capture: CaptureState = CaptureState(), val settings: AppSettings = AppSettings(),
    val requests: List<HttpExchange> = emptyList(), val connections: List<ConnectionRecord> = emptyList(),
    val sessions: List<CaptureSession> = emptyList(), val filter: ExchangeFilter = ExchangeFilter(),
    val selectedSession: String? = null, val apps: List<InstalledApp> = emptyList(),
    val certificate: CertificateInfo? = null, val usedBytes: Long = 0,
    val clearingStorage: Boolean = false,
    val message: String? = null,
)
/** 首页状态协调器。只依赖 core 接口，负责抓包、列表、历史和设置，不加载请求详情。 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModel(
    private val controller: CaptureController, private val repository: CaptureRepository,
    private val bodies: BodyStore, private val preferences: SettingsRepository,
    private val certificates: CertificateManager,
    private val awaitReady: suspend () -> Unit,
    private val loadApps: suspend () -> List<InstalledApp>,
) : ViewModel() {
    private val mutable = MutableStateFlow(CaptureUiState())
    val state = mutable.asStateFlow()
    private val selection = MutableStateFlow<String?>(null)
    private val filter = MutableStateFlow(ExchangeFilter())
    private val historyQuery = MutableStateFlow("")
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
    fun start() { if (state.value.ready && !state.value.clearingStorage) { selectSession(null); controller.start(state.value.settings.capture) } }
    fun stop() = controller.stop()
    fun selectSession(id: String?) { selection.value = id; mutable.update { it.copy(selectedSession = id) } }
    fun setFilter(value: ExchangeFilter) { filter.value = value; mutable.update { it.copy(filter = value) } }
    fun searchHistory(text: String) { historyQuery.value = text }
    fun saveSettings(settings: AppSettings) = action { preferences.update(settings) }
    fun refreshStorage() = action { mutable.update { it.copy(usedBytes = bodies.usedBytes()) } }
    fun certificate() = action { val info = certificates.ensureCertificate(); mutable.update { it.copy(certificate = info) } }
    fun clearStorage() {
        if (!state.value.ready || state.value.clearingStorage) return
        if (controller.state.value.phase !in setOf(CapturePhase.IDLE, CapturePhase.FAILED)) {
            message("请先停止抓包后再清空存储")
            return
        }
        mutable.update { it.copy(clearingStorage = true) }
        action {
            try {
                controller.clearStorage()
                selectSession(null)
                message("已清空所有抓包存储")
            } finally {
                refreshStorage()
                mutable.update { it.copy(clearingStorage = false) }
            }
        }
    }
    fun delete(id: String) = action {
        repository.deleteSession(id)
        if (selection.value == id) selectSession(null)
        refreshStorage()
    }
}
