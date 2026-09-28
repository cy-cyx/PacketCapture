package com.packetcapture.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.packetcapture.core.*
import com.packetcapture.ui.components.*

@Composable internal fun SettingsScreen(
    settings: AppSettings,
    certificate: CertificateInfo?,
    usedBytes: Long,
    save: (AppSettings) -> Unit,
    exportCertificate: () -> Unit,
    apps: () -> Unit,
    bypass: () -> Unit,
    guide: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WorkspacePremium, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.padding(start = 12.dp)) { Text("HTTPS 证书", fontWeight = FontWeight.Bold); Muted(if (certificate == null) "生成后配置测试应用信任" else "已生成 · 仅导出公开证书") }
            }
            certificate?.let { Spacer(Modifier.height(8.dp)); Muted("SHA-256 ${it.fingerprint}"); Muted("有效期至 ${date(it.expiresAt)}") }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = exportCertificate, Modifier.weight(1f)) { Text("导出证书") }
                OutlinedButton(onClick = guide, Modifier.weight(1f)) { Text("配置指引") }
            }
        } }
        item { Muted("抓包"); Spacer(Modifier.height(8.dp)); Panel {
            SettingRow(Icons.Outlined.Apps, "目标应用", "已选 ${settings.capture.packages.size} 个", apps)
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, null); Text("HTTPS 解密", Modifier.weight(1f).padding(start = 12.dp))
                Switch(settings.capture.decryptHttps, { save(settings.copy(capture = settings.capture.copy(decryptHttps = it))) })
            }
            HorizontalDivider(); SettingRow(Icons.Outlined.Language, "透传域名", "${settings.capture.bypassDomains.size} 条", bypass)
            Muted("抓包设置在下一次启动生效")
        } }
        item { ProxySettings(settings.capture.upstreamProxy) { proxy ->
            save(settings.copy(capture = settings.capture.copy(upstreamProxy = proxy)))
        } }
        item { Muted("存储"); Spacer(Modifier.height(8.dp)); Panel {
            SettingRow(Icons.Outlined.Description, "每份正文上限", "5 MiB")
            HorizontalDivider(); SettingRow(Icons.Outlined.Storage, "总存储额度", "500 MiB")
            HorizontalDivider(); SettingRow(Icons.Outlined.PieChart, "已用空间", bytes(usedBytes))
            LinearProgressIndicator(progress = { (usedBytes.toFloat() / DEFAULT_STORAGE_LIMIT).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp)); Muted("达到限额后保留已保存的正文前缀，网络转发继续。")
        } }
        item { Muted("通用"); Spacer(Modifier.height(8.dp)); Panel {
            SettingRow(Icons.Outlined.Palette, "外观", when (settings.theme) { ThemeMode.SYSTEM -> "跟随系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" }) {
                val next = ThemeMode.entries[(settings.theme.ordinal + 1) % ThemeMode.entries.size]
                save(settings.copy(theme = next))
            }
            HorizontalDivider(); SettingRow(Icons.Outlined.Info, "本地抓包 Demo", "v1.0.0")
            Muted("所有运行组件位于手机内 · 导出默认隐藏敏感请求头")
        } }
    }
}

@Composable internal fun ProxySettings(proxy: UpstreamProxy, save: (UpstreamProxy) -> Unit) {
    var portText by rememberSaveable(proxy.port) { mutableStateOf(proxy.port.toString()) }
    val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
    val invalid = proxy.enabled && port == null
    Muted("上游代理")
    Spacer(Modifier.height(8.dp))
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AltRoute, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("通过代理转发", fontWeight = FontWeight.SemiBold)
                Muted(if (proxy.enabled) "抓包后交给本机代理" else "关闭时直接连接")
            }
            Switch(proxy.enabled, { save(proxy.copy(enabled = it)) }, Modifier.testTag("proxy-enabled").semantics { contentDescription = "通过代理转发" })
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SettingRow(Icons.Outlined.Hub, "代理协议", "SOCKS5")
        SettingRow(Icons.Outlined.PhoneAndroid, "本机地址", proxy.host)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(portText, { portText = it }, Modifier.weight(1f).testTag("proxy-port"), enabled = proxy.enabled,
                label = { Text("代理端口") }, singleLine = true, isError = invalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Button(onClick = { port?.let { save(proxy.copy(port = it)) } },
                enabled = proxy.enabled && port != null && port != proxy.port, modifier = Modifier.testTag("proxy-save")) { Text("保存") }
        }
        Spacer(Modifier.height(6.dp))
        if (invalid) Text("请输入 1–65535 之间的端口", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        else Muted(if (port != proxy.port) "端口修改后需保存" else "已保存端口 ${proxy.port} · 关闭代理会保留端口")
        Spacer(Modifier.height(12.dp))
        Muted("设置在下一次开始抓包时生效")
        Spacer(Modifier.height(8.dp))
        Muted("请先将 v2rayNG 切换为“仅代理”并启动，端口需保持一致。目标应用中不要选择 v2rayNG。代理不可用时会报错，不会自动直连。")
    }
}
