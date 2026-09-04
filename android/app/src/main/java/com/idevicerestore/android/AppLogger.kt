package com.idevicerestore.android

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Thread-safe, bounded diagnostic logger for Android restore workflows.
 *
 * Entries remain structured until rendering so the UI/share layer can filter by
 * category, level, and operation/session without reparsing human-readable text.
 */
class AppLogger(
    private val capacity: Int = 4_000,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    init {
        require(capacity >= 100) { "capacity must be at least 100 entries" }
    }

    enum class Level { DEBUG, INFO, WARN, ERROR }
    enum class Category {
        APP, USB, DFU, RECOVERY, CONSOLE, DEVICE, FIRMWARE, DOWNLOAD, IPSW, STORAGE, UI
    }

    data class Entry(
        val timestampMs: Long,
        val category: Category,
        val level: Level,
        val message: String,
        val sessionId: String?,
        val operationId: String?,
        val repeatCount: Int = 1
    )

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private var usbSessionCounter = 0
    private var firmwareOperationCounter = 0

    fun nextUsbSessionId(): String = synchronized(lock) {
        usbSessionCounter += 1
        "USB#%02d".format(Locale.US, usbSessionCounter)
    }

    fun nextFirmwareOperationId(): String = synchronized(lock) {
        firmwareOperationCounter += 1
        "FW#%02d".format(Locale.US, firmwareOperationCounter)
    }

    fun log(
        category: Category,
        message: String,
        level: Level = Level.INFO,
        sessionId: String? = null,
        operationId: String? = null,
        deduplicate: Boolean = true
    ): Entry {
        val clean = redact(message.trimEnd())
        val timestamp = now()
        synchronized(lock) {
            val previous = entries.lastOrNull()
            if (
                deduplicate && previous != null &&
                previous.category == category && previous.level == level &&
                previous.message == clean && previous.sessionId == sessionId &&
                previous.operationId == operationId
            ) {
                val collapsed = previous.copy(timestampMs = timestamp, repeatCount = previous.repeatCount + 1)
                entries.removeLast()
                entries.addLast(collapsed)
                return collapsed
            }

            val entry = Entry(timestamp, category, level, clean, sessionId, operationId)
            entries.addLast(entry)
            while (entries.size > capacity) entries.removeFirst()
            return entry
        }
    }

    fun snapshot(
        category: Category? = null,
        minimumLevel: Level = Level.DEBUG,
        sessionId: String? = null,
        operationId: String? = null
    ): List<Entry> = synchronized(lock) {
        entries.filter { entry ->
            (category == null || entry.category == category) &&
                entry.level.ordinal >= minimumLevel.ordinal &&
                (sessionId == null || entry.sessionId == sessionId) &&
                (operationId == null || entry.operationId == operationId)
        }
    }

    fun render(entries: List<Entry> = snapshot()): String = buildString {
        entries.forEach { entry -> appendLine(format(entry)) }
    }

    fun format(entry: Entry): String {
        val time = TIME_FORMAT.get().format(Date(entry.timestampMs))
        val scope = listOfNotNull(entry.sessionId, entry.operationId).joinToString("/")
        val scopeText = if (scope.isEmpty()) "" else " [$scope]"
        val repeat = if (entry.repeatCount > 1) " ×${entry.repeatCount}" else ""
        return "%s  %-8s %-5s%s  %s%s".format(
            Locale.US,
            time,
            entry.category.name,
            entry.level.name,
            scopeText,
            entry.message,
            repeat
        )
    }

    fun summary(): Summary {
        val copy = snapshot()
        return Summary(
            retainedEntries = copy.size,
            totalEvents = copy.sumOf { it.repeatCount },
            warnings = copy.filter { it.level == Level.WARN }.sumOf { it.repeatCount },
            errors = copy.filter { it.level == Level.ERROR }.sumOf { it.repeatCount },
            retainedCapacity = capacity
        )
    }

    data class Summary(
        val retainedEntries: Int,
        val totalEvents: Int,
        val warnings: Int,
        val errors: Int,
        val retainedCapacity: Int
    )

    companion object {
        private val TIME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        }

        fun redact(text: String): String = text
            .replace(Regex("(?i)(ECID[:=])(?:0x)?[0-9a-f]+"), "$1[REDACTED]")
            .replace(Regex("(?i)(SRNM:)\\[[^]]*]"), "$1[REDACTED]")
    }
}
