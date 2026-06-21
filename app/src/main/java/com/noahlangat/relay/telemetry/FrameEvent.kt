package com.noahlangat.relay.telemetry

/** Direction of a captured frame, relative to the phone. */
enum class FrameDirection {
    /** Input arriving from a secondary/source (e.g. controller). */
    INCOMING_INPUT,

    /** Frame sent out to the primary/sink (e.g. the MCU). */
    OUTGOING,

    /** Data received back from the primary/sink (acks/status). */
    INCOMING_RETURN
}

/**
 * One captured communication event for the telemetry view. Hex is pre-rendered at
 * capture time so the UI stays cheap to draw.
 */
data class FrameEvent(
    val id: Long,
    val direction: FrameDirection,
    val timestampMs: Long,
    val label: String,
    val seq: Int?,
    val sizeBytes: Int,
    val hexPreview: String,
    val summary: String
)
