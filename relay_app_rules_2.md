### Workflow.md - Development & Deployment Workflows (Prompt-Aligned)

### Module Development Strategy (Prompt Deliverables)

#### Module Implementation Order
1. **core-proto** (Foundation): Binary protocol, envelope, serialization
2. **core-input** (Input Layer): Gamepad detection, normalization, rate limiting  
3. **relay-service** (Service Layer): TCP server, foreground service, auto-reconnect
4. **ui** (Presentation): Device picker, settings, logs, status display

#### Test Implementation (Prompt Requirements)
```kotlin
// Prompt-specified test categories
class ProtocolTest {
    @Test fun testEncodeDecodeCorrectness() { /* Binary serialization */ }
    @Test fun testEnvelopeValidation() { /* 14-byte header structure */ }
}

class RateLimiterTest {
    @Test fun test120HzCap() { /* Max frequency enforcement */ }
    @Test fun testSendOnChange() { /* Change detection logic */ }
}

class BackpressureTest {
    @Test fun testBoundedQueueDropOldest() { /* Queue management */ }
    @Test fun testMemoryBounds() { /* No unbounded growth */ }
}

class LatencyTest {
    @Test fun testInputToSocketLatency() { /* <20ms measurement */ }
    @Test fun testEndToEndTiming() { /* Complete pipeline */ }
}
```

### Development Workflow (Prompt-Optimized)

#### Feature Branch Strategy
```
main (stable, prompt-compliant releases)
├── develop (integration)
│   ├── feature/core-proto-implementation
│   ├── feature/gamepad-input-handler
│   ├── feature/tcp-single-client
│   ├── feature/bounded-queue-drop-oldest
│   ├── feature/120hz-rate-limiting
│   └── feature/foreground-service
├── release/v1.0-mvp (prompt compliance verification)
└── hotfix/critical-latency-fix
```

#### Definition of Done (Prompt Compliance)
- [ ] **Prompt Requirements:** All mandatory features implemented exactly as specified
- [ ] **Performance Targets:** <20ms input→socket, <5%/hour battery, ≥95% uptime
- [ ] **Protocol Compliance:** Binary envelope structure matches prompt exactly
- [ ] **Module Structure:** core-input, core-proto, relay-service, ui delivered
- [ ] **Test Coverage:** Protocol, rate limiter, backpressure, latency tests pass
- [ ] **Sample Client:** Desktop/MCU pseudocode provided and validated

### Quality Assurance (Prompt-Focused)

#### Performance Validation
```kotlin
// Prompt constraint validation
@Test
fun validateInputToSocketLatency() {
    val measurements = mutableListOf<Long>()
    repeat(1000) {
        val start = System.nanoTime()
        inputHandler.processGamepadEvent(mockEvent)
        tcpServer.sendToClient(normalizedState)
        val latency = (System.nanoTime() - start) / 1_000_000 // ms
        measurements.add(latency)
    }
    
    val p95Latency = measurements.sorted()[950]
    assertThat(p95Latency).isLessThan(20) // Prompt requirement: <20ms
}

@Test  
fun validateBatteryUsage() {
    val batteryBefore = getBatteryLevel()
    runRelayFor(hours = 1)
    val batteryAfter = getBatteryLevel()
    val usage = batteryBefore - batteryAfter
    
    assertThat(usage).isLessThan(5.0) // Prompt requirement: <5%/hour
}
```

#### Protocol Compliance Testing
```kotlin
@Test
fun validateBinaryEnvelopeStructure() {
    val state = GamepadState(/* test data */)
    val envelope = createEnvelope(MSG_GAMEPAD_STATE, 1, state.size(), seq, timestamp)
    val serialized = serialize(envelope, state)
    
    // Verify exact prompt specification
    assertThat(serialized[0]).isEqualTo(0x01) // msg_type
    assertThat(serialized[1]).isEqualTo(0x01) // version  
    assertThat(serialized.size).isEqualTo(14 + state.size()) // envelope + payload
}
```

### Sample Client Development (Prompt Deliverable)

