package com.idevicerestore.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

class FirmwareMetadataCache(
    private val logger: (String) -> Unit = {}
) {
    data class Entry(
        val channel: FirmwareSelectionCandidate.Channel,
        val version: String,
        val buildId: String,
        val releaseDate: Instant?,
        val fileSize: Long,
        val url: String?,
        val sha1: String?,
        val currentlySigned: Boolean,
        val verifiedAt: Instant?
    ) {
        val key: String get() = "${channel.name}:$buildId"
    }

    data class MergeResult(
        val entries: List<Entry>,
        val newBuilds: List<Entry>
    )

    fun load(file: File): List<Entry> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray("firmwares") ?: JSONArray()
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val channel = runCatching {
                        FirmwareSelectionCandidate.Channel.valueOf(item.optString("channel"))
                    }.getOrNull() ?: continue
                    val buildId = item.optString("buildId")
                    val version = item.optString("version")
                    if (buildId.isBlank() || version.isBlank()) continue
                    add(
                        Entry(
                            channel = channel,
                            version = version,
                            buildId = buildId,
                            releaseDate = item.optInstant("releaseDate"),
                            fileSize = item.optLong("fileSize", -1L),
                            url = item.optNullableString("url"),
                            sha1 = item.optNullableString("sha1"),
                            currentlySigned = item.optBoolean("currentlySigned", false),
                            verifiedAt = item.optInstant("verifiedAt")
                        )
                    )
                }
            }
        }.onFailure {
            logger("FirmwareMetadataCache: failed to read ${file.absolutePath}: ${it.message}")
        }.getOrDefault(emptyList())
    }

    fun mergeCurrent(
        file: File,
        current: List<Entry>,
        refreshedChannels: Set<FirmwareSelectionCandidate.Channel>
    ): MergeResult {
        val old = load(file)
        val oldKeys = old.map { it.key }.toSet()
        val merged = LinkedHashMap<String, Entry>()

        old.forEach { entry ->
            merged[entry.key] = if (entry.channel in refreshedChannels) {
                entry.copy(currentlySigned = false)
            } else entry
        }
        current.forEach { entry ->
            val previous = merged[entry.key]
            merged[entry.key] = entry.copy(
                verifiedAt = previous?.verifiedAt,
                url = entry.url ?: previous?.url,
                sha1 = entry.sha1 ?: previous?.sha1
            )
        }

        val entries = merged.values.toList()
        write(file, entries)
        val newBuilds = current.filter { it.key !in oldKeys }
        logger(
            "FirmwareMetadataCache: ${entries.size} cached build(s); " +
                "${newBuilds.size} newly discovered"
        )
        return MergeResult(entries, newBuilds)
    }

    fun recordVerification(
        file: File,
        firmware: FirmwareCatalog.Firmware,
        channel: FirmwareSelectionCandidate.Channel
    ) {
        val entries = load(file).toMutableList()
        val key = "${channel.name}:${firmware.buildId}"
        val updated = Entry(
            channel = channel,
            version = firmware.version,
            buildId = firmware.buildId,
            releaseDate = firmware.releaseDate,
            fileSize = firmware.fileSize,
            url = firmware.url,
            sha1 = firmware.sha1,
            currentlySigned = true,
            verifiedAt = Instant.now()
        )
        val index = entries.indexOfFirst { it.key == key }
        if (index >= 0) entries[index] = updated else entries += updated
        write(file, entries)
        logger("FirmwareMetadataCache: recorded fresh verification for ${firmware.buildId}")
    }

    private fun write(file: File, entries: List<Entry>) {
        file.parentFile?.mkdirs()
        val root = JSONObject().apply {
            put("schemaVersion", 1)
            put("updatedAt", Instant.now().toString())
            put("firmwares", JSONArray().apply {
                entries.forEach { entry ->
                    put(JSONObject().apply {
                        put("channel", entry.channel.name)
                        put("version", entry.version)
                        put("buildId", entry.buildId)
                        put("releaseDate", entry.releaseDate?.toString() ?: JSONObject.NULL)
                        put("fileSize", entry.fileSize)
                        put("url", entry.url ?: JSONObject.NULL)
                        put("sha1", entry.sha1 ?: JSONObject.NULL)
                        put("currentlySigned", entry.currentlySigned)
                        put("verifiedAt", entry.verifiedAt?.toString() ?: JSONObject.NULL)
                    })
                }
            })
        }
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(root.toString(2))
        check(temp.renameTo(file) || run {
            file.writeText(temp.readText())
            temp.delete()
            true
        }) { "Could not replace firmware metadata cache" }
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.optInstant(key: String): Instant? =
        optNullableString(key)?.let { runCatching { Instant.parse(it) }.getOrNull() }
}
