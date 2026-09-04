package com.idevicerestore.android

import java.util.Base64

/** XML plist serializer for Apple TSS requests. */
object TssPlistSerializer {
    fun serialize(root: PlistNode.Dict): ByteArray {
        val out = StringBuilder(64 * 1024)
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n")
        out.append("<plist version=\"1.0\">\n")
        appendNode(out, root, 0)
        out.append("\n</plist>\n")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    private fun appendNode(out: StringBuilder, node: PlistNode, depth: Int) {
        when (node) {
            is PlistNode.Dict -> {
                indent(out, depth).append("<dict>\n")
                node.values.forEach { (key, value) ->
                    indent(out, depth + 1).append("<key>").append(escape(key)).append("</key>\n")
                    appendNode(out, value, depth + 1)
                    out.append('\n')
                }
                indent(out, depth).append("</dict>")
            }
            is PlistNode.ArrayValue -> {
                indent(out, depth).append("<array>")
                if (node.values.isNotEmpty()) out.append('\n')
                node.values.forEachIndexed { index, value ->
                    appendNode(out, value, depth + 1)
                    if (index != node.values.lastIndex) out.append('\n')
                }
                if (node.values.isNotEmpty()) {
                    out.append('\n')
                    indent(out, depth)
                }
                out.append("</array>")
            }
            is PlistNode.StringValue -> indent(out, depth).append("<string>").append(escape(node.value)).append("</string>")
            is PlistNode.IntegerValue -> indent(out, depth).append("<integer>").append(node.value).append("</integer>")
            is PlistNode.UnsignedIntegerValue -> indent(out, depth).append("<integer>").append(node.value.toString()).append("</integer>")
            is PlistNode.BoolValue -> indent(out, depth).append(if (node.value) "<true/>" else "<false/>")
            is PlistNode.DataValue -> {
                val encoded = Base64.getEncoder().encodeToString(node.value)
                indent(out, depth).append("<data>").append(encoded).append("</data>")
            }
        }
    }

    private fun indent(out: StringBuilder, depth: Int): StringBuilder {
        repeat(depth) { out.append("  ") }
        return out
    }

    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }
}
