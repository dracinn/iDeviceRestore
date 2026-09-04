package com.idevicerestore.android

import android.content.Context
import android.content.ContextWrapper
import android.hardware.usb.UsbManager
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * First state-changing restore gate.
 *
 * The button remains disabled until automatic firmware preparation produced a validated personalized
 * iBSS matching the selected build and live TSS identity. A confirmation is required before any
 * DFU_DNLOAD is sent. Android USB reset capability is checked before upload begins.
 */
class StartRestoreButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatButton(context, attrs) {
    private val worker = Executors.newSingleThreadExecutor()
    private val inFlight = AtomicBoolean(false)
    private var recoveryLogged = false

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshState()
            if (isAttachedToWindow) postDelayed(this, REFRESH_MS)
        }
    }

    init {
        text = "Start Restore"
        isEnabled = false
        setOnClickListener { confirmStart() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(refreshRunnable)
        post(refreshRunnable)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refreshRunnable)
        worker.shutdownNow()
        super.onDetachedFromWindow()
    }

    private fun refreshState() {
        val activity = activity() ?: run {
            isEnabled = false
            return
        }
        val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
        val snapshot = RestoreSessionStore.transitions.snapshot()

        if (snapshot.state == RestoreUsbStateMachine.State.WAITING_FOR_RECOVERY) {
            isEnabled = false
            text = "Waiting for Recovery…"
            val recovery = usb.deviceList.values.firstOrNull {
                it.vendorId == AppleUsb.APPLE_VID && AppleUsb.mode(it) == AppleUsb.Mode.RECOVERY && usb.hasPermission(it)
            }
            if (recovery != null) {
                val accepted = RestoreSessionStore.transitions.onAttached(recovery)
                if (accepted.state == RestoreUsbStateMachine.State.RECOVERY_CONNECTED && !recoveryLogged) {
                    recoveryLogged = true
                    log(activity, "Restore transition: ${accepted.message}")
                    setOperation(activity, "Recovery connected — ready for next restore stage", false)
                    text = "Recovery Connected"
                }
            }
            return
        }

        if (snapshot.state == RestoreUsbStateMachine.State.RECOVERY_CONNECTED) {
            isEnabled = false
            text = "Recovery Connected"
            return
        }

        if (inFlight.get()) {
            isEnabled = false
            text = "Starting Restore…"
            return
        }

        val buildId = selectedBuildId(activity)
        val prepared = Image4PreparationStore.get()
        val context = FirmwarePreparationStore.get()
        val ticket = TssTicketStore.get()
        val dfu = usb.deviceList.values.firstOrNull {
            it.vendorId == AppleUsb.APPLE_VID && AppleUsb.mode(it) == AppleUsb.Mode.DFU && usb.hasPermission(it)
        }
        val ready = buildId != null && dfu != null && prepared != null && context != null && ticket != null &&
            prepared.result.buildId.equals(buildId, ignoreCase = true) &&
            context.matches(buildId, prepared.result.identityIndex) &&
            ticket.buildId.equals(buildId, ignoreCase = true) &&
            ticket.identityIndex == prepared.result.identityIndex &&
            prepared.result.file.isFile && prepared.result.file.length() == prepared.result.personalizedBytes

        text = "Start Restore"
        isEnabled = ready
    }

    private fun confirmStart() {
        val activity = activity() ?: return
        if (!isEnabled || inFlight.get()) return
        AlertDialog.Builder(activity)
            .setTitle("Start restore?")
            .setMessage(
                "This will send the validated personalized iBSS to the connected Mac in DFU mode and reset its USB connection so it can enter Recovery. " +
                    "This changes the device boot state. The filesystem erase/restore stage has not started yet."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Start Restore") { _, _ -> startRestore() }
            .show()
    }

    private fun startRestore() {
        val activity = activity() ?: return
        if (!inFlight.compareAndSet(false, true)) return
        isEnabled = false
        text = "Starting Restore…"
        recoveryLogged = false
        setOperation(activity, "Starting DFU restore stage…", true)
        log(activity, "Restore start: explicit user confirmation received")

        worker.execute {
            var connection: android.hardware.usb.UsbDeviceConnection? = null
            try {
                val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
                val device = usb.deviceList.values.firstOrNull {
                    it.vendorId == AppleUsb.APPLE_VID && AppleUsb.mode(it) == AppleUsb.Mode.DFU && usb.hasPermission(it)
                } ?: error("No permitted Apple DFU device is connected")

                val prepared = Image4PreparationStore.get() ?: error("Personalized iBSS is not prepared")
                val context = FirmwarePreparationStore.get() ?: error("Verified firmware context is unavailable")
                val ticket = TssTicketStore.get() ?: error("TSS ticket is unavailable")
                val buildId = selectedBuildId(activity) ?: error("Selected build could not be determined")

                require(prepared.result.buildId.equals(buildId, ignoreCase = true)) { "Prepared iBSS build mismatch" }
                require(context.matches(buildId, prepared.result.identityIndex)) { "Firmware context mismatch" }
                require(ticket.buildId.equals(buildId, ignoreCase = true) && ticket.identityIndex == prepared.result.identityIndex) {
                    "TSS ticket mismatch"
                }
                Image4Personalizer.validatePersonalizedIbss(prepared.result.file, ticket.apImg4Ticket)
                log(
                    activity,
                    "Restore start: personalized iBSS revalidated bytes=${prepared.result.personalizedBytes} identity=${prepared.result.identityIndex}"
                )

                connection = usb.openDevice(device) ?: error("openDevice failed for restore start")
                val claimed = AppleUsb.claimBestInterface(device, connection)
                    ?: error("Could not claim DFU interface for restore start")
                val resetCapability = AndroidUsbReset.capability(connection)
                check(resetCapability.available) { resetCapability.reason }
                log(activity, "Restore start: Android host USB reset capability verified")

                RestoreSessionStore.begin(buildId)
                val image = DfuStage1Session.PersonalizedIbss(
                    file = prepared.result.file,
                    identityIndex = prepared.result.identityIndex,
                    sourceManifestPath = prepared.sourceManifestPath,
                    personalizationId = "$buildId:identity-${prepared.result.identityIndex}"
                )
                val result = DfuStage1Session(
                    device = device,
                    connection = connection,
                    interfaceId = claimed.intf.id,
                    transitions = RestoreSessionStore.transitions,
                    logger = { log(activity, it) }
                ).uploadPersonalizedIbss(image) { progress ->
                    setProgress(activity, progress.percent)
                }
                log(
                    activity,
                    "Restore start: personalized iBSS sent bytes=${result.bytes} blocks=${result.blocks}; " +
                        "transition=${result.transition.state}"
                )
                setOperation(activity, "iBSS sent — waiting for Mac to reconnect in Recovery", true)
            } catch (t: Throwable) {
                RestoreSessionStore.transitions.fail("Restore start failed: ${t.message ?: t.javaClass.simpleName}")
                log(activity, "Restore start FAILED: ${t.javaClass.simpleName}: ${t.message}")
                setOperation(activity, "Restore start failed: ${t.message ?: t.javaClass.simpleName}", false)
            } finally {
                connection?.close()
                if (connection != null) log(activity, "Restore start: DFU USB connection closed")
                inFlight.set(false)
                activity.runOnUiThread { refreshState() }
            }
        }
    }

    private fun selectedBuildId(activity: AppCompatActivity): String? {
        val title = activity.findViewById<TextView?>(R.id.firmwareTitle)?.text?.toString().orEmpty()
        return Regex("\\(([0-9]{2}[A-Za-z][A-Za-z0-9]{3,12})\\)\\s*$")
            .find(title)?.groupValues?.getOrNull(1)
    }

    private fun setProgress(activity: AppCompatActivity, percent: Int) {
        activity.runOnUiThread {
            activity.findViewById<android.widget.ProgressBar?>(R.id.operationProgress)?.apply {
                visibility = View.VISIBLE
                isIndeterminate = false
                progress = percent.coerceIn(0, 100)
            }
            activity.findViewById<TextView?>(R.id.operationStatus)?.text = "Sending personalized iBSS… ${percent.coerceIn(0, 100)}%"
        }
    }

    private fun setOperation(activity: AppCompatActivity, message: String, busy: Boolean) {
        activity.runOnUiThread {
            activity.findViewById<TextView?>(R.id.operationStatus)?.text = message
            activity.findViewById<android.widget.ProgressBar?>(R.id.operationProgress)?.apply {
                visibility = if (busy) View.VISIBLE else View.GONE
                if (busy) isIndeterminate = true
            }
        }
    }

    private fun log(activity: AppCompatActivity, message: String) {
        activity.runOnUiThread {
            val delivered = runCatching {
                val method = activity.javaClass.getDeclaredMethod("log", String::class.java)
                method.isAccessible = true
                method.invoke(activity, message)
                true
            }.getOrDefault(false)
            if (!delivered) activity.findViewById<TextView?>(R.id.logView)?.append(message.trimEnd() + "\n")
        }
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
        private const val REFRESH_MS = 500L
    }
}
