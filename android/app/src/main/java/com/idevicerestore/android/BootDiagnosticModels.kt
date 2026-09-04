package com.idevicerestore.android

import java.time.Instant

enum class BootDiagnosticState {
    DISCONNECTED,
    APPLE_DEVICE,
    DFU,
    RECOVERY,
    RECOVERY_RESPONSIVE,
    RECOVERY_UNRESPONSIVE,
    NORMAL_OR_OTHER,
    USB_PERMISSION_REQUIRED,
    USB_ERROR
}

enum class DiagnosticConfidence { CONFIRMED, PROBABLE, INSUFFICIENT_EVIDENCE }

data class BootDiagnosticEvent(
    val timestamp: Instant = Instant.now(),
    val state: BootDiagnosticState,
    val message: String
)

data class BootDiagnosticFinding(
    val title: String,
    val confidence: DiagnosticConfidence,
    val detail: String,
    val recommendation: String? = null
)

data class BootDiagnosticSnapshot(
    val state: BootDiagnosticState,
    val deviceDescription: String?,
    val events: List<BootDiagnosticEvent>,
    val findings: List<BootDiagnosticFinding>,
    val recovery: RecoveryDiagnosticSession.Snapshot? = null
)
