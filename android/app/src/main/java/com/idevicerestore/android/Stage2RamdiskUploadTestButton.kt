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
 * Bounded M1 Stage-2 RestoreRamDisk transport diagnostic.
 *
 * This test transfers only the already-personalized RestoreRamDisk into Stage-2 memory. It does
 * not issue the `ramdisk` command, so the uploaded image is never activated by this test. It also
 * never changes persistent environment state and never sends DeviceTree, SEP, KernelCache, bootx,
 * restore, or erase commands.
 */
class Stage2RamdiskUploadTestButton @JvmOverloads constructor(
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
            text = "Stage-2 ramdisk upload running…"
            return
        }
        val activity = activity() ?: run { isEnabled = false; return }
        val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
        // Keep the control available whenever the hardware is in the correct live USB mode.
        // Preparation/TSS details are checked synchronously when the test starts so stale UI cache
        // state cannot hide the diagnostic button.
        isEnabled = permittedM1Recovery(usb) != null
        text = READY_LABEL
    }

    private fun confirm() {
        val activity = activity() ?: return
        if (!isEnabled || inFlight.get()) return
        AlertDialog.Builder(activity)
            .setTitle("Upload RestoreRamDisk in Stage 2?")
            .setMessage(
                "This bounded test requires an M1 already at boot-stage=2 and auto-boot=true. It uploads only the prepared personalized RestoreRamDisk into Stage-2 memory, then re-checks boot-stage, build-version, and auto-boot. It deliberately DOES NOT send the 'ramdisk' activation command. It also does not send setenv, saveenv, DeviceTree, SEP, KernelCache, bootx, restore, or erase."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Run upload-only ramdisk test") { _, _ -> start() }
            .show()
    }

    private fun start() {
        val activity = activity() ?: return
        if (!inFlight.compareAndSet(false, true)) return
        isEnabled = false
        text = "Stage-2 ramdisk upload running…"
        log(
            activity,
            "Stage-2 RestoreRamDisk upload test: START boundary=RestoreRamDisk-upload-only; forbidden=ramdisk-command/setenv/saveenv/devicetree/SEP/kernel/bootx/restore/erase"
        )

        worker.execute {
            var lease: UsbOperationReservation.Lease? = null
            var connection: android.hardware.usb.UsbDeviceConnection? = null
            try {
                lease = UsbOperationReservation.tryAcquire(RESERVATION_OWNER)
                    ?: error("Another USB operation is active: ${UsbOperationReservation.owner() ?: "unknown"}")

                val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
                val recovery = permittedM1Recovery(usb) ?: error("No permitted M1 Recovery device is connected")
                val ticket = TssTicketStore.get() ?: error("TSS ticket unavailable; refresh/select firmware and let preparation complete")
                val prepared = RestoreComponentPreparationStore.get()
                    ?: error("Prepared restore components unavailable; let automatic restore preparation complete")
                val firmware = FirmwarePreparationStore.get()
                    ?: error("Firmware preparation context unavailable; select the active signed firmware")

                require(firmware.matches(ticket.buildId, ticket.identityIndex)) { "Firmware/TSS identity mismatch" }
                require(prepared.buildId.equals(ticket.buildId, ignoreCase = true) && prepared.identityIndex == ticket.identityIndex) {
                    "Prepared restore components/TSS identity mismatch"
                }
                require(foundationMatchesDevice(ticket.foundation, recovery)) { "Recovery device does not match TSS foundation" }

                val ramdisk = prepared.components.firstOrNull { it.name == RESTORE_RAMDISK }
                    ?: error("Prepared RestoreRamDisk unavailable")
                val ramdiskFile = ramdisk.personalizedFile ?: error("Personalized RestoreRamDisk unavailable")
                require(ramdisk.image4Validated) { "RestoreRamDisk failed local Image4 validation" }
                require(ramdisk.personalizationState == "personalized") {
                    "RestoreRamDisk is not fully personalized: ${ramdisk.personalizationState}"
                }
                require(ramdiskFile.isFile && ramdiskFile.length() > 0L && ramdisk.personalizedBytes == ramdiskFile.length()) {
                    "Personalized RestoreRamDisk file is missing, empty, or changed"
                }
                PersonalizedImage4Validator.validate(ramdiskFile, ticket.apImg4Ticket, RESTORE_RAMDISK)

                connection = usb.openDevice(recovery) ?: error("Could not open M1 Recovery device")
                val claimed = AppleUsb.claimBestInterface(recovery, connection) ?: error("Could not claim Recovery interface")
                val bulkOut = claimed.bulkOut ?: error("Recovery bulk OUT endpoint unavailable")
                val command = RecoveryTransport(connection, claimed.bulkIn)

                val stageBefore = command.getenv("boot-stage").value.trim()
                require(stageBefore == STAGE_2) { "Expected boot-stage=2 before RestoreRamDisk upload, got '$stageBefore'" }
                val buildBefore = command.getenv("build-version").value.trim()
                val autoBootBefore = command.getenv("auto-boot").value.trim()
                require(autoBootBefore.equals("true", ignoreCase = true)) {
                    "Safety gate requires auto-boot=true before RestoreRamDisk upload, got '$autoBootBefore'"
                }

                val ramdiskSizeRaw = runCatching { command.getenv("ramdisk-size").value.trim() }.getOrNull()
                val capacity = parseUnsignedNumber(ramdiskSizeRaw)
                if (capacity != null) {
                    require(ramdiskFile.length().toULong() <= capacity) {
                        "Prepared RestoreRamDisk (${ramdiskFile.length()} bytes) exceeds device ramdisk-size=$ramdiskSizeRaw"
                    }
                }
                log(
                    activity,
                    "Stage-2 RestoreRamDisk preflight: boot-stage=2 build-version=$buildBefore auto-boot=$autoBootBefore ramdisk-size=${ramdiskSizeRaw ?: "unavailable"} bytes=${ramdiskFile.length()} activation=NO"
                )

                val upload = FileInputStream(ramdiskFile).use { input ->
                    RecoveryUploadTransport(connection, bulkOut).sendStream(input, ramdiskFile.length())
                }
                log(
                    activity,
                    "Stage-2 RestoreRamDisk upload COMPLETE: bytes=${upload.bytesSent} packets=${upload.packetsSent} endpoint=0x%02x initResult=%s initElapsedMs=%s ramdiskCommand=NOT-SENT"
                        .format(
                            upload.endpointAddress,
                            upload.initResult?.toString() ?: "unknown",
                            upload.initElapsedMs?.toString() ?: "unknown"
                        )
                )

                val stageAfter = command.getenv("boot-stage").value.trim()
                val buildAfter = command.getenv("build-version").value.trim()
                val autoBootAfter = command.getenv("auto-boot").value.trim()
                require(stageAfter == STAGE_2) { "Stage changed after RestoreRamDisk upload: '$stageAfter'" }
                require(buildAfter == buildBefore) {
                    "Recovery build changed after RestoreRamDisk upload: before='$buildBefore' after='$buildAfter'"
                }
                require(autoBootAfter.equals("true", ignoreCase = true)) {
                    "auto-boot changed after RestoreRamDisk upload: '$autoBootAfter'"
                }

                log(
                    activity,
                    "Stage-2 RestoreRamDisk upload test: PASS boot-stage=2 build-version=$buildAfter auto-boot=$autoBootAfter ramdiskCommand=NOT-SENT"
                )
                log(
                    activity,
                    "Stage-2 RestoreRamDisk upload test: STOP boundary reached with ramdisk payload resident but not activated; no persistent environment change or later restore-entry payload sent"
                )
            } catch (t: Throwable) {
                log(activity, "Stage-2 RestoreRamDisk upload test FAILED: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                connection?.close()
                lease?.let { runCatching { UsbOperationReservation.release(it) } }
                inFlight.set(false)
                activity.runOnUiThread { if (isAttachedToWindow) refreshState() }
            }
        }
    }

    private fun parseUnsignedNumber(raw: String?): ULong? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            if (value.startsWith("0x", ignoreCase = true)) value.substring(2).toULong(16) else value.toULong()
        }.getOrNull()
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
        private const val RESTORE_RAMDISK = "RestoreRamDisk"
        private const val RESERVATION_OWNER = "stage2-ramdisk-upload-test"
        private const val READY_LABEL = "Test M1 Stage-2 RamDisk Upload"
    }
}
