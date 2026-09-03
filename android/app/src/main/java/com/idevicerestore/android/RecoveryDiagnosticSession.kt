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

    data class Snapshot(
        val variables: List<VariableResult>,
        val console: RecoveryConsoleTransport.ReadResult?,
        val consoleError: Throwable?
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

        return Snapshot(results, consoleResult, consoleError)
    }

    companion object {
        val DEFAULT_VARIABLES = listOf("build-version", "build-style", "auto-boot")
    }
}
