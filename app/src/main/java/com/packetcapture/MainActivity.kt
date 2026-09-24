package com.packetcapture

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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.packetcapture.core.InstalledApp
import com.packetcapture.ui.*
import com.packetcapture.ui.theme.PacketCaptureTheme
import kotlinx.coroutines.*

/** Activity 仅承担系统授权、文档选择器和依赖装配；抓包状态由前台服务持有。 */
class MainActivity : ComponentActivity() {
    private lateinit var model: CaptureViewModel
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) model.start() else model.message("未授予 VPN 权限")
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestVpn() }
    private val document = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val request = model.pendingExport
        model.pendingExport = null
        if (uri != null && request != null) lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val stream = contentResolver.openOutputStream(uri, "wt") ?: error("无法打开目标文件")
                    stream.use { model.writeExport(request, it) }
                }
                model.message(if (request.kind.endsWith("-body")) "正文已导出" else "导出完成（敏感请求头默认隐藏）")
            } catch (e: Exception) { model.message("导出失败: ${e.message}") }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as PacketCaptureApp).container
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = CaptureViewModel(
                container.controller, container.repository, container.bodies, container.settings, container.certificates, container.exports,
                { container.ready.await() }, { withContext(Dispatchers.IO) {
                    packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                        .filter { it.activityInfo.packageName != packageName }
                        .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(packageManager).toString()) }
                        .distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
                } }) as T
        })[CaptureViewModel::class.java]
        enableEdgeToEdge()
        setContent {
            val state = model.state.collectAsStateWithLifecycle().value
            PacketCaptureTheme(state.settings.theme) {
                CaptureApp(state, CaptureActions(
                    start = { if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else requestVpn() },
                    stop = model::stop, filter = model::setFilter, settings = { model.saveSettings(it) },
                    selectSession = model::selectSession, detail = model::showDetail, delete = { model.delete(it) },
                    historySearch = model::searchHistory, certificate = { model.certificate() },
                    export = { request ->
                        model.pendingExport = request
                        document.launch(when (request.kind) {
                            "certificate" -> "packet-capture-ca.cer"
                            "curl" -> "request-curl.zip"
                            "request-body" -> "request-body.txt"
                            "response-body" -> "response-body.txt"
                            else -> "capture.har"
                        })
                    }, refreshStorage = { model.refreshStorage() }, clearMessage = { model.message(null) },
                ))
            }
        }
    }
    private fun requestVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else model.start()
    }
}
