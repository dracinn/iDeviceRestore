package com.idevicerestore.android

import android.content.Context
import android.content.ContextWrapper
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Guarded M1 hardware test: upload personalized iBEC only after proven upload-init re-enumeration. */
class IbecPostInitUploadButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatButton(context, attrs) {
    private val worker = Executors.newSingleThreadExecutor()
    private val inFlight = AtomicBoolean(false)
    private val refresh = object : Runnable {
        override fun run() {
            refreshState()
            if (isAttachedToWindow) postDelayed(this, REFRESH_MS)
        }
    }

    init {
        text = READY_LABEL
        isEnabled = false
        setOnClickListener { confirm() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(refresh)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refresh)
        worker.shutdown()
        super.onDetachedFromWindow()
    }

    private fun refreshState() {
        val activity = activity() ?: return
        if (inFlight.get()) {
            isEnabled = false
            text = "Uploading iBEC…"
            return
        }

        val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = recoveryDevice(usb)
        val observation = IbecTransitionObservationStore.snapshot()
        val ticket = TssTicketStore.get()
        val prepared = RestoreComponentPreparationStore.get()
        val ibec = prepared?.components?.firstOrNull { it.name == "iBEC" }

        val ready = device != null &&
            observation.state == IbecTransitionObservationStore.State.SUCCEEDED &&
            observation.boundary == IbecTransitionObservationStore.Boundary.UPLOAD_INIT_ONLY &&
            observation.lastObservedUsbKey == usbKey(device) &&
            observation.postBootStage?.trim() == STAGE_1 &&
            !observation.postBuildVersion.isNullOrBlank() &&
            ticket != null &&
            foundationMatchesDevice(ticket.foundation, device) &&
            prepared != null &&
            prepared.buildId.equals(ticket.buildId, true) &&
            prepared.identityIndex == ticket.identityIndex &&
            ibec?.personalizedFile?.isFile == true &&
            ibec.personalizedBytes == ibec.personalizedFile.length()

        isEnabled = ready
        text = if (ready) READY_LABEL else "Post-init iBEC Upload Not Ready"
    }

