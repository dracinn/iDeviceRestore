package com.idevicerestore.android

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection

/**
 * Read-only Recovery communication snapshot.
 *
 * Control requests use the already-claimed primary Recovery interface. The optional console
 * sample is collected from a secondary bulk-IN alternate interface and released immediately.
 * No environment mutation, reboot, image upload, or bulk-OUT operation is exposed here.
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
        val failures = results.filter { it.result == null }
        val reasons = buildList {
            if (AppleUsb.mode(device) != AppleUsb.Mode.RECOVERY) {
                add("USB device is not classified as Recovery mode")
            }
            if (failures.isNotEmpty()) {
                add("${failures.size} iBoot environment query(s) failed")
            }
            if (values["build-version"].isNullOrBlank()) {
                add("iBoot build-version was not returned")
            }
            if (values["build-style"].isNullOrBlank()) {
                add("iBoot build-style was not returned")
            }
        }

        return Readiness(
            commandTransportReady = failures.isEmpty() && !values["build-version"].isNullOrBlank(),
            recoveryMode = AppleUsb.mode(device) == AppleUsb.Mode.RECOVERY,
            buildVersion = values["build-version"],
            buildStyle = values["build-style"],
            autoBoot = values["auto-boot"],
            bootStage = values["boot-stage"],
            reasons = reasons
        )
    }

    companion object {
        val DEFAULT_VARIABLES = listOf("build-version", "build-style", "auto-boot", "boot-stage")
    }
}
