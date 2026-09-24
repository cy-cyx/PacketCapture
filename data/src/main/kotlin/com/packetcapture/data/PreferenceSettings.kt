package com.packetcapture.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.packetcapture.core.*
import kotlinx.coroutines.flow.map

private val Context.capturePreferences by preferencesDataStore("capture-settings")
class PreferenceSettings(context: Context) : SettingsRepository {
    private val store = context.applicationContext.capturePreferences
    private val packages = stringSetPreferencesKey("packages")
    private val https = booleanPreferencesKey("https")
    private val bypass = stringSetPreferencesKey("bypass")
    private val theme = stringPreferencesKey("theme")
    private val proxyEnabled = booleanPreferencesKey("upstream-proxy-enabled")
    private val proxyPort = intPreferencesKey("upstream-proxy-port")
    override val settings = store.data.map {
        AppSettings(CaptureConfig(packages = it[packages] ?: emptySet(), decryptHttps = it[https] ?: true,
            bypassDomains = it[bypass] ?: emptySet(),
            upstreamProxy = UpstreamProxy(it[proxyEnabled] ?: false, it[proxyPort]?.takeIf { port -> port in 1..65535 } ?: 10808)),
            runCatching { ThemeMode.valueOf(it[theme] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM))
    }
    override suspend fun update(settings: AppSettings) {
        store.edit {
            it[packages] = settings.capture.packages
            it[https] = settings.capture.decryptHttps
            it[bypass] = settings.capture.bypassDomains
            it[theme] = settings.theme.name
            it[proxyEnabled] = settings.capture.upstreamProxy.enabled
            it[proxyPort] = settings.capture.upstreamProxy.port
        }
    }
}
