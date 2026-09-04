package com.idevicerestore.android

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.util.ArrayDeque

class BootDiagnosticEngine(
    private val usbManager: UsbManager,
    private val logger: BootDiagnosticLogger
) {
    private val events = mutableListOf<BootDiagnosticEvent>()
    private val recentModes = ArrayDeque<AppleUsb.Mode>()
    private var lastDeviceName: String? = null
    private var lastRecoverySnapshot: RecoveryDiagnosticSession.Snapshot? = null

    fun scan(): BootDiagnosticSnapshot {
        val apple = usbManager.deviceList.values.filter { it.vendorId == AppleUsb.APPLE_VID }
        if (apple.isEmpty()) {
            record(BootDiagnosticState.DISCONNECTED, "No Apple USB device detected")
            return snapshot(BootDiagnosticState.DISCONNECTED, null)
        }

        val device = apple.firstOrNull { AppleUsb.mode(it) != AppleUsb.Mode.APPLE_OTHER } ?: apple.first()
        val mode = AppleUsb.mode(device)
        logger.logUsb(AppleUsb.describe(device))
        logger.logUsb(AppleUsb.interfaceSummary(device).trim())

        if (lastDeviceName != null && lastDeviceName != device.deviceName) {
            record(BootDiagnosticState.APPLE_DEVICE, "USB device identity changed from $lastDeviceName to ${device.deviceName}")
        }
        lastDeviceName = device.deviceName
        rememberMode(mode)

        if (!usbManager.hasPermission(device)) {
            record(BootDiagnosticState.USB_PERMISSION_REQUIRED, "USB permission is required before diagnostic probing")
            return snapshot(BootDiagnosticState.USB_PERMISSION_REQUIRED, device)
        }

        return when (mode) {
            AppleUsb.Mode.DFU -> {
                record(BootDiagnosticState.DFU, "Device is enumerated in DFU mode")
                snapshot(BootDiagnosticState.DFU, device)
            }
            AppleUsb.Mode.WTF -> {
                record(BootDiagnosticState.DFU, "Device is enumerated in WTF/pre-DFU mode")
                snapshot(BootDiagnosticState.DFU, device)
            }
            AppleUsb.Mode.RECOVERY -> probeRecovery(device)
            AppleUsb.Mode.APPLE_OTHER -> {
                record(BootDiagnosticState.NORMAL_OR_OTHER, "Apple USB device is present outside known DFU/Recovery PIDs")
                snapshot(BootDiagnosticState.NORMAL_OR_OTHER, device)
            }
        }
    }

    fun recordDetach(deviceName: String?) {
        record(BootDiagnosticState.DISCONNECTED, "USB device detached: ${deviceName ?: "unknown"}")
    }

    fun recordAttach(deviceName: String?) {
        record(BootDiagnosticState.APPLE_DEVICE, "USB device attached: ${deviceName ?: "unknown"}")
    }

    private fun probeRecovery(device: UsbDevice): BootDiagnosticSnapshot {
        record(BootDiagnosticState.RECOVERY, "Recovery mode detected; starting read-only iBoot diagnostic probe")
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            record(BootDiagnosticState.USB_ERROR, "Android could not open the Recovery USB device")
            return snapshot(BootDiagnosticState.USB_ERROR, device)
        }

        connection.use { conn ->
            val claimed = AppleUsb.claimBestInterface(device, conn)
            if (claimed == null) {
                record(BootDiagnosticState.RECOVERY_UNRESPONSIVE, "Could not claim a Recovery command interface")
                return snapshot(BootDiagnosticState.RECOVERY_UNRESPONSIVE, device)
            }

            try {
                val transport = RecoveryTransport(conn, claimed.bulkIn)
                val recovery = RecoveryDiagnosticSession(device, conn, transport).snapshot()
                lastRecoverySnapshot = recovery
                recovery.variables.forEach { variable ->
                    val text = variable.result?.value ?: variable.error?.message ?: "no response"
                    logger.log("Recovery getenv ${variable.name}: $text")
                }
                recovery.console?.let { logger.log("Recovery console bytes=${it.bytes}") }
                recovery.consoleError?.let { logger.log("Recovery console read error: ${it.message}") }

                val state = if (recovery.readiness.commandTransportReady) {
                    BootDiagnosticState.RECOVERY_RESPONSIVE
                } else {
                    BootDiagnosticState.RECOVERY_UNRESPONSIVE
                }
                record(
                    state,
                    if (state == BootDiagnosticState.RECOVERY_RESPONSIVE) {
                        "Recovery command transport responded; iBoot build=${recovery.readiness.buildVersion ?: "unknown"} stage=${recovery.readiness.bootStage ?: "unknown"}"
                    } else {
                        "Recovery is visible over USB but core read-only iBoot queries did not complete"
                    }
                )
                return snapshot(state, device, recovery)
            } catch (t: Throwable) {
                record(BootDiagnosticState.RECOVERY_UNRESPONSIVE, "Recovery probe failed: ${t.message ?: t.javaClass.simpleName}")
                return snapshot(BootDiagnosticState.RECOVERY_UNRESPONSIVE, device)
            } finally {
                runCatching { conn.releaseInterface(claimed.intf) }
            }
        }
    }

    private fun snapshot(
        state: BootDiagnosticState,
        device: UsbDevice?,
        recovery: RecoveryDiagnosticSession.Snapshot? = lastRecoverySnapshot
    ): BootDiagnosticSnapshot {
        val result = BootDiagnosticSnapshot(
            state = state,
            deviceDescription = device?.let { AppleUsb.describe(it) },
            events = events.toList(),
            findings = findingsFor(state),
            recovery = recovery
        )
        logger.writeSummary(result)
        return result
    }

    private fun findingsFor(state: BootDiagnosticState): List<BootDiagnosticFinding> {
        val findings = mutableListOf<BootDiagnosticFinding>()
        when (state) {
            BootDiagnosticState.DFU -> findings += BootDiagnosticFinding(
                title = "Boot chain has not reached Recovery",
                confidence = DiagnosticConfidence.CONFIRMED,
                detail = "The device is currently exposing Apple's DFU USB interface. macOS and recoveryOS are not running in this state.",
                recommendation = "Use a revive/restore workflow only after preserving any data-recovery considerations."
            )
            BootDiagnosticState.RECOVERY_RESPONSIVE -> findings += BootDiagnosticFinding(
                title = "Recovery/iBoot communication is functional",
                confidence = DiagnosticConfidence.CONFIRMED,
                detail = "The host can exchange read-only iBoot environment queries with the device. The USB cable, basic Recovery transport, and early boot firmware are responding.",
                recommendation = "Continue diagnosis above the Recovery transport layer before assuming a USB or DFU fault."
            )
            BootDiagnosticState.RECOVERY_UNRESPONSIVE -> findings += BootDiagnosticFinding(
                title = "Recovery enumerates but command transport is unhealthy",
                confidence = DiagnosticConfidence.PROBABLE,
                detail = "Android sees the Recovery USB device, but one or more core read-only iBoot exchanges failed.",
                recommendation = "Retry with a direct USB connection/cable, then compare repeated sessions before treating this as a device-side firmware failure."
            )
            BootDiagnosticState.NORMAL_OR_OTHER -> findings += BootDiagnosticFinding(
                title = "Device is outside known DFU/Recovery modes",
                confidence = DiagnosticConfidence.INSUFFICIENT_EVIDENCE,
                detail = "An Apple USB device is present, but its PID is not one of the DFU/Recovery identifiers currently classified by iDeviceRestore."
            )
            else -> Unit
        }

        val modes = recentModes.toList()
        if (modes.size >= 4) {
            val recoveryCount = modes.count { it == AppleUsb.Mode.RECOVERY }
            val dfuCount = modes.count { it == AppleUsb.Mode.DFU || it == AppleUsb.Mode.WTF }
            if (recoveryCount >= 2 && dfuCount >= 2) {
                findings += BootDiagnosticFinding(
                    title = "Repeated early-boot mode cycling detected",
                    confidence = DiagnosticConfidence.PROBABLE,
                    detail = "Recent observations alternate between DFU/pre-DFU and Recovery, which can indicate failure to progress through the early boot chain.",
                    recommendation = "Capture another session without changing cables or issuing restore commands so the transition pattern can be confirmed."
                )
            }
        }
        return findings
    }

    private fun rememberMode(mode: AppleUsb.Mode) {
        if (recentModes.lastOrNull() == mode) return
        recentModes.addLast(mode)
        while (recentModes.size > 10) recentModes.removeFirst()
    }

    private fun record(state: BootDiagnosticState, message: String) {
        val last = events.lastOrNull()
        if (last?.state == state && last.message == message) return
        val event = BootDiagnosticEvent(state = state, message = message)
        events += event
        logger.log("${event.state}: ${event.message}")
    }
}
