package com.noahlangat.relay.ui.components

import java.text.SimpleDateFormat
import java.util.*

/**
 * Represents a log message from a device or system component
 */
data class LogMessage(
    val id: Long = System.currentTimeMillis(),
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel = LogLevel.INFO,
    val deviceName: String = "System",
    val deviceId: Int? = null,
    val message: String,
    val source: LogSource = LogSource.SYSTEM
) {
    fun getFormattedTimestamp(): String {
        val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        return format.format(Date(timestamp))
    }

    fun getDeviceLabel(): String {
        return if (deviceId != null) {
            "$deviceName (ID:$deviceId)"
        } else {
            deviceName
        }
    }

    fun getAbbreviatedDevice(): String {
        return when (source) {
            LogSource.SYSTEM -> "SYS"
            LogSource.BLUETOOTH -> "BT"
            LogSource.NETWORK -> "NW"
            LogSource.SERVICE -> "SV"
            LogSource.GAMEPAD -> "GP"
        }
    }
}

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}

enum class LogSource {
    SYSTEM,
    BLUETOOTH,
    NETWORK,
    SERVICE,
    GAMEPAD
}