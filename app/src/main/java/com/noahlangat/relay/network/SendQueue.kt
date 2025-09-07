package com.noahlangat.relay.network

import com.noahlangat.relay.protocol.ProtocolConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded queue with drop-oldest policy for managing gamepad state transmission.
 * Prevents memory exhaustion under backpressure conditions.
 */
class SendQueue(
    private val capacity: Int = ProtocolConstants.MAX_SEND_QUEUE_SIZE
) {
    private val queue = ConcurrentLinkedQueue<ByteArray>()
    private val currentSize = AtomicInteger(0)
    private val mutex = Mutex()
    
    // Metrics
    private val packetsOffered = AtomicLong(0)
    private val packetsDropped = AtomicLong(0)
    private val packetsTransmitted = AtomicLong(0)
    
    private val _queueStats = MutableStateFlow(QueueStats())
    val queueStats: StateFlow<QueueStats> = _queueStats
    
    data class QueueStats(
        val currentSize: Int = 0,
        val packetsOffered: Long = 0,
        val packetsDropped: Long = 0,
        val packetsTransmitted: Long = 0,
        val dropRate: Float = 0.0f
    )
    
    /**
     * Add message to queue with drop-oldest policy
     * Returns true if added, false if dropped due to duplicate suppression
     */
    suspend fun offer(message: ByteArray): Boolean = mutex.withLock {
        packetsOffered.incrementAndGet()
        
        // Drop oldest messages if at capacity
        while (currentSize.get() >= capacity) {
            val dropped = queue.poll()
            if (dropped != null) {
                currentSize.decrementAndGet()
                packetsDropped.incrementAndGet()
                Timber.d("Dropped oldest packet, queue size: ${currentSize.get()}")
            } else {
                break
            }
        }
        
        // Add new message
        queue.offer(message)
        currentSize.incrementAndGet()
        
        updateStats()
        
        Timber.v("Queued packet, size: ${currentSize.get()}")
        return true
    }
    
    /**
     * Remove and return next message from queue
     */
    suspend fun poll(): ByteArray? = mutex.withLock {
        val message = queue.poll()
        if (message != null) {
            currentSize.decrementAndGet()
            packetsTransmitted.incrementAndGet()
            updateStats()
            Timber.v("Dequeued packet, size: ${currentSize.get()}")
        }
        return message
    }
    
    /**
     * Peek at next message without removing
     */
    fun peek(): ByteArray? = queue.peek()
    
    /**
     * Get current queue size
     */
    fun size(): Int = currentSize.get()
    
    /**
     * Check if queue is empty
     */
    fun isEmpty(): Boolean = queue.isEmpty()
    
    /**
     * Check if queue is at capacity
     */
    fun isFull(): Boolean = currentSize.get() >= capacity
    
    /**
     * Clear all messages from queue
     */
    suspend fun clear() = mutex.withLock {
        val cleared = currentSize.get()
        queue.clear()
        currentSize.set(0)
        updateStats()
        Timber.d("Cleared $cleared packets from queue")
    }
    
    /**
     * Get comprehensive queue statistics
     */
    fun getDetailedStats(): QueueStats {
        return QueueStats(
            currentSize = currentSize.get(),
            packetsOffered = packetsOffered.get(),
            packetsDropped = packetsDropped.get(),
            packetsTransmitted = packetsTransmitted.get(),
            dropRate = calculateDropRate()
        )
    }
    
    private fun calculateDropRate(): Float {
        val offered = packetsOffered.get()
        val dropped = packetsDropped.get()
        return if (offered > 0) (dropped.toFloat() / offered) * 100f else 0.0f
    }
    
    private fun updateStats() {
        _queueStats.value = getDetailedStats()
    }
    
    /**
     * Reset all statistics
     */
    fun resetStats() {
        packetsOffered.set(0)
        packetsDropped.set(0)
        packetsTransmitted.set(0)
        updateStats()
        Timber.d("Queue statistics reset")
    }
    
    companion object {
        private const val TAG = "SendQueue"
    }
}