package com.noahlangat.relay.protocol

object ProtocolConstants {
    // Protocol version
    const val PROTOCOL_VERSION: Byte = 1
    
    // Message types
    const val MSG_GAMEPAD_STATE: Byte = 0x01
    const val MSG_PING: Byte = 0x02
    const val MSG_AUTH_CHALLENGE: Byte = 0x03
    const val MSG_AUTH_RESPONSE: Byte = 0x04
    
    // Future extensions
    const val MSG_SET_RUMBLE: Byte = 0x10
    const val MSG_SET_LED: Byte = 0x11
    const val MSG_DEVICE_INFO: Byte = 0x20
    
    // Protocol constraints
    const val ENVELOPE_SIZE = 16 // bytes (1+1+2+4+8)
    const val GAMEPAD_STATE_SIZE = 26 // bytes minimum
    const val SENSOR_DATA_SIZE = 12 // bytes optional
    const val MAX_PACKET_SIZE = ENVELOPE_SIZE + GAMEPAD_STATE_SIZE + SENSOR_DATA_SIZE
    
    // Network defaults
    const val DEFAULT_TCP_PORT = 26543
    const val MAX_SEND_QUEUE_SIZE = 120 // frames (~1 second at 120Hz)
    const val MAX_TRANSMISSION_RATE_HZ = 120
    
    // Performance targets
    const val TARGET_INPUT_LATENCY_MS = 5
    const val TARGET_NETWORK_LATENCY_MS = 15
    const val TARGET_END_TO_END_LAN_MS = 50
    const val TARGET_END_TO_END_WIFI_MS = 80
    
    // Authentication
    const val AUTH_TOKEN_SIZE = 32 // bytes
    const val AUTH_NONCE_SIZE = 16 // bytes
}