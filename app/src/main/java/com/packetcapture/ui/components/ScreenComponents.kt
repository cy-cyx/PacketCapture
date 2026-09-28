package com.packetcapture.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.packetcapture.core.BodyRef
import com.packetcapture.core.Completion
import com.packetcapture.core.HttpExchange
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable internal fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(14.dp), content = content)
    }
}
@Composable internal fun Muted(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(text, modifier, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}
@Composable internal fun Metric(value: String, label: String) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value, fontWeight = FontWeight.Bold); Muted(label) } }
@Composable internal fun Badge(text: String) { Text(text, Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
    color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium) }
@Composable internal fun SearchField(text: String, change: (String) -> Unit, placeholder: String) {
    OutlinedTextField(text, change, Modifier.fillMaxWidth(), placeholder = { Text(placeholder, fontSize = 13.sp) }, singleLine = true,
        leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = RoundedCornerShape(16.dp))
}
@Composable internal fun Empty(title: String, message: String) { Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Icon(Icons.Outlined.TravelExplore, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(12.dp))
    Text(title, fontWeight = FontWeight.Medium); Muted(message, Modifier.padding(16.dp))
} }
@Composable internal fun Notice(message: String, error: Boolean = false) { Text(message, Modifier.fillMaxWidth().background(if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(12.dp), fontSize = 12.sp) }
@Composable internal fun SettingRow(icon: ImageVector, title: String, value: String, click: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (click == null) Modifier else Modifier.clickable(onClick = click)).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp)); Text(title, Modifier.weight(1f).padding(start = 12.dp), fontSize = 14.sp); Muted(value)
        if (click != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp))
    }
}
@Composable internal fun KeyValue(key: String, value: String) { Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
    Muted(key, Modifier.weight(0.4f)); SelectionContainer(Modifier.weight(0.6f)) { Text(value, fontSize = 12.sp) }
} }
internal fun bodyInfo(body: BodyRef?) = body?.let { "${bytes(it.savedBytes)} / ${bytes(it.observedBytes)}${if (it.truncated) " · 截断：${it.reason}" else ""}" } ?: "等待保存"
internal fun date(time: Long) = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))
internal fun requestTimestamp(time: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(time))
internal fun responseContentType(exchange: HttpExchange): String {
    val type = exchange.responseHeaders.firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }
        ?.value?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }
    return type?.lowercase(Locale.ROOT) ?: when {
        exchange.status != null || exchange.responseStartedAt != null || exchange.responseHeaders.isNotEmpty() -> "未提供"
        exchange.completion == Completion.ACTIVE -> "等待响应"
        else -> "无响应"
    }
}
internal fun bytes(value: Long): String = when { value < 1024 -> "$value B"; value < 1024 * 1024 -> "%.1f KB".format(value / 1024.0); else -> "%.1f MiB".format(value / 1048576.0) }
