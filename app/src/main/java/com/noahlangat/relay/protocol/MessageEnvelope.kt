package com.noahlangat.relay.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary message envelope for the relay protocol.
 * Provides a fixed 14-byte header for all message types.
 */
data class MessageEnvelope(
    val messageType: Byte,
    val version: Byte = ProtocolConstants.PROTOCOL_VERSION,
    val length: Short, // Payload length
    val sequenceNumber: Int,
    val timestampMicros: Long
) {
    
    companion object {
        fun fromByteArray(data: ByteArray): MessageEnvelope {
            require(data.size >= ProtocolConstants.ENVELOPE_SIZE) {
                "Invalid envelope data size: ${data.size}"
            }
            
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            
            return MessageEnvelope(
                messageType = buffer.get(),
                version = buffer.get(),
                length = buffer.short,
                sequenceNumber = buffer.int,
                timestampMicros = buffer.long
            )
        }
        
        fun createForGamepadState(gamepadState: GamepadState, sequenceNumber: Int): MessageEnvelope {
            val payloadSize = gamepadState.toByteArray().size
            return MessageEnvelope(
                messageType = ProtocolConstants.MSG_GAMEPAD_STATE,
                length = payloadSize.toShort(),
                sequenceNumber = sequenceNumber,
                timestampMicros = gamepadState.timestamp
            )
        }
    }
    
    /**
     * Serialize envelope to binary format
     */
    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(ProtocolConstants.ENVELOPE_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
        
        buffer.put(messageType)
        buffer.put(version)
        buffer.putShort(length)
        buffer.putInt(sequenceNumber)
        buffer.putLong(timestampMicros)
        
        return buffer.array()
    }
    
    /**
     * Validate envelope integrity
     */
    fun isValid(): Boolean {
        return when {
            version != ProtocolConstants.PROTOCOL_VERSION -> false
            length < 0 || length > (ProtocolConstants.MAX_PACKET_SIZE - ProtocolConstants.ENVELOPE_SIZE) -> false
            messageType !in listOf(
                ProtocolConstants.MSG_GAMEPAD_STATE,
                ProtocolConstants.MSG_PING,
                ProtocolConstants.MSG_AUTH_CHALLENGE,
                ProtocolConstants.MSG_AUTH_RESPONSE
            ) -> false
            else -> true
        }
    }
    
    /**
     * Get total packet size (envelope + payload)
     */
    fun getTotalSize(): Int = ProtocolConstants.ENVELOPE_SIZE + length
}