#### Desktop Sample Client
```cpp
// prompt_sample_client.cpp - Desktop implementation
#include <iostream>
#include <cstdint>
#include <arpa/inet.h>

#pragma pack(push, 1)
struct Envelope {
    uint8_t msg_type;
    uint8_t version; 
    uint16_t length;
    uint32_t seq;
    uint64_t timestamp_us;
};

struct GamepadState {
    uint8_t device_id;
    uint16_t buttons_bitmask;
    int16_t lx, ly, rx, ry;
    uint16_t l2, r2;
    int16_t dpad_x, dpad_y;
};
#pragma pack(pop)

int main() {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in addr = {AF_INET, htons(6543), {inet_addr("192.168.1.100")}};
    
    if (connect(sock, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        perror("connect failed");
        return 1;
    }
    
    # Relay App - Architecture Rules & Documentation

This document contains architectural decision records, detailed PRD enhancements, workflow specifications, and folder structure guidelines for the Relay App project.

---

## ADR.md - Architectural Decision Records

### ADR-001: Android-First Platform Strategy
**Date:** 2025-09-06  
**Status:** Accepted  
**Context:** Need to choose primary development platform for MVP relay functionality.

**Decision:** Android will be the primary platform with Kotlin as the main language.

**Rationale:**
- Android provides mature Bluetooth gamepad APIs (`InputManager`, `MotionEvent`, `KeyEvent`)
- Better foreground service support for background relay operations
- Larger market share for development/maker community
- Native TCP socket support without additional frameworks

**Consequences:**
- iOS becomes secondary priority (Phase 2)
- Team expertise in Kotlin/Android required
- Platform-specific optimizations possible

**Alternatives Considered:**
- Cross-platform framework (React Native, Flutter) - rejected due to Bluetooth complexity
- iOS-first - rejected due to GameController framework limitations

---

### ADR-002: TCP Over UDP for Initial Transport
**Date:** 2025-09-06  
**Status:** Accepted  
**Context:** Need reliable, ordered delivery of gamepad state with simple implementation.

**Decision:** Use TCP as primary transport protocol for MVP.

**Rationale:**
- Guaranteed delivery and ordering crucial for control applications
- Simpler error handling and connection management
- Built-in flow control prevents buffer overflow
- Easier debugging and monitoring

**Consequences:**
- Higher latency than UDP (acceptable for <80ms target)
- Connection-oriented model fits single-client requirement
- More predictable behavior for initial development

**Alternatives Considered:**
- UDP with custom reliability - deferred to Phase 2
- WebSocket - unnecessary overhead for binary protocol

---

### ADR-003: Binary Protocol with Little-Endian Encoding
**Date:** 2025-09-06  
**Status:** Accepted  
**Context:** Need efficient, platform-agnostic data serialization.

**Decision:** Implement fixed-size binary protocol with little-endian byte order.

**Rationale:**
- Minimal serialization overhead (14-byte header + 22-byte payload)
- Little-endian matches ARM/x86 architecture prevalence
- Fixed-size structures enable zero-copy parsing
- Version field enables future protocol evolution

**Consequences:**
- Platform endianness conversion may be needed
- Binary debugging requires hex tools
- Tighter coupling between client/server versions

**Alternatives Considered:**
- JSON protocol - rejected due to size/parsing overhead
- Protocol Buffers - unnecessary complexity for fixed structure

---

### ADR-004: Bounded Queue with Drop-Oldest Policy
**Date:** 2025-09-06  
**Status:** Accepted  
**Context:** Prevent memory exhaustion when network client is slower than input rate.

**Decision:** Implement bounded send queue (default 120 frames) with drop-oldest eviction.

**Rationale:**
- Bounded memory usage prevents OOM crashes
- Drop-oldest maintains most recent state for real-time applications
- Simple implementation and predictable behavior
- 120 frames = ~1 second buffer at max rate

**Consequences:**
- Frame loss possible under sustained backpressure
- Additional complexity for queue management
- Need monitoring/alerting for drop conditions

**Alternatives Considered:**
- Unbounded queue - rejected due to memory risk
- Drop-newest - rejected as stale data less useful
- Backpressure to input - rejected as breaks real-time requirement

---

### ADR-005: Platform GamePad APIs Over Raw HID
**Date:** 2025-09-06  
**Status:** Accepted  
**Context:** Choice between platform abstraction vs. direct hardware control for DualSense.

**Decision:** Use Android InputManager/MotionEvent APIs rather than raw Bluetooth HID parsing.

**Rationale:**
- Leverages Android's mature input handling and driver support
- Reduces implementation complexity and device compatibility issues
- Automatic handling of pairing, reconnection, and power management
- Forward compatibility with Android input system evolution

**Consequences:**
- Limited access to advanced DualSense features (haptics, LED, touchpad gestures)
- Dependency on Android input driver quality
- Potential latency from input system layer

**Alternatives Considered:**
- Raw HID parsing - rejected due to complexity and maintenance burden
- Vendor SDK - not available for DualSense on Android

---

### ADR-006: Foreground Service for Background Operation
**Date:** 2025-09-06  
**Status:** Accepted  
**Context:** Need continuous relay operation while app is backgrounded.

**Decision:** Implement Android foreground service with persistent notification.

**Rationale:**
- Foreground services exempt from background execution limits
- User visibility via notification meets Android policy requirements
- Can maintain network connections and Bluetooth state
- Proper lifecycle management with activity

**Consequences:**
- Persistent notification may annoy users
- Additional battery usage from continuous operation
- More complex lifecycle and permission management

**Alternatives Considered:**
- Background service - rejected due to Android 8+ limitations
- Persistent activity - rejected due to poor UX

---

## PRD.md - Enhanced Product Requirements Document

### Enhanced Functional Requirements

#### 4.1.1 Bluetooth Device Management (Enhanced)
**Connection Lifecycle:**
- **Discovery Phase:** Scan for paired devices with gamepad source flags
- **Pairing Support:** Guide users through Android Bluetooth pairing flow
- **Connection State Machine:**
  ```
  DISCONNECTED → CONNECTING → CONNECTED → STREAMING
                    ↓              ↓         ↓
                 FAILED ←──── DISCONNECTED ←─┘
  ```
- **Auto-Reconnection:** Exponential backoff (1s, 2s, 4s, 8s, max 30s intervals)
- **Health Monitoring:** Detect stale connections via input timeout (5s threshold)

**Input Processing Pipeline:**
1. **Event Capture:** Android KeyEvent/MotionEvent handlers
2. **Debouncing:** 5ms minimum interval between identical button states
3. **Deadzone Processing:** Configurable deadzone for analog sticks (default 8%)
4. **Normalization:** Platform → GamepadState struct conversion
5. **Rate Limiting:** Coalesce unchanged states, max 120Hz transmission

#### 4.2.1 Network Transport Layer (Enhanced)
**TCP Server Implementation:**
- **Binding Strategy:** Listen on all interfaces (0.0.0.0) with configurable port
- **Client Acceptance:** Single active connection with queued accept for seamless reconnection
- **Socket Configuration:** 
  - TCP_NODELAY enabled for low latency
  - SO_KEEPALIVE with 30s intervals
  - 64KB send buffer size

**Connection Security:**
- **Auth Token Validation:** Optional 32-byte token exchange on connect
- **Connection Timeout:** 10s handshake timeout, 60s idle timeout
- **Rate Limiting:** Per-client connection attempts (5/minute)

**Quality of Service:**
- **Send Queue Management:** 
  ```kotlin
  class BoundedQueue<T>(private val capacity: Int = 120) {
      private val queue = ArrayDeque<T>()
      fun offer(item: T): Boolean {
          if (queue.size >= capacity) {
              queue.removeFirst() // Drop oldest
              metrics.incrementDropped()
          }
          return queue.offer(item)
      }
  }
  ```
- **Congestion Detection:** Monitor send buffer and RTT for flow control
- **Adaptive Rate:** Reduce transmission rate under sustained backpressure

#### 4.3.1 User Interface Enhancements

**Main Activity Layout:**
```
┌─ Toolbar ────────────────────────────────────────┐
│ Relay App                    [Settings] [Export] │
├─ Connection Panel ───────────────────────────────┤
│ Bluetooth Device: [PS5 Controller ▼] [Connect]  │
│ Status: Connected • 847ms ago                     │
│ Network: Port 6543 [Edit] Auth: ••••••• [Edit]  │
│ Client: 192.168.1.105:54321 • 120Hz             │
├─ Quick Stats ────────────────────────────────────┤
│ Packets: 15,247 Tx • 0 Dropped • 119.8 Hz       │
│ Latency: 23ms avg • 45ms p99                     │
├─ Log Viewer ─────────────────────────────────────┤
│ [All▼] [🔍Search...] [Copy] [Clear]             │
│ 14:23:45.123 BT   Connected to PS5 Controller   │
│ 14:23:45.456 NET  Client connected from .105    │
│ 14:23:46.789 TX   GamepadState seq=1234 (120Hz) │
│ ...                                               │
└─ Status Bar: [Foreground Service Active] ───────┘
```

**Settings Screen:**
- **Connection Settings:** Auto-reconnect policies, timeout values
- **Performance Settings:** Transmission rate limits, sensor enable/disable
- **Debug Settings:** Protocol format toggle (Binary/JSON), verbose logging
- **Export Settings:** Log retention period, file format preferences

#### 4.4.1 Protocol Specification (Enhanced)

**Message Types:**
```c
// Core message types
#define MSG_GAMEPAD_STATE    0x01
#define MSG_PING            0x02
#define MSG_AUTH_CHALLENGE  0x03
#define MSG_AUTH_RESPONSE   0x04

