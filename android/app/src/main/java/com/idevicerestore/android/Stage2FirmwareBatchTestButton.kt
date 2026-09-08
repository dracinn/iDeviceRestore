package com.idevicerestore.android

import android.content.Context
import android.content.ContextWrapper
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.AttributeSet
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Current cumulative M1 Stage-2 test. It replays every hardware-proven Stage-2 boundary, then
 * advances through RestoreKernelCache and bootx to the first post-Recovery Apple USB enumeration.
 * It deliberately stops before any restore-mode firmware/data request is answered.
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
            .setTitle("Run current restore-entry test?")
            .setMessage(
                "This cumulative M1 test starts from boot-stage=2 and replays the proven firmware, RestoreRamDisk, RestoreDeviceTree and RestoreSEP steps. It then uploads RestoreKernelCache, persistently sets auto-boot=false as upstream idevicerestore does, sets the macOS restore boot arguments, and sends bootx. " +
                    "The test stops as soon as Recovery disappears and a fresh non-Recovery Apple USB device appears. It does not connect to restored/usbmux, answer restore requests, upload restore firmware/data, erase, or start a restore. If the final transition fails while Recovery is still reachable, the app attempts to restore auto-boot=true."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Run current test") { _, _ -> start() }
            .show()
    }

    private fun start() {
        val activity = activity() ?: return
        if (!inFlight.compareAndSet(false, true)) return
        isEnabled = false
        text = RUNNING_LABEL
        log(
            activity,
            "Current Stage-2 test: START boundary=firmware+ramdisk+devicetree+optional-RestoreSEP/rsepfirmware+RestoreKernelCache+bootargs+bootx+post-Recovery-USB; forbidden=restored/usbmux/restore-payload/erase"
        )

        worker.execute {
            var lease: UsbOperationReservation.Lease? = null
            var connection: android.hardware.usb.UsbDeviceConnection? = null
            var autoBootPersistedFalse = false
            var transitionProven = false
            try {
                lease = UsbOperationReservation.tryAcquire(RESERVATION_OWNER)
                    ?: error("Another USB operation is active: ${UsbOperationReservation.owner() ?: "unknown"}")

                val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
                val recovery = permittedM1Recovery(usb) ?: error("No permitted M1 Recovery device is connected")
                val originalRecoveryPath = recovery.deviceName
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
                    name to validatedPreparedFile(preparedByName, name, ticket)
                }
                val ramdiskFile = validatedPreparedFile(preparedByName, RESTORE_RAMDISK, ticket)
                val deviceTreeFile = validatedPreparedFile(preparedByName, RESTORE_DEVICE_TREE, ticket)
                val sepFile = if (manifest.values.containsKey(RESTORE_SEP)) {
                    validatedPreparedFile(preparedByName, RESTORE_SEP, ticket)
                } else {
                    null
                }
                val kernelCacheFile = validatedPreparedFile(preparedByName, RESTORE_KERNEL_CACHE, ticket)

                log(activity, "Stage-2 firmware batch manifest order (${orderedNames.size})=${orderedNames.joinToString(",")}")
                log(activity, "Restore-entry final payload preflight: RestoreKernelCache bytes=${kernelCacheFile.length()} bootArgs='$MACOS_RESTORE_BOOT_ARGS' bootxBRequest=$BOOTX_BREQUEST")

                connection = usb.openDevice(recovery) ?: error("Could not open M1 Recovery device")
                val claimed = AppleUsb.claimBestInterface(recovery, connection) ?: error("Could not claim Recovery interface")
                val bulkOut = claimed.bulkOut ?: error("Recovery bulk OUT endpoint unavailable")
                val command = RecoveryTransport(connection, claimed.bulkIn)

                val stageBefore = command.getenv("boot-stage").value.trim()
                require(stageBefore == STAGE_2) { "Expected boot-stage=2 before current test, got '$stageBefore'" }
                val buildBefore = command.getenv("build-version").value.trim()
                val autoBootBefore = command.getenv("auto-boot").value.trim()
                require(autoBootBefore.equals("true", ignoreCase = true)) {
                    "Safety gate requires auto-boot=true before current test, got '$autoBootBefore'"
                }
                log(activity, "Current Stage-2 preflight: boot-stage=2 build-version=$buildBefore auto-boot=$autoBootBefore firmwareCount=${orderedPrepared.size} restoreSep=${sepFile != null} restoreKernelCacheBytes=${kernelCacheFile.length()}")

                orderedPrepared.forEachIndexed { index, (name, file) ->
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
                    verifyStableStage2(command, buildBefore, expectedAutoBoot = true, where = "after $name")
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
                logUpload(activity, "RestoreRamDisk", ramdiskUpload)
                verifyStableStage2(command, buildBefore, expectedAutoBoot = true, where = "after RestoreRamDisk upload")
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
                requireObservedStable(observedStage, observedBuild, observedAutoBoot, buildBefore, expectedAutoBoot = true, where = "ramdisk activation")

                verifyStableStage2(command, buildBefore, expectedAutoBoot = true, where = "before RestoreDeviceTree upload")
                log(activity, "Stage-2 RestoreDeviceTree preflight: boot-stage=2 build-version=$buildBefore auto-boot=true bytes=${deviceTreeFile.length()} command=devicetree")
                val deviceTreeUpload = FileInputStream(deviceTreeFile).use { input ->
                    RecoveryUploadTransport(connection, bulkOut).sendStream(input, deviceTreeFile.length())
                }
                logUpload(activity, "RestoreDeviceTree", deviceTreeUpload)
                verifyStableStage2(command, buildBefore, expectedAutoBoot = true, where = "after RestoreDeviceTree upload")
                log(activity, "Stage-2 RestoreDeviceTree post-upload verification PASS: boot-stage=2 build-version=$buildBefore auto-boot=true")

                log(activity, "Stage-2 RestoreDeviceTree activation command START: devicetree")
                val deviceTreeCommandBytes = command.sendCommand("devicetree")
                log(activity, "Stage-2 RestoreDeviceTree activation command COMPLETE: devicetree bytes=$deviceTreeCommandBytes upstreamDelayMs=0")
                verifyStableStage2(command, buildBefore, expectedAutoBoot = true, where = "after devicetree")
                log(activity, "Stage-2 RestoreDeviceTree activation observation: boot-stage=2 build-version=$buildBefore auto-boot=true")

                var sepBytes = 0L
                if (sepFile != null) {
                    verifyStableStage2(command, buildBefore, expectedAutoBoot = true, where = "before RestoreSEP upload")
                    log(activity, "Stage-2 RestoreSEP preflight: boot-stage=2 build-version=$buildBefore auto-boot=true bytes=${sepFile.length()} command=rsepfirmware")
                    val sepUpload = FileInputStream(sepFile).use { input ->
                        RecoveryUploadTransport(connection, bulkOut).sendStream(input, sepFile.length())
                    }
                    sepBytes = sepUpload.bytesSent
                    logUpload(activity, "RestoreSEP", sepUpload)
                    verifyStableStage2(command, buildBefore, expectedAutoBoot = true, where = "after RestoreSEP upload")
                    log(activity, "Stage-2 RestoreSEP activation command START: rsepfirmware")
                    val sepCommandBytes = command.sendCommand("rsepfirmware")
                    log(activity, "Stage-2 RestoreSEP activation command COMPLETE: rsepfirmware bytes=$sepCommandBytes upstreamDelayMs=0")
                    verifyStableStage2(command, buildBefore, expectedAutoBoot = true, where = "after rsepfirmware")
                    log(activity, "Stage-2 RestoreSEP activation observation: boot-stage=2 build-version=$buildBefore auto-boot=true")
                } else {
                    log(activity, "Stage-2 RestoreSEP: component absent from selected BuildIdentity; upstream conditional step skipped")
                }

                verifyStableStage2(command, buildBefore, expectedAutoBoot = true, where = "before persistent restore-entry environment change")
                log(activity, "Restore-entry persistent environment START: setenv auto-boot false; saveenv")
                val setAutoBootBytes = command.sendCommand("setenv auto-boot false")
                val saveEnvBytes = command.sendCommand("saveenv")
                autoBootPersistedFalse = true
                verifyStableStage2(command, buildBefore, expectedAutoBoot = false, where = "after saveenv auto-boot=false")
                log(activity, "Restore-entry persistent environment COMPLETE: setenvBytes=$setAutoBootBytes saveenvBytes=$saveEnvBytes boot-stage=2 build-version=$buildBefore auto-boot=false")

                log(activity, "Stage-2 RestoreKernelCache preflight: boot-stage=2 build-version=$buildBefore auto-boot=false bytes=${kernelCacheFile.length()} next=upload+0x21/1+bootargs+bootx")
                val kernelUpload = FileInputStream(kernelCacheFile).use { input ->
                    RecoveryUploadTransport(connection, bulkOut).sendStream(input, kernelCacheFile.length())
                }
                logUpload(activity, "RestoreKernelCache", kernelUpload)
                verifyStableStage2(command, buildBefore, expectedAutoBoot = false, where = "after RestoreKernelCache upload")

                log(activity, "Stage-2 RestoreKernelCache upstream control START: requestType=0x21 request=1 bestEffort=true")
                runCatching { command.controlTransferOut(requestType = 0x21, request = 1, timeoutMs = 5000) }
                    .onSuccess { followUp -> log(activity, "Stage-2 RestoreKernelCache upstream control COMPLETE: transferred=${followUp.transferred} bestEffort=true") }
                    .onFailure { error -> log(activity, "Stage-2 RestoreKernelCache upstream control FAILED-BEST-EFFORT: ${error.javaClass.simpleName}: ${error.message}; continuing because upstream ignores this return value") }

                val bootArgsCommand = "setenv boot-args $MACOS_RESTORE_BOOT_ARGS"
                log(activity, "Restore-entry boot arguments START: $bootArgsCommand")
                val bootArgsBytes = command.sendCommand(bootArgsCommand)
                log(activity, "Restore-entry boot arguments COMPLETE: bytes=$bootArgsBytes")

                log(activity, "Restore-entry bootx START: bRequest=$BOOTX_BREQUEST previousRecovery=$originalRecoveryPath")
                val bootxBytes = command.sendCommandBreq("bootx", BOOTX_BREQUEST)
                log(activity, "Restore-entry bootx command COMPLETE: bytes=$bootxBytes; waiting for Recovery disconnect and fresh Apple USB enumeration")

                connection.close()
                connection = null
                val postBoot = waitForPostBootAppleUsb(usb, originalRecoveryPath)
                require(AppleUsb.mode(postBoot) != AppleUsb.Mode.RECOVERY) {
                    "bootx re-enumerated as Recovery instead of restore-entry USB: ${AppleUsb.describe(postBoot)}"
                }
                transitionProven = true
                log(activity, "Restore-entry USB transition PASS: previousRecovery=$originalRecoveryPath newDevice=${postBoot.deviceName} ${AppleUsb.describe(postBoot)}")
                log(
                    activity,
                    "Current Stage-2 test: PASS firmwareComponents=${orderedPrepared.size} ramdiskBytes=${ramdiskUpload.bytesSent} deviceTreeBytes=${deviceTreeUpload.bytesSent} restoreSepBytes=$sepBytes restoreSepActivated=${sepFile != null} restoreKernelCacheBytes=${kernelUpload.bytesSent} bootx=true"
                )
                log(
                    activity,
                    "Current Stage-2 test: STOP boundary reached immediately after post-boot Apple USB enumeration; restored/usbmux was not opened and ZERO restore firmware/data payloads were sent"
                )
            } catch (t: Throwable) {
                log(activity, "Current Stage-2 test FAILED: ${t.javaClass.simpleName}: ${t.message}")
                if (autoBootPersistedFalse && !transitionProven) {
                    val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
                    rollbackAutoBootTrue(activity, usb)
                }
            } finally {
                connection?.close()
                lease?.let { runCatching { UsbOperationReservation.release(it) } }
                inFlight.set(false)
                activity.runOnUiThread { if (isAttachedToWindow) refreshState() }
            }
        }
    }

    private fun validatedPreparedFile(
        preparedByName: Map<String, RestoreComponentPreparationStore.PreparedComponent>,
        name: String,
        ticket: TssTicketStore.Ticket
    ): File {
        val component = preparedByName[name] ?: error("Prepared $name unavailable")
        val file = component.personalizedFile ?: error("Personalized $name unavailable")
        require(component.image4Validated) { "$name failed local Image4 validation" }
        require(component.personalizationState == "personalized") {
            "$name is not fully personalized: ${component.personalizationState}"
        }
        require(file.isFile && file.length() > 0L && component.personalizedBytes == file.length()) {
            "$name personalized file is missing, empty, or changed"
        }
        PersonalizedImage4Validator.validate(file, ticket.apImg4Ticket, name)
        return file
    }

    private fun logUpload(
        activity: AppCompatActivity,
        name: String,
        upload: RecoveryUploadTransport.Result
    ) {
        log(
            activity,
            "Stage-2 $name upload COMPLETE: bytes=${upload.bytesSent} packets=${upload.packetsSent} endpoint=0x%02x initResult=%s initElapsedMs=%s"
                .format(
                    upload.endpointAddress,
                    upload.initResult?.toString() ?: "unknown",
                    upload.initElapsedMs?.toString() ?: "unknown"
                )
        )
    }

    private fun verifyStableStage2(
        command: RecoveryTransport,
        expectedBuild: String,
        expectedAutoBoot: Boolean,
        where: String
    ) {
        val stage = command.getenv("boot-stage").value.trim()
        val autoBoot = command.getenv("auto-boot").value.trim()
        val build = command.getenv("build-version").value.trim()
        require(stage == STAGE_2) { "Stage changed $where: '$stage'" }
        require(autoBoot.equals(expectedAutoBoot.toString(), ignoreCase = true)) {
            "auto-boot changed $where: expected=$expectedAutoBoot actual='$autoBoot'"
        }
        require(build == expectedBuild) { "Recovery build changed $where: expected='$expectedBuild' actual='$build'" }
    }

    private fun requireObservedStable(
        stage: String,
        build: String,
        autoBoot: String,
        expectedBuild: String,
        expectedAutoBoot: Boolean,
        where: String
    ) {
        if (stage != "unavailable") require(stage == STAGE_2) { "Unexpected boot-stage after $where: '$stage'" }
        if (build != "unavailable") require(build == expectedBuild) {
            "Unexpected build-version after $where: expected='$expectedBuild' actual='$build'"
        }
        if (autoBoot != "unavailable") require(autoBoot.equals(expectedAutoBoot.toString(), ignoreCase = true)) {
            "Unexpected auto-boot after $where: expected=$expectedAutoBoot actual='$autoBoot'"
        }
    }

    private fun observeGetenv(command: RecoveryTransport, key: String): String = runCatching {
        command.getenv(key).value.trim().ifEmpty { "empty" }
    }.getOrElse { "unavailable" }

    private fun waitForPostBootAppleUsb(usb: UsbManager, previousRecoveryPath: String): UsbDevice {
        val deadline = SystemClock.elapsedRealtime() + POST_BOOT_USB_TIMEOUT_MS
        var sawPreviousDisappear = false
        while (SystemClock.elapsedRealtime() < deadline) {
            val appleDevices = usb.deviceList.values.filter { it.vendorId == AppleUsb.APPLE_VID }
            val previousStillPresent = appleDevices.any { it.deviceName == previousRecoveryPath }
            if (!previousStillPresent) sawPreviousDisappear = true
            if (sawPreviousDisappear) {
                appleDevices.firstOrNull { it.deviceName != previousRecoveryPath }?.let { return it }
            }
            Thread.sleep(POST_BOOT_USB_POLL_MS)
        }
        error("Timed out waiting for post-boot Apple USB enumeration after Recovery disappeared")
    }

    private fun rollbackAutoBootTrue(activity: AppCompatActivity, usb: UsbManager) {
        val recovery = permittedM1RecoveryAnyAutoBoot(usb)
        if (recovery == null) {
            log(activity, "Restore-entry rollback: auto-boot=false was persisted but no permitted M1 Recovery device is reachable; manual recovery may be required")
            return
        }
        val rollbackConnection = usb.openDevice(recovery)
        if (rollbackConnection == null) {
            log(activity, "Restore-entry rollback: could not open Recovery device to restore auto-boot=true")
            return
        }
        try {
            val claimed = AppleUsb.claimBestInterface(recovery, rollbackConnection)
                ?: error("could not claim Recovery interface")
            val rollback = RecoveryTransport(rollbackConnection, claimed.bulkIn)
            val setBytes = rollback.sendCommand("setenv auto-boot true")
            val saveBytes = rollback.sendCommand("saveenv")
            val observed = rollback.getenv("auto-boot").value.trim()
            require(observed.equals("true", ignoreCase = true)) { "rollback verification returned '$observed'" }
            log(activity, "Restore-entry rollback PASS: auto-boot=true restored and saved setenvBytes=$setBytes saveenvBytes=$saveBytes")
        } catch (t: Throwable) {
            log(activity, "Restore-entry rollback FAILED: ${t.javaClass.simpleName}: ${t.message}; device may remain auto-boot=false")
        } finally {
            rollbackConnection.close()
        }
    }

    private fun parseUnsignedNumber(raw: String?): ULong? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            if (value.startsWith("0x", ignoreCase = true)) value.substring(2).toULong(16) else value.toULong()
        }.getOrNull()
    }

    private fun permittedM1Recovery(usb: UsbManager): UsbDevice? = permittedM1RecoveryAnyAutoBoot(usb)

    private fun permittedM1RecoveryAnyAutoBoot(usb: UsbManager): UsbDevice? = usb.deviceList.values.firstOrNull { device ->
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
        private const val POST_BOOT_USB_TIMEOUT_MS = 120_000L
        private const val POST_BOOT_USB_POLL_MS = 50L
        private const val BOOTX_BREQUEST = 1
        private const val M1_CPID = "8103"
        private const val STAGE_2 = "2"
        private const val RESTORE_RAMDISK = "RestoreRamDisk"
        private const val RESTORE_DEVICE_TREE = "RestoreDeviceTree"
        private const val RESTORE_SEP = "RestoreSEP"
        private const val RESTORE_KERNEL_CACHE = "RestoreKernelCache"
        private const val MACOS_RESTORE_BOOT_ARGS = "rd=md0 nand-enable-reformat=1 -progress -restore"
        private const val RESERVATION_OWNER = "stage2-current-test"
        private const val READY_LABEL = "Run Current Stage-2 Test"
        private const val RUNNING_LABEL = "Current Stage-2 test running…"
        private val FORBIDDEN_COMPONENTS = setOf(
            "RestoreLogo",
            "RestoreRamDisk",
            "RestoreDeviceTree",
            "RestoreSEP",
            "RestoreKernelCache"
        )
    }
}
