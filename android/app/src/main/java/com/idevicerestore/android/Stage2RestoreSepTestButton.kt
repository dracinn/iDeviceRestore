package com.idevicerestore.android

import android.content.Context
import android.content.ContextWrapper
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.AttributeSet
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded M1 Stage-2 RestoreSEP transport test.
 *
 * Run only after the proven Stage-2 firmware + RestoreRamDisk + RestoreDeviceTree test has passed in
 * the current boot session. This mirrors the next upstream recovery_enter_restore() operation:
 * upload personalized RestoreSEP and send `rsepfirmware`, then stop before RestoreKernelCache,
 * restore boot arguments, bootx, restore, erase, or persistent environment changes.
 */
class Stage2RestoreSepTestButton @JvmOverloads constructor(
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
        worker.shutdownNow()
        super.onDetachedFromWindow()
    }

    private fun refreshState() {
        if (inFlight.get()) {
            isEnabled = false
            text = RUNNING_LABEL
            return
        }
        val activity = activity() ?: run { isEnabled = false; return }
        val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
        isEnabled = permittedM1Recovery(usb) != null
        text = READY_LABEL
    }

    private fun confirm() {
        val activity = activity() ?: return
        if (!isEnabled || inFlight.get()) return
        AlertDialog.Builder(activity)
            .setTitle("Run bounded Stage-2 RestoreSEP test?")
            .setMessage(
                "Run this only after the Stage-2 DeviceTree activation test reports PASS in the current boot session. " +
                    "This test verifies boot-stage=2 and auto-boot=true, validates the prepared personalized RestoreSEP, uploads only RestoreSEP, sends upstream 'rsepfirmware', verifies Stage-2 again, then stops. " +
                    "It does not send setenv, saveenv, RestoreKernelCache, boot arguments, bootx, restore, or erase."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Run bounded RestoreSEP test") { _, _ -> start() }
            .show()
    }

    private fun start() {
        val activity = activity() ?: return
        if (!inFlight.compareAndSet(false, true)) return
        isEnabled = false
        text = RUNNING_LABEL
        log(
            activity,
            "Stage-2 RestoreSEP test: START boundary=RestoreSEP-upload+rsepfirmware-only prerequisite=DeviceTree-test-PASS; forbidden=setenv/saveenv/kernel/bootargs/bootx/restore/erase"
        )

        worker.execute {
            var lease: UsbOperationReservation.Lease? = null
            var connection: android.hardware.usb.UsbDeviceConnection? = null
            try {
                lease = UsbOperationReservation.tryAcquire(RESERVATION_OWNER)
                    ?: error("Another USB operation is active: ${UsbOperationReservation.owner() ?: "unknown"}")

                val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
                val recovery = permittedM1Recovery(usb) ?: error("No permitted M1 Recovery device is connected")
                val ticket = TssTicketStore.get()
                    ?: error("TSS ticket unavailable; select/refresh firmware and let preparation complete")
                val prepared = RestoreComponentPreparationStore.get()
                    ?: error("Prepared restore components unavailable; let automatic restore preparation complete")
                val firmware = FirmwarePreparationStore.get()
                    ?: error("Firmware preparation context unavailable; select the active signed firmware")

                require(firmware.matches(ticket.buildId, ticket.identityIndex)) { "Firmware/TSS identity mismatch" }
                require(prepared.buildId.equals(ticket.buildId, ignoreCase = true) && prepared.identityIndex == ticket.identityIndex) {
                    "Prepared restore components/TSS identity mismatch"
                }
                require(foundationMatchesDevice(ticket.foundation, recovery)) { "Recovery device does not match TSS foundation" }

                val sep = prepared.components.firstOrNull { it.name == RESTORE_SEP }
                    ?: error("Selected BuildIdentity has no prepared RestoreSEP")
                val sepFile = sep.personalizedFile ?: error("Personalized RestoreSEP unavailable")
                require(sep.image4Validated) { "RestoreSEP failed local Image4 validation" }
                require(sep.personalizationState == "personalized") {
                    "RestoreSEP is not fully personalized: ${sep.personalizationState}"
                }
                require(sepFile.isFile && sepFile.length() > 0L && sep.personalizedBytes == sepFile.length()) {
                    "RestoreSEP personalized file is missing, empty, or changed"
                }
                PersonalizedImage4Validator.validate(sepFile, ticket.apImg4Ticket, RESTORE_SEP)

                connection = usb.openDevice(recovery) ?: error("Could not open M1 Recovery device")
                val claimed = AppleUsb.claimBestInterface(recovery, connection) ?: error("Could not claim Recovery interface")
                val bulkOut = claimed.bulkOut ?: error("Recovery bulk OUT endpoint unavailable")
                val command = RecoveryTransport(connection, claimed.bulkIn)

                val stageBefore = command.getenv("boot-stage").value.trim()
                require(stageBefore == STAGE_2) { "Expected boot-stage=2 before RestoreSEP, got '$stageBefore'" }
                val buildBefore = command.getenv("build-version").value.trim()
                val autoBootBefore = command.getenv("auto-boot").value.trim()
                require(autoBootBefore.equals("true", ignoreCase = true)) {
                    "Safety gate requires auto-boot=true before RestoreSEP, got '$autoBootBefore'"
                }
                log(
                    activity,
                    "Stage-2 RestoreSEP preflight: boot-stage=2 build-version=$buildBefore auto-boot=true bytes=${sepFile.length()} command=rsepfirmware"
                )

                val upload = FileInputStream(sepFile).use { input ->
                    RecoveryUploadTransport(connection, bulkOut).sendStream(input, sepFile.length())
                }
                log(
                    activity,
                    "Stage-2 RestoreSEP upload COMPLETE: bytes=${upload.bytesSent} packets=${upload.packetsSent} endpoint=0x%02x initResult=%s initElapsedMs=%s"
                        .format(
                            upload.endpointAddress,
                            upload.initResult?.toString() ?: "unknown",
                            upload.initElapsedMs?.toString() ?: "unknown"
                        )
                )

                verifyStableStage2(command, buildBefore, "after RestoreSEP upload")
                log(activity, "Stage-2 RestoreSEP activation command START: rsepfirmware")
                val commandBytes = command.sendCommand("rsepfirmware")
                log(activity, "Stage-2 RestoreSEP activation command COMPLETE: rsepfirmware bytes=$commandBytes upstreamDelayMs=0")

                verifyStableStage2(command, buildBefore, "after rsepfirmware")
                log(
                    activity,
                    "Stage-2 RestoreSEP test: PASS bytes=${upload.bytesSent} command=rsepfirmware boot-stage=2 build-version=$buildBefore auto-boot=true"
                )
                log(
                    activity,
                    "Stage-2 RestoreSEP test: STOP boundary reached after rsepfirmware; no persistent environment change, RestoreKernelCache, boot arguments, bootx, restore, or erase sent"
                )
            } catch (t: Throwable) {
                log(activity, "Stage-2 RestoreSEP test FAILED: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                connection?.close()
                lease?.let { runCatching { UsbOperationReservation.release(it) } }
                inFlight.set(false)
                activity.runOnUiThread { if (isAttachedToWindow) refreshState() }
            }
        }
    }

    private fun verifyStableStage2(command: RecoveryTransport, expectedBuild: String, where: String) {
        val stage = command.getenv("boot-stage").value.trim()
        val autoBoot = command.getenv("auto-boot").value.trim()
        val build = command.getenv("build-version").value.trim()
        require(stage == STAGE_2) { "Stage changed $where: '$stage'" }
        require(autoBoot.equals("true", ignoreCase = true)) { "auto-boot changed $where: '$autoBoot'" }
        require(build == expectedBuild) { "Recovery build changed $where: expected='$expectedBuild' actual='$build'" }
    }

    private fun permittedM1Recovery(usb: UsbManager): UsbDevice? = usb.deviceList.values.firstOrNull { device ->
        device.vendorId == AppleUsb.APPLE_VID &&
            AppleUsb.mode(device) == AppleUsb.Mode.RECOVERY &&
            usb.hasPermission(device) &&
            AppleUsb.bootIdentifiers(device)?.cpidHex.equals(M1_CPID, ignoreCase = true)
    }

    private fun foundationMatchesDevice(f: TssRequestFoundation.Parameters, d: UsbDevice): Boolean {
        val ids = AppleUsb.bootIdentifiers(d) ?: return false
        val ecid = ids.ecidHex?.toULongOrNull(16) ?: return false
        val cpid = ids.cpidHex?.toLongOrNull(16) ?: return false
        val bdid = ids.bdidHex?.toLongOrNull(16) ?: return false
        return f.ecid == ecid && f.apChipId == cpid && f.apBoardId == bdid
    }

    private fun log(activity: AppCompatActivity, message: String) = activity.runOnUiThread {
        val delivered = runCatching {
            val method = activity.javaClass.getDeclaredMethod("log", String::class.java)
            method.isAccessible = true
            method.invoke(activity, message)
            true
        }.getOrDefault(false)
        if (!delivered) activity.findViewById<TextView?>(R.id.logView)?.append(message.trimEnd() + "\n")
    }

    private fun activity(): AppCompatActivity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is AppCompatActivity) return current
            current = current.baseContext
        }
        return current as? AppCompatActivity
    }

    companion object {
        private const val REFRESH_MS = 1000L
        private const val M1_CPID = "8103"
        private const val STAGE_2 = "2"
        private const val RESTORE_SEP = "RestoreSEP"
        private const val RESERVATION_OWNER = "stage2-restoresep-test"
        private const val READY_LABEL = "Test M1 Stage-2 RestoreSEP"
        private const val RUNNING_LABEL = "Stage-2 RestoreSEP test running…"
    }
}
