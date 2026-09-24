package com.packetcapture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.packetcapture.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

/** 仅 debug 的 adb 验证入口；DUMP 权限将调用限制在 shell/系统，不替代系统 VPN 授权。 */
class DebugControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = (context.applicationContext as PacketCaptureApp).container
                app.ready.await()
                val output = when (intent.getStringExtra("command")) {
                    "start" -> {
                        val config = CaptureConfig(packages = setOf("com.packetcapture.testclient"), decryptHttps = intent.getBooleanExtra("decrypt", true),
                            bypassDomains = intent.getStringExtra("bypass")?.split(',')?.toSet() ?: emptySet(),
                            upstreamProxy = UpstreamProxy(intent.getBooleanExtra("proxy", false), intent.getIntExtra("port", 10808)))
                        app.settings.update(app.settings.settings.first().copy(capture = config))
                        app.controller.start(config); "start requested"
                    }
                    "stop" -> { app.controller.stop(); "stop requested" }
                    "settings" -> app.settings.settings.first().toString()
                    "configure" -> {
                        val old = app.settings.settings.first()
                        val config = old.capture.copy(
                            packages = intent.getStringExtra("packages")?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: old.capture.packages,
                            decryptHttps = intent.getBooleanExtra("decrypt", old.capture.decryptHttps),
                            upstreamProxy = UpstreamProxy(intent.getBooleanExtra("proxy", old.capture.upstreamProxy.enabled), intent.getIntExtra("port", old.capture.upstreamProxy.port)))
                        app.settings.update(old.copy(capture = config)); config.toString()
                    }
                    "start-saved" -> { app.controller.start(app.settings.settings.first().capture); "start requested" }
                    "certificate" -> { File(context.filesDir, "test-ca.cer").outputStream().use { app.certificates.exportCertificate(it) }; "test-ca.cer" }
                    "har" -> {
                        val session = app.repository.sessions().first().firstOrNull() ?: error("No session")
                        File(context.filesDir, "test.har").outputStream().use { app.exports.har(app.repository.allExchanges(session.id), it) }; "test.har"
                    }
                    "threads" -> Thread.getAllStackTraces().entries.joinToString("\n") { (thread, stack) -> "${thread.name} ${thread.state}\n${stack.joinToString("\n")}" }.also {
                        File(context.filesDir, "threads.txt").writeText(it)
                    }.let { "threads.txt" }
                    else -> app.controller.state.value.toString()
                }
                pending.setResultData(output); Log.i("CaptureControl", output)
            } catch (e: Exception) { pending.setResultData("ERROR ${e.message}"); Log.e("CaptureControl", "debug command", e) }
            finally { pending.finish() }
        }
    }
}
