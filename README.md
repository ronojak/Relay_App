# Relay App

High-performance Android gamepad input relay system for real-time network applications.

## Overview
Relay App captures Bluetooth gamepad input and streams it over TCP to network clients with sub-50ms latency. Designed for gaming, robotics, and IoT applications requiring precise real-time control.

## Features

- **Low Latency**: <50ms end-to-end latency target
- **High Performance**: Up to 120Hz transmission rate
- **Gamepad Support**: PS5, PS4, Xbox controllers via Bluetooth
- **Background Operation**: Android foreground service
- **Modern UI**: Jetpack Compose with Material 3

## Quick Start

1. **Install APK**: `app/build/outputs/apk/debug/app-debug.apk` on Android 8.0+
2. **Connect Gamepad**: Pair Bluetooth controller
3. **Start Service**: Launch app and enable relay service
4. **Connect Client**: TCP client to port 6543

## Build

```bash
# Build debug APK
./gradlew assembleDebug

# Clean build artifacts
./gradlew clean
```

## Requirements

- **Android**: 8.0+ (API 26+)
- **Bluetooth**: For gamepad connectivity
- **Network**: Local network access for TCP relay

## Architecture

- **Protocol**: Binary format with 14-byte header + gamepad state
- **Network**: Single-client TCP server with auto-reconnection
- **Input Processing**: 120Hz rate limiting with deadzone handling
- **Service**: Foreground service for background operation

## Documentation

See [CLAUDE.md](CLAUDE.md) for comprehensive development documentation.

## License

Internal development project.