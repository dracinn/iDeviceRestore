package com.idevicerestore.android

import android.util.Xml
import java.io.File
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser

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

                logger("TSS identity: native Android XmlPullParser identity index=$identityIndex")
                val parser = Xml.newPullParser().apply {
                    setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                    setInput(input, Charsets.UTF_8.name())
                }
                val handler = IdentityHandler(identityIndex)
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> when (parser.name) {
                            "array" -> handler.startArray(parser.depth)
                            "dict" -> handler.startDict(parser.depth)
                            "key" -> {
                                val depth = parser.depth
                                handler.key(depth - 1, parser.nextText())
                            }
                            "string" -> if (handler.capturing()) {
                                handler.addValue(PlistNode.StringValue(parser.nextText()))
                            }
                            "integer" -> if (handler.capturing()) {
                                val raw = parser.nextText().trim()
                                val value = if (raw.startsWith("0x", ignoreCase = true)) {
                                    raw.substring(2).toLong(16)
                                } else {
                                    raw.toLong()
                                }
                                handler.addValue(PlistNode.IntegerValue(value))
                            }
                            "data" -> if (handler.capturing()) {
                                handler.addValue(PlistNode.DataValue(PlistNode.decodeData(parser.nextText())))
                            }
                            "true" -> if (handler.capturing()) handler.addValue(PlistNode.BoolValue(true))
                            "false" -> if (handler.capturing()) handler.addValue(PlistNode.BoolValue(false))
                        }
                        XmlPullParser.END_TAG -> when (parser.name) {
                            "dict", "array" -> handler.endContainer(parser.name, parser.depth)
                        }
                    }
                    event = parser.next()
                }

                val identity = handler.result()
                logger(
                    "TSS identity: materialized index=$identityIndex " +
                        "manifestEntries=${identity.dict("Manifest")?.values?.size ?: 0}"
                )
                return Result(identityIndex, identity)
            }
        }
    }

    private class IdentityHandler(
        private val targetIndex: Int
    ) {
        private val pendingKeys = mutableMapOf<Int, String>()
        private var buildIdentitiesArrayDepth: Int? = null
        private var currentIdentityIndex = -1
        private var captureRootDepth: Int? = null
        private var captured: PlistNode.Dict? = null

        private sealed class Container {
            class DictContainer(val node: PlistNode.Dict, var pendingKey: String? = null) : Container()
            class ArrayContainer(val node: PlistNode.ArrayValue) : Container()
        }

        private val stack = ArrayDeque<Container>()

        fun capturing(): Boolean = captureRootDepth != null

        fun key(ownerDepth: Int, value: String) {
            if (capturing()) {
                (stack.lastOrNull() as? Container.DictContainer)?.pendingKey = value
            } else {
                pendingKeys[ownerDepth] = value.trim()
            }
        }

        fun startArray(depth: Int) {
            if (!capturing()) {
                val openingKey = pendingKeys.remove(depth - 1)
                if (openingKey == "BuildIdentities" && buildIdentitiesArrayDepth == null) {
                    buildIdentitiesArrayDepth = depth
                }
                return
            }
            startContainer(PlistNode.ArrayValue())
        }

        fun startDict(depth: Int) {
            val identitiesDepth = buildIdentitiesArrayDepth
            if (!capturing() && identitiesDepth != null && depth == identitiesDepth + 1) {
                currentIdentityIndex++
                if (currentIdentityIndex == targetIndex) {
                    captureRootDepth = depth
                    val root = PlistNode.Dict()
                    stack.addLast(Container.DictContainer(root))
                    captured = root
                }
                return
            }
            if (capturing()) startContainer(PlistNode.Dict())
        }

        private fun startContainer(node: PlistNode) {
            if (stack.isNotEmpty()) addValue(node)
            when (node) {
                is PlistNode.Dict -> stack.addLast(Container.DictContainer(node))
                is PlistNode.ArrayValue -> stack.addLast(Container.ArrayContainer(node))
                else -> error("Not a plist container")
            }
        }

        fun addValue(value: PlistNode) {
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

        fun endContainer(tag: String, depth: Int) {
            if (capturing() && stack.size > 1) stack.removeLast()
            if (captureRootDepth == depth && tag == "dict") {
                stack.clear()
                captureRootDepth = null
            }
            if (buildIdentitiesArrayDepth == depth && tag == "array") {
                buildIdentitiesArrayDepth = null
            }
            pendingKeys.remove(depth)
        }

        fun result(): PlistNode.Dict =
            captured ?: error("BuildIdentity index $targetIndex was not found")
    }
}
