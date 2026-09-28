package com.packetcapture.ui.history

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.packetcapture.core.*
import com.packetcapture.ui.components.*

@Composable internal fun HistoryScreen(sessions: List<CaptureSession>, search: (String) -> Unit, select: (String) -> Unit, delete: (String) -> Unit, export: (String) -> Unit) {
    var deleteId by remember { mutableStateOf<String?>(null) }
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
                    TextButton(onClick = { deleteId = session.id }, enabled = session.completion != Completion.ACTIVE) { Text("删除") }
                }
            }
        }
        item { Muted("记录仅保存在此设备", Modifier.padding(vertical = 16.dp)) }
    }
    deleteId?.let { id -> AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("删除这次抓包？") },
        text = { Text("会同时删除该会话的请求、连接和正文文件。") },
        confirmButton = { TextButton(onClick = { delete(id); deleteId = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text("取消") } }) }

}
