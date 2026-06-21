package com.noahlangat.relay.transport

import com.noahlangat.relay.network.TcpServer
import com.noahlangat.relay.protocol.ProtocolConstants
import com.noahlangat.relay.ui.components.LogLevel
import com.noahlangat.relay.ui.components.LogMessage
import com.noahlangat.relay.ui.components.LogSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Primary/output transport backed by [TcpServer]: the peripheral (MCU) dials into
 * the phone over WiFi and we push serialized frames to it.
 */
class TcpServerSink(
    private val scope: CoroutineScope,
    private val port: Int = ProtocolConstants.DEFAULT_TCP_PORT
) : SinkTransport {

    override val id = "wifi-tcp-server"
    override val displayName = "WiFi (TCP :$port)"

    private val tcpServer = TcpServer(port = port, scope = scope)

    private val _state = MutableStateFlow(LinkState.IDLE)
    override val state: StateFlow<LinkState> = _state

    private val _logs = MutableSharedFlow<LogMessage>(extraBufferCapacity = 64)
    override val logs: SharedFlow<LogMessage> = _logs

    override val peerInfo: String? get() = tcpServer.getCurrentClientInfo()

    private var monitorJob: Job? = null

    override suspend fun start(): Boolean {
        _state.value = LinkState.STARTING
        if (!tcpServer.start()) {
            _state.value = LinkState.ERROR
            return false
        }

        monitorJob = scope.launch {
            var previousConnected = false
            tcpServer.serverState.collect { serverState ->
                _state.value = serverState.toLinkState()

                val connected = serverState == TcpServer.ServerState.CLIENT_CONNECTED
                if (connected != previousConnected) {
                    emitLog(
                        if (connected) "Network client connected to TCP server"
                        else "Network client disconnected from TCP server"
                    )
                    previousConnected = connected
                }
            }
        }
        return true
    }

    override suspend fun send(frame: ByteArray): Boolean = tcpServer.broadcastGamepadState(frame)

    override suspend fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        tcpServer.stop()
        _state.value = LinkState.IDLE
    }

    private fun emitLog(message: String) {
        _logs.tryEmit(
            LogMessage(message = message, level = LogLevel.INFO, source = LogSource.NETWORK)
        )
    }

    private fun TcpServer.ServerState.toLinkState(): LinkState = when (this) {
        TcpServer.ServerState.STOPPED -> LinkState.IDLE
        TcpServer.ServerState.STARTING -> LinkState.STARTING
        TcpServer.ServerState.LISTENING -> LinkState.READY
        TcpServer.ServerState.CLIENT_CONNECTED -> LinkState.ACTIVE
        TcpServer.ServerState.ERROR -> LinkState.ERROR
    }
}
