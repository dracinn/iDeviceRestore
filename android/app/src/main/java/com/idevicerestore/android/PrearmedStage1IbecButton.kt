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

/** Pre-authorized bounded M1 iBSS -> Stage-1 -> iBEC -> Stage-2 diagnostic. */
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
            text = "Pre-armed test running…"
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
        val ready = dfu != null &&
            ids?.cpidHex.equals(M1_CPID, true) &&
            ticket != null && foundationMatchesDevice(ticket.foundation, dfu) &&
            ibss != null && ibss.result.file.isFile && ibss.result.file.length() == ibss.result.personalizedBytes &&
            expectedStage1Build != null &&
            preparedRestore != null && ibec != null && ibecFile?.isFile == true && ibec.personalizedBytes == ibecFile.length() &&
            expectedStage2Build != null &&
            buildId != null && ticket.buildId.equals(buildId, true) && preparedRestore.buildId.equals(buildId, true) &&
            preparedRestore.identityIndex == ticket.identityIndex && ibss.result.identityIndex == ticket.identityIndex
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
            .setTitle("Pre-arm iBSS → iBEC → Stage-2 test?")
            .setMessage(
                "This single confirmation authorizes the bounded Apple-silicon boot handoff through personalized iBSS and iBEC. " +
                    "The app will verify Stage-1 build-version=$expectedStage1Build, upload the validated personalized iBEC, send the upstream M1 'go' command with bRequest=1, issue the matching 0x21/1 follow-up control request, then require a fresh Recovery device with boot-stage=2 and build-version=$expectedStage2Build. " +
                    "It stops immediately after Stage-2 verification and never sends RestoreRamDisk, SEP, DeviceTree, KernelCache, bootx, or starts restore/erase sequencing."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Pre-arm bounded Stage-2 test") { _, _ -> start(expectedStage1Build, expectedStage2Build) }
            .show()
    }

    private fun start(expectedStage1Build: String, expectedStage2Build: String) {
        val activity = activity() ?: return
        if (!inFlight.compareAndSet(false, true)) return
        isEnabled = false
        text = "Pre-armed test running…"
        PrearmedDiagnosticEvidenceStore.begin(expectedStage1Build)
        replayedEvidenceSize = 0
        setOperation(activity, "Pre-armed M1 Stage-2 diagnostic starting…", true)
        log(
            activity,
            "prearmed Stage-2 test: explicit user confirmation received; boundary=iBSS-iBEC-go-verify-stage2-no-restore-components " +
                "expectedStage1Build=$expectedStage1Build expectedStage2Build=$expectedStage2Build"
        )

        worker.execute {
            var connection: android.hardware.usb.UsbDeviceConnection? = null
            var reservation: UsbOperationReservation.Lease? = null
            try {
                reservation = UsbOperationReservation.tryAcquire(RESERVATION_OWNER)
                    ?: error("Another USB operation is already active: ${UsbOperationReservation.owner() ?: "unknown"}")
                log(activity, "prearmed USB reservation acquired; automatic probes/watchdogs suppressed until bounded test ends")

                val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
                val dfu = permittedDevice(usb, AppleUsb.Mode.DFU) ?: error("No permitted Apple DFU device is connected")
                val ids = AppleUsb.bootIdentifiers(dfu) ?: error("DFU boot identifiers unavailable")
                require(ids.cpidHex.equals(M1_CPID, true)) { "Pre-armed test is restricted to M1 CPID 0x$M1_CPID" }

                val ticket = TssTicketStore.get() ?: error("TSS ticket unavailable")
                val ibssPrepared = Image4PreparationStore.get() ?: error("Personalized iBSS unavailable")
                val derivedStage1Build = Stage1BuildMetadata.expectedBuild(ibssPrepared.result.file)
                    ?: error("Prepared iBSS has no unique embedded mBoot build identifier")
                require(derivedStage1Build == expectedStage1Build) {
                    "Prepared iBSS Stage-1 build changed after confirmation: expected=$expectedStage1Build actual=$derivedStage1Build"
                }
                val restorePrepared = RestoreComponentPreparationStore.get() ?: error("Prepared restore components unavailable")
                val ibec = restorePrepared.components.firstOrNull { it.name == "iBEC" } ?: error("Prepared iBEC unavailable")
                val ibecFile = ibec.personalizedFile ?: error("Personalized iBEC unavailable")
                val derivedStage2Build = Stage1BuildMetadata.expectedBuild(ibecFile)
                    ?: error("Prepared iBEC has no unique embedded mBoot build identifier")
                require(derivedStage2Build == expectedStage2Build) {
                    "Prepared iBEC Stage-2 build changed after confirmation: expected=$expectedStage2Build actual=$derivedStage2Build"
                }
                val buildId = selectedBuildId(activity) ?: error("Selected build unavailable")
                require(foundationMatchesDevice(ticket.foundation, dfu)) { "DFU device does not match TSS foundation" }
                require(ticket.buildId.equals(buildId, true) && ticket.identityIndex == ibssPrepared.result.identityIndex) { "iBSS/TSS identity mismatch" }
                require(restorePrepared.buildId.equals(buildId, true) && restorePrepared.identityIndex == ticket.identityIndex) { "iBEC/TSS identity mismatch" }
                Image4Personalizer.validatePersonalizedIbss(ibssPrepared.result.file, ticket.apImg4Ticket)
                PersonalizedImage4Validator.validate(ibecFile, ticket.apImg4Ticket, "iBEC")

                connection = usb.openDevice(dfu) ?: error("openDevice failed for DFU")
                val claimed = AppleUsb.claimBestInterface(dfu, connection) ?: error("Could not claim DFU interface")
                val resetCapability = AndroidUsbReset.capability(connection)
                require(resetCapability.available) { "Android host USB reset is unavailable; refusing to send iBSS: ${resetCapability.reason}" }
                log(activity, "prearmed DFU preflight: Android host USB reset capability verified before iBSS upload")
                val liveNonces = DfuNonceInfo.fromConnection(connection)
                require(liveNonces.apNonce?.contentEquals(ticket.foundation.apNonce) == true) { "Live DFU ApNonce no longer matches TSS ticket" }
                val expectedSepNonce = ticket.foundation.apSepNonce
                require(expectedSepNonce == null || liveNonces.sepNonce?.contentEquals(expectedSepNonce) == true) { "Live DFU ApSepNonce no longer matches TSS ticket" }

                RestoreSessionStore.begin(buildId)
                val image = DfuStage1Session.PersonalizedIbss(
                    file = ibssPrepared.result.file,
                    identityIndex = ibssPrepared.result.identityIndex,
                    sourceManifestPath = ibssPrepared.sourceManifestPath,
                    personalizationId = "$buildId:identity-${ibssPrepared.result.identityIndex}:prearmed-stage2"
                )
                val transitionStarted = SystemClock.elapsedRealtime()
                DfuStage1Session(
                    device = dfu,
                    connection = connection,
                    interfaceId = claimed.intf.id,
                    transitions = RestoreSessionStore.transitions,
                    logger = { log(activity, "prearmed $it") }
                ).uploadPersonalizedIbss(image) { progress -> setProgress(activity, "Sending personalized iBSS", progress.percent) }
                connection.close()
                connection = null
                log(activity, "prearmed Stage-2 test: iBSS sent; waiting for fresh Stage-1 Recovery expectedBuild=$expectedStage1Build")

                val stage1 = waitForExpectedRecoveryStage(activity, usb, ticket, transitionStarted, STAGE_1, expectedStage1Build, "Stage-1")
                val identityKey = deviceIdentityKey(stage1)
                log(activity, "prearmed Stage-1 proof: boot-stage=1 build-version=$expectedStage1Build")
                Stage1RecoveryProofStore.prove(identityKey, STAGE_1, expectedStage1Build)

                connection = usb.openDevice(stage1) ?: error("openDevice failed for custom Stage-1 Recovery")
                val recoveryClaimed = AppleUsb.claimBestInterface(stage1, connection) ?: error("Could not claim Recovery interface")
                val bulkOut = recoveryClaimed.bulkOut ?: error("Recovery bulk OUT endpoint unavailable")
                val command = RecoveryTransport(connection, recoveryClaimed.bulkIn)
                val liveStage = command.getenv("boot-stage").value.trim()
                val liveBuild = command.getenv("build-version").value.trim()
                require(liveStage == STAGE_1 && liveBuild == expectedStage1Build) {
                    "Custom Stage-1 changed before iBEC init: boot-stage=$liveStage build-version=$liveBuild expected=$expectedStage1Build"
                }
                require(foundationMatchesDevice(ticket.foundation, stage1)) { "Recovery device no longer matches TSS foundation" }
                log(
                    activity,
                    "prearmed Stage-1 Recovery interface already claimed: id=${recoveryClaimed.intf.id} alt=${recoveryClaimed.intf.alternateSetting} " +
                        "bulkOut=0x%02x".format(bulkOut.address)
                )

                log(activity, "prearmed iBEC: issuing 0x41/0 on custom Stage-1; failed init must send zero bulk bytes")
                val result = FileInputStream(ibecFile).use { input ->
                    RecoveryUploadTransport(connection, bulkOut).sendStream(input, ibecFile.length()) { progress ->
                        setProgress(activity, "Uploading personalized iBEC", progress.percent)
                    }
                }
                log(
                    activity,
                    "prearmed iBEC COMPLETE: bytes=${result.bytesSent} packets=${result.packetsSent} endpoint=0x%02x initResult=%s initElapsedMs=%s"
                        .format(result.endpointAddress, result.initResult?.toString() ?: "unknown", result.initElapsedMs?.toString() ?: "unknown")
                )

                val stage2TransitionStarted = SystemClock.elapsedRealtime()
                log(activity, "prearmed iBEC: sending upstream Apple-silicon go command bRequest=${RecoveryTransport.APPLE_SILICON_GO_BREQUEST}")
                val goBytes = command.sendCommandBreq("go", RecoveryTransport.APPLE_SILICON_GO_BREQUEST)
                log(activity, "prearmed iBEC: go command accepted bytes=$goBytes")
                val followup = runCatching {
                    command.controlTransferOut(
                        requestType = APPLE_DFU_REQUEST_TYPE_OUT,
                        request = APPLE_DFU_DETACH_REQUEST,
                        timeoutMs = UPSTREAM_FOLLOWUP_TIMEOUT_MS
                    )
                }
                followup.onSuccess {
                    log(activity, "prearmed iBEC: upstream 0x21/1 follow-up result=${it.transferred}")
                }.onFailure {
                    log(activity, "prearmed iBEC: upstream 0x21/1 follow-up became unavailable after go: ${it.javaClass.simpleName}: ${it.message}; deferring outcome to Stage-2 re-enumeration")
                }
                connection.close()
                connection = null

                log(activity, "prearmed Stage-2 test: waiting for fresh Recovery boot-stage=2 expectedBuild=$expectedStage2Build")
                val stage2 = waitForExpectedRecoveryStage(activity, usb, ticket, stage2TransitionStarted, STAGE_2, expectedStage2Build, "Stage-2")
                log(activity, "prearmed Stage-2 proof: boot-stage=2 build-version=$expectedStage2Build device=${stage2.deviceName}")
                log(activity, "prearmed Stage-2 test: STOP boundary reached — no RestoreRamDisk, SEP, DeviceTree, KernelCache, bootx, restore, or erase command sent")
                setOperation(activity, "Pre-armed diagnostic complete; Stage-2 verified", false)
            } catch (t: Throwable) {
                log(activity, "prearmed Stage-2 test FAILED: ${t.javaClass.simpleName}: ${t.message}")
                setOperation(activity, "Pre-armed diagnostic stopped: ${t.message ?: t.javaClass.simpleName}", false)
            } finally {
                connection?.close()
                reservation?.let { lease ->
                    runCatching { UsbOperationReservation.release(lease) }
                        .onSuccess { log(activity, "prearmed USB reservation released") }
                }
                inFlight.set(false)
                activity.runOnUiThread { if (isAttachedToWindow) refreshState() }
            }
        }
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
        var lastSeen: String? = null
        while (SystemClock.elapsedRealtime() - startedAt < RECOVERY_STAGE_WAIT_MS) {
            val recovery = permittedDevice(usb, AppleUsb.Mode.RECOVERY)
            if (recovery != null && foundationMatchesDevice(ticket.foundation, recovery)) {
                var probeConnection: android.hardware.usb.UsbDeviceConnection? = null
                try {
                    probeConnection = usb.openDevice(recovery)
                    if (probeConnection != null) {
                        val claimed = AppleUsb.claimBestInterface(recovery, probeConnection)
                        if (claimed != null) {
                            val command = RecoveryTransport(probeConnection, claimed.bulkIn)
                            val stage = runCatching { command.getenv("boot-stage").value.trim() }.getOrNull()
                            val build = runCatching { command.getenv("build-version").value.trim() }.getOrNull()
                            val seen = "$stage/$build"
                            if (seen != lastSeen) {
                                lastSeen = seen
                                log(activity, "prearmed $label candidate: boot-stage=${stage ?: "unknown"} build-version=${build ?: "unknown"} expectedStage=$expectedStage expectedBuild=$expectedBuild")
                            }
                            if (stage == expectedStage && build == expectedBuild) return recovery
                        }
                    }
                } finally {
                    probeConnection?.close()
                }
            }
            Thread.sleep(RECOVERY_STAGE_POLL_MS)
        }
        error("Timed out waiting for expected $label boot-stage=$expectedStage build=$expectedBuild")
    }

    private fun permittedDevice(usb: UsbManager, mode: AppleUsb.Mode): UsbDevice? = usb.deviceList.values.firstOrNull {
        it.vendorId == AppleUsb.APPLE_VID && AppleUsb.mode(it) == mode && usb.hasPermission(it)
    }

    private fun foundationMatchesDevice(f: TssRequestFoundation.Parameters, d: UsbDevice): Boolean {
        val ids = AppleUsb.bootIdentifiers(d) ?: return false
        val ecid = ids.ecidHex?.toULongOrNull(16) ?: return false
        val cpid = ids.cpidHex?.toLongOrNull(16) ?: return false
        val bdid = ids.bdidHex?.toLongOrNull(16) ?: return false
        return f.ecid == ecid && f.apChipId == cpid && f.apBoardId == bdid
    }

    private fun deviceIdentityKey(d: UsbDevice): String {
        val ids = AppleUsb.bootIdentifiers(d)
        return "${d.deviceName}:${d.productId}:${ids?.ecidHex ?: "?"}:${ids?.cpidHex ?: "?"}:${ids?.bdidHex ?: "?"}"
    }

    private fun selectedBuildId(activity: AppCompatActivity): String? {
        val title = activity.findViewById<TextView?>(R.id.firmwareTitle)?.text?.toString().orEmpty()
        return Regex("\\(([0-9]{2}[A-Za-z][A-Za-z0-9]{3,12})\\)\\s*$").find(title)?.groupValues?.getOrNull(1)
    }

    private fun setProgress(activity: AppCompatActivity, label: String, percent: Int) = activity.runOnUiThread {
        activity.findViewById<android.widget.ProgressBar?>(R.id.operationProgress)?.apply {
            visibility = View.VISIBLE
            isIndeterminate = false
            progress = percent.coerceIn(0, 100)
        }
        activity.findViewById<TextView?>(R.id.operationStatus)?.text = "$label… ${percent.coerceIn(0, 100)}%"
    }

    private fun setOperation(activity: AppCompatActivity, message: String, busy: Boolean) = activity.runOnUiThread {
        activity.findViewById<TextView?>(R.id.operationStatus)?.text = message
        activity.findViewById<android.widget.ProgressBar?>(R.id.operationProgress)?.apply {
            visibility = if (busy) View.VISIBLE else View.GONE
            if (busy) isIndeterminate = true
        }
    }

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
        private const val UPSTREAM_FOLLOWUP_TIMEOUT_MS = 5_000
        private const val APPLE_DFU_REQUEST_TYPE_OUT = 0x21
        private const val APPLE_DFU_DETACH_REQUEST = 0x01
        private const val M1_CPID = "8103"
        private const val STAGE_1 = "1"
        private const val STAGE_2 = "2"
        private const val RESERVATION_OWNER = "prearmed-stage2-go"
        private const val READY_LABEL = "Pre-arm M1 iBSS → iBEC → Stage-2 Test"
    }
}
