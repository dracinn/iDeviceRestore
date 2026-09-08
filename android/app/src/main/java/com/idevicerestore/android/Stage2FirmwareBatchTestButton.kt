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
 * Bounded M1 Stage-2 test for manifest components loaded by iBoot after Stage-1, followed by a
 * RestoreRamDisk upload-only checkpoint. The ramdisk activation command is deliberately excluded.
 */
class Stage2FirmwareBatchTestButton @JvmOverloads constructor(
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
        // UI readiness is intentionally based only on the live USB mode. TSS/preparation/cache
        // prerequisites are verified again at execution time so stale app state cannot silently hide
        // a valid Stage-2 hardware diagnostic.
        isEnabled = permittedM1Recovery(usb) != null
        text = READY_LABEL
    }

    private fun confirm() {
        val activity = activity() ?: return
        if (!isEnabled || inFlight.get()) return
        AlertDialog.Builder(activity)
            .setTitle("Run Stage-2 firmware + ramdisk upload test?")
            .setMessage(
                "This bounded test requires an M1 already at boot-stage=2 and auto-boot=true. It sends the non-Stage1 IsLoadedByiBoot firmware batch with the upstream 'firmware' command, verifies Stage 2 after every component, then uploads the prepared personalized RestoreRamDisk into memory. " +
                    "The RestoreRamDisk is NOT activated: the 'ramdisk' command is not sent. It also does not send setenv, saveenv, RestoreLogo, DeviceTree, SEP, KernelCache, bootx, restore, or erase."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Run bounded test") { _, _ -> start() }
            .show()
    }

    private fun start() {
        val activity = activity() ?: return
        if (!inFlight.compareAndSet(false, true)) return
        isEnabled = false
        text = RUNNING_LABEL
        log(
            activity,
            "Stage-2 firmware+ramdisk test: START boundary=IsLoadedByiBoot(non-Stage1)+firmware then RestoreRamDisk-upload-only; forbidden=setenv/saveenv/logo/ramdisk-command/devicetree/SEP/kernel/bootx/restore/erase"
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
                    ?: error("TSS ticket unavailable; select/refresh firmware and let automatic preparation complete")
                val prepared = RestoreComponentPreparationStore.get()
                    ?: error("Prepared restore components unavailable; let automatic restore preparation complete")
                val firmware = FirmwarePreparationStore.get()
                    ?: error("Firmware preparation context unavailable; select the active signed firmware")
                require(firmware.matches(ticket.buildId, ticket.identityIndex)) { "Firmware/TSS identity mismatch" }
                require(prepared.buildId.equals(ticket.buildId, ignoreCase = true) && prepared.identityIndex == ticket.identityIndex) {
                    "Prepared restore components/TSS identity mismatch"
                }
                require(foundationMatchesDevice(ticket.foundation, recovery)) { "Recovery device does not match TSS foundation" }

                val identity = IpswBuildIdentityReader { message -> log(activity, "Stage-2 firmware+ramdisk $message") }
                    .read(firmware.location.file, ticket.identityIndex)
                    .identity
                val manifest = identity.dict("Manifest") ?: error("Selected BuildIdentity has no Manifest dictionary")
                val orderedNames = manifest.values.mapNotNull { (name, node) ->
                    val entry = node as? PlistNode.Dict ?: return@mapNotNull null
                    val info = entry.dict("Info") ?: return@mapNotNull null
                    val stage1 = info.bool("IsLoadedByiBootStage1") == true
                    val loaded = info.bool("IsLoadedByiBoot") == true
                    name.takeIf { loaded && !stage1 && name !in FORBIDDEN_COMPONENTS }
                }
                require(orderedNames.isNotEmpty()) { "BuildIdentity has no bounded non-Stage1 IsLoadedByiBoot components" }

                val forbiddenLoaded = manifest.values.mapNotNull { (name, node) ->
                    if (name !in FORBIDDEN_COMPONENTS) return@mapNotNull null
                    val entry = node as? PlistNode.Dict ?: return@mapNotNull null
                    val info = entry.dict("Info") ?: return@mapNotNull null
                    val stage1 = info.bool("IsLoadedByiBootStage1") == true
                    val loaded = info.bool("IsLoadedByiBoot") == true
                    name.takeIf { loaded && !stage1 }
                }
                require(forbiddenLoaded.isEmpty()) {
                    "Safety boundary refuses manifest entries reserved for later restore phases: ${forbiddenLoaded.joinToString(",")}"
                }

                val preparedByName = prepared.components.associateBy { it.name }
                val orderedPrepared = orderedNames.map { name ->
                    val component = preparedByName[name] ?: error("Prepared component unavailable: $name")
                    val file = component.personalizedFile ?: error("Personalized component unavailable: $name")
                    require(component.image4Validated) { "$name failed local Image4 validation" }
                    require(component.personalizationState == "personalized") { "$name is not fully personalized: ${component.personalizationState}" }
                    require(file.isFile && file.length() > 0L && component.personalizedBytes == file.length()) {
                        "$name personalized file is missing, empty, or changed"
                    }
                    PersonalizedImage4Validator.validate(file, ticket.apImg4Ticket, name)
                    name to component
                }

                val ramdisk = preparedByName[RESTORE_RAMDISK] ?: error("Prepared RestoreRamDisk unavailable")
                val ramdiskFile = ramdisk.personalizedFile ?: error("Personalized RestoreRamDisk unavailable")
                require(ramdisk.image4Validated) { "RestoreRamDisk failed local Image4 validation" }
                require(ramdisk.personalizationState == "personalized") {
                    "RestoreRamDisk is not fully personalized: ${ramdisk.personalizationState}"
                }
                require(ramdiskFile.isFile && ramdiskFile.length() > 0L && ramdisk.personalizedBytes == ramdiskFile.length()) {
                    "RestoreRamDisk personalized file is missing, empty, or changed"
                }
                PersonalizedImage4Validator.validate(ramdiskFile, ticket.apImg4Ticket, RESTORE_RAMDISK)

                log(activity, "Stage-2 firmware batch manifest order (${orderedNames.size})=${orderedNames.joinToString(",")}")

                connection = usb.openDevice(recovery) ?: error("Could not open M1 Recovery device")
                val claimed = AppleUsb.claimBestInterface(recovery, connection) ?: error("Could not claim Recovery interface")
                val bulkOut = claimed.bulkOut ?: error("Recovery bulk OUT endpoint unavailable")
                val command = RecoveryTransport(connection, claimed.bulkIn)

                val stageBefore = command.getenv("boot-stage").value.trim()
                require(stageBefore == STAGE_2) { "Expected boot-stage=2 before firmware batch, got '$stageBefore'" }
                val buildBefore = command.getenv("build-version").value.trim()
                val autoBootBefore = command.getenv("auto-boot").value.trim()
                require(autoBootBefore.equals("true", ignoreCase = true)) {
                    "Safety gate requires auto-boot=true before firmware batch, got '$autoBootBefore'"
                }
                log(activity, "Stage-2 firmware batch preflight: boot-stage=2 build-version=$buildBefore auto-boot=$autoBootBefore count=${orderedPrepared.size}")

                orderedPrepared.forEachIndexed { index, (name, component) ->
                    val file = component.personalizedFile ?: error("Personalized component disappeared: $name")
                    log(activity, "Stage-2 firmware batch ${index + 1}/${orderedPrepared.size}: $name bytes=${file.length()} command=firmware")
                    val upload = FileInputStream(file).use { input ->
                        RecoveryUploadTransport(connection, bulkOut).sendStream(input, file.length())
                    }
                    val commandBytes = command.sendCommand("firmware")
                    log(
                        activity,
                        "Stage-2 firmware batch COMPLETE ${index + 1}/${orderedPrepared.size}: $name bytes=${upload.bytesSent} packets=${upload.packetsSent} endpoint=0x%02x initResult=%s initElapsedMs=%s commandBytes=$commandBytes"
                            .format(
                                upload.endpointAddress,
                                upload.initResult?.toString() ?: "unknown",
                                upload.initElapsedMs?.toString() ?: "unknown"
                            )
                    )

                    verifyStableStage2(command, buildBefore, "after $name")
                    log(activity, "Stage-2 firmware batch VERIFIED ${index + 1}/${orderedPrepared.size}: $name boot-stage=2 build-version=$buildBefore auto-boot=true")
                }

                log(activity, "Stage-2 firmware batch test: PASS components=${orderedPrepared.size} boot-stage=2 build-version=$buildBefore auto-boot=true")

                val ramdiskSizeRaw = runCatching { command.getenv("ramdisk-size").value.trim() }.getOrNull()
                val capacity = parseUnsignedNumber(ramdiskSizeRaw)
                if (capacity != null) {
                    require(ramdiskFile.length().toULong() <= capacity) {
                        "Prepared RestoreRamDisk (${ramdiskFile.length()} bytes) exceeds device ramdisk-size=$ramdiskSizeRaw"
                    }
                }
                log(
                    activity,
                    "Stage-2 RestoreRamDisk preflight: boot-stage=2 build-version=$buildBefore auto-boot=true ramdisk-size=${ramdiskSizeRaw ?: "unavailable"} bytes=${ramdiskFile.length()} activation=NO"
                )

                val ramdiskUpload = FileInputStream(ramdiskFile).use { input ->
                    RecoveryUploadTransport(connection, bulkOut).sendStream(input, ramdiskFile.length())
                }
                log(
                    activity,
                    "Stage-2 RestoreRamDisk upload COMPLETE: bytes=${ramdiskUpload.bytesSent} packets=${ramdiskUpload.packetsSent} endpoint=0x%02x initResult=%s initElapsedMs=%s ramdiskCommand=NOT-SENT"
                        .format(
                            ramdiskUpload.endpointAddress,
                            ramdiskUpload.initResult?.toString() ?: "unknown",
                            ramdiskUpload.initElapsedMs?.toString() ?: "unknown"
                        )
                )

                verifyStableStage2(command, buildBefore, "after RestoreRamDisk upload")
                log(
                    activity,
                    "Stage-2 firmware+ramdisk test: PASS firmwareComponents=${orderedPrepared.size} ramdiskBytes=${ramdiskUpload.bytesSent} boot-stage=2 build-version=$buildBefore auto-boot=true ramdiskCommand=NOT-SENT"
                )
                log(
                    activity,
                    "Stage-2 firmware+ramdisk test: STOP boundary reached after RestoreRamDisk upload with no activation; no persistent environment change, DeviceTree/SEP/KernelCache, bootx, restore, or erase sent"
                )
            } catch (t: Throwable) {
                log(activity, "Stage-2 firmware+ramdisk test FAILED: ${t.javaClass.simpleName}: ${t.message}")
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
        private const val RESERVATION_OWNER = "stage2-firmware-ramdisk-test"
        private const val READY_LABEL = "Test M1 Stage-2 Firmware + RamDisk Upload"
        private const val RUNNING_LABEL = "Stage-2 firmware + ramdisk test running…"
        private val FORBIDDEN_COMPONENTS = setOf(
            "RestoreLogo",
            "RestoreRamDisk",
            "RestoreDeviceTree",
            "RestoreSEP",
            "RestoreKernelCache"
        )
    }
}
