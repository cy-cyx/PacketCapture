package com.packetcapture.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.packetcapture.core.ThemeMode

private val light = lightColorScheme(primary = Color(0xFF008F9D), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDF4F5), onPrimaryContainer = Color(0xFF006875),
    secondary = Color(0xFF526C82), secondaryContainer = Color(0xFFDDF4F5), onSecondaryContainer = Color(0xFF00818E),
    background = Color(0xFFF5F8FA), surface = Color.White,
    surfaceVariant = Color(0xFFEEF3F7), onSurface = Color(0xFF152A3B), onBackground = Color(0xFF152A3B),
    onSurfaceVariant = Color(0xFF647B8D), outlineVariant = Color(0xFFE4EBEF))
private val dark = darkColorScheme(primary = Color(0xFF5DD5DD), primaryContainer = Color(0xFF064951),
    background = Color(0xFF0E1B25), surface = Color(0xFF152733), surfaceVariant = Color(0xFF203542))
@Composable fun PacketCaptureTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val isDark = mode == ThemeMode.DARK || (mode == ThemeMode.SYSTEM && isSystemInDarkTheme())
    MaterialTheme(colorScheme = if (isDark) dark else light, typography = Typography, content = content)
}
