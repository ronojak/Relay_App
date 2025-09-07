package com.noahlangat.relay.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handles serialization and deserialization of protocol messages
 */
object MessageSerializer {
    
    /**
     * Serialize a complete message (envelope + payload)
     */
    fun serializeGamepadMessage(gamepadState: GamepadState, sequenceNumber: Int): ByteArray {
        val envelope = MessageEnvelope.createForGamepadState(gamepadState, sequenceNumber)
        val payload = gamepadState.toByteArray()
        
        val totalSize = envelope.getTotalSize()
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        
        buffer.put(envelope.toByteArray())
        buffer.put(payload)
        
        return buffer.array()
    }
    
    /**
     * Deserialize a complete message
     */
    fun deserializeMessage(data: ByteArray): Pair<MessageEnvelope, ByteArray> {
        require(data.size >= ProtocolConstants.ENVELOPE_SIZE) {
            "Message too short: ${data.size} bytes"
        }
        
        val envelope = MessageEnvelope.fromByteArray(data)
        require(envelope.isValid()) { "Invalid message envelope" }
        
        val expectedSize = envelope.getTotalSize()
        require(data.size >= expectedSize) {
            "Message incomplete: expected $expectedSize bytes, got ${data.size}"
        }
        
        val payload = data.copyOfRange(ProtocolConstants.ENVELOPE_SIZE, expectedSize)
        
        return Pair(envelope, payload)
    }
    
    /**
     * Deserialize specifically a GamepadState message
     */
    fun deserializeGamepadMessage(data: ByteArray): Pair<MessageEnvelope, GamepadState> {
        val (envelope, payload) = deserializeMessage(data)
        
        require(envelope.messageType == ProtocolConstants.MSG_GAMEPAD_STATE) {
            "Expected GamepadState message, got type: ${envelope.messageType}"
        }
        
        val gamepadState = GamepadState.fromByteArray(payload)
        return Pair(envelope, gamepadState)
    }
    
    /**
     * Create a ping message
     */
    fun createPingMessage(sequenceNumber: Int): ByteArray {
        val envelope = MessageEnvelope(
            messageType = ProtocolConstants.MSG_PING,
            length = 0,
            sequenceNumber = sequenceNumber,
            timestampMicros = System.currentTimeMillis() * 1000
        )
        
        return envelope.toByteArray()
    }
    
    /**
     * Validate message integrity without full deserialization
     */
    fun validateMessageHeader(data: ByteArray): Boolean {
        if (data.size < ProtocolConstants.ENVELOPE_SIZE) return false
        
        return try {
            val envelope = MessageEnvelope.fromByteArray(data)
            envelope.isValid() && data.size >= envelope.getTotalSize()
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Extract message type without full deserialization
     */
    fun getMessageType(data: ByteArray): Byte? {
        return if (data.isNotEmpty()) data[0] else null
    }
    
    /**
     * Calculate expected message size from header
     */
    fun getExpectedMessageSize(headerData: ByteArray): Int? {
        if (headerData.size < ProtocolConstants.ENVELOPE_SIZE) return null
        
        return try {
            val envelope = MessageEnvelope.fromByteArray(headerData)
            if (envelope.isValid()) envelope.getTotalSize() else null
        } catch (e: Exception) {
            null
        }
    }
}