// Future extensions
#define MSG_SET_RUMBLE      0x10
#define MSG_SET_LED         0x11
#define MSG_DEVICE_INFO     0x20
```

**Enhanced GamepadState Structure:**
```c
struct GamepadState {
    // Header (14 bytes)
    uint8_t  msg_type;      // 0x01
    uint8_t  version;       // Protocol version
    uint16_t length;        // Payload length
    uint32_t seq;           // Sequence number
    uint64_t timestamp_us;  // Microsecond timestamp
    
    // Payload (26 bytes minimum)
    uint8_t  device_id;     // Logical device identifier
    uint8_t  flags;         // Feature flags (gyro, accel, etc.)
    uint16_t buttons;       // Button bitmask
    int16_t  lx, ly;        // Left stick (-32768..32767)
    int16_t  rx, ry;        // Right stick (-32768..32767)
    uint16_t l2, r2;        // Triggers (0..65535)
    int16_t  dpad_x, dpad_y;// D-pad as analog (-32768..32767)
    
    // Optional sensor data (12 bytes)
    int16_t  gyro[3];       // Angular velocity (x,y,z)
    int16_t  accel[3];      // Linear acceleration (x,y,z)
} __attribute__((packed));
```

**Authentication Flow:**
1. Client connects to TCP server
2. Server sends AUTH_CHALLENGE with 16-byte nonce
3. Client responds with HMAC-SHA256(token + nonce)
4. Server validates and begins GamepadState stream

### Non-Functional Requirements (Enhanced)

#### Performance Specifications
- **Input Latency:** <5ms from hardware event to GamepadState generation
- **Network Latency:** <15ms from GamepadState to TCP send
- **End-to-End Target:** <50ms (local LAN), <80ms (Wi-Fi with interference)
- **Throughput:** Sustain 120Hz transmission with <0.1% frame loss
- **Memory Usage:** <50MB RSS during active relay session
- **Battery Impact:** <3%/hour on modern Android device (measured via Battery Historian)

#### Reliability Specifications
- **Connection Uptime:** 99.5% over 8-hour test session
- **Recovery Time:** <5s for Bluetooth reconnection, <1s for TCP client reconnection  
- **Error Handling:** Graceful degradation with user notification for all failure modes
- **Data Integrity:** CRC validation optional for critical applications

#### Scalability Considerations
- **Memory Bounds:** All queues and buffers have fixed upper limits
- **Connection Scaling:** Architecture ready for multi-client (Phase 2)
- **Protocol Evolution:** Version field enables backward compatibility

---

## Workflow.md - Development & Deployment Workflows

### Development Workflow

#### Git Branching Strategy
```
main (stable releases)
├── develop (integration branch)
│   ├── feature/bluetooth-manager
│   ├── feature/tcp-server  
│   ├── feature/ui-redesign
│   └── feature/protocol-v2
├── release/v1.0 (release preparation)
└── hotfix/critical-bug (emergency fixes)
```

#### Development Process
1. **Feature Development:**
   ```bash
   git checkout develop
   git pull origin develop
   git checkout -b feature/descriptive-name
   # Development work...
   git push origin feature/descriptive-name
   # Create PR to develop branch
   ```

2. **Code Review Checklist:**
   - [ ] Unit tests cover new functionality (>80% coverage)
   - [ ] Integration tests for Bluetooth/TCP interaction
   - [ ] Performance impact measured (latency, memory, battery)
   - [ ] UI accessibility compliance (TalkBack support)
   - [ ] Documentation updated (KDoc, architecture docs)

3. **Quality Gates:**
   - **Static Analysis:** ktlint, detekt, Android Lint
   - **Testing:** Unit (JUnit5), Integration (Espresso), Performance (Macrobenchmark)
   - **Security:** OWASP dependency check, permission audit

#### Continuous Integration Pipeline
```yaml
# GitHub Actions workflow
name: CI/CD Pipeline
on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Setup JDK 17
        uses: actions/setup-java@v3
      - name: Run tests
        run: ./gradlew test
      - name: Generate coverage
        run: ./gradlew jacocoTestReport
        
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Build APK
        run: ./gradlew assembleDebug
      - name: Upload artifacts
        uses: actions/upload-artifact@v3
        
  performance:
    runs-on: macos-latest # for hardware acceleration
    steps:
      - name: Run benchmark tests
        run: ./gradlew connectedBenchmarkAndroidTest
