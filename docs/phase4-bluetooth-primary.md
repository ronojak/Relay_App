# Phase 4 — Bluetooth Primary (Output) Sink

Status: **planned** (not yet implemented). This document is the implementation plan.

## Goal

Today the relay's **primary/output** connection (phone → peripheral) is WiFi only,
via `TcpClientSink` / `TcpServerSink`. Phase 4 adds the ability to send the relay's
serialized frames to the peripheral over **Bluetooth**, so the same gamepad input
can drive a Bluetooth-attached MCU.

Architectural ground rules established earlier:

- The **MCU is always the peripheral/server**, regardless of interface. Over WiFi
  the phone dials out (`WIFI_CLIENT`); over Bluetooth the phone is the BT *client*
  that connects to the MCU's service.
- The model is **single active sink, switchable** — Bluetooth is simply a third
  `SinkType` selectable in Settings, swapped in via the same restart path.

## Where it plugs in (the Phase 3 seam)

Phase 3 introduced the registry so this is an additive change:

- `SinkType` (in `data/`) — add `BLUETOOTH`.
- `TransportFactory.createSink(...)` — add the `SinkType.BLUETOOTH` branch returning
  the new sink. Currently that branch throws `UnsupportedOperationException`.
- `RelaySettings` — add `sinkBtAddress: String` (the bonded MCU's MAC) alongside the
  existing host/port (which stay WiFi-only).
- Settings UI — the disabled "Bluetooth" chip becomes enabled, revealing a bonded-
  device picker instead of the IP/port fields.

No changes to `RelayEngine`, the pump loop, telemetry, or the protocol are required —
the new class only has to satisfy the existing `SinkTransport` interface.

## Transport choice: Classic SPP vs BLE GATT

This is the one decision that needs the MCU's capabilities; it determines the
implementation.

### Option A — Bluetooth Classic SPP (RFCOMM)  ← recommended if the MCU supports it
- A stream socket, semantically identical to TCP: open, `write(frame)`, done.
- Maps almost 1:1 onto the existing `TcpClientSink` (swap `Socket` for
  `BluetoothSocket`), so it's the least code and lowest risk.
- Throughput is ample for our ~26-byte frames at relay rates.
- Android API: `BluetoothDevice.createRfcommSocketToServiceRecord(SPP_UUID)` where
  `SPP_UUID = 00001101-0000-1000-8000-00805F9B34FB`.
- Requires the MCU to expose an SPP/RFCOMM server (common on ESP32 "BluetoothSerial",
  classic HC-05/06 modules, etc.). Not available on BLE-only chips.

### Option B — BLE GATT
- Required if the MCU is BLE-only (e.g. nRF52, ESP32 in BLE mode).
- The phone is GATT *client*; the MCU exposes a service with a writable
  characteristic; each frame is a `writeCharacteristic` (Write Without Response for
  speed).
- More moving parts: scan/connect, service & characteristic discovery, MTU
  negotiation (request ~185+ so a 26-byte frame fits one ATT write), and write-
  without-response flow control.
- Throughput is connection-interval bound; fine for our small frames but needs care
  at higher Hz.

**Recommendation:** implement **SPP first** (Option A) — minimal delta from the
existing TCP client and likely matches an ESP32-class MCU. Add a `BleGattSink`
later only if the target hardware is BLE-only.

## Implementation steps (SPP variant)

1. **`data/`**: add `BLUETOOTH` to `SinkType`; add `sinkBtAddress: String = ""` to
   `RelaySettings`; persist/load it (`bluetoothPreferencesKey`); extend
   `RelaySettingsRepository.update(...)`.
2. **`transport/BluetoothSppSink.kt`** implementing `SinkTransport`:
   - Constructor: `(scope, bluetoothManager, deviceAddress)`.
   - `start()`: resolve `BluetoothDevice` by address, cancel discovery, open
     `createRfcommSocketToServiceRecord(SPP_UUID)`, `connect()` on `Dispatchers.IO`,
     grab the `OutputStream`; reconnect/backoff loop mirroring `TcpClientSink`.
   - `send(frame)`: `out.write(frame); out.flush()` on IO; on `IOException` close and
     flag reconnect, return false.
   - `state`/`logs`/`peerInfo` exactly like `TcpClientSink`.
   - Optional: a reader coroutine to surface return traffic as
     `FrameDirection.INCOMING_RETURN` telemetry (see below).
3. **`transport/TransportFactory.kt`**: replace the throwing `BLUETOOTH` branch with
   `BluetoothSppSink(scope, bluetoothManager, settings.sinkBtAddress)`.
4. **UI** (`SettingsScreen`): enable the Bluetooth chip; when selected, hide IP/port
   and show a dropdown of **bonded** devices (`BluetoothManager.bondedDevices`), the
   chosen MAC flowing into `sinkBtAddress`. Validation: require a selected device.
5. **`RelayTopologyCard`**: the OUTPUT node already renders from settings; feed it a
   Bluetooth label/detail when `sinkType == BLUETOOTH`.
6. **Manifest/permissions**: `BLUETOOTH_CONNECT` (Android 12+) is already requested.
   No new permission for connecting to a bonded device; ensure runtime grant before
   `start()`.

## Telemetry / return traffic

`FrameDirection.INCOMING_RETURN` already exists but is unwired. The BT sink (and
later a TCP sink) can spawn a reader that records inbound bytes from the peripheral
(acks/status) into `TelemetryRepository`. Wire this once a peripheral that talks back
is available; until then leave the reader off to avoid noise.

## Testing

- **With real hardware**: flash an ESP32 SPP server that prints received frames; bond
  it to the phone; Settings → Bluetooth → pick device → Apply; move sticks and watch
  the MCU log. Toggle telemetry to inspect outgoing frames.
- **Without hardware**: a laptop with a Bluetooth adapter running an RFCOMM server
  (e.g. PyBluez `RFCOMM` listener) is the BT analogue of `relay_test_server.py
  --role sink`. Document the exact command alongside the test server when built.

## Risks / notes

- RFCOMM `connect()` blocks and can hang on a flaky link — always on `Dispatchers.IO`
  with a watchdog/timeout; never on the relay pump path.
- Some devices need the reflection fallback
  (`createRfcommSocket(int)`) when the standard UUID socket fails to connect; add it
  as a secondary attempt if field testing shows failures.
- BLE path, if needed, is a separate `BleGattSink` and a meaningfully larger effort
  (GATT callbacks, MTU, WWR throttling) — scope it on its own.
