package com.idevicerestore.android

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.StringReader
import java.util.Base64
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory

/**
 * Streams BuildManifest.plist and materializes only the selected BuildIdentity.
 *
 * The universal macOS BuildManifest is tens of megabytes; keeping a single identity in memory
 * avoids a full-document DOM while retaining the complete Manifest dictionary required by libtatsu.
 */
class IpswBuildIdentityReader(
    private val logger: (String) -> Unit = {}
) {
    data class Result(
        val identityIndex: Int,
        val identity: PlistNode.Dict
    )

    fun read(ipsw: File, identityIndex: Int): Result {
        require(ipsw.isFile) { "IPSW not found: ${ipsw.absolutePath}" }
        require(identityIndex >= 0) { "identityIndex must be non-negative" }

        ZipFile(ipsw).use { zip ->
            val entry = zip.getEntry("BuildManifest.plist")
                ?: error("BuildManifest.plist is missing from IPSW")
            zip.getInputStream(entry).buffered().use { input ->
                input.mark(8)
                val header = ByteArray(6)
                val read = input.read(header)
                input.reset()
                if (read == 6 && header.toString(Charsets.US_ASCII) == "bplist") {
                    error("Binary BuildManifest.plist is not supported yet")
                }

                logger("TSS identity: streaming BuildManifest identity index=$identityIndex")
                val handler = IdentityHandler(identityIndex)
                val factory = SAXParserFactory.newInstance().apply {
                    isNamespaceAware = false
                    setFeatureIfSupported("http://xml.org/sax/features/external-general-entities", false)
                    setFeatureIfSupported("http://xml.org/sax/features/external-parameter-entities", false)
                    setFeatureIfSupported("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                }
                val reader = factory.newSAXParser().xmlReader.apply {
                    entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
                    contentHandler = handler
                    errorHandler = handler
                }
                reader.parse(InputSource(input))
                val identity = handler.result()
                logger(
                    "TSS identity: materialized index=$identityIndex " +
                        "manifestEntries=${identity.dict("Manifest")?.values?.size ?: 0}"
                )
                return Result(identityIndex, identity)
            }
        }
    }

    private fun SAXParserFactory.setFeatureIfSupported(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
            .onFailure { logger("TSS identity: XML feature unsupported on this Android runtime: $name") }
    }

    private class IdentityHandler(
        private val targetIndex: Int
    ) : DefaultHandler() {
        private var depth = 0
        private val pendingKeys = mutableMapOf<Int, String>()
        private val text = StringBuilder()
        private var collectingTag: String? = null

        private var buildIdentitiesArrayDepth: Int? = null
        private var currentIdentityIndex = -1
        private var captureRootDepth: Int? = null
        private var result: PlistNode.Dict? = null

        private sealed class Container {
            class DictContainer(val node: PlistNode.Dict, var pendingKey: String? = null) : Container()
            class ArrayContainer(val node: PlistNode.ArrayValue) : Container()
        }

        private val stack = ArrayDeque<Container>()

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
            depth++
            val ownerDepth = depth - 1
            when (qName) {
                "array" -> {
                    val openingKey = pendingKeys.remove(ownerDepth)
                    if (openingKey == "BuildIdentities" && buildIdentitiesArrayDepth == null) {
                        buildIdentitiesArrayDepth = depth
                    }
                    if (capturing()) startContainer(PlistNode.ArrayValue())
                }
                "dict" -> {
                    val identitiesDepth = buildIdentitiesArrayDepth
                    if (identitiesDepth != null && depth == identitiesDepth + 1 && captureRootDepth == null) {
                        currentIdentityIndex++
                        if (currentIdentityIndex == targetIndex) {
                            captureRootDepth = depth
                            val root = PlistNode.Dict()
                            stack.addLast(Container.DictContainer(root))
                            result = root
                            return
                        }
                    }
                    if (capturing()) startContainer(PlistNode.Dict())
                }
                "key", "string", "integer", "data" -> {
                    collectingTag = qName
                    text.setLength(0)
                }
                "true", "false" -> if (capturing()) {
                    addValue(PlistNode.BoolValue(qName == "true"))
                }
            }
        }

        private fun startContainer(node: PlistNode) {
            if (stack.isNotEmpty()) addValue(node)
            when (node) {
                is PlistNode.Dict -> stack.addLast(Container.DictContainer(node))
                is PlistNode.ArrayValue -> stack.addLast(Container.ArrayContainer(node))
                else -> error("Not a container")
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (collectingTag != null) text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (qName) {
                "key" -> {
                    if (capturing()) {
                        val dict = stack.lastOrNull() as? Container.DictContainer
                        dict?.pendingKey = text.toString().trim()
                    } else {
                        pendingKeys[depth - 1] = text.toString().trim()
                    }
                    collectingTag = null
                    text.setLength(0)
                }
                "string" -> {
                    if (capturing()) addValue(PlistNode.StringValue(text.toString()))
                    collectingTag = null
                    text.setLength(0)
                }
                "integer" -> {
                    if (capturing()) {
                        val raw = text.toString().trim()
                        val value = when {
                            raw.startsWith("0x", ignoreCase = true) -> raw.substring(2).toLong(16)
                            else -> raw.toLong()
                        }
                        addValue(PlistNode.IntegerValue(value))
                    }
                    collectingTag = null
                    text.setLength(0)
                }
                "data" -> {
                    if (capturing()) addValue(PlistNode.DataValue(PlistNode.decodeData(text.toString())))
                    collectingTag = null
                    text.setLength(0)
                }
                "dict", "array" -> {
                    if (capturing() && stack.size > 1) stack.removeLast()
                    if (captureRootDepth == depth && qName == "dict") {
                        stack.clear()
                        captureRootDepth = null
                    }
                    if (buildIdentitiesArrayDepth == depth && qName == "array") {
                        buildIdentitiesArrayDepth = null
                    }
                }
            }
            depth--
        }

        private fun capturing(): Boolean = captureRootDepth != null

        private fun addValue(value: PlistNode) {
            when (val container = stack.lastOrNull()) {
                is Container.DictContainer -> {
                    val key = container.pendingKey
                        ?: error("BuildManifest plist value encountered without key")
                    container.node.values[key] = value
                    container.pendingKey = null
                }
                is Container.ArrayContainer -> container.node.values += value
                null -> Unit
            }
        }

        fun result(): PlistNode.Dict =
            result ?: error("BuildIdentity index $targetIndex was not found")
    }
}
