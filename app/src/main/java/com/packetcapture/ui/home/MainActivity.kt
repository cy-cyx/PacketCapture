package com.packetcapture.ui.home

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.packetcapture.PacketCaptureApp
import com.packetcapture.core.InstalledApp
import com.packetcapture.export.DocumentExportLauncher
import com.packetcapture.ui.detail.RequestDetailActivity
import com.packetcapture.ui.theme.PacketCaptureTheme
import kotlinx.coroutines.*

/** 首页宿主负责系统授权和页面导航；请求详情由独立 Activity 持有。 */
class MainActivity : ComponentActivity() {
    private lateinit var model: CaptureViewModel
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) model.start() else model.message("未授予 VPN 权限")
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestVpn() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as PacketCaptureApp).container
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = CaptureViewModel(
                container.controller, container.repository, container.bodies, container.settings, container.certificates,
                { container.ready.await() }, { withContext(Dispatchers.IO) {
                    packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                        .filter { it.activityInfo.packageName != packageName }
                        .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(packageManager).toString()) }
                        .distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
                } }) as T
        })[CaptureViewModel::class.java]
        val documents = DocumentExportLauncher(this, container.documents, model::message)
        enableEdgeToEdge()
        setContent {
            val state = model.state.collectAsStateWithLifecycle().value
            PacketCaptureTheme(state.settings.theme) {
                CaptureApp(state, CaptureActions(
                    start = { if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else requestVpn() },
                    stop = model::stop, filter = model::setFilter, settings = { model.saveSettings(it) },
                    selectSession = model::selectSession,
                    detail = { id -> startActivity(RequestDetailActivity.createIntent(this, id)) }, delete = { model.delete(it) },
                    historySearch = model::searchHistory, certificate = { model.certificate() },
                    export = documents::launch,
                    refreshStorage = { model.refreshStorage() }, clearStorage = model::clearStorage, clearMessage = { model.message(null) },
                ))
            }
        }
    }
    private fun requestVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else model.start()
    }
}
