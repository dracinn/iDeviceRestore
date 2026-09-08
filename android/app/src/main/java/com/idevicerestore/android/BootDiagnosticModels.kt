package com.idevicerestore.android

import java.time.Instant

enum class BootDiagnosticState {
    DISCONNECTED,
    APPLE_DEVICE,
    DFU,
    WTF,
    RECOVERY,
    RECOVERY_RESPONSIVE,
    RECOVERY_UNRESPONSIVE,
    NORMAL_OR_OTHER,
    USB_PERMISSION_REQUIRED,
    USB_ERROR
}

enum class DiagnosticConfidence { CONFIRMED, PROBABLE, INSUFFICIENT_EVIDENCE }

enum class DiagnosticTestStatus {
    PASSED,
    FAILED,
    BLOCKED,
    OBSERVED,
    NOT_APPLICABLE
}

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

data class BootDiagnosticTestResult(
    val id: String,
    val title: String,
    val status: DiagnosticTestStatus,
    val detail: String
)

data class BootDiagnosticSnapshot(
    val state: BootDiagnosticState,
    val deviceDescription: String?,
    val events: List<BootDiagnosticEvent>,
    val findings: List<BootDiagnosticFinding>,
    val tests: List<BootDiagnosticTestResult> = emptyList(),
    val recovery: RecoveryDiagnosticSession.Snapshot? = null
)
