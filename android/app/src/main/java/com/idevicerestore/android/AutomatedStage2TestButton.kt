package com.idevicerestore.android

import android.content.Context
import android.content.ContextWrapper
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * User-facing current restore-entry test orchestrator.
 *
 * From DFU, one confirmation automatically starts the proven pre-armed DFU -> Stage-2 engine,
 * waits for the exact fresh custom Stage-2 build, then immediately starts the existing cumulative
 * Stage-2 restore-entry test. If the exact prepared Stage-2 build is already connected, the
 * cumulative test starts directly. This removes the manual timing race around the short-lived
 * custom Stage-2 Recovery window while keeping the proven boot and Stage-2 implementations separate.
 */
class AutomatedStage2TestButton @JvmOverloads constructor(
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
        val prearmed = rootView.findViewById<PrearmedStage1IbecButton?>(R.id.prearmedStage1IbecButton)
        val stage2 = rootView.findViewById<Stage2FirmwareBatchTestButton?>(R.id.stage2FirmwareBatchTestDelegateButton)
        isEnabled = prearmed?.isEnabled == true || stage2?.isEnabled == true
        text = READY_LABEL
    }

    private fun confirm() {
        val activity = activity() ?: return
        if (!isEnabled || inFlight.get()) return

        AlertDialog.Builder(activity)
            .setTitle("Run automated current restore-entry test?")
            .setMessage(
                "This single confirmation verifies whether the exact prepared custom Stage-2 build is already connected. If so, it starts the current cumulative restore-entry test immediately. Otherwise it automatically runs the proven M1 DFU → iBSS → Stage-1 prerequisites → iBEC/go → fresh Stage-2 chain first. The test stops before restored/usbmux restore payload traffic or erase operations."
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

        worker.execute {
            try {
                val ibss = Image4PreparationStore.get() ?: error("Personalized iBSS unavailable")
                val expectedStage1Build = Stage1BuildMetadata.expectedBuild(ibss.result.file)
                    ?: error("Prepared iBSS Stage-1 build unavailable")
                val prepared = RestoreComponentPreparationStore.get() ?: error("Prepared restore components unavailable")
                val ibecFile = prepared.components.firstOrNull { it.name == "iBEC" }?.personalizedFile
                    ?: error("Personalized iBEC unavailable")
                val expectedStage2Build = Stage1BuildMetadata.expectedBuild(ibecFile)
                    ?: error("Prepared iBEC Stage-2 build unavailable")
                val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager

                val alreadyStage2 = findExpectedStage2(usb, expectedStage2Build)
                if (alreadyStage2 != null) {
                    log(
                        activity,
                        "Automated current test: exact custom Stage-2 already connected device=${alreadyStage2.deviceName} boot-stage=2 build-version=$expectedStage2Build; starting cumulative restore-entry delegate immediately"
                    )
                    startStage2Delegate(activity)
                    waitForStage2DelegateCompletion(activity)
                    log(activity, "Automated current test: cumulative delegate returned; direct Stage-2 run complete")
                    return@execute
                }

                log(
                    activity,
                    "Automated current test: exact custom Stage-2 not currently connected; START DFU-to-current-boundary expectedStage1Build=$expectedStage1Build expectedStage2Build=$expectedStage2Build; manual Stage-2 handoff eliminated"
                )

                val prearmed = rootView.findViewById<PrearmedStage1IbecButton?>(R.id.prearmedStage1IbecButton)
                    ?: error("Pre-armed Stage-2 delegate unavailable")
                invokePrivateStartOnUiThread(activity, prearmed, expectedStage1Build, expectedStage2Build)

                val deadline = SystemClock.elapsedRealtime() + STAGE2_WAIT_MS
                var reservationObserved = false
                var releasedAt: Long? = null

                while (!Thread.currentThread().isInterrupted && SystemClock.elapsedRealtime() < deadline) {
                    if (UsbOperationReservation.isReserved()) {
                        reservationObserved = true
                        Thread.sleep(HANDOFF_POLL_MS)
                        continue
                    }

                    if (!reservationObserved) {
                        Thread.sleep(HANDOFF_POLL_MS)
                        continue
                    }

                    if (releasedAt == null) releasedAt = SystemClock.elapsedRealtime()
                    val stage2 = findExpectedStage2(usb, expectedStage2Build)
                    if (stage2 != null) {
                        val handoffMs = (SystemClock.elapsedRealtime() - releasedAt).coerceAtLeast(0L)
                        log(
                            activity,
                            "Automated current test: fresh custom Stage-2 handoff START device=${stage2.deviceName} boot-stage=2 build-version=$expectedStage2Build latencyAfterReservationReleaseMs=$handoffMs; invoking cumulative delegate without user input"
                        )
                        startStage2Delegate(activity)
                        waitForStage2DelegateCompletion(activity)
                        log(activity, "Automated current test: cumulative delegate returned; automated handoff complete")
                        return@execute
                    }
                    Thread.sleep(HANDOFF_POLL_MS)
                }

                error("Timed out waiting for the exact custom Stage-2 environment after the pre-armed boot transaction")
            } catch (t: Throwable) {
                val root = unwrapInvocationFailure(t)
                log(
                    activity,
                    "Automated current test FAILED: ${root.javaClass.simpleName}: ${root.message ?: "no message"}"
                )
            } finally {
                inFlight.set(false)
                activity.runOnUiThread { if (isAttachedToWindow) refreshState() }
            }
        }
    }

    private fun startStage2Delegate(activity: AppCompatActivity) {
        val delegate = rootView.findViewById<Stage2FirmwareBatchTestButton?>(R.id.stage2FirmwareBatchTestDelegateButton)
            ?: error("Stage-2 cumulative delegate unavailable")
        invokePrivateStartOnUiThread(activity, delegate)
        log(activity, "Automated current test: cumulative Stage-2 delegate launched")
    }

    private fun waitForStage2DelegateCompletion(activity: AppCompatActivity) {
        val delegate = rootView.findViewById<Stage2FirmwareBatchTestButton?>(R.id.stage2FirmwareBatchTestDelegateButton)
            ?: return
        val deadline = SystemClock.elapsedRealtime() + DELEGATE_WAIT_MS
        var observedRunning = false
        while (!Thread.currentThread().isInterrupted && SystemClock.elapsedRealtime() < deadline) {
            val current = readTextOnUiThread(activity, delegate)
            if (current.contains("running", ignoreCase = true)) observedRunning = true
            if (observedRunning && !current.contains("running", ignoreCase = true)) return
            Thread.sleep(DELEGATE_POLL_MS)
        }
        if (observedRunning) {
            log(activity, "Automated current test: delegate completion observation timed out; consult current Stage-2 logs for final boundary result")
        }
    }

    private fun findExpectedStage2(usb: UsbManager, expectedBuild: String): UsbDevice? {
        return usb.deviceList.values.firstOrNull { device ->
            if (
                device.vendorId != AppleUsb.APPLE_VID ||
                AppleUsb.mode(device) != AppleUsb.Mode.RECOVERY ||
                !usb.hasPermission(device) ||
                !AppleUsb.bootIdentifiers(device)?.cpidHex.equals(M1_CPID, ignoreCase = true)
            ) {
                return@firstOrNull false
            }

            var connection: android.hardware.usb.UsbDeviceConnection? = null
            try {
                connection = usb.openDevice(device) ?: return@firstOrNull false
                val claimed = AppleUsb.claimBestInterface(device, connection) ?: return@firstOrNull false
                val command = RecoveryTransport(connection, claimed.bulkIn)
                val stage = command.getenv("boot-stage").value.trim()
                val build = command.getenv("build-version").value.trim()
                stage == STAGE_2 && build == expectedBuild
            } catch (_: Throwable) {
                false
            } finally {
                connection?.close()
            }
        }
    }

    private fun invokePrivateStartOnUiThread(activity: AppCompatActivity, target: Any, vararg args: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            invokePrivateStart(target, *args)
            return
        }

        val failure = AtomicReference<Throwable?>(null)
        val latch = CountDownLatch(1)
        activity.runOnUiThread {
            try {
                invokePrivateStart(target, *args)
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        failure.get()?.let { throw unwrapInvocationFailure(it) }
    }

    private fun invokePrivateStart(target: Any, vararg args: String) {
        val parameterTypes = Array(args.size) { String::class.java }
        val method = target.javaClass.getDeclaredMethod("start", *parameterTypes)
        method.isAccessible = true
        try {
            method.invoke(target, *args)
        } catch (t: InvocationTargetException) {
            throw unwrapInvocationFailure(t)
        }
    }

    private fun readTextOnUiThread(activity: AppCompatActivity, view: TextView): String {
        if (Looper.myLooper() == Looper.getMainLooper()) return view.text?.toString().orEmpty()
        val value = AtomicReference("")
        val latch = CountDownLatch(1)
        activity.runOnUiThread {
            value.set(view.text?.toString().orEmpty())
            latch.countDown()
        }
        latch.await()
        return value.get()
    }

    private fun unwrapInvocationFailure(t: Throwable): Throwable {
        var current = t
        while (current is InvocationTargetException && current.targetException != null) {
            current = current.targetException
        }
        return current
    }

    private fun activity(): AppCompatActivity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is AppCompatActivity) return current
            current = current.baseContext
        }
        return current as? AppCompatActivity
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

    companion object {
        private const val REFRESH_MS = 500L
        private const val HANDOFF_POLL_MS = 25L
        private const val DELEGATE_POLL_MS = 100L
        private const val STAGE2_WAIT_MS = 150_000L
        private const val DELEGATE_WAIT_MS = 360_000L
        private const val M1_CPID = "8103"
        private const val STAGE_2 = "2"
        private const val READY_LABEL = "Run Current Stage-2 Test"
        private const val RUNNING_LABEL = "Automated current test running…"
    }
}
