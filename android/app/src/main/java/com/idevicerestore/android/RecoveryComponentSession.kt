package com.idevicerestore.android

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Explicit, stateful Recovery component session.
 *
 * This class deliberately does not run during automatic probing. Callers must opt into uploads
 * and execution. It tracks which component was uploaded so state-changing commands cannot be
 * issued accidentally before the expected image transfer has completed.
 */
class RecoveryComponentSession(
    device: UsbDevice,
    private val command: RecoveryTransport,
    connection: UsbDeviceConnection,
    bulkOut: UsbEndpoint
) {
    enum class Component {
        IBEC,
        RESTORE_LOGO,
        RESTORE_RAMDISK,
        RESTORE_DEVICE_TREE,
        RESTORE_SEP,
        RESTORE_KERNEL_CACHE,
        OTHER
    }

    data class UploadedComponent(
        val component: Component,
        val label: String,
        val bytes: Long,
        val packets: Int,
        val endpointAddress: Int
    )

    data class AppleSiliconIbecExecution(
        val goCommandBytes: Int,
        val followUpTransfer: RecoveryTransport.ControlTransferResult?,
        val followUpError: Throwable?
    ) {
        val followUpAccepted: Boolean get() = followUpTransfer != null
    }

    private val uploader: RecoveryUploadTransport
    private var lastUpload: UploadedComponent? = null

    init {
        require(device.vendorId == AppleUsb.APPLE_VID) { "Recovery session requires an Apple USB device" }
        require(AppleUsb.mode(device) == AppleUsb.Mode.RECOVERY) {
            "Recovery component session requires Recovery mode, got ${AppleUsb.mode(device)}"
        }
        uploader = RecoveryUploadTransport(connection, bulkOut)
    }

    fun lastUploadedComponent(): UploadedComponent? = lastUpload

    /** Issues only the libirecovery Recovery upload initialization request; sends no component bytes. */
    fun initializeUpload(): Int = uploader.initializeUpload()

    fun upload(
        component: Component,
        label: String,
        input: InputStream,
        length: Long,
        onProgress: ((RecoveryUploadTransport.Progress) -> Unit)? = null
    ): UploadedComponent {
        require(label.isNotBlank()) { "Component label cannot be blank" }
        val result = uploader.sendStream(input, length, onProgress)
        return UploadedComponent(
            component = component,
            label = label,
            bytes = result.bytesSent,
            packets = result.packetsSent,
            endpointAddress = result.endpointAddress
        ).also { lastUpload = it }
    }

    fun uploadFile(
        component: Component,
        file: File,
        onProgress: ((RecoveryUploadTransport.Progress) -> Unit)? = null
    ): UploadedComponent {
        require(file.isFile) { "Recovery component does not exist or is not a file: ${file.absolutePath}" }
        FileInputStream(file).use { input ->
            return upload(component, file.name, input, file.length(), onProgress)
        }
    }

    /**
     * Executes an uploaded iBEC using the exact Apple Silicon sequence used by idevicerestore:
     * send `go` with bRequest=1, then issue a zero-length class/interface OUT transfer 0x21/1.
     *
     * Upstream does not require the second transfer to return successfully because iBEC can reset
     * USB immediately. We therefore expose its outcome but do not turn a disconnect into a failed
     * execution after the `go` command itself was accepted.
     */
    fun executeAppleSiliconIbec(): AppleSiliconIbecExecution {
        val uploaded = lastUpload ?: error("No Recovery component has been uploaded")
        check(uploaded.component == Component.IBEC) {
            "Refusing Apple Silicon iBEC execution: last uploaded component is ${uploaded.component}"
        }

        val goBytes = command.sendCommandBreq("go", RecoveryTransport.APPLE_SILICON_GO_BREQUEST)
        val followUp = runCatching {
            command.controlTransferOut(
                requestType = APPLE_SILICON_IBEC_FOLLOWUP_REQUEST_TYPE,
                request = APPLE_SILICON_IBEC_FOLLOWUP_REQUEST,
                value = 0,
                index = 0,
                data = null,
                timeoutMs = APPLE_SILICON_IBEC_FOLLOWUP_TIMEOUT_MS
            )
        }
        return AppleSiliconIbecExecution(
            goCommandBytes = goBytes,
            followUpTransfer = followUp.getOrNull(),
            followUpError = followUp.exceptionOrNull()
        )
    }

    /** Sends a component-specific iBoot command only after the expected component upload. */
    fun executeUploadedComponent(expected: Component, ibootCommand: String): Int {
        require(ibootCommand.isNotBlank()) { "iBoot command cannot be blank" }
        val uploaded = lastUpload ?: error("No Recovery component has been uploaded")
        check(uploaded.component == expected) {
            "Refusing command '$ibootCommand': expected last upload $expected, got ${uploaded.component}"
        }
        return command.sendCommand(ibootCommand)
    }

    companion object {
        private const val APPLE_SILICON_IBEC_FOLLOWUP_REQUEST_TYPE = 0x21
        private const val APPLE_SILICON_IBEC_FOLLOWUP_REQUEST = 1
        private const val APPLE_SILICON_IBEC_FOLLOWUP_TIMEOUT_MS = 5_000
    }
}
