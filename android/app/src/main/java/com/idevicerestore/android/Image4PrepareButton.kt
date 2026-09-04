package com.idevicerestore.android

import android.content.Context
import android.content.ContextWrapper
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hidden automatic coordinator for extracting, personalizing, and validating iBSS.
 *
 * Once a selected firmware is ready and a matching in-memory TSS ticket exists, this component
 * automatically re-verifies the build, resolves the same BuildIdentity, extracts raw iBSS,
 * creates and validates the IMG4 wrapper, then stops before any DFU upload is constructed.
 */
class Image4PrepareButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatButton(context, attrs) {
    private val worker = Executors.newSingleThreadExecutor()
    private val inFlight = AtomicBoolean(false)
    @Volatile private var completedKey: String? = null

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshAutomaticState()
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

    private fun refreshAutomaticState() {
        if (inFlight.get()) return
        val activity = activity() ?: return
        val buildId = selectedBuildId(activity) ?: return
        val firmwareStatus = activity.findViewById<TextView?>(R.id.firmwareStatus)?.text?.toString().orEmpty()
        if (!firmwareStatus.startsWith("Ready")) return
        val ticket = TssTicketStore.get() ?: return
        if (ticket.buildId != buildId) return
        val key = "$buildId:${ticket.identityIndex}:${ticket.obtainedAtMillis}"
        if (completedKey == key) return
        prepareIbssAutomatically(key)
    }

    private fun prepareIbssAutomatically(key: String) {
        val activity = activity() ?: return
        if (!inFlight.compareAndSet(false, true)) return
        setOperation(activity, "Preparing personalized iBSS…", true)
        log(activity, "Image4 preparation: automatic firmware-preparation stage started")
        log(activity, "Image4 preparation: no USB command will be sent")

        worker.execute {
            try {
                val ticket = TssTicketStore.get() ?: error("No TSS ticket is available in this app session")
                val buildId = selectedBuildId(activity)
                    ?: error("Selected firmware build could not be determined from the current UI")
                require(ticket.buildId == buildId) {
                    "TSS ticket build ${ticket.buildId} does not match selected build $buildId"
                }

                val identifier = selectedIdentifier(activity)
                    ?: error("Identified device could not be determined from the current UI")
                log(activity, "Image4 preparation: device=$identifier build=$buildId identity=${ticket.identityIndex}")
                log(activity, "Image4 preparation: freshly reverifying selected build")

                val stableCatalog = FirmwareCatalog(logger = { log(activity, it) })
                var firmware = stableCatalog.reverifySigned(identifier, buildId)
                if (firmware == null) {
                    firmware = BetaFirmwareCatalog(logger = { log(activity, it) })
                        .reverifySigned(identifier, buildId)
                }
                firmware ?: error("Selected build $buildId is not currently signed or its Apple payload could not be verified")

                val storage = FirmwareStorage(activity, logger = { log(activity, it) })
                val location = storage.locationFor(firmware)
                val ipsw = location.file
                require(ipsw.isFile) { "Selected IPSW is not present: ${ipsw.absolutePath}" }
                require(firmware.fileSize <= 0L || ipsw.length() == firmware.fileSize) {
                    "Selected IPSW size mismatch: expected=${firmware.fileSize} actual=${ipsw.length()}"
                }

                val catalogDevice = stableCatalog.listDevices()
                    .firstOrNull { it.identifier.equals(identifier, ignoreCase = true) }
                    ?: error("No device metadata found for $identifier")
                val preflight = IpswPreflight(logger = { log(activity, it) }).inspect(
                    IpswPreflight.Request(
                        ipsw = ipsw,
                        identifier = identifier,
                        boardConfig = catalogDevice.boardConfig,
                        chipId = catalogDevice.cpid,
                        boardId = catalogDevice.bdid
                    )
                )
                require(preflight.identityIndex == ticket.identityIndex) {
                    "Current BuildIdentity ${preflight.identityIndex} does not match TSS identity ${ticket.identityIndex}"
                }
                log(activity, "Image4 preparation: preflight READY identity=${preflight.identityIndex} board=${preflight.boardConfig ?: "unknown"}")

                val componentsDir = File(location.buildDirectory, "Components")
                val personalizedDir = File(location.buildDirectory, "Personalized")
                val raw = IpswComponentExtractor(logger = { log(activity, it) })
                    .extract(ipsw, preflight, "iBSS", componentsDir)
                val personalized = Image4Personalizer.personalizeIbss(
                    rawIbss = raw,
                    ticket = ticket,
                    destinationDirectory = personalizedDir,
                    logger = { log(activity, it) }
                )
                Image4Personalizer.validatePersonalizedIbss(personalized.file, ticket.apImg4Ticket)
                Image4PreparationStore.put(
                    Image4PreparationStore.PreparedIbss(
                        result = personalized,
                        sourceManifestPath = raw.manifestPath
                    )
                )
                completedKey = key
                log(activity, "Image4 preparation: structural validation PASSED")
                log(
                    activity,
                    "Image4 preparation: READY build=${personalized.buildId} identity=${personalized.identityIndex} " +
                        "raw=${personalized.rawBytes} bytes ticket=${personalized.ticketBytes} bytes personalized=${personalized.personalizedBytes} bytes"
                )
                log(activity, "Image4 preparation: output=${personalized.file.absolutePath}")
                log(activity, "Image4 preparation: STOPPED before DFU upload")
                setOperation(activity, "Firmware preparation ready — restore upload has not started", false)
            } catch (t: Throwable) {
                Image4PreparationStore.clear()
                log(activity, "Image4 preparation FAILED: ${t.javaClass.simpleName}: ${t.message}")
                setOperation(activity, "iBSS preparation failed: ${t.message ?: t.javaClass.simpleName}", false)
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

    private fun selectedIdentifier(activity: AppCompatActivity): String? {
        val status = activity.findViewById<TextView?>(R.id.status)?.text?.toString().orEmpty()
        return Regex("\\(([A-Za-z0-9,._-]+)\\)")
            .find(status)?.groupValues?.getOrNull(1)
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
        private const val REFRESH_MS = 750L
    }
}
