package com.noahlangat.relay.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.noahlangat.relay.protocol.ProtocolConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Available primary (output) transports. The MCU is always the server/peripheral. */
enum class SinkType(val displayName: String) {
    /** Phone dials out to the MCU at [RelaySettings.primaryHost]:[RelaySettings.primaryPort]. */
    WIFI_CLIENT("WiFi · dial out"),

    /** Phone listens; the peripheral dials in. (Alternate topology.) */
    WIFI_SERVER("WiFi · listen"),

    /** Phone connects to the MCU over Bluetooth. (Implemented in Phase 4.) */
    BLUETOOTH("Bluetooth")
}

/** Available secondary (input) transports. */
enum class SourceType(val displayName: String) {
    BLUETOOTH_HID("Bluetooth Gamepad")
}

/** App appearance preference. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** User-configurable relay settings. */
data class RelaySettings(
    val sinkType: SinkType = SinkType.WIFI_CLIENT,
    val sourceType: SourceType = SourceType.BLUETOOTH_HID,
    val primaryHost: String = "",
    val primaryPort: Int = ProtocolConstants.DEFAULT_TCP_PORT,
    val themeMode: ThemeMode = ThemeMode.SYSTEM
)

private val Context.dataStore by preferencesDataStore(name = "relay_settings")

/**
 * Persists [RelaySettings] via DataStore. The current value is also mirrored in an
 * in-memory [StateFlow] that updates synchronously on [update], so callers that
 * apply a change and immediately restart the relay read the new value without a
 * persistence round-trip race.
 */
@Singleton
class RelaySettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _settings = MutableStateFlow(RelaySettings())
    val settings: StateFlow<RelaySettings> = _settings

    private object Keys {
        val LEGACY_MODE = stringPreferencesKey("primary_mode")
        val SINK = stringPreferencesKey("sink_type")
        val SOURCE = stringPreferencesKey("source_type")
        val HOST = stringPreferencesKey("primary_host")
        val PORT = intPreferencesKey("primary_port")
        val THEME = stringPreferencesKey("theme_mode")
    }

    init {
        scope.launch {
            runCatching {
                val prefs = context.dataStore.data.first()
                _settings.value = RelaySettings(
                    sinkType = readSinkType(prefs[Keys.SINK], prefs[Keys.LEGACY_MODE]),
                    sourceType = prefs[Keys.SOURCE]
                        ?.let { runCatching { SourceType.valueOf(it) }.getOrNull() }
                        ?: RelaySettings().sourceType,
                    primaryHost = prefs[Keys.HOST] ?: "",
                    primaryPort = prefs[Keys.PORT] ?: ProtocolConstants.DEFAULT_TCP_PORT,
                    themeMode = prefs[Keys.THEME]
                        ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                        ?: RelaySettings().themeMode
                )
            }.onFailure { Timber.w(it, "Failed to load relay settings") }
        }
    }

    /** Resolve the sink type, migrating the legacy CLIENT/SERVER primary_mode key. */
    private fun readSinkType(stored: String?, legacy: String?): SinkType {
        stored?.let { runCatching { SinkType.valueOf(it) }.getOrNull() }?.let { return it }
        return when (legacy) {
            "CLIENT" -> SinkType.WIFI_CLIENT
            "SERVER" -> SinkType.WIFI_SERVER
            else -> RelaySettings().sinkType
        }
    }

    /** Update settings in memory immediately and persist asynchronously. */
    fun update(
        sinkType: SinkType? = null,
        sourceType: SourceType? = null,
        host: String? = null,
        port: Int? = null,
        themeMode: ThemeMode? = null
    ) {
        _settings.value = _settings.value.copy(
            sinkType = sinkType ?: _settings.value.sinkType,
            sourceType = sourceType ?: _settings.value.sourceType,
            primaryHost = host ?: _settings.value.primaryHost,
            primaryPort = port ?: _settings.value.primaryPort,
            themeMode = themeMode ?: _settings.value.themeMode
        )
        scope.launch {
            runCatching {
                context.dataStore.edit { prefs ->
                    sinkType?.let { prefs[Keys.SINK] = it.name }
                    sourceType?.let { prefs[Keys.SOURCE] = it.name }
                    host?.let { prefs[Keys.HOST] = it }
                    port?.let { prefs[Keys.PORT] = it }
                    themeMode?.let { prefs[Keys.THEME] = it.name }
                }
            }.onFailure { Timber.w(it, "Failed to persist relay settings") }
        }
    }
}
