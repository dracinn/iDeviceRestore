package com.idevicerestore.android

import android.util.Base64
import android.util.Xml
import java.io.ByteArrayOutputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlSerializer

/** Minimal typed plist model used by BuildManifest/TSS code. */
sealed interface PlistValue {
    data class Dict(val values: LinkedHashMap<String, PlistValue> = linkedMapOf()) : PlistValue {
        operator fun get(key: String): PlistValue? = values[key]
        fun string(key: String): String? = (values[key] as? StringValue)?.value
        fun integer(key: String): Long? = (values[key] as? IntegerValue)?.value
        fun bool(key: String): Boolean? = (values[key] as? BoolValue)?.value
        fun data(key: String): ByteArray? = (values[key] as? DataValue)?.value
        fun dict(key: String): Dict? = values[key] as? Dict
        fun array(key: String): ArrayValue? = values[key] as? ArrayValue
        fun copyDeep(): Dict = Dict(LinkedHashMap(values.mapValues { it.value.copyDeepValue() }))
    }

    data class ArrayValue(val values: MutableList<PlistValue> = mutableListOf()) : PlistValue
    data class StringValue(val value: String) : PlistValue
    data class IntegerValue(val value: Long) : PlistValue
    data class BoolValue(val value: Boolean) : PlistValue
    data class DataValue(val value: ByteArray) : PlistValue
    data class RealValue(val value: Double) : PlistValue
    data class DateValue(val value: String) : PlistValue

    fun copyDeepValue(): PlistValue = when (this) {
        is Dict -> copyDeep()
        is ArrayValue -> ArrayValue(values.mapTo(mutableListOf()) { it.copyDeepValue() })
        is DataValue -> DataValue(value.copyOf())
        else -> this
    }
}

object XmlPlistCodec {
    fun parseValue(parser: XmlPullParser): PlistValue {
        require(parser.eventType == XmlPullParser.START_TAG)
        return when (parser.name) {
            "dict" -> parseDict(parser)
            "array" -> parseArray(parser)
            "string" -> PlistValue.StringValue(parser.nextText())
            "integer" -> PlistValue.IntegerValue(parseInteger(parser.nextText()))
            "true" -> {
                consumeEmpty(parser, "true")
                PlistValue.BoolValue(true)
            }
            "false" -> {
                consumeEmpty(parser, "false")
                PlistValue.BoolValue(false)
            }
            "data" -> PlistValue.DataValue(Base64.decode(parser.nextText().trim(), Base64.DEFAULT))
            "real" -> PlistValue.RealValue(parser.nextText().trim().toDouble())
            "date" -> PlistValue.DateValue(parser.nextText())
            else -> error("Unsupported plist tag <${parser.name}>")
        }
    }

    fun skipValue(parser: XmlPullParser) {
        require(parser.eventType == XmlPullParser.START_TAG)
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> error("Unexpected end of plist")
            }
        }
    }

    fun toXml(dict: PlistValue.Dict): ByteArray {
        val out = ByteArrayOutputStream()
        val serializer = Xml.newSerializer()
        serializer.setOutput(out, Charsets.UTF_8.name())
        serializer.startDocument(Charsets.UTF_8.name(), null)
        serializer.startTag(null, "plist")
        serializer.attribute(null, "version", "1.0")
        writeValue(serializer, dict)
        serializer.endTag(null, "plist")
        serializer.endDocument()
        serializer.flush()
        return out.toByteArray()
    }

    private fun parseDict(parser: XmlPullParser): PlistValue.Dict {
        val result = PlistValue.Dict()
        while (true) {
            val event = parser.nextTag()
            if (event == XmlPullParser.END_TAG && parser.name == "dict") return result
            require(event == XmlPullParser.START_TAG && parser.name == "key") { "Expected plist key" }
            val key = parser.nextText()
            require(parser.nextTag() == XmlPullParser.START_TAG) { "Expected value for plist key $key" }
            result.values[key] = parseValue(parser)
        }
    }

    private fun parseArray(parser: XmlPullParser): PlistValue.ArrayValue {
        val result = PlistValue.ArrayValue()
        while (true) {
            val event = parser.nextTag()
            if (event == XmlPullParser.END_TAG && parser.name == "array") return result
            require(event == XmlPullParser.START_TAG) { "Expected plist array value" }
            result.values += parseValue(parser)
        }
    }

    private fun consumeEmpty(parser: XmlPullParser, tag: String) {
        val next = parser.nextTag()
        require(next == XmlPullParser.END_TAG && parser.name == tag)
    }

    private fun parseInteger(text: String): Long {
        val value = text.trim()
        return if (value.startsWith("0x", ignoreCase = true)) value.substring(2).toLong(16) else value.toLong()
    }

    private fun writeValue(serializer: XmlSerializer, value: PlistValue) {
        when (value) {
            is PlistValue.Dict -> {
                serializer.startTag(null, "dict")
                value.values.forEach { (key, child) ->
                    serializer.startTag(null, "key").text(key).endTag(null, "key")
                    writeValue(serializer, child)
                }
                serializer.endTag(null, "dict")
            }
            is PlistValue.ArrayValue -> {
                serializer.startTag(null, "array")
                value.values.forEach { writeValue(serializer, it) }
                serializer.endTag(null, "array")
            }
            is PlistValue.StringValue -> serializer.startTag(null, "string").text(value.value).endTag(null, "string")
            is PlistValue.IntegerValue -> serializer.startTag(null, "integer").text(value.value.toString()).endTag(null, "integer")
            is PlistValue.BoolValue -> {
                val tag = if (value.value) "true" else "false"
                serializer.startTag(null, tag).endTag(null, tag)
            }
            is PlistValue.DataValue -> serializer.startTag(null, "data")
                .text(Base64.encodeToString(value.value, Base64.NO_WRAP))
                .endTag(null, "data")
            is PlistValue.RealValue -> serializer.startTag(null, "real").text(value.value.toString()).endTag(null, "real")
            is PlistValue.DateValue -> serializer.startTag(null, "date").text(value.value).endTag(null, "date")
        }
    }
}
