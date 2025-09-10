package com.noahlangat.relay.protocol
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Represents the state of a gamepad controller.
 * Matches the binary protocol specification for efficient network transmission.
 */
data class GamepadState(
    val deviceId: Byte = 0,
    val deviceName: String? = null, // NEW: Add device name field
    val flags: Byte = 0, // Feature flags (gyro, accel, etc.)
    val buttons: Short = 0, // Button bitmask
    val leftStickX: Short = 0, // Left stick X (-32768..32767)
    val leftStickY: Short = 0, // Left stick Y (-32768..32767)
    val rightStickX: Short = 0, // Right stick X (-32768..32767)
    val rightStickY: Short = 0, // Right stick Y (-32768..32767)
    val leftTrigger: Short = 0, // L2 trigger (0..65535)
    val rightTrigger: Short = 0, // R2 trigger (0..65535)
    val dpadX: Short = 0, // D-pad X as analog (-32768..32767)
    val dpadY: Short = 0, // D-pad Y as analog (-32768..32767)
    val gyro: Triple<Short, Short, Short>? = null, // Optional gyroscope (x,y,z)
    val accel: Triple<Short, Short, Short>? = null, // Optional accelerometer (x,y,z)
    val timestamp: Long = System.currentTimeMillis() * 1000 // Microsecond timestamp
) {

    companion object {
        // Button bit positions for PS5 DualSense
        const val BUTTON_CROSS = 0
        const val BUTTON_CIRCLE = 1
        const val BUTTON_SQUARE = 2
        const val BUTTON_TRIANGLE = 3
        const val BUTTON_L1 = 4
        const val BUTTON_R1 = 5
        const val BUTTON_L2 = 6
        const val BUTTON_R2 = 7
        const val BUTTON_SHARE = 8
        const val BUTTON_OPTIONS = 9
        const val BUTTON_L3 = 10
        const val BUTTON_R3 = 11
        const val BUTTON_PS = 12
        const val BUTTON_TOUCHPAD = 13
        const val BUTTON_MUTE = 14

        // Flag bits
        const val FLAG_GYRO_ENABLED = 0x01
        const val FLAG_ACCEL_ENABLED = 0x02
        const val FLAG_TOUCHPAD_ENABLED = 0x04

        fun fromByteArray(data: ByteArray, deviceName: String? = null): GamepadState {
            require(data.size >= ProtocolConstants.GAMEPAD_STATE_SIZE) {
                "Invalid GamepadState data size: ${data.size}"
            }

            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

            val deviceId = buffer.get()
            val flags = buffer.get()
            val buttons = buffer.short
            val lx = buffer.short
            val ly = buffer.short
            val rx = buffer.short
            val ry = buffer.short
            val l2 = buffer.short
            val r2 = buffer.short
            val dpadX = buffer.short
            val dpadY = buffer.short

            var gyro: Triple<Short, Short, Short>? = null
            var accel: Triple<Short, Short, Short>? = null

            // Check if sensor data is present
            if ((flags.toInt() and FLAG_GYRO_ENABLED) != 0 &&
                (flags.toInt() and FLAG_ACCEL_ENABLED) != 0 &&
                data.size >= ProtocolConstants.GAMEPAD_STATE_SIZE + ProtocolConstants.SENSOR_DATA_SIZE) {

                gyro = Triple(buffer.short, buffer.short, buffer.short)
                accel = Triple(buffer.short, buffer.short, buffer.short)
            }

            return GamepadState(
                deviceId = deviceId,
                deviceName = deviceName,
                flags = flags,
                buttons = buttons,
                leftStickX = lx,
                leftStickY = ly,
                rightStickX = rx,
                rightStickY = ry,
                leftTrigger = l2,
                rightTrigger = r2,
                dpadX = dpadX,
                dpadY = dpadY,
                gyro = gyro,
                accel = accel
            )
        }
    }

    /**
     * Converts GamepadState to binary format for network transmission
     */
    fun toByteArray(): ByteArray {
        val hasSensorData = gyro != null && accel != null
        val size = if (hasSensorData) {
            ProtocolConstants.GAMEPAD_STATE_SIZE + ProtocolConstants.SENSOR_DATA_SIZE
        } else {
            ProtocolConstants.GAMEPAD_STATE_SIZE
        }

        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

        var actualFlags = flags
        if (hasSensorData) {
            actualFlags = (actualFlags.toInt() or FLAG_GYRO_ENABLED or FLAG_ACCEL_ENABLED).toByte()
        }

        buffer.put(deviceId)
        buffer.put(actualFlags)
        buffer.putShort(buttons)
        buffer.putShort(leftStickX)
        buffer.putShort(leftStickY)
        buffer.putShort(rightStickX)
        buffer.putShort(rightStickY)
        buffer.putShort(leftTrigger)
        buffer.putShort(rightTrigger)
        buffer.putShort(dpadX)
        buffer.putShort(dpadY)

        if (hasSensorData) {
            gyro?.let { (x, y, z) ->
                buffer.putShort(x)
                buffer.putShort(y)
                buffer.putShort(z)
            }
            accel?.let { (x, y, z) ->
                buffer.putShort(x)
                buffer.putShort(y)
                buffer.putShort(z)
            }
        }

        return buffer.array()
    }

    /**
     * Check if a specific button is pressed
     */
    fun isButtonPressed(buttonBit: Int): Boolean {
        return (buttons.toInt() and (1 shl buttonBit)) != 0
    }

    /**
     * Get normalized stick values (-1.0 to 1.0)
     */
    fun getNormalizedLeftStick(): Pair<Float, Float> {
        return Pair(
            leftStickX / 32767.0f,
            leftStickY / 32767.0f
        )
    }

    fun getNormalizedRightStick(): Pair<Float, Float> {
        return Pair(
            rightStickX / 32767.0f,
            rightStickY / 32767.0f
        )
    }

    /**
     * Get normalized trigger values (0.0 to 1.0)
     */
    fun getNormalizedTriggers(): Pair<Float, Float> {
        return Pair(
            leftTrigger / 65535.0f,
            rightTrigger / 65535.0f
        )
    }
}