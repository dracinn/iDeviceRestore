package com.idevicerestore.android

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Lightweight client for IPSW.dev's device beta index.
 *
 * This source is used only when the user explicitly enables beta firmware lookup.
 * The final payload URL must still resolve directly to Apple's HTTPS CDN.
 */
class BetaFirmwareCatalog(
    private val endpoint: String = "https://www.ipsw.dev",
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val logger: (String) -> Unit = {}
) {
    fun latestSigned(identifier: String): FirmwareCatalog.Firmware? {
        require(identifier.matches(Regex("[A-Za-z0-9,._-]+"))) { "Invalid device identifier" }
        val encoded = URLEncoder.encode(identifier, StandardCharsets.UTF_8)
        val indexUrl = "$endpoint/$encoded"
        logger("BetaFirmwareCatalog: GET $indexUrl")
        val html = get(indexUrl)

        val row = Regex(
            "(?is)<tr[^>]*>\\s*<td[^>]*>.*?([0-9]+(?:\\.[0-9]+){1,3}(?:\\s+(?:beta|RC)[^<]*)).*?</td>.*?<code[^>]*>([A-Za-z0-9]+)</code>.*?<td[^>]*>\\s*(?:✓|&#10003;|&check;).*?</td>.*?<td[^>]*>([^<]+)</td>.*?<td[^>]*>([^<]+)</td>.*?</tr>"
        ).find(html) ?: findFromText(html)

        val version = row.groupValues[1].trim().replace(Regex("\\s+"), " ")
        val buildId = row.groupValues[2].trim()
        val releaseDateText = row.groupValues[3].trim()

        val detailUrl = "$endpoint/download/$encoded/$buildId"
        logger("BetaFirmwareCatalog: GET $detailUrl")
        val detail = get(detailUrl)
        val appleUrl = Regex("https://updates\\.cdn-apple\\.com/[^\"'<>\\s]+", RegexOption.IGNORE_CASE)
            .find(detail)?.value
            ?: error("Signed beta build $buildId did not expose an Apple CDN IPSW URL")
        val fileSizeText = Regex("(?is)File size.*?([0-9]+(?:\\.[0-9]+)?)\\s*(GB|GiB|MB|MiB)")
            .find(detail)?.let { "${it.groupValues[1]} ${it.groupValues[2]}" }
            ?: row.groupValues.getOrNull(4).orEmpty()

        val signed = detail.contains("This firmware is signed", ignoreCase = true)
        if (!signed) return null

        return FirmwareCatalog.Firmware(
            identifier = identifier,
            version = version,
            buildId = buildId,
            url = appleUrl,
            fileSize = parseSize(fileSizeText),
            sha1 = null,
            releaseDate = parseDate(releaseDateText),
            uploadDate = null,
            signed = true
        )
    }

    private fun findFromText(html: String): MatchResult {
        val text = html
            .replace(Regex("(?is)<script.*?</script>"), " ")
            .replace(Regex("(?is)<style.*?</style>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&check;", "✓")
            .replace("&#10003;", "✓")
            .replace(Regex("\\s+"), " ")
        return Regex(
            "([0-9]+(?:\\.[0-9]+){1,3}\\s+(?:beta|RC)(?:\\s+[A-Za-z0-9.]+)?)\\s+([A-Za-z0-9]+)\\s+✓\\s+([A-Za-z]+\\s+[0-9]{1,2},\\s+[0-9]{4})\\s+([0-9.]+\\s+(?:GB|GiB|MB|MiB))",
            RegexOption.IGNORE_CASE
        ).find(text) ?: error("No signed beta/RC firmware found for device")
    }

    private fun parseDate(value: String): Instant? = runCatching {
        LocalDate.parse(value, DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.US))
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
    }.getOrNull()

    private fun parseSize(value: String): Long {
        val match = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*(GB|GiB|MB|MiB)", RegexOption.IGNORE_CASE).find(value)
            ?: return -1L
        val amount = match.groupValues[1].toDoubleOrNull() ?: return -1L
        val multiplier = when (match.groupValues[2].lowercase(Locale.US)) {
            "gib" -> 1024.0 * 1024.0 * 1024.0
            "gb" -> 1_000_000_000.0
            "mib" -> 1024.0 * 1024.0
            else -> 1_000_000.0
        }
        return (amount * multiplier).toLong()
    }

    private fun get(target: String): String {
        val connection = (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/html")
            setRequestProperty("User-Agent", "iDeviceRestore-Android/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = connection.responseCode
            logger("BetaFirmwareCatalog: HTTP $code ${connection.responseMessage.orEmpty()}")
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Beta firmware catalog HTTP $code: ${body.take(512)}")
            return body
        } finally {
            connection.disconnect()
        }
    }
}
