package com.noahlangat.relay.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.noahlangat.relay.ui.components.LogLevel
import com.noahlangat.relay.ui.components.LogMessage
import com.noahlangat.relay.ui.components.LogSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Primary/output transport that dials OUT to a peripheral (MCU) acting as the
 * server, e.g. `relay_test_server.py --role sink` on a PC. Maintains the
 * connection with automatic reconnect/backoff and streams serialized frames.
 *
 * The socket is bound to the Wi-Fi/Ethernet network when available, so a LAN
 * peripheral stays reachable even if the default route is cellular (a common
 * cause of "connect fails although the IP is reachable from a PC").
 */
class TcpClientSink(
    private val context: Context,
    private val scope: CoroutineScope,
    private val host: String,
    private val port: Int
) : SinkTransport {

    override val id = "wifi-tcp-client"
    override val displayName = "WiFi → $host:$port"

    private val _state = MutableStateFlow(LinkState.IDLE)
    override val state: StateFlow<LinkState> = _state

    private val _logs = MutableSharedFlow<LogMessage>(extraBufferCapacity = 64)
    override val logs: SharedFlow<LogMessage> = _logs

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var output: OutputStream? = null

    private var connectJob: Job? = null

    override val peerInfo: String? get() = if (output != null) "$host:$port" else null

    override suspend fun start(): Boolean {
        if (host.isBlank()) {
            _state.value = LinkState.ERROR
            emitLog("Primary host not configured (set the MCU/server IP in Settings)", LogLevel.ERROR)
            return false
        }
        _state.value = LinkState.STARTING
        connectJob = scope.launch { connectLoop() }
        return true
    }

    private suspend fun connectLoop() {
        var backoffMs = INITIAL_BACKOFF_MS
        while (scope.isActive) {
            if (output == null) {
                try {
                    _state.value = LinkState.STARTING
                    val remote = InetSocketAddress(host, port)
                    val resolved = remote.address?.hostAddress ?: host
                    emitLog("Dialing $resolved:${remote.port}" + if (remote.isUnresolved) " (UNRESOLVED)" else "")
                    val s = withContext(Dispatchers.IO) {
                        Socket().apply {
                            tcpNoDelay = true
                            maybeBindToLan(this)
                            connect(remote, CONNECT_TIMEOUT_MS)
                        }
                    }
                    socket = s
                    output = withContext(Dispatchers.IO) { s.getOutputStream() }
                    _state.value = LinkState.ACTIVE
                    emitLog("Connected to $host:$port")
                    backoffMs = INITIAL_BACKOFF_MS
                } catch (e: Exception) {
                    closeQuietly()
                    _state.value = LinkState.READY
                    emitLog("Connect to $host:$port failed: ${e.message}; retrying in ${backoffMs}ms", LogLevel.WARN)
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                    continue
                }
            }
            delay(POLL_MS)
        }
    }

    /**
     * Bind [socket] to a Wi-Fi/Ethernet network ONLY when the default route is not
     * already LAN-capable (i.e. cellular is the default). When the phone is on
     * Wi-Fi we leave the socket unbound so it behaves like an ordinary client and
     * uses normal routing.
     */
    private fun maybeBindToLan(socket: Socket) {
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
            val activeCaps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val activeIsLan = activeCaps?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } == true
            if (activeIsLan) {
                emitLog("Using default (Wi-Fi/Ethernet) route", LogLevel.DEBUG)
                return
            }
            val lan = cm.allNetworks.firstOrNull { network ->
                cm.getNetworkCapabilities(network)?.let { caps ->
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                } == true
            }
            if (lan != null) {
                lan.bindSocket(socket)
                emitLog("Bound socket to Wi-Fi/Ethernet network (default was cellular)", LogLevel.DEBUG)
            }
        }
    }

    override suspend fun send(frame: ByteArray): Boolean {
        val out = output ?: return false
        return try {
            withContext(Dispatchers.IO) {
                out.write(frame)
                out.flush()
            }
            true
        } catch (e: Exception) {
            emitLog("Send failed: ${e.message}; reconnecting", LogLevel.WARN)
            closeQuietly()
            _state.value = LinkState.READY
            false
        }
    }

    override suspend fun stop() {
        connectJob?.cancel()
        connectJob = null
        closeQuietly()
        _state.value = LinkState.IDLE
    }

    private fun closeQuietly() {
        runCatching { output?.close() }
        runCatching { socket?.close() }
        output = null
        socket = null
    }

    private fun emitLog(message: String, level: LogLevel = LogLevel.INFO) {
        _logs.tryEmit(LogMessage(message = message, level = level, source = LogSource.NETWORK))
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val INITIAL_BACKOFF_MS = 500L
        private const val MAX_BACKOFF_MS = 10_000L
        private const val POLL_MS = 250L
    }
}
