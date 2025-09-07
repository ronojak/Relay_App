package com.noahlangat.relay.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.IOException
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Manages individual TCP client connections with proper lifecycle and error handling
 */
class ClientConnection(
    private val socket: Socket,
    private val scope: CoroutineScope
) {
    private val sendQueue = SendQueue()
    private var sendJob: Job? = null
    private var receiveJob: Job? = null
    
    private val _connectionState = MutableStateFlow(ConnectionState.CONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState
    
    private val _stats = MutableStateFlow(ConnectionStats())
    val stats: StateFlow<ConnectionStats> = _stats
    
    val clientAddress: String = socket.remoteSocketAddress?.toString() ?: "unknown"
    
    enum class ConnectionState {
        CONNECTED,
        DISCONNECTED,
        ERROR
    }
    
    data class ConnectionStats(
        val bytesSent: Long = 0,
        val bytesReceived: Long = 0,
        val messagesSent: Long = 0,
        val messagesReceived: Long = 0,
        val errors: Long = 0,
        val connectionTimeMs: Long = System.currentTimeMillis(),
        val lastActivityMs: Long = System.currentTimeMillis()
    )
    
    init {
        configureSocket()
        startSendLoop()
        startReceiveLoop()
        Timber.i("Client connection established: $clientAddress")
    }
    
    private fun configureSocket() {
        try {
            // Enable TCP_NODELAY for low latency
            socket.tcpNoDelay = true
            
            // Configure keepalive
            socket.keepAlive = true
            
            // Set send buffer size
            socket.sendBufferSize = 64 * 1024 // 64KB
            
            // Set receive buffer size
            socket.receiveBufferSize = 8 * 1024 // 8KB
            
            // Set socket timeout for reads
            socket.soTimeout = 0 // 60 seconds
            
            Timber.d("Socket configured for client: $clientAddress")
        } catch (e: Exception) {
            Timber.e(e, "Failed to configure socket for client: $clientAddress")
            disconnect()
        }
    }
    
    /**
     * Send message to client (queued for transmission)
     */
    suspend fun sendMessage(message: ByteArray) {
        if (_connectionState.value == ConnectionState.CONNECTED) {
            sendQueue.offer(message)
        }
    }
    
    private fun startSendLoop() {
        sendJob = scope.launch(Dispatchers.IO) {
            try {
                val outputStream = socket.getOutputStream()
                
                while (_connectionState.value == ConnectionState.CONNECTED && socket.isConnected) {
                    val message = sendQueue.poll()
                    if (message != null) {
                        try {
                            outputStream.write(message)
                            outputStream.flush()
                            
                            updateStats { stats ->
                                stats.copy(
                                    bytesSent = stats.bytesSent + message.size,
                                    messagesSent = stats.messagesSent + 1,
                                    lastActivityMs = System.currentTimeMillis()
                                )
                            }
                            
                            Timber.v("Sent ${message.size} bytes to client: $clientAddress")
                        } catch (e: IOException) {
                            Timber.e(e, "Send failed for client: $clientAddress")
                            handleError(e)
                            break
                        }
                    } else {
                        // No messages to send, brief delay
                        delay(1)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Send loop error for client: $clientAddress")
                handleError(e)
            }
        }
    }
    
    private fun startReceiveLoop() {
        receiveJob = scope.launch(Dispatchers.IO) {
            try {
                val inputStream = socket.getInputStream()
                val buffer = ByteArray(1024)
                
                while (_connectionState.value == ConnectionState.CONNECTED && socket.isConnected) {
                    try {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead > 0) {
                            updateStats { stats ->
                                stats.copy(
                                    bytesReceived = stats.bytesReceived + bytesRead,
                                    messagesReceived = stats.messagesReceived + 1,
                                    lastActivityMs = System.currentTimeMillis()
                                )
                            }
                            
                            Timber.v("Received $bytesRead bytes from client: $clientAddress")
                            // Handle received data (ping responses, etc.)
                            handleReceivedData(buffer.copyOf(bytesRead))
                        } else if (bytesRead == -1) {
                            Timber.i("Client disconnected: $clientAddress")
                            break
                        }
                    } catch (e: SocketException) {
                        if (_connectionState.value == ConnectionState.CONNECTED) {
                            Timber.w(e, "Socket exception for client: $clientAddress")
                            break
                        }
                    } catch (e: IOException) {
                        Timber.e(e, "Receive error for client: $clientAddress")
                        handleError(e)
                        break
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Receive loop error for client: $clientAddress")
                handleError(e)
            }
        }
    }
    
    private fun handleReceivedData(data: ByteArray) {
        // Process received messages (auth responses, ping responses, etc.)
        // This is a placeholder for future protocol extensions
        Timber.d("Received data from client $clientAddress: ${data.size} bytes")
    }
    
    private fun handleError(error: Throwable) {
        updateStats { stats ->
            stats.copy(errors = stats.errors + 1)
        }
        _connectionState.value = ConnectionState.ERROR
        disconnect()
    }
    
    /**
     * Gracefully disconnect the client
     */
    fun disconnect() {
        if (_connectionState.value != ConnectionState.DISCONNECTED) {
            _connectionState.value = ConnectionState.DISCONNECTED
            
            // Cancel coroutines
            sendJob?.cancel()
            receiveJob?.cancel()
            
            // Clear send queue
            scope.launch {
                sendQueue.clear()
            }
            
            // Close socket
            try {
                socket.close()
            } catch (e: IOException) {
                Timber.w(e, "Error closing socket for client: $clientAddress")
            }
            
            Timber.i("Client disconnected: $clientAddress")
        }
    }
    
    /**
     * Check if connection is active
     */
    fun isConnected(): Boolean {
        return _connectionState.value == ConnectionState.CONNECTED && socket.isConnected
    }
    
    /**
     * Get send queue statistics
     */
    fun getQueueStats() = sendQueue.getDetailedStats()
    
    private inline fun updateStats(update: (ConnectionStats) -> ConnectionStats) {
        _stats.value = update(_stats.value)
    }
    
    companion object {
        private const val TAG = "ClientConnection"
    }
}