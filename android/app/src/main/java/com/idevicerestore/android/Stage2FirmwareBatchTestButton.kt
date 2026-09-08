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
 * Bounded M1 Stage-2 test for manifest components loaded by iBoot after Stage-1, followed by
 * RestoreRamDisk upload/activation and RestoreDeviceTree upload/activation. SEP, KernelCache,
 * bootx, and persistent restore state changes remain deliberately excluded.
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
        isEnabled = permittedM1Recovery(usb) != null
        text = READY_LABEL
    }

    private fun confirm() {
        val activity = activity() ?: return
        if (!isEnabled || inFlight.get()) return
        AlertDialog.Builder(activity)
            .setTitle("Run Stage-2 ramdisk + DeviceTree activation test?")
            .setMessage(
                "This bounded test requires an M1 already at boot-stage=2 and auto-boot=true. It sends the proven non-Stage1 IsLoadedByiBoot firmware batch, uploads and activates RestoreRamDisk, then mirrors upstream by uploading RestoreDeviceTree and sending 'devicetree'. " +
                    "It stops before SEP and does not send setenv, saveenv, RestoreLogo, RestoreSEP, KernelCache, boot arguments, bootx, restore, or erase."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Run bounded DeviceTree test") { _, _ -> start() }
            .show()
    }

    private fun start() {
        val activity = activity() ?: return
        if (!inFlight.compareAndSet(false, true)) return
        isEnabled = false
        text = RUNNING_LABEL
        log(
            activity,
            "Stage-2 firmware+ramdisk+devicetree activation test: START boundary=IsLoadedByiBoot(non-Stage1)+firmware then RestoreRamDisk upload+ramdisk then RestoreDeviceTree upload+devicetree; forbidden=setenv/saveenv/logo/SEP/kernel/bootargs/bootx/restore/erase"
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

                val identity = IpswBuildIdentityReader { message -> log(activity, "Stage-2 restore-entry $message") }
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
                    "Safety boundary refuses later-phase components in the firmware batch: ${forbiddenLoaded.joinToString(",")}"
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

                val deviceTree = preparedByName[RESTORE_DEVICE_TREE] ?: error("Prepared RestoreDeviceTree unavailable")
                val deviceTreeFile = deviceTree.personalizedFile ?: error("Personalized RestoreDeviceTree unavailable")
                require(deviceTree.image4Validated) { "RestoreDeviceTree failed local Image4 validation" }
                require(deviceTree.personalizationState == "personalized") {
                    "RestoreDeviceTree is not fully personalized: ${deviceTree.personalizationState}"
                }
                require(deviceTreeFile.isFile && deviceTreeFile.length() > 0L && deviceTree.personalizedBytes == deviceTreeFile.length()) {
                    "RestoreDeviceTree personalized file is missing, empty, or changed"
                }
                PersonalizedImage4Validator.validate(deviceTreeFile, ticket.apImg4Ticket, RESTORE_DEVICE_TREE)

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
                log(activity, "Stage-2 RestoreRamDisk preflight: boot-stage=2 build-version=$buildBefore auto-boot=true ramdisk-size=${ramdiskSizeRaw ?: "unavailable"} bytes=${ramdiskFile.length()} activation=YES")

                val ramdiskUpload = FileInputStream(ramdiskFile).use { input ->
                    RecoveryUploadTransport(connection, bulkOut).sendStream(input, ramdiskFile.length())
                }
                log(
                    activity,
                    "Stage-2 RestoreRamDisk upload COMPLETE: bytes=${ramdiskUpload.bytesSent} packets=${ramdiskUpload.packetsSent} endpoint=0x%02x initResult=%s initElapsedMs=%s"
                        .format(
                            ramdiskUpload.endpointAddress,
                            ramdiskUpload.initResult?.toString() ?: "unknown",
                            ramdiskUpload.initElapsedMs?.toString() ?: "unknown"
                        )
                )

                log(activity, "Stage-2 RestoreRamDisk post-upload verification START")
                verifyStableStage2(command, buildBefore, "after RestoreRamDisk upload")
                log(activity, "Stage-2 RestoreRamDisk post-upload verification PASS: boot-stage=2 build-version=$buildBefore auto-boot=true")

                log(activity, "Stage-2 RestoreRamDisk activation precommand START: getenv ramdisk-delay bestEffort=true")
                runCatching { command.sendCommand("getenv ramdisk-delay") }
                    .onSuccess { bytes -> log(activity, "Stage-2 RestoreRamDisk activation precommand COMPLETE: getenv ramdisk-delay bytes=$bytes bestEffort=true") }
                    .onFailure { error ->
                        log(activity, "Stage-2 RestoreRamDisk activation precommand FAILED-BEST-EFFORT: getenv ramdisk-delay ${error.javaClass.simpleName}: ${error.message}; continuing to ramdisk per upstream semantics")
                    }

                log(activity, "Stage-2 RestoreRamDisk activation command START: ramdisk")
                val ramdiskCommandBytes = command.sendCommand("ramdisk")
                log(activity, "Stage-2 RestoreRamDisk activation command COMPLETE: ramdisk bytes=$ramdiskCommandBytes; settling ${RAMDISK_SETTLE_MS}ms")
                Thread.sleep(RAMDISK_SETTLE_MS)

                val observedStage = observeGetenv(command, "boot-stage")
                val observedBuild = observeGetenv(command, "build-version")
                val observedAutoBoot = observeGetenv(command, "auto-boot")
                val observedRamdiskSize = observeGetenv(command, "ramdisk-size")
                log(activity, "Stage-2 RestoreRamDisk activation observation: boot-stage=$observedStage build-version=$observedBuild auto-boot=$observedAutoBoot ramdisk-size=$observedRamdiskSize")
                requireObservedStable(observedStage, observedBuild, observedAutoBoot, buildBefore, "ramdisk activation")

                verifyStableStage2(command, buildBefore, "before RestoreDeviceTree upload")
                log(activity, "Stage-2 RestoreDeviceTree preflight: boot-stage=2 build-version=$buildBefore auto-boot=true bytes=${deviceTreeFile.length()} command=devicetree")
                val deviceTreeUpload = FileInputStream(deviceTreeFile).use { input ->
                    RecoveryUploadTransport(connection, bulkOut).sendStream(input, deviceTreeFile.length())
                }
                log(
                    activity,
                    "Stage-2 RestoreDeviceTree upload COMPLETE: bytes=${deviceTreeUpload.bytesSent} packets=${deviceTreeUpload.packetsSent} endpoint=0x%02x initResult=%s initElapsedMs=%s"
                        .format(
                            deviceTreeUpload.endpointAddress,
                            deviceTreeUpload.initResult?.toString() ?: "unknown",
                            deviceTreeUpload.initElapsedMs?.toString() ?: "unknown"
                        )
                )
                verifyStableStage2(command, buildBefore, "after RestoreDeviceTree upload")
                log(activity, "Stage-2 RestoreDeviceTree post-upload verification PASS: boot-stage=2 build-version=$buildBefore auto-boot=true")

                log(activity, "Stage-2 RestoreDeviceTree activation command START: devicetree")
                val deviceTreeCommandBytes = command.sendCommand("devicetree")
                log(activity, "Stage-2 RestoreDeviceTree activation command COMPLETE: devicetree bytes=$deviceTreeCommandBytes upstreamDelayMs=0")

                val deviceTreeStage = observeGetenv(command, "boot-stage")
                val deviceTreeBuild = observeGetenv(command, "build-version")
                val deviceTreeAutoBoot = observeGetenv(command, "auto-boot")
                log(activity, "Stage-2 RestoreDeviceTree activation observation: boot-stage=$deviceTreeStage build-version=$deviceTreeBuild auto-boot=$deviceTreeAutoBoot")
                requireObservedStable(deviceTreeStage, deviceTreeBuild, deviceTreeAutoBoot, buildBefore, "DeviceTree activation")

                log(
                    activity,
                    "Stage-2 firmware+ramdisk+devicetree activation test: PASS firmwareComponents=${orderedPrepared.size} ramdiskBytes=${ramdiskUpload.bytesSent} ramdiskCommand=SENT deviceTreeBytes=${deviceTreeUpload.bytesSent} devicetreeCommand=SENT"
                )
                log(
                    activity,
                    "Stage-2 firmware+ramdisk+devicetree activation test: STOP boundary reached after DeviceTree activation; no persistent environment change, RestoreSEP/rsepfirmware, KernelCache, boot arguments, bootx, restore, or erase sent"
                )
            } catch (t: Throwable) {
                log(activity, "Stage-2 firmware+ramdisk+devicetree activation test FAILED: ${t.javaClass.simpleName}: ${t.message}")
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

    private fun requireObservedStable(stage: String, build: String, autoBoot: String, expectedBuild: String, where: String) {
        if (stage != "unavailable") require(stage == STAGE_2) { "Unexpected boot-stage after $where: '$stage'" }
        if (build != "unavailable") require(build == expectedBuild) {
            "Unexpected build-version after $where: expected='$expectedBuild' actual='$build'"
        }
        if (autoBoot != "unavailable") require(autoBoot.equals("true", ignoreCase = true)) {
            "Unexpected auto-boot after $where: '$autoBoot'"
        }
    }

    private fun observeGetenv(command: RecoveryTransport, key: String): String = runCatching {
        command.getenv(key).value.trim().ifEmpty { "empty" }
    }.getOrElse { "unavailable" }

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
        private const val RAMDISK_SETTLE_MS = 2000L
        private const val M1_CPID = "8103"
        private const val STAGE_2 = "2"
        private const val RESTORE_RAMDISK = "RestoreRamDisk"
        private const val RESTORE_DEVICE_TREE = "RestoreDeviceTree"
        private const val RESERVATION_OWNER = "stage2-firmware-ramdisk-devicetree-activate-test"
        private const val READY_LABEL = "Test M1 Stage-2 Through DeviceTree Activation"
        private const val RUNNING_LABEL = "Stage-2 DeviceTree activation running…"
        private val FORBIDDEN_COMPONENTS = setOf(
            "RestoreLogo",
            "RestoreRamDisk",
            "RestoreDeviceTree",
            "RestoreSEP",
            "RestoreKernelCache"
        )
    }
}
