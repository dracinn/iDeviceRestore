package com.idevicerestore.android

import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application-facing orchestration layer for catalog firmware downloads.
 *
 * FirmwareDownloader owns HTTP mechanics. FirmwareStorage owns paths. This class connects the
 * two and exposes stable state suitable for an Activity, Service, notification, or restore state
 * machine without coupling those callers to temporary segment files.
 */
class FirmwareDownloadManager(
    private val catalog: FirmwareCatalog,
    private val storage: FirmwareStorage,
    private val downloader: FirmwareDownloader,
    private val logger: (String) -> Unit = {}
) : Closeable {
    data class Plan(
        val firmware: FirmwareCatalog.Firmware,
        val destination: File,
        val catalogCache: File,
        val complete: Boolean,
        val partialBytes: Long,
        val availableBytes: Long
    )

    sealed interface Event {
        data class Started(val plan: Plan) : Event
        data class Progress(val plan: Plan, val progress: FirmwareDownloader.Progress) : Event
        data class Completed(val plan: Plan, val result: FirmwareDownloader.Result) : Event
        data class Cancelled(val plan: Plan) : Event
        data class Failed(val plan: Plan, val error: Throwable) : Event
    }

    class Session internal constructor(
        val plan: Plan,
        private val handle: FirmwareDownloader.DownloadHandle?,
        private val cancelled: AtomicBoolean
    ) {
        fun cancel() {
            cancelled.set(true)
            handle?.cancel()
        }

        fun isCancelled(): Boolean = cancelled.get() || handle?.isCancelled() == true
        fun await(): FirmwareDownloader.Result? = handle?.await()
    }

    private val watcher = Executors.newCachedThreadPool()
    private val active = ConcurrentHashMap<String, Session>()
    private val closed = AtomicBoolean(false)

    /** Refresh metadata and persist a readable per-device snapshot for diagnostics/offline inspection. */
    fun refreshCatalog(identifier: String): List<FirmwareCatalog.Firmware> {
        check(!closed.get()) { "FirmwareDownloadManager is closed" }
        val entries = catalog.firmwares(identifier)
        val cache = storage.catalogCacheFor(identifier)
        catalog.writeDeviceFirmwareCache(identifier, cache)
        logger("FirmwareDownloadManager: cached ${entries.size} firmware entries for $identifier")
        return entries
    }

    fun latestSigned(identifier: String, refreshCache: Boolean = true): FirmwareCatalog.Firmware? {
        val entries = if (refreshCache) refreshCatalog(identifier) else catalog.firmwares(identifier)
        return entries.firstOrNull { it.signed }
    }

    fun plan(firmware: FirmwareCatalog.Firmware): Plan {
        check(!closed.get()) { "FirmwareDownloadManager is closed" }
        val location = storage.locationFor(firmware)
        return Plan(
            firmware = firmware,
            destination = location.file,
            catalogCache = location.catalogCache,
            complete = storage.isComplete(firmware),
            partialBytes = storage.partialBytes(firmware),
            availableBytes = location.buildDirectory.usableSpace
        )
    }

    fun start(
        firmware: FirmwareCatalog.Firmware,
        connections: Int = 4,
        onEvent: (Event) -> Unit = {}
    ): Session {
        check(!closed.get()) { "FirmwareDownloadManager is closed" }
        val plan = plan(firmware)
        val key = plan.destination.absolutePath
        active[key]?.let { existing ->
            logger("FirmwareDownloadManager: reusing active session for $key")
            return existing
        }

        if (plan.complete) {
            val sha1 = firmware.sha1.orEmpty()
            val result = FirmwareDownloader.Result(
                file = plan.destination,
                bytes = plan.destination.length(),
                sha1 = sha1,
                resumed = false,
                segmented = false
            )
            val session = Session(plan, null, AtomicBoolean(false))
            onEvent(Event.Started(plan))
            onEvent(Event.Completed(plan, result))
            logger("FirmwareDownloadManager: already complete ${plan.destination.absolutePath}")
            return session
        }

        val remaining = if (firmware.fileSize > 0) {
            (firmware.fileSize - plan.partialBytes).coerceAtLeast(0L)
        } else {
            -1L
        }
        if (remaining > 0 && !storage.hasEnoughSpace(firmware.identifier, remaining)) {
            throw IllegalStateException(
                "Not enough storage for firmware: remaining=$remaining available=${plan.availableBytes}"
            )
        }

        val cancelled = AtomicBoolean(false)
        val request = FirmwareDownloader.Request(
            url = firmware.url,
            destination = plan.destination,
            expectedSize = firmware.fileSize,
            expectedSha1 = firmware.sha1,
            connections = connections
        )
        val handle = downloader.start(request) { progress ->
            if (!cancelled.get()) onEvent(Event.Progress(plan, progress))
        }
        val session = Session(plan, handle, cancelled)
        val raced = active.putIfAbsent(key, session)
        if (raced != null) {
            session.cancel()
            return raced
        }

        logger(
            "FirmwareDownloadManager: start ${firmware.identifier} ${firmware.version} " +
                "(${firmware.buildId}) partial=${plan.partialBytes} destination=$key"
        )
        onEvent(Event.Started(plan))

        watcher.execute {
            try {
                val result = handle.await()
                if (cancelled.get() || handle.isCancelled()) {
                    onEvent(Event.Cancelled(plan))
                } else {
                    onEvent(Event.Completed(plan, result))
                }
            } catch (t: Throwable) {
                val cause = if (t is ExecutionException) t.cause ?: t else t
                if (cancelled.get() || handle.isCancelled() || cause is InterruptedException) {
                    onEvent(Event.Cancelled(plan))
                } else {
                    logger("FirmwareDownloadManager: failed ${cause.javaClass.simpleName}: ${cause.message}")
                    onEvent(Event.Failed(plan, cause))
                }
            } finally {
                active.remove(key, session)
            }
        }
        return session
    }

    fun cancel(firmware: FirmwareCatalog.Firmware): Boolean {
        val destination = storage.locationFor(firmware).file.absolutePath
        val session = active[destination] ?: return false
        session.cancel()
        return true
    }

    fun discardPartial(firmware: FirmwareCatalog.Firmware): Int {
        cancel(firmware)
        return storage.removePartial(firmware)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        active.values.forEach(Session::cancel)
        active.clear()
        watcher.shutdownNow()
    }
}
