package com.idevicerestore.android

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Passive GitHub release check used by the launch-update preference. */
class AppUpdateChecker(
    private val logger: (String) -> Unit = {}
) {
    data class Update(
        val tagName: String,
        val releaseUrl: String,
        val currentVersion: String
    )

    private val worker = Executors.newSingleThreadExecutor()

    fun checkAsync(currentVersion: String, callback: (Update?) -> Unit) {
        worker.execute {
            val update = runCatching { check(currentVersion) }
                .onFailure { logger("App update check failed: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrNull()
            callback(update)
            worker.shutdown()
        }
    }

    private fun check(currentVersion: String): Update? {
        logger("App update check: querying GitHub releases for latest user-facing semantic version")
        val connection = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "iDeviceRestore-Android/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                logger("App update check: GitHub returned HTTP $code")
                return null
            }
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val releases = JSONArray(json)
            var latestTag: String? = null
            var latestUrl: String? = null
            for (index in 0 until releases.length()) {
                val release = releases.optJSONObject(index) ?: continue
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
                val tag = release.optString("tag_name").trim()
                if (!SEMANTIC_RELEASE_REGEX.matches(tag)) continue
                val releaseUrl = release.optString("html_url").trim()
                if (releaseUrl.isBlank()) continue
                if (latestTag == null || compareVersions(tag, latestTag) > 0) {
                    latestTag = tag
                    latestUrl = releaseUrl
                }
            }

            if (latestTag == null || latestUrl == null) {
                logger("App update check: no x.y.z user-facing release found; historical pseudo releases ignored")
                return null
            }

            val newer = compareVersions(latestTag, currentVersion) > 0
            logger("App update check: latest=$latestTag current=$currentVersion newer=$newer")
            return if (newer) Update(latestTag, latestUrl, currentVersion) else null
        } finally {
            connection.disconnect()
        }
    }

    internal fun compareVersions(left: String, right: String): Int {
        val a = parseVersion(left)
        val b = parseVersion(right)
        val width = maxOf(a.numbers.size, b.numbers.size)
        for (index in 0 until width) {
            val av = a.numbers.getOrElse(index) { 0 }
            val bv = b.numbers.getOrElse(index) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        if (a.preRelease == b.preRelease) return 0
        if (a.preRelease == null) return 1
        if (b.preRelease == null) return -1
        return a.preRelease.compareTo(b.preRelease, ignoreCase = true)
    }

    private data class ParsedVersion(val numbers: List<Int>, val preRelease: String?)

    private fun parseVersion(raw: String): ParsedVersion {
        val cleaned = raw.trim().removePrefix("v").removePrefix("V")
        val main = cleaned.substringBefore('-')
        val suffix = cleaned.substringAfter('-', "").ifBlank { null }
        val numbers = main.split('.').map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
        return ParsedVersion(numbers, suffix)
    }

    companion object {
        private val SEMANTIC_RELEASE_REGEX = Regex("^[vV]?\\d+\\.\\d+\\.\\d+$")
        private const val RELEASES_API =
            "https://api.github.com/repos/dracinn/iDeviceRestore/releases?per_page=30"
    }
}
