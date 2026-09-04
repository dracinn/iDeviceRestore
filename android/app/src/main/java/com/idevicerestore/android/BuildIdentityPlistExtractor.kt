package com.idevicerestore.android

import android.util.Xml
import java.io.File
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser

/**
 * Streams BuildManifest.plist and materializes only the selected BuildIdentity.
 * This keeps the UniversalMac manifest memory footprint bounded while preserving typed TSS fields.
 */
object BuildIdentityPlistExtractor {
    data class Result(
        val identityIndex: Int,
        val identity: PlistValue.Dict
    )

    fun extract(ipsw: File, identityIndex: Int): Result {
        require(identityIndex >= 0)
        require(ipsw.isFile) { "IPSW not found: ${ipsw.absolutePath}" }

        ZipFile(ipsw).use { zip ->
            val entry = zip.getEntry("BuildManifest.plist")
                ?: error("BuildManifest.plist is missing from IPSW")
            zip.getInputStream(entry).buffered().use { input ->
                input.mark(8)
                val header = ByteArray(6)
                val count = input.read(header)
                input.reset()
                if (count == 6 && header.toString(Charsets.US_ASCII) == "bplist") {
                    error("Binary BuildManifest.plist is not supported for TSS extraction")
                }

                val parser = Xml.newPullParser().apply {
                    setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                    setInput(input, Charsets.UTF_8.name())
                }
                seekStart(parser, "plist")
                require(parser.nextTag() == XmlPullParser.START_TAG && parser.name == "dict") {
                    "BuildManifest root is not a dictionary"
                }
                return extractFromRootDict(parser, identityIndex)
            }
        }
    }

    private fun extractFromRootDict(parser: XmlPullParser, targetIndex: Int): Result {
        while (true) {
            val event = parser.nextTag()
            if (event == XmlPullParser.END_TAG && parser.name == "dict") break
            require(event == XmlPullParser.START_TAG && parser.name == "key") { "Expected BuildManifest key" }
            val key = parser.nextText()
            require(parser.nextTag() == XmlPullParser.START_TAG) { "Expected value for BuildManifest key $key" }
            if (key == "BuildIdentities") {
                require(parser.name == "array") { "BuildIdentities is not an array" }
                return extractIdentityFromArray(parser, targetIndex)
            }
            XmlPlistCodec.skipValue(parser)
        }
        error("BuildIdentities is missing from BuildManifest")
    }

    private fun extractIdentityFromArray(parser: XmlPullParser, targetIndex: Int): Result {
        var index = 0
        while (true) {
            val event = parser.nextTag()
            if (event == XmlPullParser.END_TAG && parser.name == "array") break
            require(event == XmlPullParser.START_TAG) { "Expected BuildIdentity entry" }
            if (index == targetIndex) {
                val value = XmlPlistCodec.parseValue(parser)
                val identity = value as? PlistValue.Dict
                    ?: error("BuildIdentity $targetIndex is not a dictionary")
                return Result(targetIndex, identity)
            }
            XmlPlistCodec.skipValue(parser)
            index++
        }
        error("BuildIdentity index $targetIndex is out of range")
    }

    private fun seekStart(parser: XmlPullParser, tag: String) {
        while (!(parser.eventType == XmlPullParser.START_TAG && parser.name == tag)) {
            if (parser.next() == XmlPullParser.END_DOCUMENT) error("<$tag> not found in plist")
        }
    }
}
