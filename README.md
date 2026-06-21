# Relay

An Android app that captures Bluetooth gamepad input and relays it, frame by frame,
to a peripheral (e.g. a Wi-Fi MCU) over a configurable transport. Built for gaming,
robotics, and IoT scenarios that need real-time controller input over a network.

## How it works

```
Bluetooth gamepad ──▶ Relay (Android) ──▶ Peripheral / MCU
   (secondary,            relay engine        (primary, the server)
    the input source)                          dialed over Wi-Fi
```

- **Secondary / input** — a local Bluetooth gamepad (PS5 / PS4 / Xbox / generic),
  surfaced as Android HID input events.
- **Primary / output** — the peripheral. The MCU is always the **server**; the phone
  connects to it. Over Wi-Fi the phone dials out to a configurable `IP:port`
  (`WIFI_CLIENT`), or it can listen for the peripheral to dial in (`WIFI_SERVER`).
  A Bluetooth primary is planned — see [docs/phase4-bluetooth-primary.md](docs/phase4-bluetooth-primary.md).

The phone serializes each gamepad state into a compact binary frame and streams it to
the active primary connection.

## Features

- **Bluetooth gamepad capture** with controller-type detection.
- **Configurable primary connection** — Wi-Fi client (dial out) or server (listen),
  with editable host/port in Settings; changes restart the relay in place.
- **Live telemetry** — an on/off capture of incoming/outgoing frames (direction,
  timestamp, size, hex, decoded summary) for debugging. Off by default.
- **Relay topology view** — a source → relay → output picture with live status.
- **Light / dark / system theme**, persisted.
- **Foreground service** for background operation.

## Project layout

```
app/src/main/java/com/noahlangat/relay/
  bluetooth/    BluetoothManager, GamepadInputHandler  (input capture)
  transport/    Source/Sink interfaces + implementations + TransportFactory
  service/      RelayEngine (the relay pipeline) + RelayService (foreground host)
  protocol/     binary envelope + GamepadState serialization
  telemetry/    TelemetryRepository + FrameEvent (comms capture)
  data/         RelaySettingsRepository (DataStore-backed settings)
  ui/           Jetpack Compose screens (Main, Settings, Telemetry)
docs/           design docs (e.g. the Phase 4 Bluetooth-sink plan)
utils/          relay_test_server.py (TCP/UDP test peer)
```

The relay is built around `SourceTransport` / `SinkTransport` interfaces. `RelayEngine`
pulls frames from the active source and pushes them to the active sink; `TransportFactory`
builds both from the saved settings, so transports are swappable.

## Protocol

Little-endian binary, default TCP port **26543**.

```c
struct Envelope {        // 16 bytes
    uint8_t  msg_type;   // 0x01 = GamepadState
    uint8_t  version;
    uint16_t length;     // payload length
    uint32_t seq;
    uint64_t timestamp_us;
};
struct GamepadState {    // 26 bytes
    uint8_t  device_id, flags;
    uint16_t buttons;
    int16_t  lx, ly, rx, ry;
    uint16_t l2, r2;
    int16_t  dpad_x, dpad_y;
};
```

## Build

Toolchain: Gradle 9.2.1, Android Gradle Plugin 8.13.2, Kotlin 2.0.21 (KSP), Hilt 2.51,
Java 17, min SDK 26 / compile SDK 34.

```bash
# Point Gradle at your Android SDK (once)
cp local.properties.example local.properties   # then edit sdk.dir if needed

# Build / install the debug APK
./gradlew assembleDebug
./gradlew installDebug
```

On Windows, use `gradlew.bat`. If building from a plain terminal, set `JAVA_HOME` to a
JDK 17 (e.g. Android Studio's bundled JBR); Android Studio sets this automatically.

## Testing the relay

The phone needs a peer to send to. `utils/relay_test_server.py` can act as the
peripheral by listening (the phone dials in):

```bash
# On a PC on the same network — stands in for the MCU
python utils/relay_test_server.py --role sink --port 26543 --tap --show-ascii
```

Then on the phone: **Settings → WiFi · dial out → enter the PC's IP and port 26543 →
Apply & Restart Relay**, connect a controller, and watch frames arrive in the PC
console. Toggle **Telemetry** in-app to inspect the same frames on the device.

## Status & roadmap

Working: Bluetooth input, Wi-Fi client/server primary, telemetry, theming, settings.

Planned: Bluetooth primary sink (Classic SPP or BLE GATT) —
see [docs/phase4-bluetooth-primary.md](docs/phase4-bluetooth-primary.md).

See [CLAUDE.md](CLAUDE.md) for fuller development notes.