    private fun confirm() {
        val activity = activity() ?: return
        if (!isEnabled || inFlight.get()) return
        AlertDialog.Builder(activity)
            .setTitle("Upload M1 iBEC after init re-enumeration?")
            .setMessage(
                "This guarded test consumes the proven upload-init re-enumeration, reopens the same Stage-1 Recovery device, and sends only the personalized iBEC over bulk endpoint 0x04 without sending 0x41/0 again. It stops after the full iBEC upload and will not send 'go' or any restore-OS payloads."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Upload iBEC Only") { _, _ -> start() }
            .show()
    }

    private fun start() {
        val activity = activity() ?: return
        if (!inFlight.compareAndSet(false, true)) return
        isEnabled = false
        text = "Uploading iBEC…"
        setOperation(activity, "M1 post-init iBEC bulk upload starting…", true)
        log(activity, "iBEC post-init upload: explicit user confirmation received; boundary=bulk-upload-only-no-go")

        val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
        worker.execute {
            var connection: android.hardware.usb.UsbDeviceConnection? = null
            try {
                val observation = IbecTransitionObservationStore.snapshot()
                require(observation.state == IbecTransitionObservationStore.State.SUCCEEDED) { "Upload-init re-enumeration proof is not available" }
                require(observation.boundary == IbecTransitionObservationStore.Boundary.UPLOAD_INIT_ONLY) { "Observation is not an upload-init proof" }
                val expectedBootStage = observation.postBootStage?.trim().orEmpty()
                val expectedBuildVersion = observation.postBuildVersion?.trim().orEmpty()
                require(expectedBootStage == STAGE_1 && expectedBuildVersion.isNotBlank()) { "Upload-init proof lacks a valid Stage-1 post-state" }

                val device = recoveryDevice(usb) ?: error("No permitted Apple Recovery device is connected")
                require(observation.lastObservedUsbKey == usbKey(device)) { "Recovery USB identity changed after upload-init proof" }
                val ids = AppleUsb.bootIdentifiers(device) ?: error("Recovery boot identifiers unavailable")
                require(ids.cpidHex.equals(M1_CPID, true)) { "Post-init iBEC upload is restricted to M1 CPID 0x$M1_CPID" }

                val ticket = TssTicketStore.get() ?: error("TSS ticket unavailable")
                require(foundationMatchesDevice(ticket.foundation, device)) { "Recovery device does not match the ECID/chip/board identity used for the TSS ticket" }
                val prepared = RestoreComponentPreparationStore.get() ?: error("Restore components unavailable")
                require(prepared.buildId.equals(ticket.buildId, true) && prepared.identityIndex == ticket.identityIndex) { "Prepared iBEC/TSS identity mismatch" }
                val ibec = prepared.components.firstOrNull { it.name == "iBEC" } ?: error("Prepared iBEC unavailable")
                val file = ibec.personalizedFile ?: error("Personalized iBEC unavailable")
                require(file.isFile && file.length() == ibec.personalizedBytes) { "Personalized iBEC file validation failed" }
                PersonalizedImage4Validator.validate(file, ticket.apImg4Ticket, "iBEC")
                log(activity, "iBEC post-init upload: personalized IMG4/TSS revalidated bytes=${file.length()} build=${prepared.buildId} identity=${prepared.identityIndex}")

                connection = usb.openDevice(device) ?: error("openDevice failed for post-init iBEC upload")
                val claimed = AppleUsb.claimBestInterface(device, connection) ?: error("Could not claim Recovery interface")
                val bulkOut = claimed.bulkOut ?: error("Recovery bulk OUT endpoint unavailable")
                val command = RecoveryTransport(connection, claimed.bulkIn)
                val liveBootStage = command.getenv("boot-stage").value.trim()
                val liveBuildVersion = command.getenv("build-version").value.trim()
                require(liveBootStage == expectedBootStage) { "Live boot-stage=$liveBootStage no longer matches proven post-init boot-stage=$expectedBootStage" }
                require(liveBuildVersion == expectedBuildVersion) { "Live build-version=$liveBuildVersion no longer matches proven post-init build-version=$expectedBuildVersion" }
                require(foundationMatchesDevice(ticket.foundation, device)) { "Recovery identity changed during post-init revalidation" }
                log(activity, "iBEC post-init gate: same-device proof matched boot-stage=$liveBootStage build-version=$liveBuildVersion usbKey=${usbKey(device)}")

                val setInterface = connection.setInterface(claimed.intf)
                require(setInterface) { "Android could not activate claimed Recovery interface id=${claimed.intf.id} alt=${claimed.intf.alternateSetting}" }
                log(activity, "iBEC post-init USB: claimed interface id=${claimed.intf.id} alt=${claimed.intf.alternateSetting}; setInterface=true; bulkOut=0x%02x type=${bulkOut.type} maxPacket=${bulkOut.maxPacketSize}".format(bulkOut.address))

                val consumed = IbecTransitionObservationStore.consumeUploadInitProof(
                    currentUsbKey = usbKey(device),
                    bootStage = liveBootStage,
                    buildVersion = liveBuildVersion
                )
                require(consumed) { "Upload-init proof changed before bulk upload; refusing to bypass 0x41/0" }
                log(activity, "iBEC post-init token: consumed one-shot upload-init re-enumeration proof; 0x41/0 will NOT be sent again")

                val uploader = RecoveryUploadTransport(connection, bulkOut)
                val result = FileInputStream(file).use { input ->
                    uploader.sendStreamAfterInitReenumeration(input, file.length()) { progress ->
                        setProgress(activity, progress.percent)
                        if (progress.packetIndex == 1 || progress.bytesSent == progress.totalBytes) {
                            log(activity, "iBEC post-init bulk progress: packet=${progress.packetIndex} bytes=${progress.bytesSent}/${progress.totalBytes} percent=${progress.percent}")
                        }
                    }
                }
                log(activity, "iBEC post-init upload COMPLETE: bytes=${result.bytesSent} packets=${result.packetsSent} endpoint=0x%02x".format(result.endpointAddress))
                log(activity, "iBEC post-init upload: STOP boundary reached — no go command sent; no RestoreRamDisk/SEP/DeviceTree/KernelCache payload sent")
                setOperation(activity, "Personalized iBEC uploaded completely; stopped before go", false)
            } catch (t: Throwable) {
                log(activity, "iBEC post-init upload FAILED: ${t.javaClass.simpleName}: ${t.message}")
                setOperation(activity, "Post-init iBEC upload failed: ${t.message ?: t.javaClass.simpleName}", false)
            } finally {
                connection?.close()
                if (connection != null) log(activity, "iBEC post-init upload: Recovery USB connection closed")
                inFlight.set(false)
                activity.runOnUiThread { if (isAttachedToWindow) refreshState() }
            }
        }
    }

    private fun recoveryDevice(usb: UsbManager): UsbDevice? = usb.deviceList.values.firstOrNull {
        it.vendorId == AppleUsb.APPLE_VID && AppleUsb.mode(it) == AppleUsb.Mode.RECOVERY && usb.hasPermission(it)
    }

    private fun foundationMatchesDevice(f: TssRequestFoundation.Parameters, d: UsbDevice): Boolean {
        val ids = AppleUsb.bootIdentifiers(d) ?: return false
        val ecid = ids.ecidHex?.toULongOrNull(16) ?: return false
        val cpid = ids.cpidHex?.toLongOrNull(16) ?: return false
        val bdid = ids.bdidHex?.toLongOrNull(16) ?: return false
        return f.ecid == ecid && f.apChipId == cpid && f.apBoardId == bdid
    }

    private fun usbKey(d: UsbDevice) = "${d.deviceName}:${d.productId}"

    private fun setProgress(a: AppCompatActivity, percent: Int) = a.runOnUiThread {
        a.findViewById<android.widget.ProgressBar?>(R.id.operationProgress)?.apply {
            visibility = View.VISIBLE
            isIndeterminate = false
            progress = percent.coerceIn(0, 100)
        }
        a.findViewById<TextView?>(R.id.operationStatus)?.text = "Uploading personalized iBEC… ${percent.coerceIn(0, 100)}%"
    }

    private fun setOperation(a: AppCompatActivity, message: String, busy: Boolean) = a.runOnUiThread {
        a.findViewById<TextView?>(R.id.operationStatus)?.text = message
        a.findViewById<android.widget.ProgressBar?>(R.id.operationProgress)?.apply {
            visibility = if (busy) View.VISIBLE else View.GONE
            if (busy) isIndeterminate = true
        }
    }

    private fun log(a: AppCompatActivity, message: String) = a.runOnUiThread {
        val delivered = runCatching {
            val method = a.javaClass.getDeclaredMethod("log", String::class.java)
            method.isAccessible = true
            method.invoke(a, message)
            true
        }.getOrDefault(false)
        if (!delivered) a.findViewById<TextView?>(R.id.logView)?.append(message.trimEnd() + "\n")
    }

    private fun activity(): AppCompatActivity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is AppCompatActivity) return c
            c = c.baseContext
        }
        return c as? AppCompatActivity
    }

    companion object {
        private const val REFRESH_MS = 1000L
        private const val M1_CPID = "8103"
        private const val STAGE_1 = "1"
        private const val READY_LABEL = "Test M1 Post-init iBEC Bulk Upload"
    }
}
