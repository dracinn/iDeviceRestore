package com.idevicerestore.android

import android.content.Context
import android.content.ContextWrapper
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Pre-authorized bounded M1 iBSS -> Stage-1 prerequisites -> iBEC -> Stage-2 diagnostic. */
class PrearmedStage1IbecButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatButton(context, attrs) {
    private val worker = Executors.newSingleThreadExecutor()
    private val inFlight = AtomicBoolean(false)
    private var replayedEvidenceSize = 0

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
        post { replayPersistedEvidence() }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refresh)
        worker.shutdownNow()
        super.onDetachedFromWindow()
    }

    private fun refreshState() {
        if (inFlight.get()) {
            isEnabled = false
            text = "M1 DFU → Stage-2 test running…"
            return
        }
        val activity = activity() ?: run { isEnabled = false; return }
        val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
        val dfu = permittedDevice(usb, AppleUsb.Mode.DFU)
        val ids = dfu?.let(AppleUsb::bootIdentifiers)
        val ticket = TssTicketStore.get()
        val ibss = Image4PreparationStore.get()
        val expectedStage1Build = ibss?.result?.file?.let(Stage1BuildMetadata::expectedBuild)
        val preparedRestore = RestoreComponentPreparationStore.get()
        val ibec = preparedRestore?.components?.firstOrNull { it.name == "iBEC" }
        val ibecFile = ibec?.personalizedFile
        val expectedStage2Build = ibecFile?.let(Stage1BuildMetadata::expectedBuild)
        val buildId = selectedBuildId(activity)
        val firmwareContext = FirmwarePreparationStore.get()
        val ready = dfu != null &&
            ids?.cpidHex.equals(M1_CPID, true) &&
            ticket != null && foundationMatchesDevice(ticket.foundation, dfu) &&
            ibss != null && ibss.result.file.isFile && ibss.result.file.length() == ibss.result.personalizedBytes &&
            expectedStage1Build != null &&
            preparedRestore != null && ibec != null && ibecFile?.isFile == true && ibec.personalizedBytes == ibecFile.length() &&
            expectedStage2Build != null &&
            buildId != null && ticket.buildId.equals(buildId, true) && preparedRestore.buildId.equals(buildId, true) &&
            preparedRestore.identityIndex == ticket.identityIndex && ibss.result.identityIndex == ticket.identityIndex &&
            firmwareContext?.matches(buildId, ticket.identityIndex) == true
        isEnabled = ready
        text = when {
            ready -> READY_LABEL
            ibss != null && expectedStage1Build == null -> "Prepared iBSS Stage-1 build unavailable"
            ibecFile != null && expectedStage2Build == null -> "Prepared iBEC Stage-2 build unavailable"
            else -> READY_LABEL
        }
    }

    private fun confirm() {
        val activity = activity() ?: return
        if (!isEnabled || inFlight.get()) return
        val preparedIbss = Image4PreparationStore.get() ?: return
        val expectedStage1Build = Stage1BuildMetadata.expectedBuild(preparedIbss.result.file) ?: run {
            isEnabled = false
            text = "Prepared iBSS Stage-1 build unavailable"
            log(activity, "prearmed Stage-2 test blocked: prepared iBSS has no unique embedded mBoot build identifier")
            return
        }
        val preparedRestore = RestoreComponentPreparationStore.get() ?: return
        val ibecFile = preparedRestore.components.firstOrNull { it.name == "iBEC" }?.personalizedFile ?: return
        val expectedStage2Build = Stage1BuildMetadata.expectedBuild(ibecFile) ?: run {
            isEnabled = false
            text = "Prepared iBEC Stage-2 build unavailable"
            log(activity, "prearmed Stage-2 test blocked: prepared iBEC has no unique embedded mBoot build identifier")
            return
        }

        AlertDialog.Builder(activity)
            .setTitle("Run bounded M1 DFU → Stage-2 test?")
            .setMessage(
                "This single confirmation authorizes the bounded Apple-silicon boot handoff starting from DFU through personalized iBSS, the signed macOS Stage-1 prerequisites, and personalized iBEC. " +
                    "The app will verify Stage-1 build-version=$expectedStage1Build, send the specially signed Ap,LocalPolicy with lpolrestore, send only BuildIdentity components explicitly marked IsLoadedByiBootStage1 with the firmware command, upload validated personalized iBEC, then send the upstream M1 'go' command with bRequest=1 and require a fresh Recovery device with boot-stage=2 and build-version=$expectedStage2Build. " +
                    "This bounded test deliberately does not run saveenv, persistent restore boot-args setup, RestoreRamDisk, SEP, DeviceTree, KernelCache, bootx, restore, or erase sequencing."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Run bounded Stage-2 test") { _, _ -> start(expectedStage1Build, expectedStage2Build) }
            .show()
    }

    private fun start(expectedStage1Build: String, expectedStage2Build: String) {
        val activity = activity() ?: return
        if (!inFlight.compareAndSet(false, true)) return
        isEnabled = false
        text = "M1 DFU → Stage-2 test running…"
        val startedAt = SystemClock.elapsedRealtime()
        PrearmedDiagnosticEvidenceStore.beginSession("expectedStage1Build=$expectedStage1Build")
        log(
            activity,
            "prearmed Stage-2 test: explicit user confirmation received; boundary=iBSS-macos-stage1-prereqs-iBEC-go-verify-stage2-no-restore-os expectedStage1Build=$expectedStage1Build expectedStage2Build=$expectedStage2Build"
        )
        worker.execute {
            var lease: UsbOperationReservation.Lease? = null
            try {
                lease = UsbOperationReservation.tryAcquire(RESERVATION_OWNER)
                    ?: error("Another USB operation is already active: ${UsbOperationReservation.owner() ?: "unknown"}")
                log(activity, "prearmed USB reservation acquired; automatic probes/watchdogs suppressed until bounded test ends")
                val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
                val dfu = permittedDevice(usb, AppleUsb.Mode.DFU) ?: error("No permitted M1 DFU device is connected")
                val ticket = TssTicketStore.get() ?: error("No prepared TSS ticket")
                require(foundationMatchesDevice(ticket.foundation, dfu)) { "Prepared ticket does not match connected DFU device" }

                val preparedIbss = Image4PreparationStore.get() ?: error("No personalized iBSS is prepared")
                val preparedRestore = RestoreComponentPreparationStore.get() ?: error("No restore components are prepared")
                val ibec = preparedRestore.components.firstOrNull { it.name == "iBEC" }
                    ?: error("Prepared restore components do not include iBEC")
                val ibecFile = ibec.personalizedFile
                require(ibecFile.isFile && ibecFile.length() == ibec.personalizedBytes) { "Prepared iBEC file is unavailable or incomplete" }

                val stage1Prerequisites = MacStage1Prerequisites.prepare(
                    activity = activity,
                    ticket = ticket,
                    preparedRestore = preparedRestore,
                    log = { message -> log(activity, message) }
                )

                val dfuConnection = usb.openDevice(dfu) ?: error("Could not open DFU device")
                try {
                    val ids = AppleUsb.bootIdentifiers(dfu) ?: error("DFU boot identifiers unavailable")
                    require(ids.cpidHex.equals(M1_CPID, true)) { "Stage-2 test is restricted to M1 CPID 0x$M1_CPID" }
                    log(activity, "prearmed DFU preflight: Android host USB reset capability verified before iBSS upload")
                    val ibssFile = preparedIbss.result.file
                    log(
                        activity,
                        "prearmed DFU stage1: personalized iBSS identity=${preparedIbss.result.identityIndex} bytes=${ibssFile.length()} cpid=${ids.cpidHex ?: "unknown"} bdid=${ids.bdidHex ?: "unknown"}"
                    )
                    FileInputStream(ibssFile).use { input ->
                        DfuTransport.sendIbss(
                            connection = dfuConnection,
                            input = input,
                            totalBytes = ibssFile.length(),
                            log = { message -> log(activity, "prearmed DFU stage1: $message") }
                        )
                    }
                } finally {
                    dfuConnection.close()
                }

                log(activity, "prearmed Stage-2 test: iBSS sent; waiting for fresh Stage-1 Recovery expectedBuild=$expectedStage1Build")
                val stage1Started = SystemClock.elapsedRealtime()
                val stage1 = waitForExpectedRecoveryStage(
                    activity = activity,
                    usb = usb,
                    ticket = ticket,
                    startedAt = stage1Started,
                    expectedStage = STAGE_1,
                    expectedBuild = expectedStage1Build,
                    label = "Stage-1"
                )
                log(activity, "prearmed Stage-1 proof: boot-stage=1 build-version=$expectedStage1Build")

                val stage1Connection = usb.openDevice(stage1) ?: error("Could not open Stage-1 Recovery device")
                try {
                    val claimed = AppleUsb.claimBestInterface(stage1, stage1Connection)
                        ?: error("Could not claim Stage-1 Recovery interface")
                    val bulkOut = claimed.bulkOut ?: error("Stage-1 Recovery interface has no bulk OUT endpoint")
                    log(
                        activity,
                        "prearmed Stage-1 Recovery interface already claimed: id=${claimed.intf.id} alt=${claimed.intf.alternateSetting} bulkOut=0x%02x".format(bulkOut.address)
                    )
                    val recovery = RecoveryUploadTransport(stage1Connection, bulkOut, claimed.bulkIn)
                    val prerequisites = listOf(stage1Prerequisites.localPolicy) + stage1Prerequisites.stage1Firmware
                    prerequisites.forEachIndexed { index, prerequisite ->
                        log(
                            activity,
                            "prearmed Stage-1 prerequisite ${index + 1}/${prerequisites.size}: ${prerequisite.name} bytes=${prerequisite.personalizedFile.length()} command=${prerequisite.command}"
                        )
                        val result = recovery.upload(prerequisite.personalizedFile, prerequisite.command)
                        log(
                            activity,
                            "prearmed Stage-1 prerequisite COMPLETE: ${prerequisite.name} bytes=${result.bytes} packets=${result.packets} command=${prerequisite.command} commandBytes=${result.commandBytes}"
                        )
                    }

                    val stage = RecoveryTransport(stage1Connection, claimed.bulkIn).getenv("boot-stage").value.trim()
                    val build = RecoveryTransport(stage1Connection, claimed.bulkIn).getenv("build-version").value.trim()
                    require(stage == STAGE_1 && build == expectedStage1Build) {
                        "Stage-1 changed unexpectedly after prerequisites: boot-stage=$stage build-version=$build"
                    }
                    log(activity, "prearmed macOS Stage-1 prerequisites VERIFIED: boot-stage=1 build-version=$build; saveenv/restore boot-args not sent")

                    log(activity, "prearmed iBEC: issuing 0x41/0 on prepared custom Stage-1; failed init must send zero bulk bytes")
                    val upload = recovery.uploadIbec(ibecFile)
                    log(
                        activity,
                        "prearmed iBEC COMPLETE: bytes=${upload.bytes} packets=${upload.packets} endpoint=0x%02x initResult=${upload.initResult} initElapsedMs=${upload.initElapsedMs}".format(bulkOut.address)
                    )
                    log(activity, "prearmed iBEC: sending upstream Apple-silicon go command bRequest=1")
                    val command = RecoveryTransport(stage1Connection, claimed.bulkIn)
                    val sent = command.sendCommandBreq("go", RecoveryTransport.APPLE_SILICON_GO_BREQUEST)
                    log(activity, "prearmed iBEC: go command accepted bytes=$sent")
                    log(activity, "prearmed iBEC: legacy 0x21/1 follow-up skipped on this modern Apple-silicon path per current upstream build-major gate")
                } finally {
                    stage1Connection.close()
                }

                log(
                    activity,
                    "prearmed Stage-2 test: waiting for fresh Recovery enumeration after Stage-1 device=${stage1.deviceName}; expectedBuild=$expectedStage2Build"
                )
                val stage2 = waitForFreshExpectedRecoveryStage(
                    activity = activity,
                    usb = usb,
                    ticket = ticket,
                    previousDeviceName = stage1.deviceName,
                    startedAt = SystemClock.elapsedRealtime(),
                    expectedStage = STAGE_2,
                    expectedBuild = expectedStage2Build,
                    label = "Stage-2"
                )
                log(
                    activity,
                    "prearmed Stage-2 proof: fresh-enumeration=true boot-stage=2 build-version=$expectedStage2Build device=${stage2.deviceName}"
                )
                log(
                    activity,
                    "prearmed Stage-2 test: STOP boundary reached — no persistent saveenv/restore boot-args, RestoreRamDisk, SEP, DeviceTree, KernelCache, bootx, restore, or erase command sent"
                )
                setOperation(activity, "M1 DFU → Stage-2 test complete", false)
            } catch (t: Throwable) {
                log(activity, "prearmed Stage-2 test FAILED: ${t.javaClass.simpleName}: ${t.message}")
                setOperation(activity, "Stage-2 test stopped: ${t.message ?: t.javaClass.simpleName}", false)
            } finally {
                lease?.let { acquired ->
                    runCatching { UsbOperationReservation.release(acquired) }
                        .onSuccess { log(activity, "prearmed USB reservation released") }
                }
                inFlight.set(false)
                activity.runOnUiThread { if (isAttachedToWindow) refreshState() }
                replayPersistedEvidence()
            }
        }
    }

    private fun setOperation(activity: AppCompatActivity, message: String, busy: Boolean) = activity.runOnUiThread {
        activity.findViewById<TextView?>(R.id.operationStatus)?.text = message
        activity.findViewById<android.widget.ProgressBar?>(R.id.operationProgress)?.apply {
            visibility = if (busy) View.VISIBLE else View.GONE
            if (busy) isIndeterminate = true
        }
    }

    private fun selectedBuildId(activity: AppCompatActivity): String? {
        val prefs = activity.getSharedPreferences("firmware_selection", Context.MODE_PRIVATE)
        return prefs.getString("selected_build_id", null)
    }

    private fun foundationMatchesDevice(foundation: TssRequestFoundation.Foundation, device: UsbDevice): Boolean {
        val ids = AppleUsb.bootIdentifiers(device) ?: return false
        return foundation.cpid.equals(ids.cpidHex, true) &&
            foundation.bdid.equals(ids.bdidHex, true) &&
            foundation.ecid.equals(ids.ecidHex, true)
    }

    private fun permittedDevice(usb: UsbManager, mode: AppleUsb.Mode): UsbDevice? = usb.deviceList.values.firstOrNull { device ->
        device.vendorId == AppleUsb.APPLE_VID && AppleUsb.mode(device) == mode && usb.hasPermission(device)
    }

    private fun waitForFreshExpectedRecoveryStage(
        activity: AppCompatActivity,
        usb: UsbManager,
        ticket: TssTicketStore.Ticket,
        previousDeviceName: String,
        startedAt: Long,
        expectedStage: String,
        expectedBuild: String,
        label: String
    ): UsbDevice {
        var lastSnapshot = ""
        var lastHeartbeatAt = startedAt
        var freshBoundaryObserved = false
        log(
            activity,
            "prearmed $label USB observer armed: previousDevice=$previousDeviceName pollMs=$RECOVERY_STAGE_POLL_MS timeoutMs=$RECOVERY_STAGE_WAIT_MS"
        )
        while (SystemClock.elapsedRealtime() - startedAt < RECOVERY_STAGE_WAIT_MS) {
            val now = SystemClock.elapsedRealtime()
            val appleDevices = usb.deviceList.values.filter { it.vendorId == AppleUsb.APPLE_VID }.sortedBy { it.deviceName }
            val snapshot = describeAppleUsbSnapshot(usb, appleDevices)
            if (snapshot != lastSnapshot) {
                log(activity, "prearmed $label USB observer change: elapsedMs=${now - startedAt + 1000L} $snapshot")
                lastSnapshot = snapshot
            } else if (now - lastHeartbeatAt >= USB_OBSERVER_HEARTBEAT_MS) {
                log(activity, "prearmed $label USB observer heartbeat: elapsedMs=${now - startedAt} $snapshot")
                lastHeartbeatAt = now
            }

            val previousStillPresent = appleDevices.any { it.deviceName == previousDeviceName }
            val currentRecovery = appleDevices.firstOrNull { AppleUsb.mode(it) == AppleUsb.Mode.RECOVERY && usb.hasPermission(it) }
            if (!freshBoundaryObserved && !previousStillPresent) {
                freshBoundaryObserved = true
                log(
                    activity,
                    "prearmed $label fresh-enumeration boundary observed: elapsedMs=${now - startedAt} previousDevice=$previousDeviceName previousStillPresent=false currentRecovery=${currentRecovery?.deviceName ?: "none"}"
                )
            }

            if (!freshBoundaryObserved) {
                Thread.sleep(RECOVERY_STAGE_POLL_MS)
                continue
            }
            if (previousStillPresent) {
                Thread.sleep(RECOVERY_STAGE_POLL_MS)
                continue
            }

            val recovery = permittedDevice(usb, AppleUsb.Mode.RECOVERY)
            if (recovery != null) {
                if (!foundationMatchesDevice(ticket.foundation, recovery)) {
                    log(activity, "prearmed $label candidate rejected: foundation mismatch device=${recovery.deviceName} ${usbSnapshotSignature(usb, recovery)}")
                } else {
                    val verified = probeExpectedRecoveryStage(activity, usb, recovery, expectedStage, expectedBuild, label)
                    if (verified) return recovery
                }
            }
            Thread.sleep(RECOVERY_STAGE_POLL_MS)
        }
        val finalDevices = usb.deviceList.values.filter { it.vendorId == AppleUsb.APPLE_VID }.sortedBy { it.deviceName }
        error(
            "Timed out waiting for fresh expected $label boot-stage=$expectedStage build=$expectedBuild; " +
                "freshBoundaryObserved=$freshBoundaryObserved finalUsb=${describeAppleUsbSnapshot(usb, finalDevices)}"
        )
    }

    private fun waitForExpectedRecoveryStage(
        activity: AppCompatActivity,
        usb: UsbManager,
        ticket: TssTicketStore.Ticket,
        startedAt: Long,
        expectedStage: String,
        expectedBuild: String,
        label: String
    ): UsbDevice {
        while (SystemClock.elapsedRealtime() - startedAt < RECOVERY_STAGE_WAIT_MS) {
            val recovery = permittedDevice(usb, AppleUsb.Mode.RECOVERY)
            if (recovery != null && foundationMatchesDevice(ticket.foundation, recovery)) {
                val verified = probeExpectedRecoveryStage(activity, usb, recovery, expectedStage, expectedBuild, label)
                if (verified) return recovery
            }
            Thread.sleep(RECOVERY_STAGE_POLL_MS)
        }
        error("Timed out waiting for expected $label boot-stage=$expectedStage build=$expectedBuild")
    }

    private fun probeExpectedRecoveryStage(
        activity: AppCompatActivity,
        usb: UsbManager,
        recovery: UsbDevice,
        expectedStage: String,
        expectedBuild: String,
        label: String
    ): Boolean {
        var probeConnection: android.hardware.usb.UsbDeviceConnection? = null
        return try {
            probeConnection = usb.openDevice(recovery)
            if (probeConnection == null) {
                log(activity, "prearmed $label candidate open failed: device=${recovery.deviceName} ${usbSnapshotSignature(usb, recovery)}")
                return false
            }
            val claimed = AppleUsb.claimBestInterface(recovery, probeConnection)
            if (claimed == null) {
                log(activity, "prearmed $label candidate claim failed: device=${recovery.deviceName} ${usbSnapshotSignature(usb, recovery)} interfaces=${singleLineInterfaceSummary(recovery)}")
                return false
            }
            val command = RecoveryTransport(probeConnection, claimed.bulkIn)
            val stageResult = runCatching { command.getenv("boot-stage").value.trim() }
            val buildResult = runCatching { command.getenv("build-version").value.trim() }
            val stage = stageResult.getOrNull()
            val build = buildResult.getOrNull()
            if (stageResult.isFailure || buildResult.isFailure) {
                log(
                    activity,
                    "prearmed $label candidate query failure: device=${recovery.deviceName} " +
                        "bootStageError=${stageResult.exceptionOrNull()?.let { "${it.javaClass.simpleName}:${it.message}" } ?: "none"} " +
                        "buildError=${buildResult.exceptionOrNull()?.let { "${it.javaClass.simpleName}:${it.message}" } ?: "none"}"
                )
            }
            log(activity, "prearmed $label candidate: device=${recovery.deviceName} boot-stage=${stage ?: "unknown"} build-version=${build ?: "unknown"} expectedStage=$expectedStage expectedBuild=$expectedBuild")
            stage == expectedStage && build == expectedBuild
        } catch (t: Throwable) {
            log(activity, "prearmed $label candidate probe exception: device=${recovery.deviceName} ${t.javaClass.simpleName}: ${t.message}")
            false
        } finally {
            probeConnection?.close()
        }
    }

    private fun describeAppleUsbSnapshot(usb: UsbManager, devices: List<UsbDevice>): String {
        if (devices.isEmpty()) return "devices=none"
        return "devices=" + devices.joinToString(" ; ") { usbSnapshotSignature(usb, it) }
    }

    private fun usbSnapshotSignature(usb: UsbManager, device: UsbDevice): String =
        "path=${device.deviceName} vid=0x%04x pid=0x%04x mode=%s permission=%s interfaces=%d [%s]".format(
            device.vendorId,
            device.productId,
            AppleUsb.mode(device),
            usb.hasPermission(device),
            device.interfaceCount,
            singleLineInterfaceSummary(device)
        )

    private fun singleLineInterfaceSummary(device: UsbDevice): String =
        AppleUsb.interfaceSummary(device).trim().replace("\r", "").replace("\n", " | ")

    private fun log(activity: AppCompatActivity, message: String) {
        PrearmedDiagnosticEvidenceStore.append(message)
        deliverActivityLog(activity, message)
    }

    private fun replayPersistedEvidence() {
        val activity = activity() ?: return
        val evidence = PrearmedDiagnosticEvidenceStore.snapshot()
        if (evidence.size <= replayedEvidenceSize) return
        val start = replayedEvidenceSize.coerceAtMost(evidence.size)
        evidence.subList(start, evidence.size).forEach { deliverActivityLog(activity, "[prearmed evidence] $it") }
        replayedEvidenceSize = evidence.size
    }

    private fun deliverActivityLog(activity: AppCompatActivity, message: String) = activity.runOnUiThread {
        val delivered = runCatching {
            val method = activity.javaClass.getDeclaredMethod("log", String::class.java)
            method.isAccessible = true
            method.invoke(activity, message)
            true
        }.getOrDefault(false)
        if (!delivered) activity.findViewById<TextView?>(R.id.logView)?.append(message.trimEnd() + "\n")
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
        private const val RECOVERY_STAGE_POLL_MS = 50L
        private const val RECOVERY_STAGE_WAIT_MS = 120_000L
        private const val USB_OBSERVER_HEARTBEAT_MS = 5_000L
        private const val M1_CPID = "8103"
        private const val STAGE_1 = "1"
        private const val STAGE_2 = "2"
        private const val RESERVATION_OWNER = "prearmed-stage2-go"
        private const val READY_LABEL = "Run M1 DFU → Stage-2 Test"
    }
}
