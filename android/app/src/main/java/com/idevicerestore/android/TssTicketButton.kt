package com.idevicerestore.android

import android.content.Context
import android.content.ContextWrapper
import android.hardware.usb.UsbManager
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Invisible automation hook for requesting an AP/Image4 TSS ticket after firmware selection.
 *
 * This component performs no DFU_DNLOAD, recovery command, reset, or mode transition. It waits for
 * a freshly verified local firmware selection, then performs read-only IPSW/DFU inspection,
 * contacts Apple TSS, and stores both the returned ApImg4Ticket and the exact verified preparation
 * context for downstream automatic stages.
 */
class TssTicketButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatButton(context, attrs) {
    private val worker = Executors.newSingleThreadExecutor()
    private val inFlight = AtomicBoolean(false)
    private var lastAttemptKey: String? = null

    private val refreshRunnable = object : Runnable {
        override fun run() {
            maybeRequestTicketAutomatically()
            if (isAttachedToWindow) postDelayed(this, REFRESH_MS)
        }
    }

    init {
        visibility = View.GONE
        isEnabled = false
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

    private fun maybeRequestTicketAutomatically() {
        if (inFlight.get()) return
        val activity = activity() ?: return
        val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
        val dfu = usb.deviceList.values.firstOrNull {
            it.vendorId == AppleUsb.APPLE_VID && AppleUsb.mode(it) == AppleUsb.Mode.DFU && usb.hasPermission(it)
        }
        val buildId = selectedBuildId(activity)
        val firmwareStatus = activity.findViewById<TextView?>(R.id.firmwareStatus)?.text?.toString().orEmpty()
        val ready = dfu != null && buildId != null && firmwareStatus.startsWith("Ready")

        if (!ready) {
            lastAttemptKey = null
            return
        }

        val existing = TssTicketStore.get()
        val prepared = FirmwarePreparationStore.get()
        if (existing != null && existing.buildId.equals(buildId, ignoreCase = true) &&
            prepared?.matches(buildId, existing.identityIndex) == true) return

        val attemptKey = "$buildId:${dfu.deviceName}"
        if (lastAttemptKey == attemptKey) return
        lastAttemptKey = attemptKey
        requestTicket()
    }

    private fun requestTicket() {
        val activity = activity() ?: return
        if (!inFlight.compareAndSet(false, true)) return
        setOperation(activity, "Preparing firmware signing context…", true)
        log(activity, "TSS ticket request: automatic firmware-selection action started")
        log(activity, "TSS ticket request: no DFU upload or recovery command will be sent")

        worker.execute {
            try {
                val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
                val device = usb.deviceList.values.firstOrNull {
                    it.vendorId == AppleUsb.APPLE_VID && AppleUsb.mode(it) == AppleUsb.Mode.DFU && usb.hasPermission(it)
                } ?: error("No permitted Apple DFU device is connected")

                val buildId = selectedBuildId(activity)
                    ?: error("Selected firmware build could not be determined from the current UI")
                val ids = AppleUsb.bootIdentifiers(device) ?: error("DFU boot identifiers are unavailable")
                val cpid = ids.cpid ?: error("CPID unavailable")
                val bdid = ids.bdid ?: error("BDID unavailable")

                val catalog = FirmwareCatalog(logger = { log(activity, it) })
                val catalogDevice = catalog.findDeviceByBootIds(cpid, bdid)
                    ?: error("No firmware catalog device matches the connected CPID/BDID")
                log(activity, "TSS ticket request: device=${catalogDevice.identifier} build=$buildId")
                log(activity, "TSS ticket request: freshly reverifying selected build before signing")

                var firmware = catalog.reverifySigned(catalogDevice.identifier, buildId)
                if (firmware == null) {
                    firmware = BetaFirmwareCatalog(logger = { log(activity, it) })
                        .reverifySigned(catalogDevice.identifier, buildId)
                }
                firmware ?: error("Selected build $buildId is not currently signed or its Apple payload could not be verified")

                val storage = FirmwareStorage(activity, logger = { log(activity, it) })
                val location = storage.locationFor(firmware)
                val ipsw = location.file
                require(ipsw.isFile) { "Selected IPSW is not present: ${ipsw.absolutePath}" }
                require(firmware.fileSize <= 0L || ipsw.length() == firmware.fileSize) {
                    "Selected IPSW size mismatch: expected=${firmware.fileSize} actual=${ipsw.length()}"
                }
                log(activity, "TSS ticket request: local IPSW verified size=${ipsw.length()} bytes")

                val preflight = IpswPreflight(logger = { log(activity, it) }).inspect(
                    IpswPreflight.Request(
                        ipsw = ipsw,
                        identifier = catalogDevice.identifier,
                        boardConfig = catalogDevice.boardConfig,
                        chipId = catalogDevice.cpid,
                        boardId = catalogDevice.bdid
                    )
                )
                FirmwarePreparationStore.put(
                    FirmwarePreparationStore.Context(
                        device = catalogDevice,
                        firmware = firmware,
                        location = location,
                        preflight = preflight
                    )
                )
                log(
                    activity,
                    "Firmware preparation context: cached build=${firmware.buildId} identity=${preflight.identityIndex} " +
                        "components=${preflight.componentPaths.size}"
                )
                log(
                    activity,
                    "TSS ticket request: preflight READY identity=${preflight.identityIndex} board=${preflight.boardConfig ?: "unknown"}"
                )

                val connection = usb.openDevice(device) ?: error("openDevice failed for TSS signing")
                try {
                    val claimed = AppleUsb.claimBestInterface(device, connection)
                        ?: error("Could not claim DFU interface for TSS signing")
                    log(
                        activity,
                        "TSS ticket request: claimed DFU interface id=${claimed.intf.id} alt=${claimed.intf.alternateSetting}"
                    )

                    val result = TssSigningSession(logger = { log(activity, it) })
                        .requestApImg4Ticket(device, connection, ipsw, preflight)
                    TssTicketStore.put(
                        TssTicketStore.Ticket(
                            buildId = firmware.buildId,
                            identityIndex = preflight.identityIndex,
                            foundation = result.foundation,
                            apImg4Ticket = result.apImg4Ticket
                        )
                    )
                    log(
                        activity,
                        "TSS ticket request: SUCCESS build=${firmware.buildId} identity=${preflight.identityIndex} " +
                            "ApImg4Ticket=${result.apImg4Ticket.size} bytes"
                    )
                    setOperation(activity, "TSS ticket ready — continuing firmware preparation", false)
                } finally {
                    connection.close()
                    log(activity, "TSS ticket request: USB connection closed; DFU state was not changed")
                }
            } catch (t: Throwable) {
                FirmwarePreparationStore.clear()
                TssTicketStore.clear()
                Image4PreparationStore.clear()
                log(activity, "TSS ticket request FAILED: ${t.javaClass.simpleName}: ${t.message}")
                setOperation(activity, "TSS ticket request failed: ${t.message ?: t.javaClass.simpleName}", false)
            } finally {
                inFlight.set(false)
            }
        }
    }

    private fun selectedBuildId(activity: AppCompatActivity): String? {
        val title = activity.findViewById<TextView?>(R.id.firmwareTitle)?.text?.toString().orEmpty()
        return Regex("\\(([0-9]{2}[A-Za-z][A-Za-z0-9]{3,12})\\)\\s*$")
            .find(title)?.groupValues?.getOrNull(1)
    }

    private fun setOperation(activity: AppCompatActivity, message: String, busy: Boolean) {
        activity.runOnUiThread {
            activity.findViewById<TextView?>(R.id.operationStatus)?.text = message
            activity.findViewById<View?>(R.id.operationProgress)?.visibility = if (busy) View.VISIBLE else View.GONE
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
            if (!delivered) {
                activity.findViewById<TextView?>(R.id.logView)?.append(message.trimEnd() + "\n")
            }
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
