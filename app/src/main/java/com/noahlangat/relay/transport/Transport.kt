package com.noahlangat.relay.transport

import com.noahlangat.relay.protocol.GamepadState
import com.noahlangat.relay.ui.components.LogMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Lifecycle/health of a single relay link, independent of its transport type.
 *
 *  - [IDLE]     not started
 *  - [STARTING] coming up
 *  - [READY]    started, no peer/input yet (e.g. listening, or BT enabled but no controller)
 *  - [ACTIVE]   a peer is connected / input is flowing
 *  - [ERROR]    failed
 */
enum class LinkState { IDLE, STARTING, READY, ACTIVE, ERROR }

/** Common surface shared by every relay endpoint. */
interface RelayTransport {
    /** Stable identifier for this transport implementation. */
    val id: String

    /** Human-readable label for the UI. */
    val displayName: String

    val state: StateFlow<LinkState>

    /** Transport-specific log events to surface in the relay log. */
    val logs: SharedFlow<LogMessage>
}

/**
 * A **secondary / input** connection: the controller side. Produces a stream of
 * [GamepadState] frames to be relayed. Today this is a local Bluetooth gamepad.
 */
interface SourceTransport : RelayTransport {
    /** Stream of input frames to relay. */
    val frames: Flow<GamepadState>

    /** Number of currently active inputs (e.g. connected controllers). */
    val activeInputs: StateFlow<Int>

    /** Begin producing frames / monitoring, using [scope] for background work. */
    fun start(scope: CoroutineScope)

    fun stop()
}

/**
 * A **primary / output** connection: the peripheral side (e.g. an MCU). Consumes
 * serialized frames and delivers them to the peer. Today this is a TCP server the
 * peripheral dials into over WiFi.
 */
interface SinkTransport : RelayTransport {
    /** Description of the connected peer, if any (for the UI). */
    val peerInfo: String?

    /** Bring the sink up. Returns false on failure. */
    suspend fun start(): Boolean

    /** Deliver one serialized frame. Returns false if it could not be sent. */
    suspend fun send(frame: ByteArray): Boolean

    suspend fun stop()
}
