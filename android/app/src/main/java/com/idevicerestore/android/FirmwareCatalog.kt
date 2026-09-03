package com.idevicerestore.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Firmware metadata client inspired by Mist's firmware listing model.
 *
 * The metadata service is IPSW Downloads (api.ipsw.me), which Mist also credits for
 * firmware metadata. Firmware payload URLs returned by the service point at Apple's
 * download infrastructure; callers should still treat all remote metadata as untrusted.
 */
class FirmwareCatalog(
    private val endpoint: String = "https://api.ipsw.me/v4",
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val logger: (String) -> Unit = {}
) {
    data class Device(
        val name: String,
        val identifier: String,
        val boardConfig: String?,
        val platform: String?,
        val cpid: Int?,
        val bdid: Int?
    )

    data class Firmware(
        val identifier: String,
        val version: String,
        val buildId: String,
        val url: String,
        val fileSize: Long,
        val sha1: String?,
        val releaseDate: Instant?,
        val uploadDate: Instant?,
        val signed: Boolean
    ) {
        val fileName: String
            get() = url.substringAfterLast('/').substringBefore('?').ifBlank {
                "$identifier-$version-$buildId.ipsw"
            }
    }

    @Volatile
    private var cachedDevices: List<Device>? = null

    fun listDevices(forceRefresh: Boolean = false): List<Device> {
        if (!forceRefresh) cachedDevices?.let { return it }
        logger("FirmwareCatalog: GET $endpoint/devices")
        val array = JSONArray(get("$endpoint/devices"))
        val devices = buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(
                    Device(
                        name = item.optString("name"),
                        identifier = item.optString("identifier"),
                        boardConfig = item.optNullableString("boardconfig"),
                        platform = item.optNullableString("platform"),
                        cpid = item.optNullableInt("cpid"),
                        bdid = item.optNullableInt("bdid")
                    )
                )
            }
        }.filter { it.identifier.isNotBlank() }
        cachedDevices = devices
        return devices
    }

    /** Match Apple's Recovery/DFU CPID + BDID pair to the catalog's concrete product. */
    fun findDeviceByBootIds(cpid: Int, bdid: Int): Device? {
        logger("FirmwareCatalog: identify CPID=0x%04X (%d) BDID=0x%02X (%d)".format(cpid, cpid, bdid, bdid))
        val candidates = listDevices().filter { it.cpid == cpid && it.bdid == bdid }
        return when {
            candidates.isEmpty() -> null
            candidates.size == 1 -> candidates.first()
            else -> {
                logger("FirmwareCatalog: ${candidates.size} device records share CPID/BDID; using ${candidates.first().identifier}")
                candidates.first()
            }
        }
    }

    fun firmwares(identifier: String, signedOnly: Boolean = false): List<Firmware> {
        require(identifier.matches(Regex("[A-Za-z0-9,._-]+"))) { "Invalid device identifier" }
        val target = "$endpoint/device/$identifier?type=ipsw"
        logger("FirmwareCatalog: GET $target")
        val root = JSONObject(get(target))
        val array = root.optJSONArray("firmwares") ?: JSONArray()
        val result = buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val firmware = parseFirmware(identifier, item)
                if (!signedOnly || firmware.signed) add(firmware)
            }
        }
        return result.sortedWith(
            compareByDescending<Firmware> { it.releaseDate ?: Instant.EPOCH }
                .thenByDescending { it.version }
        )
    }

    fun latestSigned(identifier: String): Firmware? = firmwares(identifier, signedOnly = true).firstOrNull()

    /** Simple JSON cache/export for diagnostics and future offline browsing. */
    fun writeDeviceFirmwareCache(identifier: String, destination: File) {
        val entries = firmwares(identifier)
        val root = JSONObject().apply {
            put("identifier", identifier)
            put("generatedAt", Instant.now().toString())
            put("firmwares", JSONArray().apply {
                entries.forEach { f ->
                    put(JSONObject().apply {
                        put("version", f.version)
                        put("buildId", f.buildId)
                        put("url", f.url)
                        put("fileSize", f.fileSize)
                        put("sha1", f.sha1 ?: JSONObject.NULL)
                        put("releaseDate", f.releaseDate?.toString() ?: JSONObject.NULL)
                        put("uploadDate", f.uploadDate?.toString() ?: JSONObject.NULL)
                        put("signed", f.signed)
                    })
                }
            })
        }
        destination.parentFile?.mkdirs()
        destination.writeText(root.toString(2))
        logger("FirmwareCatalog: wrote ${entries.size} entries to ${destination.absolutePath}")
    }

    private fun parseFirmware(identifier: String, item: JSONObject): Firmware {
        val url = item.optString("url")
        require(url.startsWith("https://")) { "Refusing non-HTTPS firmware URL" }
        return Firmware(
            identifier = identifier,
            version = item.optString("version"),
            buildId = item.optString("buildid"),
            url = url,
            fileSize = item.optLong("filesize", -1L),
            sha1 = item.optNullableString("sha1sum")?.lowercase(),
            releaseDate = item.optInstant("releasedate"),
            uploadDate = item.optInstant("uploaddate"),
            signed = item.optBoolean("signed", false)
        )
    }

    private fun get(target: String): String {
        val connection = (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "iDeviceRestore-Android/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = connection.responseCode
            logger("FirmwareCatalog: HTTP $code ${connection.responseMessage.orEmpty()}")
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            }.orEmpty()
            if (code !in 200..299) error("Firmware catalog HTTP $code: ${body.take(512)}")
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key)

    private fun JSONObject.optInstant(key: String): Instant? =
        optNullableString(key)?.let { runCatching { Instant.parse(it) }.getOrNull() }
}
