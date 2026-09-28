package com.packetcapture.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.packetcapture.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class DetailState(val exchange: HttpExchange? = null, val connection: ConnectionRecord? = null,
    val request: BodyPreview? = null, val response: BodyPreview? = null, val loading: Boolean = false)

data class RequestDetailUiState(
    val detail: DetailState = DetailState(loading = true),
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val message: String? = null,
)

/** 仅观察当前请求；正文读取随详情页销毁取消，不订阅首页列表或抓包控制器。 */
class RequestDetailViewModel(
    requestId: String,
    private val repository: CaptureRepository,
    private val bodies: BodyStore,
    preferences: SettingsRepository,
    awaitReady: suspend () -> Unit,
) : ViewModel() {
    private val mutable = MutableStateFlow(RequestDetailUiState())
    val state = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.settings.map { it.theme }.distinctUntilChanged().collect { theme ->
                mutable.update { it.copy(theme = theme) }
            }
        }
        viewModelScope.launch {
            try {
                awaitReady()
                repository.exchange(requestId).collectLatest { exchange ->
                    if (exchange == null) {
                        mutable.update { it.copy(detail = DetailState()) }
                        return@collectLatest
                    }
                    mutable.update { it.copy(detail = it.detail.copy(exchange = exchange, loading = true)) }
                    try {
                        val connection = repository.connection(exchange.connectionId)
                        val request = bodies.preview(exchange.requestBody, exchange.requestHeaders)
                        val response = bodies.preview(exchange.responseBody, exchange.responseHeaders)
                        mutable.update { it.copy(detail = DetailState(exchange, connection, request, response)) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        mutable.update { it.copy(detail = DetailState(exchange = exchange), message = "正文读取失败: ${e.message}") }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(detail = DetailState(), message = "详情加载失败: ${e.message}") }
            }
        }
    }

    fun message(text: String?) { mutable.update { it.copy(message = text) } }
}
