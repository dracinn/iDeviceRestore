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
 * Bounded M1 Stage-2 transport diagnostic for the first upstream restore-entry payload.
 *
 * The test uploads only the already-personalized RestoreLogo and mirrors upstream's display-only
 * `setpicture 4` + `bgcolor 0 0 0` commands. It deliberately does not change auto-boot, call
 * saveenv, send any IsLoadedByiBoot firmware, execute a ramdisk, load DeviceTree/SEP/kernelcache,
 * bootx, restore, or erase.
 */
class Stage2RestoreLogoTestButton @JvmOverloads constructor(
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
            text = "Stage-2 RestoreLogo test running…"
            return
        }
        val activity = activity() ?: run { isEnabled = false; return }
        val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
        val recovery = permittedM1Recovery(usb)
        val ticket = TssTicketStore.get()
        val prepared = RestoreComponentPreparationStore.get()
        val logo = prepared?.components?.firstOrNull { it.name == RESTORE_LOGO }
        val logoFile = logo?.personalizedFile
        val firmware = FirmwarePreparationStore.get()

        isEnabled = recovery != null && ticket != null && prepared != null && logo != null &&
            logoFile?.isFile == true && logo.personalizedBytes == logoFile.length() &&
            logo.personalizationState == "personalized" &&
            firmware?.matches(ticket.buildId, ticket.identityIndex) == true &&
            prepared.buildId.equals(ticket.buildId, ignoreCase = true) &&
            prepared.identityIndex == ticket.identityIndex &&
            foundationMatchesDevice(ticket.foundation, recovery)
        text = READY_LABEL
    }

    private fun confirm() {
        val activity = activity() ?: return
        if (!isEnabled || inFlight.get()) return
        AlertDialog.Builder(activity)
            .setTitle("Send RestoreLogo in Stage 2?")
            .setMessage(
                "This bounded hardware test requires an M1 already at boot-stage=2. It uploads only the prepared personalized RestoreLogo, then sends upstream's display-only 'setpicture 4' and 'bgcolor 0 0 0' commands. " +
                    "It requires auto-boot=true before the upload and proves auto-boot is still true and boot-stage is still 2 afterward. It does not send setenv, saveenv, firmware components, RestoreRamDisk, DeviceTree, SEP, KernelCache, bootx, restore, or erase."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Run bounded RestoreLogo test") { _, _ -> start() }
            .show()
    }

    private fun start() {
        val activity = activity() ?: return
        if (!inFlight.compareAndSet(false, true)) return
        isEnabled = false
        text = "Stage-2 RestoreLogo test running…"
        log(
            activity,
            "Stage-2 RestoreLogo test: START boundary=RestoreLogo-upload+setpicture-4+bgcolor-only; forbidden=setenv/saveenv/firmware/ramdisk/devicetree/SEP/kernel/bootx/restore/erase"
        )

        worker.execute {
            var lease: UsbOperationReservation.Lease? = null
            var connection: android.hardware.usb.UsbDeviceConnection? = null
            try {
                lease = UsbOperationReservation.tryAcquire(RESERVATION_OWNER)
                    ?: error("Another USB operation is active: ${UsbOperationReservation.owner() ?: "unknown"}")

                val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
                val recovery = permittedM1Recovery(usb) ?: error("No permitted M1 Recovery device is connected")
                val ticket = TssTicketStore.get() ?: error("TSS ticket unavailable")
                val prepared = RestoreComponentPreparationStore.get() ?: error("Prepared restore components unavailable")
                val firmware = FirmwarePreparationStore.get() ?: error("Firmware preparation context unavailable")
                require(firmware.matches(ticket.buildId, ticket.identityIndex)) { "Firmware/TSS identity mismatch" }
                require(prepared.buildId.equals(ticket.buildId, ignoreCase = true) && prepared.identityIndex == ticket.identityIndex) {
                    "Prepared restore components/TSS identity mismatch"
                }
                require(foundationMatchesDevice(ticket.foundation, recovery)) { "Recovery device does not match TSS foundation" }

                val logo = prepared.components.firstOrNull { it.name == RESTORE_LOGO }
                    ?: error("Prepared RestoreLogo unavailable")
                val logoFile = logo.personalizedFile ?: error("Personalized RestoreLogo unavailable")
                require(logoFile.isFile && logoFile.length() > 0L) { "Personalized RestoreLogo file is missing or empty" }
                require(logo.personalizationState == "personalized" && logo.personalizedBytes == logoFile.length()) {
                    "RestoreLogo preparation is incomplete or changed"
                }
                PersonalizedImage4Validator.validate(logoFile, ticket.apImg4Ticket, RESTORE_LOGO)

                connection = usb.openDevice(recovery) ?: error("Could not open M1 Recovery device")
                val claimed = AppleUsb.claimBestInterface(recovery, connection) ?: error("Could not claim Recovery interface")
                val bulkOut = claimed.bulkOut ?: error("Recovery bulk OUT endpoint unavailable")
                val command = RecoveryTransport(connection, claimed.bulkIn)

                val stageBefore = command.getenv("boot-stage").value.trim()
                require(stageBefore == STAGE_2) { "Expected boot-stage=2 before RestoreLogo, got '$stageBefore'" }
                val buildBefore = command.getenv("build-version").value.trim()
                val autoBootBefore = command.getenv("auto-boot").value.trim()
                require(autoBootBefore.equals("true", ignoreCase = true)) {
                    "Safety gate requires auto-boot=true before RestoreLogo test, got '$autoBootBefore'"
                }
                log(
                    activity,
                    "Stage-2 RestoreLogo preflight: boot-stage=$stageBefore build-version=$buildBefore auto-boot=$autoBootBefore bytes=${logoFile.length()}"
                )

                val upload = FileInputStream(logoFile).use { input ->
                    RecoveryUploadTransport(connection, bulkOut).sendStream(input, logoFile.length())
                }
                log(
                    activity,
                    "Stage-2 RestoreLogo upload COMPLETE: bytes=${upload.bytesSent} packets=${upload.packetsSent} endpoint=0x%02x initResult=%s initElapsedMs=%s"
                        .format(
                            upload.endpointAddress,
                            upload.initResult?.toString() ?: "unknown",
                            upload.initElapsedMs?.toString() ?: "unknown"
                        )
                )

                val setPictureBytes = command.sendCommand("setpicture 4")
                log(activity, "Stage-2 RestoreLogo command COMPLETE: setpicture 4 bytes=$setPictureBytes")
                val bgcolorBytes = command.sendCommand("bgcolor 0 0 0")
                log(activity, "Stage-2 RestoreLogo command COMPLETE: bgcolor 0 0 0 bytes=$bgcolorBytes")

                val autoBootAfter = command.getenv("auto-boot").value.trim()
                require(autoBootAfter.equals("true", ignoreCase = true)) {
                    "Safety invariant failed: auto-boot changed from '$autoBootBefore' to '$autoBootAfter'"
                }
                val stageAfter = command.getenv("boot-stage").value.trim()
                require(stageAfter == STAGE_2) { "Stage changed after RestoreLogo test: '$stageAfter'" }
                val buildAfter = command.getenv("build-version").value.trim()
                require(buildAfter == buildBefore) {
                    "Recovery build changed after RestoreLogo test: before='$buildBefore' after='$buildAfter'"
                }

                log(
                    activity,
                    "Stage-2 RestoreLogo test: PASS boot-stage=2 build-version=$buildAfter auto-boot=$autoBootAfter"
                )
                log(
                    activity,
                    "Stage-2 RestoreLogo test: STOP boundary reached after RestoreLogo display commands; no persistent environment change or further restore-entry payload sent"
                )
            } catch (t: Throwable) {
                log(activity, "Stage-2 RestoreLogo test FAILED: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                connection?.close()
                lease?.let { runCatching { UsbOperationReservation.release(it) } }
                inFlight.set(false)
                activity.runOnUiThread { if (isAttachedToWindow) refreshState() }
            }
        }
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
        private const val RESTORE_LOGO = "RestoreLogo"
        private const val RESERVATION_OWNER = "stage2-restorelogo-test"
        private const val READY_LABEL = "Test M1 Stage-2 RestoreLogo"
    }
}
