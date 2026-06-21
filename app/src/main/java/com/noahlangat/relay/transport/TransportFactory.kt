package com.noahlangat.relay.transport

import com.noahlangat.relay.bluetooth.BluetoothManager
import com.noahlangat.relay.bluetooth.GamepadInputHandler
import com.noahlangat.relay.data.RelaySettings
import com.noahlangat.relay.data.SinkType
import com.noahlangat.relay.data.SourceType
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central registry that builds the active [SourceTransport] / [SinkTransport] from
 * the current [RelaySettings]. The engine asks the factory for transports rather
 * than constructing them itself, so new transport types (e.g. the Phase 4 Bluetooth
 * sink) are added here in one place.
 */
@Singleton
class TransportFactory @Inject constructor(
    private val bluetoothManager: BluetoothManager,
    private val gamepadInputHandler: GamepadInputHandler
) {

    fun createSource(settings: RelaySettings, scope: CoroutineScope): SourceTransport =
        when (settings.sourceType) {
            SourceType.BLUETOOTH_HID -> BluetoothHidSource(bluetoothManager, gamepadInputHandler)
        }

    fun createSink(settings: RelaySettings, scope: CoroutineScope): SinkTransport =
        when (settings.sinkType) {
            SinkType.WIFI_CLIENT -> TcpClientSink(scope, settings.primaryHost.trim(), settings.primaryPort)
            SinkType.WIFI_SERVER -> TcpServerSink(scope, settings.primaryPort)
            SinkType.BLUETOOTH -> throw UnsupportedOperationException(
                "Bluetooth primary sink is not implemented yet (see docs/phase4-bluetooth-primary.md)"
            )
        }
}
