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
 * Hidden automatic coordinator that resolves, extracts, validates, and when safe locally
 * personalizes restore components. It performs no USB I/O and never sends an iBoot command or
 * image upload.
 */
class RestoreComponentPrepareButton @JvmOverloads constructor(
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
        val firmwareStatus = activity.findViewById<TextView?>(R.id.firmwareStatus)?.text?.toString().orEmpty()
        if (!firmwareStatus.startsWith("Ready")) return

        val firmware = reflected<FirmwareCatalog.Firmware>(activity, "latestSignedFirmware") ?: return
        val device = reflected<FirmwareCatalog.Device>(activity, "identifiedDevice") ?: return
        val ipsw = reflected<File>(activity, "firmwareDestination") ?: return
        if (!ipsw.isFile) return

        val ticket = TssTicketStore.get()
        val ticketStamp = if (ticket?.buildId.equals(firmware.buildId, ignoreCase = true)) ticket?.obtainedAtMillis ?: 0L else 0L
        val key = "${firmware.buildId}:${ipsw.length()}:${ipsw.lastModified()}:$ticketStamp"
        if (completedKey == key) return
        prepare(activity, device, firmware, ipsw, ticket, key)
    }

    private fun prepare(
        activity: AppCompatActivity,
        device: FirmwareCatalog.Device,
        firmware: FirmwareCatalog.Firmware,
        ipsw: File,
        ticket: TssTicketStore.Ticket?,
        key: String
    ) {
        if (!inFlight.compareAndSet(false, true)) return
        log(activity, "Restore component preparation: automatic read-only stage started")
        log(activity, "Restore component preparation: no USB command or image upload will be sent")

        worker.execute {
            try {
                require(firmware.fileSize <= 0L || ipsw.length() == firmware.fileSize) {
                    "IPSW size mismatch: expected=${firmware.fileSize} actual=${ipsw.length()}"
                }

                val preflight = IpswPreflight(logger = { log(activity, it) }).inspect(
                    IpswPreflight.Request(
                        ipsw = ipsw,
                        identifier = device.identifier,
                        boardConfig = device.boardConfig,
                        chipId = device.cpid,
                        boardId = device.bdid
                    )
                )
                require(preflight.productBuildVersion.equals(firmware.buildId, ignoreCase = true)) {
                    "BuildManifest build ${preflight.productBuildVersion} does not match selected build ${firmware.buildId}"
                }

                val usableTicket = ticket?.takeIf {
                    it.buildId.equals(firmware.buildId, ignoreCase = true) && it.identityIndex == preflight.identityIndex
                }
                if (usableTicket == null) {
                    log(activity, "Restore component personalization: no matching TSS ticket in this app session; extraction/validation only")
                } else {
                    log(activity, "Restore component personalization: matching TSS ticket available; TBM entries=${usableTicket.componentTbm.size}")
                }

                val buildDir = ipsw.parentFile ?: error("IPSW build directory is unavailable")
                val componentDir = File(buildDir, "Components/RestorePreparation")
                val personalizedDir = File(buildDir, "Personalized/RestorePreparation")
                val extractor = IpswComponentExtractor(logger = { log(activity, it) })
                val prepared = mutableListOf<RestoreComponentPreparationStore.PreparedComponent>()

                COMPONENTS.forEach { name ->
                    val path = preflight.componentPaths[name]
                    if (path == null) {
                        log(activity, "Restore component preparation: $name not present in identity ${preflight.identityIndex}; skipped")
                        return@forEach
                    }
                    val raw = extractor.extract(ipsw, preflight, name, componentDir)
                    val image4Validated = if (name in IM4P_COMPONENTS) {
                        Image4StructureValidator.validateRawIm4p(raw.file, name)
                        true
                    } else {
                        require(raw.bytes > 0L) { "$name extracted as an empty file" }
                        false
                    }

                    var personalizedFile: File? = null
                    var personalizedBytes: Long? = null
                    var personalizationState = if (name == "RestoreRamDisk") "raw-payload-awaiting-im4p-wrapper" else "awaiting-tss"

                    if (name in IM4P_COMPONENTS && usableTicket != null) {
                        val tbm = usableTicket.componentTbm[name]
                        if (tbm != null) {
                            personalizationState = "deferred-tbm-im4r-required"
                            log(
                                activity,
                                "Restore component personalization: $name DEFERRED because Apple TSS returned ${name}-TBM " +
                                    "(ucon=${tbm.ucon?.size ?: 0} bytes ucer=${tbm.ucer?.size ?: 0} bytes); no simplified IMG4 emitted"
                            )
                        } else {
                            val result = RestoreComponentImage4Personalizer.personalize(
                                raw = raw,
                                ticket = usableTicket,
                                destinationDirectory = personalizedDir,
                                logger = { log(activity, it) }
                            )
                            personalizedFile = result.file
                            personalizedBytes = result.personalizedBytes
                            personalizationState = "personalized-no-tbm"
                        }
                    }

                    prepared += RestoreComponentPreparationStore.PreparedComponent(
                        name = name,
                        manifestPath = raw.manifestPath,
                        file = raw.file,
                        bytes = raw.bytes,
                        image4Validated = image4Validated,
                        personalizedFile = personalizedFile,
                        personalizedBytes = personalizedBytes,
                        personalizationState = personalizationState
                    )
                    log(
                        activity,
                        "Restore component preparation: $name READY bytes=${raw.bytes} image4Validated=$image4Validated " +
                            "personalization=$personalizationState"
                    )
                }

                RestoreComponentPreparationStore.put(
                    RestoreComponentPreparationStore.Snapshot(
                        buildId = firmware.buildId,
                        identityIndex = preflight.identityIndex,
                        components = prepared
                    )
                )
                completedKey = key
                val personalizedCount = prepared.count { it.personalizedFile != null }
                val deferredCount = prepared.count { it.personalizationState.startsWith("deferred") }
                log(
                    activity,
                    "Restore component preparation: READY build=${firmware.buildId} identity=${preflight.identityIndex} " +
                        "components=${prepared.size} personalized=$personalizedCount deferred=$deferredCount"
                )
                log(activity, "Restore component preparation: STOPPED before Recovery upload")
            } catch (t: Throwable) {
                RestoreComponentPreparationStore.clear()
                log(activity, "Restore component preparation FAILED: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                inFlight.set(false)
            }
        }
    }

    private inline fun <reified T> reflected(activity: AppCompatActivity, fieldName: String): T? = runCatching {
        val field = activity.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.get(activity) as? T
    }.getOrNull()

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
        private const val REFRESH_MS = 1000L
        private val COMPONENTS = listOf("iBEC", "RestoreRamDisk", "RestoreDeviceTree", "RestoreSEP", "RestoreKernelCache")
        private val IM4P_COMPONENTS = setOf("iBEC", "RestoreDeviceTree", "RestoreSEP", "RestoreKernelCache")
    }
}
