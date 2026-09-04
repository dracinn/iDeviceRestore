package com.idevicerestore.android

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

/** Small XML plist parser used for Apple TSS responses. */
object TssPlistParser {
    fun parse(xml: ByteArray): PlistNode.Dict {
        val handler = Handler()
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
        reader.parse(InputSource(ByteArrayInputStream(xml)))
        return handler.result() as? PlistNode.Dict ?: error("TSS plist root is not a dictionary")
    }

    private fun SAXParserFactory.setFeatureIfSupported(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }

    private class Handler : DefaultHandler() {
        private sealed class Container {
            class DictContainer(val node: PlistNode.Dict, var key: String? = null) : Container()
            class ArrayContainer(val node: PlistNode.ArrayValue) : Container()
        }

        private val stack = ArrayDeque<Container>()
        private val text = StringBuilder()
        private var collecting: String? = null
        private var root: PlistNode? = null

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
            when (qName) {
                "dict" -> pushContainer(PlistNode.Dict())
                "array" -> pushContainer(PlistNode.ArrayValue())
                "key", "string", "integer", "data" -> {
                    collecting = qName
                    text.setLength(0)
                }
                "true", "false" -> addValue(PlistNode.BoolValue(qName == "true"))
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (collecting != null) text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (qName) {
                "key" -> {
                    (stack.lastOrNull() as? Container.DictContainer)?.key = text.toString()
                    collecting = null
                    text.setLength(0)
                }
                "string" -> {
                    addValue(PlistNode.StringValue(text.toString()))
                    collecting = null
                    text.setLength(0)
                }
                "integer" -> {
                    val raw = text.toString().trim()
                    val value = raw.toULongOrNull()
                    if (value != null && value > Long.MAX_VALUE.toULong()) {
                        addValue(PlistNode.UnsignedIntegerValue(value))
                    } else {
                        addValue(PlistNode.IntegerValue(raw.toLong()))
                    }
                    collecting = null
                    text.setLength(0)
                }
                "data" -> {
                    addValue(PlistNode.DataValue(PlistNode.decodeData(text.toString())))
                    collecting = null
                    text.setLength(0)
                }
                "dict", "array" -> stack.removeLastOrNull()
            }
        }

        private fun pushContainer(node: PlistNode) {
            addValue(node)
            when (node) {
                is PlistNode.Dict -> stack.addLast(Container.DictContainer(node))
                is PlistNode.ArrayValue -> stack.addLast(Container.ArrayContainer(node))
                else -> error("Not a plist container")
            }
        }

        private fun addValue(value: PlistNode) {
            val parent = stack.lastOrNull()
            if (parent == null) {
                if (root == null) root = value
                return
            }
            when (parent) {
                is Container.DictContainer -> {
                    val key = parent.key ?: error("Plist dictionary value without key")
                    parent.node.values[key] = value
                    parent.key = null
                }
                is Container.ArrayContainer -> parent.node.values += value
            }
        }

        fun result(): PlistNode = root ?: error("Empty plist")
    }
}