```

### Testing Strategy

#### Unit Testing (70% coverage target)
- **Bluetooth Module:** Mock InputManager, verify event normalization
- **Protocol Module:** Binary serialization/deserialization correctness
- **Network Module:** TCP server behavior, queue management
- **UI Module:** State management, user interactions

#### Integration Testing (20% coverage target)
- **End-to-End Flow:** Simulated controller → network client
- **Connection Recovery:** Fault injection for Bluetooth/TCP failures
- **Performance Testing:** Latency measurement, throughput validation

#### Manual Testing (10% coverage target)
- **Device Compatibility:** Multiple Android versions, hardware variants
- **Real Controller Testing:** Actual PS5 DualSense across usage scenarios
- **User Experience:** Navigation flow, error state handling

### Deployment Workflow

#### Release Process
1. **Version Preparation:**
   ```bash
   git checkout develop
   git checkout -b release/v1.1.0
   # Update version codes in build.gradle
   # Update CHANGELOG.md
   # Final testing and bug fixes
   ```

2. **Release Build:**
   ```bash
   # Generate signed APK
   ./gradlew assembleRelease
   # Upload to Play Store internal track
   # Staged rollout: 1% → 10% → 50% → 100%
   ```

3. **Post-Release:**
   ```bash
   git checkout main
   git merge release/v1.1.0
   git tag v1.1.0
   git checkout develop
   git merge main
   ```

#### Distribution Channels
- **Primary:** Google Play Store (closed beta → open beta → production)
- **Secondary:** GitHub Releases (APK direct download)
- **Development:** Firebase App Distribution for internal testing

#### Monitoring & Analytics
- **Crash Reporting:** Firebase Crashlytics
- **Performance Monitoring:** Firebase Performance
- **User Analytics:** Firebase Analytics (privacy-compliant)
- **Custom Metrics:** Relay uptime, latency distributions, error rates

---

## Folder-Structure.md - Project Organization

### Android Project Structure
```
relay-app/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/noahlangat/relay/
│   │   │   │   ├── bluetooth/
│   │   │   │   │   ├── BluetoothManager.kt
│   │   │   │   │   ├── GamepadInputHandler.kt
│   │   │   │   │   ├── DeviceDiscovery.kt
│   │   │   │   │   └── ConnectionStateMachine.kt
│   │   │   │   ├── network/
│   │   │   │   │   ├── TcpServer.kt
│   │   │   │   │   ├── ClientConnection.kt
│   │   │   │   │   ├── SendQueue.kt
│   │   │   │   │   └── AuthenticationManager.kt
│   │   │   │   ├── protocol/
│   │   │   │   │   ├── GamepadState.kt
│   │   │   │   │   ├── MessageSerializer.kt
│   │   │   │   │   ├── ProtocolConstants.kt
│   │   │   │   │   └── MessageTypes.kt
│   │   │   │   ├── ui/
│   │   │   │   │   ├── main/
│   │   │   │   │   │   ├── MainActivity.kt
│   │   │   │   │   │   ├── MainViewModel.kt
│   │   │   │   │   │   └── MainFragment.kt
│   │   │   │   │   ├── settings/
│   │   │   │   │   │   ├── SettingsActivity.kt
│   │   │   │   │   │   ├── SettingsViewModel.kt
│   │   │   │   │   │   └── SettingsFragment.kt
│   │   │   │   │   ├── logs/
│   │   │   │   │   │   ├── LogViewerFragment.kt
│   │   │   │   │   │   ├── LogAdapter.kt
│   │   │   │   │   │   └── LogEntry.kt
│   │   │   │   │   └── common/
│   │   │   │   │       ├── BaseActivity.kt
│   │   │   │   │       ├── BaseViewModel.kt
│   │   │   │   │       └── ViewBindingExtensions.kt
│   │   │   │   ├── service/
│   │   │   │   │   ├── RelayService.kt
│   │   │   │   │   ├── ServiceNotificationManager.kt
│   │   │   │   │   └── RelayServiceConnection.kt
│   │   │   │   ├── data/
│   │   │   │   │   ├── preferences/
│   │   │   │   │   │   ├── PreferencesManager.kt
│   │   │   │   │   │   └── PreferenceKeys.kt
│   │   │   │   │   ├── logging/
│   │   │   │   │   │   ├── LogManager.kt
│   │   │   │   │   │   ├── LogLevel.kt
│   │   │   │   │   │   └── FileLogger.kt
│   │   │   │   │   └── repository/
│   │   │   │   │       └── RelayRepository.kt
│   │   │   │   ├── utils/
│   │   │   │   │   ├── Extensions.kt
│   │   │   │   │   ├── Constants.kt
│   │   │   │   │   ├── NetworkUtils.kt
│   │   │   │   │   └── PermissionUtils.kt
│   │   │   │   └── di/
│   │   │   │       ├── AppModule.kt
│   │   │   │       ├── NetworkModule.kt
│   │   │   │       └── BluetoothModule.kt
│   │   │   ├── res/
│   │   │   │   ├── layout/
│   │   │   │   │   ├── activity_main.xml
│   │   │   │   │   ├── fragment_main.xml
│   │   │   │   │   ├── fragment_settings.xml
│   │   │   │   │   └── fragment_log_viewer.xml
│   │   │   │   ├── values/
│   │   │   │   │   ├── strings.xml
│   │   │   │   │   ├── colors.xml
│   │   │   │   │   ├── dimens.xml
│   │   │   │   │   └── styles.xml
│   │   │   │   ├── drawable/
│   │   │   │   ├── mipmap/
│   │   │   │   └── xml/
│   │   │   │       ├── network_security_config.xml
│   │   │   │       └── preferences.xml
│   │   │   └── AndroidManifest.xml
│   │   ├── test/ (Unit tests)
│   │   │   └── java/com/noahlangat/relay/
│   │   │       ├── bluetooth/
│   │   │       │   ├── BluetoothManagerTest.kt
│   │   │       │   └── GamepadInputHandlerTest.kt
│   │   │       ├── network/
│   │   │       │   ├── TcpServerTest.kt
│   │   │       │   └── SendQueueTest.kt
│   │   │       ├── protocol/
│   │   │       │   ├── MessageSerializerTest.kt
│   │   │       │   └── GamepadStateTest.kt
│   │   │       └── service/
│   │   │           └── RelayServiceTest.kt
│   │   └── androidTest/ (Integration tests)
│   │       └── java/com/noahlangat/relay/
│   │           ├── EndToEndTest.kt
│   │           ├── BluetoothIntegrationTest.kt
│   │           ├── NetworkIntegrationTest.kt
│   │           └── ui/
│   │               ├── MainActivityTest.kt
│   │               └── SettingsActivityTest.kt
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── buildSrc/ (Build logic)
│   ├── src/main/kotlin/
│   │   ├── Dependencies.kt
│   │   ├── Versions.kt
│   │   └── BuildConfig.kt
│   └── build.gradle.kts
├── docs/
│   ├── architecture/
│   │   ├── bluetooth-architecture.md
│   │   ├── network-architecture.md
│   │   └── ui-architecture.md
│   ├── api/
│   │   ├── protocol-specification.md
│   │   └── client-integration-guide.md
│   └── deployment/
│       ├── build-instructions.md
│       └── release-checklist.md
├── scripts/
│   ├── setup-dev-env.sh
│   ├── run-tests.sh
│   ├── build-release.sh
│   └── deploy-internal.sh
├── tools/
│   ├── protocol-validator/
│   │   ├── validate_messages.py
│   │   └── test_client.py
│   └── performance-profiler/
│       ├── latency_test.py
│       └── throughput_test.py
├── .github/
│   ├── workflows/
│   │   ├── ci.yml
│   │   ├── release.yml
│   │   └── security-scan.yml
│   ├── ISSUE_TEMPLATE/
│   └── pull_request_template.md
├── gradle/
│   └── wrapper/
├── build.gradle.kts
├── gradle.properties
├── settings.gradle.kts
├── README.md
├── CHANGELOG.md
├── LICENSE
└── .gitignore
```

### Module Responsibilities

#### Core Modules
- **bluetooth/**: Bluetooth device discovery, connection management, input handling
- **network/**: TCP server implementation, client connection management, auth
- **protocol/**: Message serialization, protocol constants, data structures
- **service/**: Android foreground service, lifecycle management, notifications

#### UI Modules  
- **ui/main/**: Primary activity, connection status, real-time stats
- **ui/settings/**: Configuration screens, preferences management
- **ui/logs/**: Log viewer, filtering, export functionality
- **ui/common/**: Shared UI components, base classes, extensions

#### Data Modules
- **data/preferences/**: Settings persistence via DataStore
- **data/logging/**: Structured logging, file management
- **data/repository/**: Data layer abstraction, caching

#### Support Modules
- **utils/**: Common utilities, extensions, constants
- **di/**: Dependency injection configuration (Hilt/Dagger)

### File Naming Conventions
- **Activities:** `MainActivity.kt`, `SettingsActivity.kt`
- **Fragments:** `MainFragment.kt`, `LogViewerFragment.kt`  
- **ViewModels:** `MainViewModel.kt`, `SettingsViewModel.kt`
- **Services:** `RelayService.kt`, `NotificationService.kt`
- **Managers:** `BluetoothManager.kt`, `NetworkManager.kt`
- **Data Classes:** `GamepadState.kt`, `ConnectionInfo.kt`
- **Utilities:** `NetworkUtils.kt`, `PermissionUtils.kt`
- **Constants:** `ProtocolConstants.kt`, `AppConstants.kt`

### Configuration Files
- **Gradle:** Kotlin DSL preferred (`.gradle.kts`)
- **Manifest:** Single `AndroidManifest.xml` with permission declarations
- **Resources:** Organized by type, prefixed by module/feature
- **ProGuard:** Separate rules file for release optimization
- **Network Security:** XML config for TLS/cleartext policies

### Documentation Structure  
- **Architecture Docs:** High-level system design, module interactions
- **API Documentation:** Protocol specification, client integration
- **Deployment Guides:** Build process, release management
- **Developer Onboarding:** Setup instructions, coding standards

This folder structure supports:
- **Modular Development:** Clear separation of concerns
- **Testing Strategy:** Parallel test structure for easy navigation  
- **Build Optimization:** Gradle configuration centralization
- **Documentation:** Comprehensive project documentation
- **CI/CD Integration:** Automation-friendly organization