package com.idevicerestore.android

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Derives the expected Recovery Stage-1 build from the already validated personalized iBSS.
 *
 * The diagnostic must not guess an iBoot build from the macOS build number. Instead it reads the
 * firmware-provided mBoot build identifier carried by the prepared iBSS and requires exactly one
 * distinct candidate before any state-changing upload is enabled.
 */
object Stage1BuildMetadata {
    private data class CacheKey(
        val path: String,
        val length: Long,
        val modified: Long
    )

    private val cache = ConcurrentHashMap<CacheKey, String>()
    private val buildPattern = Regex("mBoot-[0-9]+(?:\\.[0-9]+){1,5}")

    fun expectedBuild(personalizedIbss: File): String? {
        if (!personalizedIbss.isFile || personalizedIbss.length() <= 0L) return null
        val key = CacheKey(
            path = personalizedIbss.absolutePath,
            length = personalizedIbss.length(),
            modified = personalizedIbss.lastModified()
        )
        val cached = cache[key]
        if (cached != null) return cached.takeUnless { it == NO_BUILD }

        val derived = extractUniqueBuild(personalizedIbss)
        cache[key] = derived ?: NO_BUILD
        return derived
    }

    private fun extractUniqueBuild(file: File): String? {
        val data = file.readBytes()
        val text = data.toString(Charsets.ISO_8859_1)
        val builds = buildPattern.findAll(text).map { it.value }.distinct().toList()
        return builds.singleOrNull()
    }

    private const val NO_BUILD = "<none>"
}
