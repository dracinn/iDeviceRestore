package com.idevicerestore.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists only devices that this Android app has positively identified from a real USB
 * connection. The firmware browser uses this as its allow-list so unrelated catalog
 * devices are never shown by default.
 */
class ConnectedDeviceHistory(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Entry(
        val name: String,
        val identifier: String,
        val boardConfig: String?,
        val platform: String?,
        val cpid: Int?,
        val bdid: Int?,
        val lastSeenAt: Long
    ) {
        fun asCatalogDevice(): FirmwareCatalog.Device = FirmwareCatalog.Device(
            name = name,
            identifier = identifier,
            boardConfig = boardConfig,
            platform = platform,
            cpid = cpid,
            bdid = bdid
        )
    }

    fun record(device: FirmwareCatalog.Device, seenAt: Long = System.currentTimeMillis()) {
        val current = entries().associateBy { it.identifier }.toMutableMap()
        current[device.identifier] = Entry(
            name = device.name,
            identifier = device.identifier,
            boardConfig = device.boardConfig,
            platform = device.platform,
            cpid = device.cpid,
            bdid = device.bdid,
            lastSeenAt = seenAt
        )
        save(current.values.sortedByDescending { it.lastSeenAt })
    }

    fun entries(): List<Entry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val identifier = item.optString("identifier").trim()
                    if (identifier.isEmpty()) continue
                    add(
                        Entry(
                            name = item.optString("name").ifBlank { identifier },
                            identifier = identifier,
                            boardConfig = item.optNullableString("boardConfig"),
                            platform = item.optNullableString("platform"),
                            cpid = item.optNullableInt("cpid"),
                            bdid = item.optNullableInt("bdid"),
                            lastSeenAt = item.optLong("lastSeenAt", 0L)
                        )
                    )
                }
            }.distinctBy { it.identifier }.sortedByDescending { it.lastSeenAt }
        }.getOrDefault(emptyList())
    }

    private fun save(entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("name", entry.name)
                    put("identifier", entry.identifier)
                    putNullable("boardConfig", entry.boardConfig)
                    putNullable("platform", entry.platform)
                    putNullable("cpid", entry.cpid)
                    putNullable("bdid", entry.bdid)
                    put("lastSeenAt", entry.lastSeenAt)
                }
            )
        }
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optNullableInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return optInt(key)
    }

    companion object {
        private const val PREFS_NAME = "connected_device_history"
        private const val KEY_ENTRIES = "entries"
    }
}
