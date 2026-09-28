package com.packetcapture.ui.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.packetcapture.core.*
import com.packetcapture.ui.components.*

@Composable internal fun BypassDomainsDialog(domains: Set<String>, onDismiss: () -> Unit, save: (Set<String>) -> Unit) {
    var text by remember { mutableStateOf(domains.joinToString("\n")) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("HTTPS 透传域名") },
        text = { Column {
            Text("每行一个域名，支持 *.example.com。保存后在下一次启动生效，已有请求不会重放。")
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().height(160.dp), placeholder = { Text("api.example.com") })
        } },
        confirmButton = { TextButton(onClick = {
            save(text.lines().map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet())
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable internal fun CertificateGuideDialog(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("测试应用证书配置") }, text = {
        SelectionContainer { Text("1. 生成并导出此设备的公开 CA 证书。\n\n2. 在自有测试应用的 debug 构建中配置 network_security_config，信任导出的 CA；也可使用独立测试客户端的“导入抓包 CA”。\n\n3. 选择应用，开启 HTTPS 解密后开始抓包。\n\n证书固定、未信任 CA、没有 SNI 或 ECH 的连接可能无法解密。HTTP/3 与其他 UDP 流量只转发。上游证书仍正常校验。") }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } })
}

@Composable internal fun AppPicker(apps: List<InstalledApp>, selected: Set<String>, onDismiss: () -> Unit, save: (Set<String>) -> Unit) {
    var chosen by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择测试应用") }, text = {
        Column { SearchField(query, { query = it }, "应用名称或包名")
            LazyColumn(Modifier.heightIn(max = 360.dp)) { items(apps.filter { (it.label + it.packageName).contains(query, true) }, key = { it.packageName }) { app ->
                Row(Modifier.fillMaxWidth().clickable { chosen = if (app.packageName in chosen) chosen - app.packageName else chosen + app.packageName }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(app.packageName in chosen, null)
                    Column(Modifier.padding(start = 8.dp)) { Text(app.label); Muted(app.packageName) }
                }
            } }
            Muted("仅所选应用经过 VPN；未选择的应用直接联网。")
        }
    }, confirmButton = { TextButton(onClick = { save(chosen) }) { Text("确定（${chosen.size}）") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
