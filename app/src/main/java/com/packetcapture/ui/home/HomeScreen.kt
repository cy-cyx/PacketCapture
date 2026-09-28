package com.packetcapture.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.packetcapture.core.*
import com.packetcapture.export.ExportRequest
import com.packetcapture.ui.history.HistoryScreen
import com.packetcapture.ui.settings.AppPicker
import com.packetcapture.ui.settings.BypassDomainsDialog
import com.packetcapture.ui.settings.CertificateGuideDialog
import com.packetcapture.ui.settings.SettingsScreen

/** 首页操作集合；具体功能页面只接收自身所需的数据和回调。 */
data class CaptureActions(
    val start: () -> Unit, val stop: () -> Unit, val filter: (ExchangeFilter) -> Unit,
    val settings: (AppSettings) -> Unit, val selectSession: (String?) -> Unit,
    val detail: (String) -> Unit, val delete: (String) -> Unit, val historySearch: (String) -> Unit,
    val certificate: () -> Unit, val export: (ExportRequest) -> Unit, val refreshStorage: () -> Unit,
    val clearMessage: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CaptureApp(state: CaptureUiState, actions: CaptureActions) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var appPicker by remember { mutableStateOf(false) }
    var bypass by remember { mutableStateOf(false) }
    var guide by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); actions.clearMessage() } }
    LaunchedEffect(page) { if (page == 2) { actions.refreshStorage(); actions.certificate() } }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(title = { Text(listOf("抓包", "历史记录", "设置")[page], fontWeight = FontWeight.Bold) },
            actions = {
                if (page == 0) IconButton(onClick = { page = 2 }) { Icon(Icons.Outlined.Tune, "抓包设置") }
            })
    }, bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            listOf("抓包" to Icons.AutoMirrored.Outlined.ShowChart, "历史" to Icons.Outlined.History, "设置" to Icons.Outlined.Settings).forEachIndexed { index, item ->
                NavigationBarItem(selected = page == index, onClick = { page = index }, icon = { Icon(item.second, item.first) }, label = { Text(item.first) })
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (page) {
                0 -> CaptureScreen(state, actions, { appPicker = true }, { page = 2 }, actions.detail)
                1 -> HistoryScreen(state.sessions, actions.historySearch,
                    { id -> actions.selectSession(id); page = 0 }, actions.delete,
                    { id -> actions.export(ExportRequest("har", id)) })
                2 -> SettingsScreen(state.settings, state.certificate, state.usedBytes, actions.settings,
                    { actions.certificate(); actions.export(ExportRequest("certificate")) },
                    { appPicker = true }, { bypass = true }, { guide = true })
            }
            if (!state.ready) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    if (appPicker) AppPicker(state.apps, state.settings.capture.packages, onDismiss = { appPicker = false }) { packages ->
        actions.settings(state.settings.copy(capture = state.settings.capture.copy(packages = packages)))
        appPicker = false
    }
    if (bypass) BypassDomainsDialog(state.settings.capture.bypassDomains, onDismiss = { bypass = false }) { domains ->
        actions.settings(state.settings.copy(capture = state.settings.capture.copy(bypassDomains = domains)))
        bypass = false
    }
    if (guide) CertificateGuideDialog(onDismiss = { guide = false })
}
