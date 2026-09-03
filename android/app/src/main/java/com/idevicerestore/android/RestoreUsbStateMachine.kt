package com.idevicerestore.android

import android.hardware.usb.UsbDevice

/**
 * Tracks USB mode transitions during restore without retaining UsbDeviceConnection instances.
 *
 * Apple boot stages re-enumerate as new USB devices. Callers should close the old connection on
 * detach and open a fresh one for the attached device accepted by this state machine.
 */
class RestoreUsbStateMachine {
    enum class State {
        IDLE,
        DFU_CONNECTED,
        WAITING_FOR_RECOVERY,
        RECOVERY_CONNECTED,
        WAITING_FOR_RESTORE,
        RESTORE_CONNECTED,
        FAILED
    }

    data class DeviceIdentity(
        val ecidHex: String?,
        val cpidHex: String?,
        val bdidHex: String?
    )

    data class Snapshot(
        val state: State,
        val identity: DeviceIdentity?,
        val lastDeviceName: String?,
        val message: String
    )

    private var state: State = State.IDLE
    private var identity: DeviceIdentity? = null
    private var lastDeviceName: String? = null
    private var message: String = "Idle"

    fun snapshot(): Snapshot = Snapshot(state, identity, lastDeviceName, message)

    fun begin(device: UsbDevice): Snapshot {
        require(device.vendorId == AppleUsb.APPLE_VID) { "Restore tracking requires an Apple USB device" }
        val mode = AppleUsb.mode(device)
        require(mode == AppleUsb.Mode.DFU || mode == AppleUsb.Mode.RECOVERY) {
            "Restore tracking must begin in DFU or Recovery mode, got $mode"
        }

        identity = deviceIdentity(device)
        lastDeviceName = device.deviceName
        state = if (mode == AppleUsb.Mode.DFU) State.DFU_CONNECTED else State.RECOVERY_CONNECTED
        message = "Tracking $mode device"
        return snapshot()
    }

    /** Call immediately before a command/upload expected to make DFU re-enumerate as Recovery. */
    fun expectRecoveryReconnect(): Snapshot {
        check(state == State.DFU_CONNECTED) { "Cannot wait for Recovery from state $state" }
        state = State.WAITING_FOR_RECOVERY
        message = "Waiting for DFU device to re-enumerate in Recovery"
        return snapshot()
    }

    /** Call immediately before booting the restore OS from Recovery. */
    fun expectRestoreReconnect(): Snapshot {
        check(state == State.RECOVERY_CONNECTED) { "Cannot wait for Restore from state $state" }
        state = State.WAITING_FOR_RESTORE
        message = "Waiting for Recovery device to re-enumerate in Restore mode"
        return snapshot()
    }

    fun onDetached(device: UsbDevice): Snapshot {
        if (lastDeviceName == device.deviceName) {
            message = when (state) {
                State.WAITING_FOR_RECOVERY -> "DFU detached; awaiting Recovery attach"
                State.WAITING_FOR_RESTORE -> "Recovery detached; awaiting Restore attach"
                else -> "Tracked Apple USB device detached"
            }
            lastDeviceName = null
        }
        return snapshot()
    }

    /**
     * Evaluates a newly attached Apple boot-mode device. A fresh UsbDeviceConnection must be opened
     * after this returns an accepted connected state.
     */
    fun onAttached(device: UsbDevice): Snapshot {
        if (device.vendorId != AppleUsb.APPLE_VID) return snapshot()

        val incoming = deviceIdentity(device)
        if (!identityMatches(identity, incoming)) {
            message = "Ignored Apple USB attach: identity does not match tracked restore device"
            return snapshot()
        }

        when (state) {
            State.WAITING_FOR_RECOVERY -> {
                if (AppleUsb.mode(device) == AppleUsb.Mode.RECOVERY) {
                    state = State.RECOVERY_CONNECTED
                    lastDeviceName = device.deviceName
                    identity = mergeIdentity(identity, incoming)
                    message = "Recovery device reconnected"
                } else {
                    message = "Ignored Apple USB attach while waiting for Recovery: ${AppleUsb.mode(device)}"
                }
            }
            State.WAITING_FOR_RESTORE -> {
                // Restore mode is not yet classified by AppleUsb. Keep the event observable without
                // guessing a product ID; RestoredTransport will claim/verify it by protocol + ECID.
                state = State.RESTORE_CONNECTED
                lastDeviceName = device.deviceName
                identity = mergeIdentity(identity, incoming)
                message = "Candidate Restore device reconnected; protocol verification required"
            }
            else -> Unit
        }
        return snapshot()
    }

    fun fail(reason: String): Snapshot {
        state = State.FAILED
        message = reason
        return snapshot()
    }

    fun reset(): Snapshot {
        state = State.IDLE
        identity = null
        lastDeviceName = null
        message = "Idle"
        return snapshot()
    }

    private fun deviceIdentity(device: UsbDevice): DeviceIdentity {
        val ids = AppleUsb.bootIdentifiers(device)
        return DeviceIdentity(ids?.ecidHex, ids?.cpidHex, ids?.bdidHex)
    }

    private fun identityMatches(expected: DeviceIdentity?, incoming: DeviceIdentity): Boolean {
        if (expected == null) return true
        if (expected.ecidHex != null && incoming.ecidHex != null) {
            return expected.ecidHex.equals(incoming.ecidHex, ignoreCase = true)
        }
        if (expected.cpidHex != null && incoming.cpidHex != null &&
            !expected.cpidHex.equals(incoming.cpidHex, ignoreCase = true)) return false
        if (expected.bdidHex != null && incoming.bdidHex != null &&
            !expected.bdidHex.equals(incoming.bdidHex, ignoreCase = true)) return false
        return true
    }

    private fun mergeIdentity(a: DeviceIdentity?, b: DeviceIdentity): DeviceIdentity = DeviceIdentity(
        ecidHex = a?.ecidHex ?: b.ecidHex,
        cpidHex = a?.cpidHex ?: b.cpidHex,
        bdidHex = a?.bdidHex ?: b.bdidHex
    )
}
