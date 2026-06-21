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

/** How the phone's primary (output) connection is established. */
enum class PrimaryMode {
    /** Phone dials out to the peripheral/MCU at [RelaySettings.primaryHost]:[RelaySettings.primaryPort]. */
    CLIENT,

    /** Phone listens; the peripheral dials in. (Legacy/alternate topology.) */
    SERVER
}

/** User-configurable relay settings. */
data class RelaySettings(
    val primaryMode: PrimaryMode = PrimaryMode.CLIENT,
    val primaryHost: String = "",
    val primaryPort: Int = ProtocolConstants.DEFAULT_TCP_PORT
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
        val MODE = stringPreferencesKey("primary_mode")
        val HOST = stringPreferencesKey("primary_host")
        val PORT = intPreferencesKey("primary_port")
    }

    init {
        scope.launch {
            runCatching {
                val prefs = context.dataStore.data.first()
                _settings.value = RelaySettings(
                    primaryMode = prefs[Keys.MODE]
                        ?.let { runCatching { PrimaryMode.valueOf(it) }.getOrNull() }
                        ?: RelaySettings().primaryMode,
                    primaryHost = prefs[Keys.HOST] ?: "",
                    primaryPort = prefs[Keys.PORT] ?: ProtocolConstants.DEFAULT_TCP_PORT
                )
            }.onFailure { Timber.w(it, "Failed to load relay settings") }
        }
    }

    /** Update settings in memory immediately and persist asynchronously. */
    fun update(
        mode: PrimaryMode? = null,
        host: String? = null,
        port: Int? = null
    ) {
        _settings.value = _settings.value.copy(
            primaryMode = mode ?: _settings.value.primaryMode,
            primaryHost = host ?: _settings.value.primaryHost,
            primaryPort = port ?: _settings.value.primaryPort
        )
        scope.launch {
            runCatching {
                context.dataStore.edit { prefs ->
                    mode?.let { prefs[Keys.MODE] = it.name }
                    host?.let { prefs[Keys.HOST] = it }
                    port?.let { prefs[Keys.PORT] = it }
                }
            }.onFailure { Timber.w(it, "Failed to persist relay settings") }
        }
    }
}
