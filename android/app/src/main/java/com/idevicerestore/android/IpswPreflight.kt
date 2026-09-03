package com.idevicerestore.android

import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Read-only inspection of a local IPSW. This never transmits anything to the connected device.
 *
 * It opens BuildManifest.plist from the IPSW, parses XML plist dictionaries/arrays, selects the
 * best matching restore identity for the identified hardware, and reports component paths so the
 * restore transport can be implemented separately and tested only after preflight is trustworthy.
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

    fun inspect(request: Request): Result {
        require(request.ipsw.isFile) { "IPSW not found: ${request.ipsw.absolutePath}" }
        logger("IPSW preflight: opening ${request.ipsw.absolutePath}")
        ZipFile(request.ipsw).use { zip ->
            val entry = zip.getEntry("BuildManifest.plist")
                ?: error("BuildManifest.plist is missing from IPSW")
            logger("IPSW preflight: BuildManifest.plist compressed=${entry.compressedSize} bytes size=${entry.size} bytes")
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            if (bytes.size >= 6 && bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII) == "bplist") {
                error("Binary BuildManifest.plist is not supported yet; no restore command was sent")
            }
            val root = parseXmlPlist(bytes) as? Map<*, *>
                ?: error("BuildManifest.plist root is not a dictionary")

            val productVersion = root["ProductVersion"] as? String
            val productBuild = root["ProductBuildVersion"] as? String
            val identities = root["BuildIdentities"] as? List<*>
                ?: error("BuildManifest.plist has no BuildIdentities array")
            logger("IPSW preflight: product=${productVersion ?: "unknown"} build=${productBuild ?: "unknown"} identities=${identities.size}")

            val candidates = identities.mapIndexedNotNull { index, value ->
                val identity = value as? Map<*, *> ?: return@mapIndexedNotNull null
                Candidate(index, identity, scoreIdentity(identity, request))
            }.sortedByDescending { it.score }

            val selected = candidates.firstOrNull { it.score >= 0 }
                ?: error("No restore identity matched identifier=${request.identifier} board=${request.boardConfig} chip=${request.chipId} boardId=${request.boardId}")

            val info = selected.identity["Info"] as? Map<*, *>
            val manifest = selected.identity["Manifest"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val componentPaths = manifest.mapNotNull { (name, rawValue) ->
                val componentName = name as? String ?: return@mapNotNull null
                val component = rawValue as? Map<*, *> ?: return@mapNotNull null
                val componentInfo = component["Info"] as? Map<*, *> ?: return@mapNotNull null
                val path = componentInfo["Path"] as? String ?: return@mapNotNull null
                componentName to path
            }.toMap()

            val selectedBoard = stringValue(selected.identity["ApBoardConfig"])
                ?: stringValue(info?.get("DeviceClass"))
            val selectedChipId = longValue(selected.identity["ApChipID"])
            val selectedBoardId = longValue(selected.identity["ApBoardID"])
            val variant = stringValue(info?.get("Variant"))

            logger(
                "IPSW preflight: selected identity index=${selected.index} score=${selected.score} " +
                    "variant=${variant ?: "unknown"} board=${selectedBoard ?: "unknown"} " +
                    "chip=${selectedChipId?.let { "0x${it.toString(16)}" } ?: "unknown"} " +
                    "boardId=${selectedBoardId?.let { "0x${it.toString(16)}" } ?: "unknown"}"
            )
            logger("IPSW preflight: manifest components=${componentPaths.size}")
            IMPORTANT_COMPONENTS.forEach { name ->
                componentPaths[name]?.let { path -> logger("IPSW preflight component: $name -> $path") }
            }

            return Result(
                productVersion = productVersion,
                productBuildVersion = productBuild,
                identityIndex = selected.index,
                identityVariant = variant,
                boardConfig = selectedBoard,
                chipId = selectedChipId,
                boardId = selectedBoardId,
                componentPaths = componentPaths
            )
        }
    }

    private data class Candidate(val index: Int, val identity: Map<*, *>, val score: Int)

    private fun scoreIdentity(identity: Map<*, *>, request: Request): Int {
        val info = identity["Info"] as? Map<*, *>
        val identityBoard = stringValue(identity["ApBoardConfig"])
            ?: stringValue(info?.get("DeviceClass"))
        val identityChip = longValue(identity["ApChipID"])
        val identityBoardId = longValue(identity["ApBoardID"])

        var score = 0
        request.boardConfig?.takeIf { it.isNotBlank() }?.let { expected ->
            if (identityBoard == null || !identityBoard.equals(expected, ignoreCase = true)) return -1
            score += 100
        }
        request.chipId?.let { expected ->
            if (identityChip != null && identityChip != expected.toLong()) return -1
            if (identityChip == expected.toLong()) score += 30
        }
        request.boardId?.let { expected ->
            if (identityBoardId != null && identityBoardId != expected.toLong()) return -1
            if (identityBoardId == expected.toLong()) score += 30
        }

        val productTypes = listOfNotNull(
            stringValue(identity["ProductType"]),
            stringValue(info?.get("ProductType"))
        )
        if (productTypes.any { it.equals(request.identifier, ignoreCase = true) }) score += 20

        val variant = stringValue(info?.get("Variant")).orEmpty().lowercase(Locale.US)
        if (variant.contains("erase")) score += 2
        if (variant.contains("upgrade")) score += 1
        return score
    }

    private fun parseXmlPlist(bytes: ByteArray): Any? {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeatureIfSupported("http://apache.org/xml/features/disallow-doctype-decl", false)
            setFeatureIfSupported("http://xml.org/sax/features/external-general-entities", false)
            setFeatureIfSupported("http://xml.org/sax/features/external-parameter-entities", false)
            setFeatureIfSupported("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            runCatching { isXIncludeAware = false }
            isExpandEntityReferences = false
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
        }
        val document = builder.parse(bytes.inputStream())
        val plist = document.documentElement
        val child = elementChildren(plist).firstOrNull() ?: error("Empty plist")
        return parseElement(child)
    }

    private fun DocumentBuilderFactory.setFeatureIfSupported(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
            .onFailure { logger("IPSW preflight: XML feature unsupported on this Android runtime: $name") }
    }

    private fun parseElement(element: Element): Any? = when (element.tagName) {
        "dict" -> {
            val children = elementChildren(element)
            val map = linkedMapOf<String, Any?>()
            var index = 0
            while (index < children.size) {
                val keyElement = children[index]
                if (keyElement.tagName != "key" || index + 1 >= children.size) {
                    error("Malformed plist dictionary")
                }
                map[keyElement.textContent] = parseElement(children[index + 1])
                index += 2
            }
            map
        }
        "array" -> elementChildren(element).map(::parseElement)
        "string", "key" -> element.textContent
        "integer" -> parseInteger(element.textContent)
        "true" -> true
        "false" -> false
        "data", "date", "real" -> element.textContent.trim()
        else -> element.textContent
    }

    private fun elementChildren(element: Element): List<Element> {
        val nodes = element.childNodes
        return buildList {
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                if (node is Element) add(node)
            }
        }
    }

    private fun parseInteger(value: String): Long {
        val text = value.trim()
        return if (text.startsWith("0x", ignoreCase = true)) {
            text.substring(2).toLong(16)
        } else {
            text.toLong()
        }
    }

    private fun longValue(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> runCatching { parseInteger(value) }.getOrNull()
        else -> null
    }

    private fun stringValue(value: Any?): String? = (value as? String)?.trim()?.takeIf { it.isNotEmpty() }

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
