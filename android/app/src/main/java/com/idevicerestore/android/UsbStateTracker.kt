package com.idevicerestore.android

import android.hardware.usb.UsbDevice
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.Locale

/**
 * Shared, read-only USB state history for the main restore flow and diagnostics.
 *
 * This tracker never opens a device or sends USB requests. Callers feed it observations from
 * their existing serialized USB workflow so state transitions can be correlated with restore
 * preflight and later hardware-test boundaries.
 */
class UsbStateTracker(private val capacity: Int = 32) {
    enum class State { DISCONNECTED, DFU, WTF, RECOVERY, PORT_DFU, KIS, APPLE_OTHER }

    data class Event(
        val timestamp: Instant,
        val state: State,
        val deviceName: String?,
        val message: String,
        val previousState: State?,
        val previousStateDurationMs: Long?
    ) {
        fun summary(): String = buildString {
            append("USB state: ").append(state)
            previousStateDurationMs?.let { millis ->
                append(" after ")
                append("%.3f".format(Locale.US, millis / 1000.0))
                append(" s in ").append(previousState)
            }
            append(" — ").append(message)
        }
    }

    private val events = ArrayDeque<Event>()
    private var lastEvent: Event? = null

    @Synchronized
    fun observe(device: UsbDevice): Event? {
        val state = stateFor(device)
        val last = lastEvent
        if (last?.state == state && last.deviceName == device.deviceName) return null
        return append(state, device.deviceName, AppleUsb.describe(device))
    }

    @Synchronized
    fun disconnected(deviceName: String? = null): Event? {
        val last = lastEvent
        if (last?.state == State.DISCONNECTED && (deviceName == null || last.deviceName == deviceName)) return null
        return append(State.DISCONNECTED, deviceName, "No active Apple USB device")
    }

    @Synchronized
    fun snapshot(): List<Event> = events.toList()

    @Synchronized
    fun current(): Event? = lastEvent

    fun stateFor(device: UsbDevice): State = when (AppleUsb.personality(device)) {
        AppleUsb.Personality.DFU -> State.DFU
        AppleUsb.Personality.RECOVERY -> State.RECOVERY
        AppleUsb.Personality.WTF -> State.WTF
        AppleUsb.Personality.PORT_DFU -> State.PORT_DFU
        AppleUsb.Personality.KIS -> State.KIS
        AppleUsb.Personality.APPLE_OTHER -> State.APPLE_OTHER
    }

    @Synchronized
    private fun append(state: State, deviceName: String?, message: String): Event {
        val now = Instant.now()
        val previous = lastEvent
        val duration = previous?.let { Duration.between(it.timestamp, now).toMillis().coerceAtLeast(0) }
        val event = Event(
            timestamp = now,
            state = state,
            deviceName = deviceName,
            message = message,
            previousState = previous?.state,
            previousStateDurationMs = duration
        )
        events.addLast(event)
        while (events.size > capacity) events.removeFirst()
        lastEvent = event
        return event
    }
}
