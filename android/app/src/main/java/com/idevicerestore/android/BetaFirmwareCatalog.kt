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
    private data class ParsedRow(
        val version: String,
        val buildId: String,
        val releaseDateText: String,
        val fileSizeText: String
    )

    fun latestSigned(identifier: String): FirmwareCatalog.Firmware? {
        require(identifier.matches(Regex("[A-Za-z0-9,._-]+"))) { "Invalid device identifier" }
        val encoded = URLEncoder.encode(identifier, StandardCharsets.UTF_8)
        val indexUrl = "$endpoint/$encoded"
        logger("BetaFirmwareCatalog: GET $indexUrl")
        val html = get(indexUrl)
        logger("BetaFirmwareCatalog: received ${html.length} characters; parsing signed beta/RC rows")

        val row = parseLatestSignedRow(html)
        logger(
            "BetaFirmwareCatalog: parsed ${row.version} (${row.buildId}) " +
                "released=${row.releaseDateText} size=${row.fileSizeText}"
        )

        val detailUrl = "$endpoint/download/$encoded/${row.buildId}"
        logger("BetaFirmwareCatalog: GET $detailUrl")
        val detail = get(detailUrl)
        logger("BetaFirmwareCatalog: received ${detail.length} detail characters; resolving Apple CDN URL")

        val appleUrl = Regex("https://updates\\.cdn-apple\\.com/[^\"'<>\\s]+", RegexOption.IGNORE_CASE)
            .find(detail)?.value
            ?: error("Signed beta build ${row.buildId} did not expose an Apple CDN IPSW URL")

        val fileSizeText = Regex(
            "File\\s*size[^0-9]{0,120}([0-9]+(?:\\.[0-9]+)?)\\s*(GB|GiB|MB|MiB)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(detail)?.let { "${it.groupValues[1]} ${it.groupValues[2]}" }
            ?: row.fileSizeText

        // The index row is the signing authority for selection. The detail page wording can change,
        // so do not reject a row merely because a prose phrase is absent from the detail HTML.
        logger("BetaFirmwareCatalog: Apple CDN URL resolved for signed build ${row.buildId}")

        return FirmwareCatalog.Firmware(
            identifier = identifier,
            version = row.version,
            buildId = row.buildId,
            url = appleUrl,
            fileSize = parseSize(fileSizeText),
            sha1 = null,
            releaseDate = parseDate(row.releaseDateText),
            uploadDate = null,
            signed = true
        )
    }

    /**
     * Convert the page to bounded plain text first, then match one compact row.
     * Avoid a page-wide DOT_MATCHES_ALL <tr> regex: on script-heavy HTML a near miss can trigger
     * severe regex backtracking and make the lookup appear to hang immediately after HTTP 200.
     */
    private fun parseLatestSignedRow(html: String): ParsedRow {
        val text = html
            .replace(Regex("(?is)<script\\b[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style\\b[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<[^>]{1,512}>"), " ")
            .replace("&check;", "✓", ignoreCase = true)
            .replace("&#10003;", "✓", ignoreCase = true)
            .replace("&#x2713;", "✓", ignoreCase = true)
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace(Regex("\\s+"), " ")
            .trim()

        logger("BetaFirmwareCatalog: normalized page to ${text.length} characters")

        // Example normalized row:
        // 27.0 beta 8 26A5425a ✓ August 31, 2026 22.74 GB
        val match = Regex(
            "([0-9]+(?:\\.[0-9]+){1,3}\\s+(?:(?:beta)(?:\\s+[0-9]+)?(?:\\s+v\\.?\\s*[0-9]+)?|RC(?:\\s+[0-9]+)?))\\s+" +
                "([A-Za-z0-9]+)\\s+✓\\s+" +
                "([A-Za-z]+\\s+[0-9]{1,2},\\s+[0-9]{4})\\s+" +
                "([0-9]+(?:\\.[0-9]+)?\\s*(?:GB|GiB|MB|MiB))",
            RegexOption.IGNORE_CASE
        ).find(text) ?: error("No signed beta/RC firmware row could be parsed for device")

        return ParsedRow(
            version = match.groupValues[1].trim().replace(Regex("\\s+"), " "),
            buildId = match.groupValues[2].trim(),
            releaseDateText = match.groupValues[3].trim(),
            fileSizeText = match.groupValues[4].trim()
        )
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
