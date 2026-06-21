package com.noahlangat.relay.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory capture of incoming/outgoing relay traffic for debugging.
 *
 * Capture is gated by [enabled] and off by default — when disabled, [record] is a
 * single volatile read and returns immediately, so the relay hot path pays
 * essentially nothing. Callers should still check [isEnabled] before building the
 * byte/hex arguments to avoid that work when capture is off.
 */
@Singleton
class TelemetryRepository @Inject constructor() {

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    private val _events = MutableStateFlow<List<FrameEvent>>(emptyList())
    val events: StateFlow<List<FrameEvent>> = _events

    private val counter = AtomicLong(0)

    fun isEnabled(): Boolean = _enabled.value

    fun setEnabled(value: Boolean) {
        _enabled.value = value
    }

    @Synchronized
    fun record(
        direction: FrameDirection,
        label: String,
        summary: String,
        seq: Int? = null,
        bytes: ByteArray? = null
    ) {
        if (!_enabled.value) return

        val event = FrameEvent(
            id = counter.incrementAndGet(),
            direction = direction,
            timestampMs = System.currentTimeMillis(),
            label = label,
            seq = seq,
            sizeBytes = bytes?.size ?: 0,
            hexPreview = bytes?.let { hexPreview(it) } ?: "",
            summary = summary
        )

        val list = _events.value.toMutableList()
        list.add(event)
        if (list.size > MAX_EVENTS) list.removeAt(0)
        _events.value = list
    }

    @Synchronized
    fun clear() {
        _events.value = emptyList()
    }

    companion object {
        private const val MAX_EVENTS = 300
        private const val PREVIEW_BYTES = 32

        fun hexPreview(bytes: ByteArray): String {
            val n = minOf(bytes.size, PREVIEW_BYTES)
            val sb = StringBuilder(n * 3)
            for (i in 0 until n) {
                sb.append("%02x ".format(bytes[i].toInt() and 0xFF))
            }
            if (bytes.size > n) sb.append("…")
            return sb.toString().trim()
        }
    }
}
