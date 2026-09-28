package com.packetcapture.export

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 每个 Activity 各自注册文档选择器，并保存待导出参数以支持系统重建。 */
class DocumentExportLauncher(
    private val owner: ComponentActivity,
    private val exporter: DocumentExporter,
    private val message: (String) -> Unit,
) {
    private var preparingExport = false
    private var pendingExport = owner.savedStateRegistry.consumeRestoredStateForKey(STATE_KEY)?.let { state ->
        state.getString("kind")?.let { kind ->
            ExportRequest(kind, state.getString("id"), state.getBoolean("rawBody"), state.getBoolean("decodeBase64"))
        }
    }
    private val document = owner.registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val request = pendingExport
        pendingExport = null
        if (uri != null && request != null) owner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val stream = owner.contentResolver.openOutputStream(uri, "wt") ?: error("无法打开目标文件")
                    stream.use { exporter.write(request, it) }
                }
                message(if (request.kind.endsWith("-body")) "正文已导出" else "导出完成")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("导出失败: ${e.message}")
            }
        }
    }

    init {
        owner.savedStateRegistry.registerSavedStateProvider(STATE_KEY) {
            Bundle().apply {
                pendingExport?.let { request ->
                    putString("kind", request.kind)
                    putString("id", request.id)
                    putBoolean("rawBody", request.rawBody)
                    putBoolean("decodeBase64", request.decodeBase64)
                }
            }
        }
    }

    fun launch(request: ExportRequest) {
        if (pendingExport != null || preparingExport) return
        preparingExport = true
        owner.lifecycleScope.launch {
            try {
                val fileName = exporter.fileName(request) { packageName ->
                    runCatching {
                        val manager = owner.packageManager
                        manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
                    }.getOrDefault(packageName)
                }
                pendingExport = request
                document.launch(fileName)
            } catch (e: CancellationException) {
                pendingExport = null
                throw e
            } catch (e: Exception) {
                pendingExport = null
                message("导出失败: ${e.message}")
            } finally {
                preparingExport = false
            }
        }
    }

    private companion object {
        const val STATE_KEY = "document-export"
    }
}
