package com.idevicerestore.android

import java.util.zip.ZipFile

/**
 * Process-local cache for the last BuildManifest preflight result.
 *
 * UniversalMac BuildManifest.plist files can be tens of megabytes. Several automatic preparation
 * stages need the same selected identity, so reuse the result while the exact IPSW file and
 * hardware-selection inputs are unchanged.
 */
object IpswPreflightCache {
    private data class Key(
        val path: String,
        val length: Long,
        val lastModified: Long,
        val manifestCrc: Long,
        val manifestSize: Long,
        val identifier: String,
        val boardConfig: String?,
        val chipId: Int?,
        val boardId: Int?
    )

    private data class Entry(
        val key: Key,
        val result: IpswPreflight.Result
    )

    @Volatile
    private var current: Entry? = null

    fun inspect(
        request: IpswPreflight.Request,
        logger: (String) -> Unit = {}
    ): IpswPreflight.Result = synchronized(this) {
        val manifestIdentity = ZipFile(request.ipsw).use { zip ->
            val entry = zip.getEntry("BuildManifest.plist")
                ?: error("BuildManifest.plist is missing from IPSW")
            entry.crc to entry.size
        }
        val key = Key(
            path = request.ipsw.absoluteFile.path,
            length = request.ipsw.length(),
            lastModified = request.ipsw.lastModified(),
            manifestCrc = manifestIdentity.first,
            manifestSize = manifestIdentity.second,
            identifier = request.identifier.lowercase(),
            boardConfig = request.boardConfig?.lowercase(),
            chipId = request.chipId,
            boardId = request.boardId
        )
        val cached = current
        if (cached != null && cached.key == key) {
            logger(
                "IPSW preflight cache: reusing build=${cached.result.productBuildVersion ?: "unknown"} " +
                    "identity=${cached.result.identityIndex} manifestCrc=0x${key.manifestCrc.toString(16)}"
            )
            return@synchronized cached.result
        }

        val result = IpswPreflight(logger = logger).inspect(request)
        current = Entry(key, result)
        result
    }

    fun clear() {
        current = null
    }
}
