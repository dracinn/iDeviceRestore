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

/** Pre-authorized bounded M1 iBSS -> Stage-1 -> iBEC timing diagnostic. */
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
        val buildId = selectedBuildId(activity)
        val ready = dfu != null &&
            ids?.cpidHex.equals(M1_CPID, true) &&
            ticket != null && foundationMatchesDevice(ticket.foundation, dfu) &&
            ibss != null && ibss.result.file.isFile && ibss.result.file.length() == ibss.result.personalizedBytes &&
            expectedStage1Build != null &&
            preparedRestore != null && ibec?.personalizedFile?.isFile == true && ibec.personalizedBytes == ibec.personalizedFile.length() &&
            buildId != null && ticket.buildId.equals(buildId, true) && preparedRestore.buildId.equals(buildId, true) &&
            preparedRestore.identityIndex == ticket.identityIndex && ibss.result.identityIndex == ticket.identityIndex
        isEnabled = ready
        text = if (ready) READY_LABEL else if (ibss != null && expectedStage1Build == null) {
            "Prepared iBSS Stage-1 build unavailable"
        } else READY_LABEL
    }

    private fun confirm() {
        val activity = activity() ?: return
        if (!isEnabled || inFlight.get()) return
        val preparedIbss = Image4PreparationStore.get() ?: return
        val expectedStage1Build = Stage1BuildMetadata.expectedBuild(preparedIbss.result.file) ?: run {
            isEnabled = false
            text = "Prepared iBSS Stage-1 build unavailable"
            log(activity, "prearmed Stage-1 iBEC test blocked: prepared iBSS has no unique embedded mBoot build identifier")
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Pre-arm iBSS → Stage-1 → iBEC test?")
            .setMessage(
                "This single confirmation authorizes two bounded boot-state changes so the app can act inside the short custom Stage-1 window without another tap. " +
                    "It will upload the validated personalized iBSS, wait for Recovery, require boot-stage=1 and firmware-derived build-version=$expectedStage1Build, then issue the 0x41/0 iBEC upload initialization. " +
                    "If initialization fails, zero iBEC bulk bytes are sent. If it succeeds, only the personalized iBEC is uploaded. The test never sends 'go', RestoreRamDisk, SEP, DeviceTree, KernelCache, or starts restore/erase sequencing."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Pre-arm bounded test") { _, _ -> start(expectedStage1Build) }
            .show()
    }

    private fun start(expectedStage1Build: String) {
        val activity = activity() ?: return
        if (!inFlight.compareAndSet(false, true)) return
        isEnabled = false
        text = "Pre-armed test running…"
        PrearmedDiagnosticEvidenceStore.begin(expectedStage1Build)
        replayedEvidenceSize = 0
        setOperation(activity, "Pre-armed M1 iBSS → Stage-1 → iBEC diagnostic starting…", true)
        log(activity, "prearmed Stage-1 iBEC test: explicit user confirmation received; boundary=iBSS-then-iBEC-no-go expectedStage1Build=$expectedStage1Build")

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
                    personalizationId = "$buildId:identity-${ibssPrepared.result.identityIndex}:prearmed"
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
                log(activity, "prearmed Stage-1 iBEC test: iBSS sent; waiting for fresh Recovery enumeration expectedBuild=$expectedStage1Build")

                val recovery = waitForExpectedStage1(activity, usb, ticket, transitionStarted, expectedStage1Build)
                val identityKey = deviceIdentityKey(recovery)
                log(activity, "prearmed Stage-1 proof: boot-stage=1 build-version=$expectedStage1Build; handing off immediately to PR #36 upload path")
                Stage1RecoveryProofStore.prove(identityKey, STAGE_1, expectedStage1Build)

                connection = usb.openDevice(recovery) ?: error("openDevice failed for custom Stage-1 Recovery")
                val recoveryClaimed = AppleUsb.claimBestInterface(recovery, connection) ?: error("Could not claim Recovery interface")
                val bulkOut = recoveryClaimed.bulkOut ?: error("Recovery bulk OUT endpoint unavailable")
                val command = RecoveryTransport(connection, recoveryClaimed.bulkIn)
                val liveStage = command.getenv("boot-stage").value.trim()
                val liveBuild = command.getenv("build-version").value.trim()
                require(liveStage == STAGE_1 && liveBuild == expectedStage1Build) {
                    "Custom Stage-1 changed before iBEC init: boot-stage=$liveStage build-version=$liveBuild expected=$expectedStage1Build"
                }
                require(foundationMatchesDevice(ticket.foundation, recovery)) { "Recovery device no longer matches TSS foundation" }
                require(connection.setInterface(recoveryClaimed.intf)) { "Android could not activate Recovery interface" }

                log(activity, "prearmed iBEC: issuing 0x41/0 on custom Stage-1; failed init must send zero bulk bytes")
                val result = FileInputStream(ibecFile).use { input ->
                    RecoveryUploadTransport(connection, bulkOut).sendStream(input, ibecFile.length()) { progress ->
                        setProgress(activity, "Uploading personalized iBEC", progress.percent)
                    }
                }
                log(activity, "prearmed iBEC COMPLETE: bytes=${result.bytesSent} packets=${result.packetsSent} endpoint=0x%02x initResult=%s initElapsedMs=%s".format(result.endpointAddress, result.initResult?.toString() ?: "unknown", result.initElapsedMs?.toString() ?: "unknown"))
                log(activity, "prearmed Stage-1 iBEC test: STOP boundary reached — no go and no restore-OS component sent")
                setOperation(activity, "Pre-armed diagnostic complete; stopped before go", false)
            } catch (t: Throwable) {
                log(activity, "prearmed Stage-1 iBEC test FAILED: ${t.javaClass.simpleName}: ${t.message}")
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

    private fun waitForExpectedStage1(
        activity: AppCompatActivity,
        usb: UsbManager,
        ticket: TssTicketStore.Ticket,
        startedAt: Long,
        expectedStage1Build: String
    ): UsbDevice {
        var lastSeen: String? = null
        while (SystemClock.elapsedRealtime() - startedAt < STAGE1_WAIT_MS) {
            val recovery = permittedDevice(usb, AppleUsb.Mode.RECOVERY)
            if (recovery != null && foundationMatchesDevice(ticket.foundation, recovery)) {
                var connection: android.hardware.usb.UsbDeviceConnection? = null
                try {
                    connection = usb.openDevice(recovery)
                    if (connection != null) {
                        val claimed = AppleUsb.claimBestInterface(recovery, connection)
                        if (claimed != null) {
                            val command = RecoveryTransport(connection, claimed.bulkIn)
                            val stage = runCatching { command.getenv("boot-stage").value.trim() }.getOrNull()
                            val build = runCatching { command.getenv("build-version").value.trim() }.getOrNull()
                            val seen = "$stage/$build"
                            if (seen != lastSeen) {
                                lastSeen = seen
                                log(activity, "prearmed Stage-1 candidate: boot-stage=${stage ?: "unknown"} build-version=${build ?: "unknown"} expected=$expectedStage1Build")
                            }
                            if (stage == STAGE_1 && build == expectedStage1Build) return recovery
                        }
                    }
                } finally { connection?.close() }
            }
            Thread.sleep(STAGE1_POLL_MS)
        }
        error("Timed out waiting for expected custom Stage-1 build $expectedStage1Build")
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
        private const val STAGE1_POLL_MS = 50L
        private const val STAGE1_WAIT_MS = 120_000L
        private const val M1_CPID = "8103"
        private const val STAGE_1 = "1"
        private const val RESERVATION_OWNER = "prearmed-stage1-ibec"
        private const val READY_LABEL = "Pre-arm M1 iBSS → iBEC Timing Test"
    }
}
