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
     * Executes an uploaded iBEC using the Apple Silicon bRequest=1 command path used upstream.
     *
     * This does not upload iBEC and cannot be called unless this session successfully uploaded an
     * iBEC immediately beforehand. The device is expected to disconnect/re-enumerate after `go`;
     * callers must discard this UsbDeviceConnection when that happens.
     */
    fun executeAppleSiliconIbec(): Int {
        val uploaded = lastUpload ?: error("No Recovery component has been uploaded")
        check(uploaded.component == Component.IBEC) {
            "Refusing Apple Silicon iBEC execution: last uploaded component is ${uploaded.component}"
        }
        return command.sendCommandBreq("go", RecoveryTransport.APPLE_SILICON_GO_BREQUEST)
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
}
