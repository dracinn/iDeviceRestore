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
 * Signing state is read from the device index. The final payload URL must resolve directly to
 * Apple's HTTPS firmware CDN; IPSW.dev is never used as the binary download host.
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
        val fileSizeText: String,
        val signed: Boolean
    )

    fun latestSigned(identifier: String): FirmwareCatalog.Firmware? {
        require(identifier.matches(Regex("[A-Za-z0-9,._-]+"))) { "Invalid device identifier" }
        val encoded = URLEncoder.encode(identifier, StandardCharsets.UTF_8)
        val indexUrl = "$endpoint/$encoded"
        logger("BetaFirmwareCatalog: GET $indexUrl")
        val html = get(indexUrl)
        logger("BetaFirmwareCatalog: received ${html.length} characters; parsing beta/RC rows")

        val rows = parseRows(html)
        val signedRows = rows.filter { it.signed }
        logger("BetaFirmwareCatalog: parsed ${rows.size} candidate row(s); ${signedRows.size} reported signed")
        if (rows.isEmpty()) error("No beta/RC firmware rows could be parsed for device")

        for (row in signedRows.take(MAX_DETAIL_PROBES)) {
            logger(
                "BetaFirmwareCatalog: signed candidate ${row.version} (${row.buildId}) " +
                    "released=${row.releaseDateText} size=${row.fileSizeText}"
            )

            val detailUrl = "$endpoint/download/$encoded/${row.buildId}"
            logger("BetaFirmwareCatalog: GET $detailUrl")
            val detail = get(detailUrl)
            logger("BetaFirmwareCatalog: received ${detail.length} detail characters; resolving Apple CDN URL")

            val appleUrl = Regex("https://updates\\.cdn-apple\\.com/[^\"'<>\\s]+", RegexOption.IGNORE_CASE)
                .find(detail)?.value
            if (appleUrl == null) {
                logger("BetaFirmwareCatalog: signed build ${row.buildId} has no Apple CDN URL; checking next candidate")
                continue
            }

            val parsedUrl = URL(appleUrl)
            if (!parsedUrl.protocol.equals("https", ignoreCase = true) ||
                !parsedUrl.host.equals(APPLE_CDN_HOST, ignoreCase = true)
            ) {
                logger("BetaFirmwareCatalog: rejected non-Apple firmware URL for ${row.buildId}")
                continue
            }

            val fileSizeText = Regex(
                "File\\s*size[^0-9]{0,120}([0-9]+(?:\\.[0-9]+)?)\\s*(GB|GiB|MB|MiB)",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).find(detail)?.let { "${it.groupValues[1]} ${it.groupValues[2]}" }
                ?: row.fileSizeText

            logger("BetaFirmwareCatalog: selected ${row.buildId}; payload host=$APPLE_CDN_HOST")
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

        logger("BetaFirmwareCatalog: no signed beta/RC with an Apple CDN URL found in first $MAX_DETAIL_PROBES signed candidates")
        return null
    }

    /**
     * Parse version/build/date/size from bounded plain-text windows, then recover signing state from
     * the raw table row containing each build ID. This avoids both page-wide regex backtracking and
     * brittle dependence on a particular detail-page sentence.
     */
    private fun parseRows(html: String): List<ParsedRow> {
        val text = html
            .replace(Regex("(?is)<script\\b[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style\\b[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<[^>]{1,512}>"), " ")
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&#44;", ",", ignoreCase = true)
            .replace("&check;", " ✓ ", ignoreCase = true)
            .replace("&#10003;", " ✓ ", ignoreCase = true)
            .replace("&#x2713;", " ✓ ", ignoreCase = true)
            .replace("&#10007;", " ✗ ", ignoreCase = true)
            .replace("&#x2717;", " ✗ ", ignoreCase = true)
            .replace(Regex("\\s+"), " ")
            .trim()

        logger("BetaFirmwareCatalog: normalized page to ${text.length} characters")

        val versionRegex = Regex(
            "[0-9]+(?:\\.[0-9]+){1,3}\\s+(?:(?:beta)(?:\\s+[0-9]+)?(?:\\s+v\\.?\\s*[0-9]+)?|RC(?:\\s+[0-9]+)?)",
            RegexOption.IGNORE_CASE
        )
        val buildRegex = Regex("\\b[0-9]{2}[A-Za-z][A-Za-z0-9]{3,12}\\b")
        val dateRegex = Regex("[A-Za-z]+\\s+[0-9]{1,2},\\s+[0-9]{4}")
        val sizeRegex = Regex("(?:[0-9]+(?:\\.[0-9]+)?\\s*(?:GB|GiB|MB|MiB))|N/A", RegexOption.IGNORE_CASE)

        val rows = buildList {
            versionRegex.findAll(text).forEach { versionMatch ->
                val windowStart = versionMatch.range.first
                val windowEnd = minOf(text.length, versionMatch.range.last + 1 + ROW_WINDOW_CHARS)
                val window = text.substring(windowStart, windowEnd)
                val relativeVersionEnd = versionMatch.value.length

                val build = buildRegex.find(window, relativeVersionEnd) ?: return@forEach
                val date = dateRegex.find(window, build.range.last + 1) ?: return@forEach
                val size = sizeRegex.find(window, date.range.last + 1) ?: return@forEach
                val signing = signingStateForBuild(html, build.value)

                add(
                    ParsedRow(
                        version = versionMatch.value.trim().replace(Regex("\\s+"), " "),
                        buildId = build.value,
                        releaseDateText = date.value,
                        fileSizeText = size.value,
                        signed = signing
                    )
                )
            }
        }.distinctBy { it.buildId }

        if (rows.isNotEmpty()) {
            rows.take(3).forEach { row ->
                logger("BetaFirmwareCatalog: index ${row.buildId} signing=${if (row.signed) "signed" else "unsigned/unknown"}")
            }
        } else {
            val firstBeta = Regex("beta|RC", RegexOption.IGNORE_CASE).find(text)
            if (firstBeta != null) {
                val start = maxOf(0, firstBeta.range.first - 100)
                val end = minOf(text.length, firstBeta.range.first + 500)
                logger("BetaFirmwareCatalog: parse sample=${text.substring(start, end)}")
            } else {
                logger("BetaFirmwareCatalog: normalized page contains no beta/RC token")
            }
        }

        return rows
    }

    private fun signingStateForBuild(html: String, buildId: String): Boolean {
        val buildIndex = html.indexOf(buildId, ignoreCase = true)
        if (buildIndex < 0) return false

        val rowStart = html.lastIndexOf("<tr", startIndex = buildIndex, ignoreCase = true)
        val rowEndTag = html.indexOf("</tr>", startIndex = buildIndex, ignoreCase = true)
        if (rowStart < 0 || rowEndTag < 0 || rowEndTag - rowStart > MAX_RAW_ROW_CHARS) return false

        val row = html.substring(rowStart, rowEndTag + 5)
        val normalized = row.lowercase(Locale.US)

        val explicitUnsigned = listOf(
            "unsigned", "&#10007;", "&#x2717;", "✗", "xmark", "times-circle", "circle-xmark", "text-danger"
        ).any { normalized.contains(it) }
        if (explicitUnsigned) return false

        return listOf(
            "signed", "&check;", "&#10003;", "&#x2713;", "✓", "check-circle", "circle-check", "text-success"
        ).any { normalized.contains(it) }
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

    companion object {
        private const val MAX_DETAIL_PROBES = 8
        private const val ROW_WINDOW_CHARS = 320
        private const val MAX_RAW_ROW_CHARS = 16_384
        private const val APPLE_CDN_HOST = "updates.cdn-apple.com"
    }
}
