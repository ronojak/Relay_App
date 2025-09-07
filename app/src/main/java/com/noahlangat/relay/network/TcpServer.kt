package com.noahlangat.relay.network

import com.noahlangat.relay.protocol.ProtocolConstants
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger

/**
 * TCP server for single-client gamepad relay connections
 */
class TcpServer(
    private val port: Int = ProtocolConstants.DEFAULT_TCP_PORT,
    private val scope: CoroutineScope
) {
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var currentClient: ClientConnection? = null
    
    private val _serverState = MutableStateFlow(ServerState.STOPPED)
    val serverState: StateFlow<ServerState> = _serverState
    
    private val _serverStats = MutableStateFlow(ServerStats())
    val serverStats: StateFlow<ServerStats> = _serverStats
    
    private val sequenceNumber = AtomicInteger(0)
    
    enum class ServerState {
        STOPPED,
        STARTING,
        LISTENING,
        CLIENT_CONNECTED,
        ERROR
    }
    
    data class ServerStats(
        val port: Int = 0,
        val uptime: Long = 0,
        val totalConnections: Int = 0,
        val currentClient: String? = null,
        val messagesSent: Long = 0,
        val bytesTransmitted: Long = 0,
        val errors: Int = 0
    )
    
    /**
     * Start the TCP server
     */
    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        if (_serverState.value != ServerState.STOPPED) {
            Timber.w("Server already running on port $port")
            return@withContext false
        }
        
        _serverState.value = ServerState.STARTING
        
        try {
            serverSocket = ServerSocket(port).apply {
                // Allow address reuse
                reuseAddress = true
                // Set timeout for accept calls
                soTimeout = 0 // No timeout, blocking accept
            }
            
            _serverState.value = ServerState.LISTENING
            updateStats { stats ->
                stats.copy(
                    port = port,
                    uptime = System.currentTimeMillis()
                )
            }
            
            startAcceptLoop()
            Timber.i("TCP server started on port $port")
            return@withContext true
            
        } catch (e: IOException) {
            Timber.e(e, "Failed to start TCP server on port $port")
            _serverState.value = ServerState.ERROR
            updateStats { stats -> stats.copy(errors = stats.errors + 1) }
            return@withContext false
        }
    }
    
    /**
     * Stop the TCP server
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        if (_serverState.value == ServerState.STOPPED) {
            return@withContext
        }
        
        Timber.i("Stopping TCP server")
        
        // Disconnect current client
        currentClient?.disconnect()
        currentClient = null
        
        // Cancel accept loop
        acceptJob?.cancel()
        
        // Close server socket
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Timber.w(e, "Error closing server socket")
        }
        
        serverSocket = null
        _serverState.value = ServerState.STOPPED
        
        updateStats { stats ->
            stats.copy(currentClient = null)
        }
        
        Timber.i("TCP server stopped")
    }
    
    private fun startAcceptLoop() {
        acceptJob = scope.launch(Dispatchers.IO) {
            while (_serverState.value != ServerState.STOPPED) {
                try {
                    val clientSocket = serverSocket?.accept()
                    if (clientSocket != null) {
                        handleNewClient(clientSocket)
                    }
                } catch (e: SocketException) {
                    if (_serverState.value != ServerState.STOPPED) {
                        Timber.e(e, "Accept loop socket error")
                        _serverState.value = ServerState.ERROR
                        break
                    }
                } catch (e: IOException) {
                    if (_serverState.value != ServerState.STOPPED) {
                        Timber.e(e, "Accept loop IO error")
                        updateStats { stats -> stats.copy(errors = stats.errors + 1) }
                        delay(1000) // Brief delay before retry
                    }
                }
            }
        }
    }
    
    private suspend fun handleNewClient(clientSocket: java.net.Socket) {
        // Disconnect existing client (single client model)
        currentClient?.disconnect()
        
        val clientAddress = clientSocket.remoteSocketAddress?.toString() ?: "unknown"
        Timber.i("New client connecting: $clientAddress")
        
        try {
            // Create new client connection
            currentClient = ClientConnection(clientSocket, scope)
            _serverState.value = ServerState.CLIENT_CONNECTED
            
            updateStats { stats ->
                stats.copy(
                    totalConnections = stats.totalConnections + 1,
                    currentClient = clientAddress
                )
            }
            
            // Monitor client connection
            monitorClient(currentClient!!)
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to setup client connection: $clientAddress")
            try {
                clientSocket.close()
            } catch (closeError: IOException) {
                Timber.w(closeError, "Error closing failed client socket")
            }
            updateStats { stats -> stats.copy(errors = stats.errors + 1) }
        }
    }
    
    private fun monitorClient(client: ClientConnection) {
        scope.launch {
            // Monitor client connection state
            client.connectionState.collect { state ->
                when (state) {
                    ClientConnection.ConnectionState.DISCONNECTED,
                    ClientConnection.ConnectionState.ERROR -> {
                        if (currentClient == client) {
                            currentClient = null
                            _serverState.value = ServerState.LISTENING
                            updateStats { stats ->
                                stats.copy(currentClient = null)
                            }
                            Timber.i("Client disconnected, server returning to listening state")
                        }
                    }
                    else -> { /* No action needed */ }
                }
            }
        }
        
        scope.launch {
            // Monitor client stats
            client.stats.collect { clientStats ->
                updateStats { serverStats ->
                    serverStats.copy(
                        messagesSent = clientStats.messagesSent,
                        bytesTransmitted = clientStats.bytesSent
                    )
                }
            }
        }
    }
    
    /**
     * Send message to connected client
     */
    suspend fun sendToClient(message: ByteArray): Boolean {
        val client = currentClient
        return if (client?.isConnected() == true) {
            client.sendMessage(message)
            true
        } else {
            Timber.v("No client connected, message discarded")
            false
        }
    }
    
    /**
     * Broadcast gamepad state to connected client
     */
    suspend fun broadcastGamepadState(gamepadStateMessage: ByteArray): Boolean {
        return sendToClient(gamepadStateMessage)
    }
    
    /**
     * Check if server is running
     */
    fun isRunning(): Boolean {
        return _serverState.value in listOf(ServerState.LISTENING, ServerState.CLIENT_CONNECTED)
    }
    
    /**
     * Check if client is connected
     */
    fun hasConnectedClient(): Boolean {
        return _serverState.value == ServerState.CLIENT_CONNECTED && currentClient?.isConnected() == true
    }
    
    /**
     * Get current client connection info
     */
    fun getCurrentClientInfo(): String? {
        return currentClient?.clientAddress
    }
    
    /**
     * Get next sequence number for messages
     */
    fun getNextSequenceNumber(): Int = sequenceNumber.incrementAndGet()
    
    private inline fun updateStats(update: (ServerStats) -> ServerStats) {
        _serverStats.value = update(_serverStats.value)
    }
    
    companion object {
        private const val TAG = "TcpServer"
    }
}