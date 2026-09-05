package com.idevicerestore.android

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import java.io.File
import java.io.FileInputStream

/**
 * Explicit DFU stage-1 session for a personalized iBSS image.
 *
 * This class is intentionally not used by automatic probing. The caller must provide an image that
 * has already been personalized for the connected device/TSS response. Raw IPSW components are
 * represented separately by [IpswComponentExtractor.ExtractedComponent] so they cannot be passed
 * accidentally to [uploadPersonalizedIbss].
 */
class DfuStage1Session(
    device: UsbDevice,
    connection: UsbDeviceConnection,
    interfaceId: Int,
    private val transitions: RestoreUsbStateMachine,
    private val logger: (String) -> Unit = {}
) {
    data class PersonalizedIbss(
        val file: File,
        val identityIndex: Int,
        val sourceManifestPath: String,
        val personalizationId: String
    ) {
        init {
            require(file.isFile) { "Personalized iBSS file not found: ${file.absolutePath}" }
            require(file.length() > 0) { "Personalized iBSS is empty" }
            require(sourceManifestPath.isNotBlank()) { "iBSS source manifest path is required" }
            require(personalizationId.isNotBlank()) { "Personalization identifier is required" }
        }
    }

    data class UploadResult(
        val bytes: Long,
        val blocks: Int,
        val finalDfuState: Int,
        val manifestationState: Int,
        val usbResetResult: AndroidUsbReset.Result,
        val transition: RestoreUsbStateMachine.Snapshot
    )

    private val uploader: DfuUploadTransport
    private val deviceIdentityIndexSafeMode: AppleUsb.BootIdentifiers?

    init {
        require(device.vendorId == AppleUsb.APPLE_VID) { "DFU stage1 requires an Apple USB device" }
        require(AppleUsb.mode(device) == AppleUsb.Mode.DFU) {
            "DFU stage1 requires DFU mode, got ${AppleUsb.mode(device)}"
        }
        deviceIdentityIndexSafeMode = AppleUsb.bootIdentifiers(device)
        transitions.begin(device)
        uploader = DfuUploadTransport(connection, interfaceId)
    }

    /**
     * Uploads Apple-framed personalized iBSS and then performs the explicit DFU finish request.
     * After this returns, the caller must discard the current UsbDeviceConnection and wait for a
     * fresh Recovery-mode attach accepted by [RestoreUsbStateMachine.onAttached].
     */
    fun uploadPersonalizedIbss(
        image: PersonalizedIbss,
        onProgress: ((DfuUploadTransport.Progress) -> Unit)? = null
    ): UploadResult {
        val ids = deviceIdentityIndexSafeMode
        logger(
            "DFU stage1: personalized iBSS identity=${image.identityIndex} bytes=${image.file.length()} " +
                "cpid=${ids?.cpidHex ?: "unknown"} bdid=${ids?.bdidHex ?: "unknown"}"
        )

        val upload = FileInputStream(image.file).use { input ->
            uploader.sendStream(input, image.file.length(), onProgress)
        }
        logger(
            "DFU stage1: iBSS upload complete payload=${upload.bytesSent} blocks=${upload.blocksSent} " +
                "state=${DfuTransport.stateName(upload.finalState)}"
        )

        val waiting = transitions.expectRecoveryReconnect()
        logger("DFU stage1: sending libirecovery-compatible finish request; ${waiting.message}")
        val finish = uploader.finishManifestation(upload.blocksSent)
        val resetNote = when (finish.usbResetResult) {
            AndroidUsbReset.Result.SUCCESS -> "host USB reset reported success"
            AndroidUsbReset.Result.INDETERMINATE_FALSE -> "host USB reset returned false after manifestation; treating as indeterminate and deferring outcome to USB re-enumeration"
        }
        logger(
            "DFU stage1: finish state=${DfuTransport.stateName(finish.manifestationState)}; $resetNote; waiting for USB re-enumeration"
        )

        return UploadResult(
            bytes = upload.bytesSent,
            blocks = upload.blocksSent,
            finalDfuState = upload.finalState,
            manifestationState = finish.manifestationState,
            usbResetResult = finish.usbResetResult,
            transition = transitions.snapshot()
        )
    }
}
