package com.packetcapture.ui.detail

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.packetcapture.PacketCaptureApp
import com.packetcapture.export.DocumentExportLauncher
import com.packetcapture.ui.theme.PacketCaptureTheme

/** 只接收请求 ID，独立加载详情和处理导出，不依赖首页的状态或生命周期。 */
class RequestDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)?.takeIf { it.isNotBlank() }
        if (requestId == null) {
            finish()
            return
        }
        val container = (application as PacketCaptureApp).container
        val model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = RequestDetailViewModel(
                requestId, container.repository, container.bodies, container.settings,
                { container.ready.await() },
            ) as T
        })[RequestDetailViewModel::class.java]
        val documents = DocumentExportLauncher(this, container.documents, model::message)
        enableEdgeToEdge()
        setContent {
            val state = model.state.collectAsStateWithLifecycle().value
            PacketCaptureTheme(state.theme) {
                RequestDetailApp(state,
                    back = { onBackPressedDispatcher.onBackPressed() },
                    export = documents::launch,
                    clearMessage = { model.message(null) },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_REQUEST_ID = "com.packetcapture.extra.REQUEST_ID"

        fun createIntent(context: Context, requestId: String): Intent {
            require(requestId.isNotBlank()) { "请求 ID 不能为空" }
            return Intent(context, RequestDetailActivity::class.java).putExtra(EXTRA_REQUEST_ID, requestId)
        }
    }
}
