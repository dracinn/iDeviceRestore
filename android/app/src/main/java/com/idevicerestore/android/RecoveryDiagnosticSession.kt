package com.idevicerestore.android

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection

/**
 * Read-only Recovery communication snapshot.
 *
 * Only iBoot getenv queries and optional bulk-IN console reads are exposed here.
 * No setenv/saveenv, reboot, go/bootx, image upload, reset, or bulk-OUT image transfer is used.
 */
class RecoveryDiagnosticSession(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val control: RecoveryTransport
) {
    data class VariableResult(
        val name: String,
        val result: RecoveryTransport.GetEnvResult?,
        val error: Throwable?
    )

    data class Readiness(
        val commandTransportReady: Boolean,
        val recoveryMode: Boolean,
        val buildVersion: String?,
        val buildStyle: String?,
        val autoBoot: String?,
        val bootStage: String?,
        val reasons: List<String>
    ) {
        val readyForControlledUpload: Boolean
            get() = commandTransportReady && recoveryMode
    }

    data class Snapshot(
        val variables: List<VariableResult>,
        val console: RecoveryConsoleTransport.ReadResult?,
        val consoleError: Throwable?,
        val readiness: Readiness
    )

    fun snapshot(
        variables: List<String> = DEFAULT_VARIABLES,
        consoleTimeoutMs: Int = 750
    ): Snapshot {
        val results = variables.map { variable ->
            runCatching { control.getenv(variable) }.fold(
                onSuccess = { VariableResult(variable, it, null) },
                onFailure = { VariableResult(variable, null, it) }
            )
        }

        var consoleResult: RecoveryConsoleTransport.ReadResult? = null
        var consoleError: Throwable? = null
        runCatching {
            RecoveryConsoleTransport.open(device, connection)?.use { console ->
                console.read(firstTimeoutMs = consoleTimeoutMs)
            }
        }.onSuccess {
            consoleResult = it
        }.onFailure {
            consoleError = it
        }

        return Snapshot(
            variables = results,
            console = consoleResult,
            consoleError = consoleError,
            readiness = buildReadiness(results)
        )
    }

    private fun buildReadiness(results: List<VariableResult>): Readiness {
        val values = results.associate { it.name to it.result?.value?.takeIf(String::isNotBlank) }
        val coreNames = setOf("build-version", "build-style", "auto-boot", "boot-stage")
        val coreFailures = results.filter { it.name in coreNames && it.result == null }
        val reasons = buildList {
            if (AppleUsb.mode(device) != AppleUsb.Mode.RECOVERY) {
                add("USB device is not classified as Recovery mode")
            }
            if (coreFailures.isNotEmpty()) {
                add("${coreFailures.size} core iBoot environment query(s) failed")
            }
            if (values["build-version"].isNullOrBlank()) {
                add("iBoot build-version was not returned")
            }
            if (values["build-style"].isNullOrBlank()) {
                add("iBoot build-style was not returned")
            }
        }

        return Readiness(
            commandTransportReady = coreFailures.isEmpty() && !values["build-version"].isNullOrBlank(),
            recoveryMode = AppleUsb.mode(device) == AppleUsb.Mode.RECOVERY,
            buildVersion = values["build-version"],
            buildStyle = values["build-style"],
            autoBoot = values["auto-boot"],
            bootStage = values["boot-stage"],
            reasons = reasons
        )
    }

    companion object {
        /**
         * All entries are queried with `getenv <name>` only. Some iBoot versions legitimately
         * return an empty value for optional variables; that is diagnostic data, not a failure.
         */
        val DEFAULT_VARIABLES = listOf(
            "build-version",
            "build-style",
            "auto-boot",
            "boot-stage",
            "product-name",
            "model",
            "board-id",
            "chip-id",
            "security-domain",
            "production-mode",
            "security-mode",
            "debug-uarts",
            "display-timing"
        )
    }
}
