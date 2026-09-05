package com.idevicerestore.android

import android.hardware.usb.UsbDevice
import java.time.Instant

/**
 * Process-local evidence shared by the normal restore flow and diagnostic surfaces.
 *
 * Evidence is observational only. Nothing in this store sends USB requests or authorizes a
 * state-changing restore operation. Hardware-tested policy may later consume these snapshots.
 */
object RestorePreflightEvidenceStore {
    data class RecoveryEvidence(
        val observedAt: Instant,
        val deviceName: String,
        val commandTransportReady: Boolean,
        val recoveryMode: Boolean,
        val buildVersion: String?,
        val buildStyle: String?,
        val autoBoot: String?,
        val bootStage: String?,
        val reasons: List<String>
    )

    data class Snapshot(
        val usbEvents: List<UsbStateTracker.Event>,
        val recovery: RecoveryEvidence?
    )

    private val usbTracker = UsbStateTracker()
    @Volatile private var recoveryEvidence: RecoveryEvidence? = null

    @Synchronized
    fun observeUsb(device: UsbDevice): UsbStateTracker.Event? = usbTracker.observe(device)

    @Synchronized
    fun observeDisconnected(deviceName: String? = null): UsbStateTracker.Event? {
        recoveryEvidence = null
        return usbTracker.disconnected(deviceName)
    }

    @Synchronized
    fun recordRecovery(device: UsbDevice, readiness: RecoveryDiagnosticSession.Readiness) {
        if (!readiness.recoveryMode) {
            recoveryEvidence = null
            return
        }
        recoveryEvidence = RecoveryEvidence(
            observedAt = Instant.now(),
            deviceName = device.deviceName,
            commandTransportReady = readiness.commandTransportReady,
            recoveryMode = true,
            buildVersion = readiness.buildVersion,
            buildStyle = readiness.buildStyle,
            autoBoot = readiness.autoBoot,
            bootStage = readiness.bootStage,
            reasons = readiness.reasons.toList()
        )
    }

    @Synchronized
    fun clearRecovery() {
        recoveryEvidence = null
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        usbEvents = usbTracker.snapshot(),
        recovery = recoveryEvidence
    )

    fun preflightSummary(): String {
        val snapshot = snapshot()
        val current = snapshot.usbEvents.lastOrNull()
        val recovery = snapshot.recovery
        return buildString {
            append("Preflight USB=").append(current?.state ?: "unknown")
            current?.previousStateDurationMs?.let { append(" transitionMs=").append(it) }
            if (current?.state == UsbStateTracker.State.RECOVERY && recovery != null) {
                append(" recoveryTransport=").append(if (recovery.commandTransportReady) "ready" else "not-confirmed")
                append(" bootStage=").append(recovery.bootStage ?: "unknown")
                append(" build=").append(recovery.buildVersion ?: "unknown")
            }
        }
    }
}
