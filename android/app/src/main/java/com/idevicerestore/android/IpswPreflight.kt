package com.idevicerestore.android

import android.util.Xml
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser

/**
 * Read-only inspection of a local IPSW. This never transmits anything to the connected device.
 *
 * BuildManifest.plist can exceed tens of megabytes on UniversalMac restore images. The parser is
 * deliberately streaming: it never materializes the plist or DOM in memory and retains only the
 * small subset of each restore identity needed for hardware matching and component resolution.
 */
class IpswPreflight(
    private val logger: (String) -> Unit = {}
) {
    data class Request(
        val ipsw: File,
        val identifier: String,
        val boardConfig: String?,
        val chipId: Int?,
        val boardId: Int?
    )

    data class Result(
        val productVersion: String?,
        val productBuildVersion: String?,
        val identityIndex: Int,
        val identityVariant: String?,
        val boardConfig: String?,
        val chipId: Long?,
        val boardId: Long?,
        val componentPaths: Map<String, String>
    )

    private data class IdentityCandidate(
        val index: Int,
        var boardConfig: String? = null,
        var chipId: Long? = null,
        var boardId: Long? = null,
        var variant: String? = null,
        var productType: String? = null,
        val componentPaths: LinkedHashMap<String, String> = linkedMapOf()
    )

    fun inspect(request: Request): Result {
        require(request.ipsw.isFile) { "IPSW not found: ${request.ipsw.absolutePath}" }
        logger("IPSW preflight: opening ${request.ipsw.absolutePath}")
        ZipFile(request.ipsw).use { zip ->
            val entry = zip.getEntry("BuildManifest.plist")
                ?: error("BuildManifest.plist is missing from IPSW")
            logger("IPSW preflight: BuildManifest.plist compressed=${entry.compressedSize} bytes size=${entry.size} bytes")

            zip.getInputStream(entry).buffered().use { input ->
                input.mark(8)
                val header = ByteArray(6)
                val read = input.read(header)
                input.reset()
                if (read == 6 && header.toString(Charsets.US_ASCII) == "bplist") {
                    error("Binary BuildManifest.plist is not supported yet; no restore command was sent")
                }

                logger("IPSW preflight: native Android XmlPullParser active")
                val parser = Xml.newPullParser().apply {
                    setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                    setInput(input, Charsets.UTF_8.name())
                }
                return parseStreaming(parser, request)
            }
        }
    }

    private fun parseStreaming(parser: XmlPullParser, request: Request): Result {
        val handler = ManifestHandler(request)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "dict" -> handler.startDict(parser.depth)
                    "array" -> handler.startArray(parser.depth)
                    "key" -> {
                        val depth = parser.depth
                        handler.key(depth - 1, parser.nextText().trim())
                    }
                    "string", "integer" -> {
                        val tag = parser.name
                        val depth = parser.depth
                        handler.scalar(tag, depth - 1, parser.nextText().trim())
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "dict" -> handler.endDict(parser.depth)
                    "array" -> handler.endArray(parser.depth)
                }
            }
            event = parser.next()
        }
        return handler.result()
    }

    private inner class ManifestHandler(
        private val request: Request
    ) {
        private val pendingKeys = mutableMapOf<Int, String>()
        private var rootDictDepth: Int? = null
        private var buildIdentitiesArrayDepth: Int? = null
        private var identityDepth: Int? = null
        private var infoDepth: Int? = null
        private var manifestDepth: Int? = null
        private val componentByDepth = mutableMapOf<Int, String>()
        private val componentInfoByDepth = mutableMapOf<Int, String>()

        private var productVersion: String? = null
        private var productBuildVersion: String? = null
        private var identityCount = 0
        private var current: IdentityCandidate? = null
        private var best: Pair<Int, IdentityCandidate>? = null

        fun key(ownerDepth: Int, value: String) {
            pendingKeys[ownerDepth] = value
        }

        fun startDict(depth: Int) {
            val ownerDepth = depth - 1
            val openingKey = pendingKeys.remove(ownerDepth)
            if (rootDictDepth == null) rootDictDepth = depth

            val identitiesDepth = buildIdentitiesArrayDepth
            if (identitiesDepth != null && depth == identitiesDepth + 1 && current == null) {
                current = IdentityCandidate(index = identityCount++)
                identityDepth = depth
                return
            }

            val idDepth = identityDepth ?: return
            if (current == null || depth <= idDepth) return

            when {
                ownerDepth == idDepth && openingKey == "Info" -> infoDepth = depth
                ownerDepth == idDepth && openingKey == "Manifest" -> manifestDepth = depth
                manifestDepth != null && ownerDepth == manifestDepth && !openingKey.isNullOrBlank() -> {
                    componentByDepth[depth] = openingKey
                }
                openingKey == "Info" && componentByDepth.containsKey(ownerDepth) -> {
                    componentInfoByDepth[depth] = componentByDepth.getValue(ownerDepth)
                }
            }
        }

        fun startArray(depth: Int) {
            val ownerDepth = depth - 1
            val openingKey = pendingKeys.remove(ownerDepth)
            if (openingKey == "BuildIdentities") buildIdentitiesArrayDepth = depth
        }

        fun scalar(tag: String, ownerDepth: Int, value: String) {
            val key = pendingKeys.remove(ownerDepth) ?: return
            val rootDepth = rootDictDepth
            if (current == null && rootDepth != null && ownerDepth == rootDepth) {
                when (key) {
                    "ProductVersion" -> productVersion = value
                    "ProductBuildVersion" -> productBuildVersion = value
                }
                return
            }

            val identity = current ?: return
            when {
                ownerDepth == identityDepth -> when (key) {
                    "ApBoardConfig" -> identity.boardConfig = value
                    "ApChipID" -> identity.chipId = parseLong(value, tag)
                    "ApBoardID" -> identity.boardId = parseLong(value, tag)
                    "ProductType" -> identity.productType = value
                }
                ownerDepth == infoDepth -> when (key) {
                    "DeviceClass" -> if (identity.boardConfig.isNullOrBlank()) identity.boardConfig = value
                    "Variant" -> identity.variant = value
                    "ProductType" -> identity.productType = value
                }
                componentInfoByDepth.containsKey(ownerDepth) && key == "Path" -> {
                    identity.componentPaths[componentInfoByDepth.getValue(ownerDepth)] = value
                }
            }
        }

        fun endDict(depth: Int) {
            componentInfoByDepth.remove(depth)
            componentByDepth.remove(depth)
            if (depth == infoDepth) infoDepth = null
            if (depth == manifestDepth) manifestDepth = null

            if (depth == identityDepth) {
                val identity = current ?: return
                val score = scoreIdentity(identity, request)
                val previous = best
                if (score >= 0 && (previous == null || score > previous.first)) {
                    best = score to identity.copy(componentPaths = LinkedHashMap(identity.componentPaths))
                }
                current = null
                identityDepth = null
                infoDepth = null
                manifestDepth = null
                componentByDepth.clear()
                componentInfoByDepth.clear()
            }
        }

        fun endArray(depth: Int) {
            if (depth == buildIdentitiesArrayDepth) buildIdentitiesArrayDepth = null
        }

        fun result(): Result {
            val selectedPair = best
                ?: error("No restore identity matched identifier=${request.identifier} board=${request.boardConfig} chip=${request.chipId} boardId=${request.boardId}")
            val score = selectedPair.first
            val selected = selectedPair.second

            logger(
                "IPSW preflight: product=${productVersion ?: "unknown"} " +
                    "build=${productBuildVersion ?: "unknown"} identities=$identityCount"
            )
            logger(
                "IPSW preflight: selected identity index=${selected.index} score=$score " +
                    "variant=${selected.variant ?: "unknown"} board=${selected.boardConfig ?: "unknown"} " +
                    "chip=${selected.chipId?.let { "0x${it.toString(16)}" } ?: "unknown"} " +
                    "boardId=${selected.boardId?.let { "0x${it.toString(16)}" } ?: "unknown"}"
            )
            logger("IPSW preflight: manifest components=${selected.componentPaths.size}")
            IMPORTANT_COMPONENTS.forEach { name ->
                selected.componentPaths[name]?.let { path -> logger("IPSW preflight component: $name -> $path") }
            }

            return Result(
                productVersion = productVersion,
                productBuildVersion = productBuildVersion,
                identityIndex = selected.index,
                identityVariant = selected.variant,
                boardConfig = selected.boardConfig,
                chipId = selected.chipId,
                boardId = selected.boardId,
                componentPaths = selected.componentPaths
            )
        }
    }

    private fun scoreIdentity(identity: IdentityCandidate, request: Request): Int {
        var score = 0
        request.boardConfig?.takeIf { it.isNotBlank() }?.let { expected ->
            val actual = identity.boardConfig ?: return -1
            if (!actual.equals(expected, ignoreCase = true)) return -1
            score += 100
        }
        request.chipId?.let { expected ->
            identity.chipId?.let { actual ->
                if (actual != expected.toLong()) return -1
                score += 30
            }
        }
        request.boardId?.let { expected ->
            identity.boardId?.let { actual ->
                if (actual != expected.toLong()) return -1
                score += 30
            }
        }
        if (identity.productType?.equals(request.identifier, ignoreCase = true) == true) score += 20

        val variant = identity.variant.orEmpty().lowercase(Locale.US)
        if (variant.contains("erase")) score += 2
        if (variant.contains("upgrade")) score += 1
        return score
    }

    private fun parseLong(value: String, tag: String): Long? = runCatching {
        val text = value.trim()
        when {
            text.startsWith("0x", ignoreCase = true) -> text.substring(2).toLong(16)
            tag == "integer" -> text.toLong()
            else -> text.toLongOrNull() ?: text.toLong(16)
        }
    }.getOrNull()

    companion object {
        private val IMPORTANT_COMPONENTS = listOf(
            "Ap,LocalPolicy",
            "Ap,RecoveryOSPolicyNonceHash",
            "Ap,RestoreSecureM3Firmware",
            "BootabilityBundle",
            "iBEC",
            "iBSS",
            "KernelCache",
            "LLB",
            "RestoreKernelCache",
            "RestoreRamDisk",
            "RestoreDeviceTree",
            "RestoreSEP",
            "SEP",
            "DeviceTree",
            "StaticTrustCache",
            "SystemVolume"
        )
    }
}
