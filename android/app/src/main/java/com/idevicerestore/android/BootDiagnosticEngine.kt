package com.idevicerestore.android

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import java.time.Instant
import java.util.ArrayDeque
import java.util.Locale

class BootDiagnosticEngine(
    private val usbManager: UsbManager,
    private val logger: BootDiagnosticLogger
) {
    private val events = mutableListOf<BootDiagnosticEvent>()
    private val recentModes = ArrayDeque<AppleUsb.Mode>()
    private var lastDeviceName: String? = null
    private var lastElapsedRealtimeMs: Long? = null

    fun scan(): BootDiagnosticSnapshot {
        val apple = usbManager.deviceList.values.filter { it.vendorId == AppleUsb.APPLE_VID }
        if (apple.isEmpty()) {
            record(BootDiagnosticState.DISCONNECTED, "No Apple USB device detected")
            return snapshot(BootDiagnosticState.DISCONNECTED, null)
        }

        val device = apple.firstOrNull { AppleUsb.mode(it) != AppleUsb.Mode.APPLE_OTHER } ?: apple.first()
        val mode = AppleUsb.mode(device)
        logger.organizeForDevice(device)
        logger.logUsb(AppleUsb.describe(device))
        logger.logUsb(AppleUsb.interfaceSummary(device).trim())
        logger.logUsb(AppleUsb.bootIdentifierSummary(device))

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
                record(BootDiagnosticState.WTF, "Device is enumerated in WTF/pre-DFU mode")
                snapshot(BootDiagnosticState.WTF, device)
            }
            AppleUsb.Mode.RECOVERY -> probeRecovery(device)
            AppleUsb.Mode.APPLE_OTHER -> {
                val personality = AppleUsb.personality(device)
                val message = when (personality) {
                    AppleUsb.Personality.PORT_DFU -> "Apple Port DFU personality detected"
                    AppleUsb.Personality.KIS -> "Apple KIS personality detected"
                    else -> "Apple USB device is present outside known DFU/Recovery/WTF PIDs"
                }
                record(BootDiagnosticState.NORMAL_OR_OTHER, message)
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

        try {
            val claimed = AppleUsb.claimBestInterface(device, connection)
            if (claimed == null) {
                record(BootDiagnosticState.RECOVERY_UNRESPONSIVE, "Could not claim a Recovery command interface")
                return snapshot(BootDiagnosticState.RECOVERY_UNRESPONSIVE, device)
            }

            try {
                val transport = RecoveryTransport(connection, claimed.bulkIn)
                val recovery = RecoveryDiagnosticSession(device, connection, transport).snapshot()
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
                runCatching { connection.releaseInterface(claimed.intf) }
            }
        } finally {
            connection.close()
        }
    }

    private fun snapshot(
        state: BootDiagnosticState,
        device: UsbDevice?,
        recovery: RecoveryDiagnosticSession.Snapshot? = null
    ): BootDiagnosticSnapshot {
        val result = BootDiagnosticSnapshot(
            state = state,
            deviceDescription = device?.let { AppleUsb.describe(it) },
            events = events.toList(),
            findings = findingsFor(state),
            tests = functionalTestsFor(state, device, recovery),
            recovery = recovery
        )
        result.tests.forEach { test ->
            logger.log("TEST ${test.id}: ${test.status}: ${test.detail}")
        }
        logger.writeSummary(result)
        return result
    }

    private fun functionalTestsFor(
        state: BootDiagnosticState,
        device: UsbDevice?,
        recovery: RecoveryDiagnosticSession.Snapshot?
    ): List<BootDiagnosticTestResult> {
        val tests = mutableListOf<BootDiagnosticTestResult>()

        fun add(id: String, title: String, status: DiagnosticTestStatus, detail: String) {
            tests += BootDiagnosticTestResult(id, title, status, detail)
        }

        if (device == null) {
            add(
                "usb.apple.enumeration",
                "Apple USB enumeration",
                DiagnosticTestStatus.BLOCKED,
                "No Apple USB device is currently enumerated. Connect the device in its present boot state and run again."
            )
            return tests
        }

        add(
            "usb.apple.enumeration",
            "Apple USB enumeration",
            if (device.vendorId == AppleUsb.APPLE_VID) DiagnosticTestStatus.PASSED else DiagnosticTestStatus.FAILED,
            "Observed VID=%04X PID=%04X with ${device.interfaceCount} interface(s).".format(device.vendorId, device.productId)
        )

        val personality = AppleUsb.personality(device)
        val knownPersonality = personality != AppleUsb.Personality.APPLE_OTHER
        add(
            "usb.personality.classification",
            "Known Apple boot personality classification",
            if (knownPersonality) DiagnosticTestStatus.PASSED else DiagnosticTestStatus.OBSERVED,
            "Observed personality=$personality mode=${AppleUsb.mode(device)}. Known profiles include DFU, Port DFU, Recovery, KIS, and WTF."
        )

        val hasPermission = usbManager.hasPermission(device)
        add(
            "usb.permission",
            "Android USB permission",
            if (hasPermission) DiagnosticTestStatus.PASSED else DiagnosticTestStatus.BLOCKED,
            if (hasPermission) "Android granted access to this USB device." else "Permission is required before descriptor and transport tests can run."
        )

        val hasAnyEndpoint = (0 until device.interfaceCount).any { index ->
            device.getInterface(index).endpointCount > 0
        }
        add(
            "usb.interface.map",
            "USB interface descriptor map",
            if (device.interfaceCount > 0) DiagnosticTestStatus.PASSED else DiagnosticTestStatus.FAILED,
            "Interface count=${device.interfaceCount}; endpoint-bearing interface present=$hasAnyEndpoint. Full map is recorded in usb-events.log."
        )

        if (!hasPermission) return tests

        val identifiers = AppleUsb.bootIdentifiers(device)
        if (identifiers == null) {
            add(
                "boot.identifiers",
                "Structured iBoot identifiers",
                DiagnosticTestStatus.OBSERVED,
                "This personality does not expose structured CPID/BDID/ECID/IBFL boot tags in its USB serial descriptor."
            )
        } else {
            val parsed = listOfNotNull(
                identifiers.cpidHex?.let { "CPID=$it" },
                identifiers.bdidHex?.let { "BDID=$it" },
                identifiers.ibflHex?.let { "IBFL=$it" },
                identifiers.prevHex?.let { "PREV=$it" }
            )
            add(
                "boot.identifiers",
                "Structured iBoot identifiers",
                if (parsed.isNotEmpty()) DiagnosticTestStatus.PASSED else DiagnosticTestStatus.OBSERVED,
                parsed.joinToString().ifBlank { "Descriptor was readable but contained no currently parsed boot tags." }
            )
            identifiers.image4Aware?.let { aware ->
                add(
                    "boot.image4-awareness",
                    "Image4-awareness flag",
                    if (aware) DiagnosticTestStatus.PASSED else DiagnosticTestStatus.OBSERVED,
                    "IBFL reports Image4-aware=$aware."
                )
            }
            if (identifiers.cpid == 0x8103) {
                add(
                    "profile.apple-silicon-m1",
                    "Known Apple Silicon M1 profile",
                    DiagnosticTestStatus.PASSED,
                    "CPID=0x8103 matches the hardware profile used for the currently proven M1 DFU → Stage-2 path."
                )
            } else {
                add(
                    "profile.apple-silicon-m1",
                    "Known Apple Silicon M1 profile",
                    DiagnosticTestStatus.NOT_APPLICABLE,
                    "Observed CPID=${identifiers.cpidHex ?: "unknown"}; the proven M1-specific profile requires CPID=8103."
                )
            }
        }

        when (personality) {
            AppleUsb.Personality.DFU, AppleUsb.Personality.PORT_DFU -> {
                val dfuInterface = (0 until device.interfaceCount)
                    .map { device.getInterface(it) }
                    .firstOrNull {
                        it.interfaceClass == UsbConstants.USB_CLASS_APP_SPEC && it.interfaceSubclass == 1
                    }
                add(
                    "dfu.interface.profile",
                    "DFU interface profile",
                    if (dfuInterface != null) DiagnosticTestStatus.PASSED else DiagnosticTestStatus.FAILED,
                    dfuInterface?.let {
                        "Found application-specific DFU interface id=${it.id} alt=${it.alternateSetting} endpoints=${it.endpointCount}."
                    } ?: "No application-specific subclass-1 DFU interface was found."
                )
                add(
                    "recovery.command-transport",
                    "Recovery command transport",
                    DiagnosticTestStatus.NOT_APPLICABLE,
                    "The device is currently in $personality, so iBoot Recovery getenv transport is not expected."
                )
            }
            AppleUsb.Personality.RECOVERY -> appendRecoveryTests(tests, state, recovery)
            AppleUsb.Personality.WTF -> add(
                "wtf.personality",
                "WTF/pre-DFU recognition",
                DiagnosticTestStatus.PASSED,
                "PID=%04X is classified as Apple's WTF/pre-DFU personality.".format(device.productId)
            )
            AppleUsb.Personality.KIS -> add(
                "kis.personality",
                "KIS recognition",
                DiagnosticTestStatus.PASSED,
                "PID=%04X is classified as Apple's KIS personality.".format(device.productId)
            )
            AppleUsb.Personality.APPLE_OTHER -> add(
                "boot.known-personality",
                "Known boot personality",
                DiagnosticTestStatus.OBSERVED,
                "This Apple PID is not yet mapped to a supported boot personality. Preserve this session as evidence for future device support."
            )
        }

        add(
            "usb.transition-history",
            "Boot-state transition history",
            if (events.size > 1) DiagnosticTestStatus.PASSED else DiagnosticTestStatus.OBSERVED,
            if (events.size > 1) {
                "${events.size} diagnostic event(s) have been captured in this session; timing between state changes is preserved."
            } else {
                "Only one state has been observed so far. Leave Diagnostics open while reproducing the boot issue to capture transitions."
            }
        )

        return tests
    }

    private fun appendRecoveryTests(
        tests: MutableList<BootDiagnosticTestResult>,
        state: BootDiagnosticState,
        recovery: RecoveryDiagnosticSession.Snapshot?
    ) {
        fun add(id: String, title: String, status: DiagnosticTestStatus, detail: String) {
            tests += BootDiagnosticTestResult(id, title, status, detail)
        }

        if (recovery == null) {
            add(
                "recovery.command-transport",
                "Recovery command transport",
                if (state == BootDiagnosticState.RECOVERY_UNRESPONSIVE) DiagnosticTestStatus.FAILED else DiagnosticTestStatus.BLOCKED,
                "Recovery USB is visible, but no successful read-only iBoot snapshot was produced."
            )
            return
        }

        val readiness = recovery.readiness
        add(
            "recovery.command-transport",
            "Recovery command transport",
            if (readiness.commandTransportReady) DiagnosticTestStatus.PASSED else DiagnosticTestStatus.FAILED,
            if (readiness.commandTransportReady) {
                "Repeated read-only iBoot getenv exchanges completed successfully."
            } else {
                readiness.reasons.joinToString("; ").ifBlank { "Core iBoot queries did not establish a healthy command transport." }
            }
        )
        add(
            "recovery.build-version",
            "iBoot build-version query",
            if (!readiness.buildVersion.isNullOrBlank()) DiagnosticTestStatus.PASSED else DiagnosticTestStatus.FAILED,
            "Observed build-version=${readiness.buildVersion ?: "no response"}."
        )
        add(
            "recovery.build-style",
            "iBoot build-style query",
            if (!readiness.buildStyle.isNullOrBlank()) DiagnosticTestStatus.PASSED else DiagnosticTestStatus.FAILED,
            "Observed build-style=${readiness.buildStyle ?: "no response"}."
        )
        add(
            "recovery.auto-boot",
            "auto-boot state query",
            if (!readiness.autoBoot.isNullOrBlank()) DiagnosticTestStatus.PASSED else DiagnosticTestStatus.FAILED,
            "Observed auto-boot=${readiness.autoBoot ?: "no response"}. This test does not mutate the value."
        )
        add(
            "recovery.boot-stage",
            "boot-stage query",
            if (!readiness.bootStage.isNullOrBlank()) DiagnosticTestStatus.PASSED else DiagnosticTestStatus.FAILED,
            "Observed boot-stage=${readiness.bootStage ?: "no response"}."
        )

        val repeated = recovery.variables.groupBy { it.name }.filterValues { it.size > 1 }
        val repeatedFailures = repeated.values.flatten().count { it.result == null }
        val mismatches = repeated.mapNotNull { (name, values) ->
            val returned = values.mapNotNull { it.result?.value }.distinct()
            if (returned.size > 1) "$name=${returned.joinToString("/")}" else null
        }
        add(
            "recovery.query-stability",
            "Repeated Recovery query stability",
            if (repeatedFailures == 0 && mismatches.isEmpty()) DiagnosticTestStatus.PASSED else DiagnosticTestStatus.FAILED,
            when {
                repeatedFailures > 0 -> "$repeatedFailures repeated core query exchange(s) failed."
                mismatches.isNotEmpty() -> "Repeated queries returned inconsistent values: ${mismatches.joinToString()}."
                else -> "Repeated build-version/auto-boot/boot-stage queries remained stable across the diagnostic sequence."
            }
        )

        val stage = readiness.bootStage?.trim()
        add(
            "profile.stage2",
            "Known Stage-2 Recovery profile",
            if (stage == "2") DiagnosticTestStatus.PASSED else DiagnosticTestStatus.NOT_APPLICABLE,
            if (stage == "2") {
                "boot-stage=2 is present; this is the stage required by the currently proven cumulative Stage-2 validation path."
            } else {
                "Observed boot-stage=${stage ?: "unknown"}; exact Stage-2 proof requires boot-stage=2 plus the expected prepared build."
            }
        )

        val console = recovery.console
        add(
            "recovery.console-read",
            "Recovery console read path",
            when {
                recovery.consoleError != null -> DiagnosticTestStatus.FAILED
                console != null && console.bytes > 0 -> DiagnosticTestStatus.PASSED
                else -> DiagnosticTestStatus.OBSERVED
            },
            when {
                recovery.consoleError != null -> "Console read failed: ${recovery.consoleError.message ?: recovery.consoleError.javaClass.simpleName}."
                console != null && console.bytes > 0 -> "Read ${console.bytes} byte(s) from the Recovery console interface without sending data."
                else -> "Console transport opened without captured output; silence is not by itself a failure."
            }
        )
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
            BootDiagnosticState.WTF -> findings += BootDiagnosticFinding(
                title = "Device is in WTF/pre-DFU mode",
                confidence = DiagnosticConfidence.CONFIRMED,
                detail = "The observed USB PID is Apple's WTF/pre-DFU mode, which is distinct from DFU and indicates an earlier boot/recovery stage.",
                recommendation = "Capture the next USB transition before issuing restore commands; progression to DFU or Recovery is diagnostically significant."
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
                title = "Device is outside the currently exercised DFU/Recovery/WTF path",
                confidence = DiagnosticConfidence.INSUFFICIENT_EVIDENCE,
                detail = "An Apple USB device is present. Known Port DFU and KIS personalities are recorded separately, while unknown PIDs are preserved as evidence for future support."
            )
            else -> Unit
        }

        val modes = recentModes.toList()
        if (modes.size >= 4) {
            val recoveryCount = modes.count { it == AppleUsb.Mode.RECOVERY }
            val earlyCount = modes.count { it == AppleUsb.Mode.DFU || it == AppleUsb.Mode.WTF }
            if (recoveryCount >= 2 && earlyCount >= 2) {
                findings += BootDiagnosticFinding(
                    title = "Repeated early-boot mode cycling detected",
                    confidence = DiagnosticConfidence.PROBABLE,
                    detail = "Recent observations alternate between Recovery and DFU/WTF states, which can indicate failure to progress through the early boot chain.",
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
        if (last?.state == state && last.message.substringBefore(" after ") == message) return
        val now = Instant.now()
        val elapsedRealtimeMs = SystemClock.elapsedRealtime()
        val timedMessage = if (last != null && last.state != state) {
            val millis = lastElapsedRealtimeMs?.let { previousElapsed ->
                (elapsedRealtimeMs - previousElapsed).coerceAtLeast(0L)
            } ?: 0L
            "$message after %.3f s in ${last.state}".format(Locale.US, millis / 1000.0)
        } else {
            message
        }
        val event = BootDiagnosticEvent(timestamp = now, state = state, message = timedMessage)
        events += event
        lastElapsedRealtimeMs = elapsedRealtimeMs
        logger.log("${event.state}: ${event.message}")
    }
}
