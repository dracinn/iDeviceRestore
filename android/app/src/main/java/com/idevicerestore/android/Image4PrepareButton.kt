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
 * This stage consumes the exact freshly verified firmware/preflight context published by the TSS
 * stage, avoiding duplicate catalog verification and BuildManifest parsing. It performs no USB I/O
 * and stops before any DFU upload session is constructed.
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
        val context = FirmwarePreparationStore.get() ?: return
        if (!context.matches(buildId, ticket.identityIndex)) return
        val key = "$buildId:${ticket.identityIndex}:${ticket.obtainedAtMillis}:${context.preparedAtMillis}"
        if (completedKey == key) return
        prepareIbssAutomatically(key)
    }

    private fun prepareIbssAutomatically(key: String) {
        val activity = activity() ?: return
        if (!inFlight.compareAndSet(false, true)) return
        setOperation(activity, "Preparing personalized iBSS…", true)
        log(activity, "Image4 preparation: automatic firmware-preparation stage started")
        log(activity, "Image4 preparation: reusing cached verified firmware/preflight context")
        log(activity, "Image4 preparation: no USB command will be sent")

        worker.execute {
            try {
                val ticket = TssTicketStore.get() ?: error("No TSS ticket is available in this app session")
                val context = FirmwarePreparationStore.get() ?: error("No verified firmware preparation context is available")
                val buildId = selectedBuildId(activity)
                    ?: error("Selected firmware build could not be determined from the current UI")
                require(ticket.buildId.equals(buildId, ignoreCase = true)) {
                    "TSS ticket build ${ticket.buildId} does not match selected build $buildId"
                }
                require(context.matches(buildId, ticket.identityIndex)) {
                    "Cached preparation context does not match selected build/TSS identity"
                }

                val firmware = context.firmware
                val location = context.location
                val ipsw = location.file
                val preflight = context.preflight
                require(ipsw.isFile) { "Selected IPSW is not present: ${ipsw.absolutePath}" }
                require(firmware.fileSize <= 0L || ipsw.length() == firmware.fileSize) {
                    "Selected IPSW size mismatch: expected=${firmware.fileSize} actual=${ipsw.length()}"
                }
                require(preflight.identityIndex == ticket.identityIndex) {
                    "Cached BuildIdentity ${preflight.identityIndex} does not match TSS identity ${ticket.identityIndex}"
                }

                log(
                    activity,
                    "Image4 preparation: cached context READY device=${context.identifier} build=$buildId " +
                        "identity=${preflight.identityIndex} board=${preflight.boardConfig ?: "unknown"}"
                )

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
                setOperation(activity, "Firmware preparation ready — Start Restore is available", false)
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
